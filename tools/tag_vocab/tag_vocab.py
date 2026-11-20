#!/usr/bin/env python3
"""TAGS-0: a fixed tag vocabulary scored against the existing SigLIP 2 index.

Every indexed photo already has a SigLIP 2 image embedding. This tool encodes
a fixed list of tags ONCE on the host with the shipped int8 text tower
(NLS-1A), calibrates one cosine threshold per tag on licensed Open Images
labels, and writes a small matrix the phone scores with one dot product per
tag: no tokenizer, no new model, no second index on the device.

Subcommands (run from the repository root; see docs/visual-search/TAGS_0.md):

  select     Open Images label CSVs  -> data/tags0_manifest.csv (IDs + tag ids only)
  encode     vocabulary.tsv + shipped text tower + tokenizer -> $WORK/text.npz
  calibrate  manifest + licensed image cache + shipped image tower -> thresholds,
             report JSON, and the asset pair tags.bin / tags.tsv
  verify     re-check a checkout's tags.bin / tags.tsv against the vocabulary
             (standard library only)

Nothing personal is read. Open Images pixels stay in the local cache
(fetched and licence-checked by ../visual_search_bench/fetch_images.py) and
never enter Git; only image IDs and our own tag ids are committed.
"""
import argparse
import collections
import csv
import hashlib
import json
import math
import os
import random
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "models")
VOCAB = os.path.join(HERE, "vocabulary.tsv")
MANIFEST = os.path.join(HERE, "data", "tags0_manifest.csv")
OUT_DIR = os.path.join(ASSETS, "siglip2_tags")

# Pins: the shipped towers (SOURCE.md of each) and the tokenizer (models.json).
IMAGE_SHA = "74da3ac2b46e4292c4639affc3cca623c2d5551d012ead113b1486c22e0f5336"
TEXT_SHA = "6fd97706a61981eb1a8d2444777209f0768162d3172c74a73134fbc1f78c0da6"
TOKENIZER_SHA = "6969e64047744a44bb3abfb5c50f8de0f7ed8b571d5444426ef931f651d1a0ef"
IMAGE_MODEL_VERSION = "siglip2_b16_224.int8:" + IMAGE_SHA[:12]
TEXT_MODEL_VERSION = "siglip2_b16_224.text.int8:" + TEXT_SHA[:12]
LOGIT_T, LOGIT_B = 112.669, -16.771725  # NLS_1_TEXT_ASSET.md, from the checkpoint
DIM = 768
MAGIC = b"ATLSTAG1"

TEMPLATE_SETS = {
    "photo": ["a photo of {}."],
    "ensemble3": ["a photo of {}.", "this is a photo of {}.", "{}"],
}

# Calibration rules (documented in TAGS_0.md).
CAP_POS, CAP_HARD, BACKGROUND = 60, 40, 2000
MIN_POS_PER_HALF = 12
MAX_BG_FPR = 0.01      # a threshold never lets more than 1% of background through
UNCAL_BG_FPR = 0.005   # tags without enough labels: 99.5th background percentile
F_BETA = 0.5           # precision weighs more than recall for automatic tags
GATE_PRECISION, GATE_RECALL = 0.6, 0.15  # evaluation-half gate for "on by default"
SEED = 667


# ---------------------------------------------------------------- vocabulary

def read_vocab(path=VOCAB):
    rows, seen = [], set()
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            c = line.split("\t")
            if len(c) != 7:
                raise SystemExit(f"vocabulary.tsv:{n}: expected 7 columns, got {len(c)}")
            tid = c[0]
            if not tid.isascii() or not tid.replace("_", "").isalnum() or tid != tid.lower() or tid in seen:
                raise SystemExit(f"vocabulary.tsv:{n}: bad or duplicate id {tid!r}")
            seen.add(tid)
            rows.append({
                "id": tid, "category": c[1], "en": c[2], "nl": c[3],
                "phrases": [p for p in c[4].split("|") if p],
                "open_images": [p for p in c[5].split("|") if p],
                "confusers": [p for p in c[6].split("|") if p],
            })
    for r in rows:
        for x in r["confusers"]:
            if x not in seen:
                raise SystemExit(f"tag {r['id']}: unknown confuser {x}")
        if not r["phrases"]:
            raise SystemExit(f"tag {r['id']}: no phrases")
    return rows


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def joined_asset(dirname, sha):
    d = os.path.join(ASSETS, dirname)
    parts = sorted((p for p in os.listdir(d) if ".part" in p), key=lambda p: int(p.rsplit("part", 1)[1]))
    data = b"".join(open(os.path.join(d, p), "rb").read() for p in parts)
    if hashlib.sha256(data).hexdigest() != sha:
        raise SystemExit(f"{dirname}: asset hash mismatch")
    return data


def session(model_bytes):
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = os.cpu_count()
    so.inter_op_num_threads = 1
    return ort.InferenceSession(model_bytes, so, providers=["CPUExecutionProvider"])


# ---------------------------------------------------------------- select

def cmd_select(a):
    vocab = read_vocab()
    names = collections.defaultdict(list)
    with open(a.classes, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            names[r["DisplayName"].lower()].append(r["LabelName"])
    mid_tags = collections.defaultdict(set)
    for t in vocab:
        for nm in t["open_images"]:
            if nm.lower() not in names:
                raise SystemExit(f"tag {t['id']}: Open Images has no class {nm!r}")
            for mid in names[nm.lower()]:
                mid_tags[mid].add(t["id"])

    available = {}  # ImageID -> split, restricted to licence-checkable ids hosted on S3
    for split, path in a.images:
        with open(path, newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                if "creativecommons.org/licenses/by/2.0" in r.get("License", ""):
                    available.setdefault(r["ImageID"], split)

    pos, neg = collections.defaultdict(set), collections.defaultdict(set)
    for path in a.labels:
        with open(path, newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                tags = mid_tags.get(r["LabelName"])
                if not tags or r["ImageID"] not in available:
                    continue
                for t in tags:
                    (pos if r["Confidence"] == "1" else neg)[t].add(r["ImageID"])

    rng = random.Random(SEED)
    rows = []
    for t in vocab:
        p = sorted(pos[t["id"]])
        h = sorted(neg[t["id"]] - pos[t["id"]])
        rng.shuffle(p); rng.shuffle(h)
        rows += [(i, available[i], t["id"], 1) for i in p[:CAP_POS]]
        rows += [(i, available[i], t["id"], 0) for i in h[:CAP_HARD]]
    # Background: random licensed val/test images, labelled or not; they count
    # as negatives for every tag they are not a verified positive of.
    pool = sorted(i for i, s in available.items() if s in ("validation", "test"))
    rng.shuffle(pool)
    rows += [(i, available[i], "", "") for i in pool[:BACKGROUND]]
    # Verified positives elsewhere in the manifest must not be scored as
    # negatives: record every known positive label of every selected image.
    chosen = {r[0] for r in rows}
    seen = {(r[0], r[2]) for r in rows}
    for t in vocab:
        for i in sorted(pos[t["id"]] & chosen):
            if (i, t["id"]) not in seen:
                rows.append((i, available[i], t["id"], 1))
                seen.add((i, t["id"]))
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["ImageID", "Split", "Tag", "Label"])
        w.writerows(sorted(rows))
    print(json.dumps({"rows": len(rows), "images": len(chosen)}))


# ---------------------------------------------------------------- encode

def cmd_encode(a):
    import numpy as np
    sys.path.insert(0, os.path.join(ROOT, "tools", "visual_search_bench"))
    import text_tower as tt  # noqa: E402  (tokenizer contract of NLS-1A)

    if sha256_file(a.tokenizer) != TOKENIZER_SHA:
        raise SystemExit("tokenizer hash mismatch")
    tok = tt.Tokenizer(a.tokenizer)
    sess = session(joined_asset("siglip2_text", TEXT_SHA))
    vocab = read_vocab()
    out = {}
    for name, templates in TEMPLATE_SETS.items():
        texts = [tpl.format(p) for t in vocab for p in t["phrases"] for tpl in templates]
        emb = np.concatenate([sess.run(None, {"tokens": tok(texts[i:i + 64])})[0]
                              for i in range(0, len(texts), 64)])
        k, mat = 0, []
        for t in vocab:
            n = len(t["phrases"]) * len(templates)
            v = emb[k:k + n].mean(0)
            mat.append(v / np.linalg.norm(v))
            k += n
        out[name] = np.stack(mat).astype(np.float32)
    os.makedirs(a.work, exist_ok=True)
    np.savez(os.path.join(a.work, "text.npz"), **out)
    print(json.dumps({k: list(v.shape) for k, v in out.items()}))


# ---------------------------------------------------------------- int8 codec (= VisualVectorCodec)

def quantize(v):
    import numpy as np
    u = v / np.linalg.norm(v)
    scale = float(np.abs(u).max()) / 127.0
    q = np.clip(np.rint(u / scale), -127, 127).astype(np.int8)
    return q, np.float32(scale)


def dequantize(q, scale):
    import numpy as np
    v = q.astype(np.float32) * np.float32(scale)
    return v / np.linalg.norm(v)


# ---------------------------------------------------------------- calibrate

def embed_images(ids, cache_dir, work):
    import numpy as np
    from PIL import Image
    path = os.path.join(work, "images.npz")
    have = {}
    if os.path.exists(path):
        z = np.load(path)
        have = dict(zip(z["ids"].tolist(), z["emb"]))
    todo = [i for i in ids if i not in have and os.path.exists(os.path.join(cache_dir, i + ".jpg"))]
    if todo:
        sess = session(joined_asset("siglip2", IMAGE_SHA))

        def prep(i):  # SiglipPreprocess.kt contract: squash bilinear 224, (x/255-0.5)/0.5
            im = Image.open(os.path.join(cache_dir, i + ".jpg")).convert("RGB").resize((224, 224), Image.BILINEAR)
            x = np.asarray(im, np.float32) / 255.0
            return ((x - 0.5) / 0.5).transpose(2, 0, 1)

        def save():
            keys = sorted(have)
            np.savez(path + ".tmp.npz", ids=np.array(keys), emb=np.stack([have[k] for k in keys]))
            os.replace(path + ".tmp.npz", path)

        for s in range(0, len(todo), 32):
            batch = todo[s:s + 32]
            e = sess.run(None, {"pixels": np.stack([prep(i) for i in batch])})[0]
            have.update(zip(batch, e))
            if s % 1024 == 0:
                print(f"embedded {s + len(batch)}/{len(todo)}", file=sys.stderr)
                save()  # resumable: a rerun embeds only what is missing
        save()
    return have


def half(image_id):
    """Deterministic split: calibration half 'c' or evaluation half 'e'."""
    return "c" if hashlib.sha256(image_id.encode()).digest()[0] % 2 == 0 else "e"


def prf(scores, labels, thr):
    tp = sum(1 for s, y in zip(scores, labels) if s >= thr and y)
    fp = sum(1 for s, y in zip(scores, labels) if s >= thr and not y)
    fn = sum(1 for s, y in zip(scores, labels) if s < thr and y)
    p = tp / (tp + fp) if tp + fp else 1.0
    r = tp / (tp + fn) if tp + fn else 0.0
    return p, r


def average_precision(scores, labels):
    order = sorted(range(len(scores)), key=lambda i: -scores[i])
    hits, total, ap = 0, sum(labels), 0.0
    for rank, i in enumerate(order, 1):
        if labels[i]:
            hits += 1
            ap += hits / rank
    return ap / total if total else float("nan")


def quantile(values, q):
    v = sorted(values)
    if not v:
        return 1.0
    k = min(len(v) - 1, max(0, math.ceil(q * len(v)) - 1))
    return v[k]


def choose_threshold(scores, labels, bg_scores):
    """Max F-beta on the calibration half, never below the 1% background cut."""
    floor = quantile(bg_scores, 1 - MAX_BG_FPR)
    best, best_f = floor, -1.0
    for thr in sorted(set(s for s, y in zip(scores, labels) if y)):
        if thr < floor:
            continue
        p, r = prf(scores, labels, thr)
        b2 = F_BETA * F_BETA
        f = (1 + b2) * p * r / (b2 * p + r) if p + r else 0.0
        if f > best_f:
            best, best_f = thr, f
    return best


def cmd_calibrate(a):
    import numpy as np
    vocab = read_vocab()
    tag_ids = [t["id"] for t in vocab]
    text = np.load(os.path.join(a.work, "text.npz"))
    rows = list(csv.DictReader(open(MANIFEST, newline="", encoding="utf-8")))
    ids = sorted({r["ImageID"] for r in rows})
    emb = embed_images(ids, a.cache, a.work)
    present = [i for i in ids if i in emb]
    # Store-side codec round trip: the phone scores int8-coded image vectors.
    img = np.stack([dequantize(*quantize(emb[i])) for i in present])
    index = {i: k for k, i in enumerate(present)}

    pos, hard, bg = collections.defaultdict(set), collections.defaultdict(set), set()
    for r in rows:
        if r["ImageID"] not in index:
            continue
        if r["Tag"] == "":
            bg.add(r["ImageID"])
        elif r["Label"] == "1":
            pos[r["Tag"]].add(r["ImageID"])
        else:
            hard[r["Tag"]].add(r["ImageID"])

    def pools(tid, h):
        p = [i for i in pos[tid] if half(i) == h]
        hn = [i for i in hard[tid] if half(i) == h and i not in pos[tid]]
        b = [i for i in bg if half(i) == h and i not in pos[tid]]
        conf = sorted({i for c in next(t for t in vocab if t["id"] == tid)["confusers"]
                       for i in pos[c] if half(i) == h and i not in pos[tid]})
        return p, hn, b, conf

    # Template choice: mean AP over tags with enough labels, calibration half only.
    template_map = {}
    for name in TEMPLATE_SETS:
        mats = np.stack([dequantize(*quantize(v)) for v in text[name]])
        aps = []
        for k, tid in enumerate(tag_ids):
            p, hn, b, _ = pools(tid, "c")
            if len(p) < MIN_POS_PER_HALF:
                continue
            ids_ = p + hn + b
            s = (img[[index[i] for i in ids_]] @ mats[k]).tolist()
            aps.append(average_precision(s, [1] * len(p) + [0] * (len(hn) + len(b))))
        template_map[name] = round(float(np.mean(aps)), 4)
    chosen = max(template_map, key=template_map.get)
    quant = [quantize(v) for v in text[chosen]]
    mats = np.stack([dequantize(q, s) for q, s in quant])

    per_tag, thresholds, calibrated = [], [], []
    for k, t in enumerate(vocab):
        tid = t["id"]
        cp, chn, cb, _ = pools(tid, "c")
        ep, ehn, eb, econf = pools(tid, "e")
        sc = lambda lst: (img[[index[i] for i in lst]] @ mats[k]).tolist() if lst else []  # noqa: E731
        cal_ok = len(cp) >= MIN_POS_PER_HALF and len(ep) >= MIN_POS_PER_HALF
        bg_all = sc(cb) + sc(eb)
        if cal_ok:
            thr = choose_threshold(sc(cp) + sc(chn) + sc(cb), [1] * len(cp) + [0] * (len(chn) + len(cb)), sc(cb))
        else:
            thr = quantile(bg_all, 1 - UNCAL_BG_FPR)
        thr = round(float(thr), 4)
        rec = {"id": tid, "calibrated": cal_ok, "threshold_cos": thr,
               "threshold_p": round(1 / (1 + math.exp(-(LOGIT_T * thr + LOGIT_B))), 4),
               "n": {"pos_c": len(cp), "pos_e": len(ep), "hard_e": len(ehn), "bg_e": len(eb), "confusers_e": len(econf)}}
        es_p, es_h, es_b, es_c = sc(ep), sc(ehn), sc(eb), sc(econf)
        if ep:
            p, r = prf(es_p + es_h + es_b, [1] * len(ep) + [0] * (len(ehn) + len(eb)), thr)
            rec.update(precision=round(p, 3), recall=round(r, 3),
                       ap=round(average_precision(es_p + es_h + es_b, [1] * len(ep) + [0] * (len(ehn) + len(eb))), 3))
            pv, rv = prf(es_p + es_h, [1] * len(ep) + [0] * len(ehn), thr)
            rec.update(precision_verified_only=round(pv, 3))
        rec["hard_negative_fpr"] = round(sum(s >= thr for s in es_h) / len(es_h), 3) if es_h else None
        rec["confuser_fpr"] = round(sum(s >= thr for s in es_c) / len(es_c), 3) if es_c else None
        rec["background_fpr"] = round(sum(s >= thr for s in es_b) / len(es_b), 4) if es_b else None
        rec["default_on"] = bool(cal_ok and rec.get("precision", 0) >= GATE_PRECISION
                                 and rec.get("recall", 0) >= GATE_RECALL)
        per_tag.append(rec)
        thresholds.append(thr)
        calibrated.append(rec["default_on"])

    # Tags per image on the background evaluation half: what a library would see.
    eb_all = [i for i in bg if half(i) == "e"]
    B = img[[index[i] for i in eb_all]] @ mats.T
    thr_v = np.array(thresholds, np.float32)
    cal_v = np.array(calibrated)
    per_img_all = (B >= thr_v).sum(1)
    per_img_cal = ((B >= thr_v) & cal_v).sum(1)

    cal = [r for r in per_tag if r["calibrated"]]
    on = [r for r in per_tag if r["default_on"]]
    report = {
        "unit": "TAGS-0 atlas-gallery#667",
        "image_model": IMAGE_MODEL_VERSION, "text_model": TEXT_MODEL_VERSION,
        "tokenizer_sha256": TOKENIZER_SHA,
        "vocabulary_sha256": sha256_file(VOCAB), "manifest_sha256": sha256_file(MANIFEST),
        "images_in_manifest": len(ids), "images_embedded": len(present),
        "templates": {"mean_ap_calibration_half": template_map, "chosen": chosen, "set": TEMPLATE_SETS[chosen]},
        "rules": {"cap_pos": CAP_POS, "cap_hard": CAP_HARD, "background": BACKGROUND,
                  "min_pos_per_half": MIN_POS_PER_HALF, "max_bg_fpr": MAX_BG_FPR,
                  "uncalibrated_bg_fpr": UNCAL_BG_FPR, "f_beta": F_BETA,
                  "default_on_gate": {"precision": GATE_PRECISION, "recall": GATE_RECALL}},
        "summary": {
            "tags": len(per_tag), "calibrated": len(cal), "default_on": len(on),
            "default_on_median_precision": round(float(np.median([r["precision"] for r in on])), 3) if on else None,
            "default_on_median_recall": round(float(np.median([r["recall"] for r in on])), 3) if on else None,
            "median_precision": round(float(np.median([r["precision"] for r in cal])), 3) if cal else None,
            "median_recall": round(float(np.median([r["recall"] for r in cal])), 3) if cal else None,
            "median_ap": round(float(np.median([r["ap"] for r in cal])), 3) if cal else None,
            "median_hard_negative_fpr": round(float(np.median([r["hard_negative_fpr"] for r in cal if r["hard_negative_fpr"] is not None])), 3) if cal else None,
            "background_tags_per_image_mean_all": round(float(per_img_all.mean()), 2),
            "background_tags_per_image_mean_default_on": round(float(per_img_cal.mean()), 2),
            "background_images_without_tag_default_on": round(float((per_img_cal == 0).mean()), 3),
        },
        "tags": per_tag,
    }
    os.makedirs(os.path.dirname(a.report), exist_ok=True)
    with open(a.report, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=1, ensure_ascii=False)
        f.write("\n")
    write_assets(vocab, quant, thresholds, calibrated, a.out_dir)
    print(json.dumps(report["summary"]))


# ---------------------------------------------------------------- assets

def write_assets(vocab, quant, thresholds, default_on, out_dir):
    """tags.bin (matrix + thresholds, read by TagMatrixCodec.kt) and tags.tsv (names)."""
    os.makedirs(out_dir, exist_ok=True)
    vocab_sha = sha256_file(VOCAB)

    def s(x):
        b = x.encode("utf-8")
        return struct.pack("<H", len(b)) + b

    buf = bytearray(MAGIC)
    buf += struct.pack("<ii", DIM, len(vocab))
    buf += s(IMAGE_MODEL_VERSION) + s(TEXT_MODEL_VERSION) + s(vocab_sha[:12])
    for t, (q, scale), thr, on in zip(vocab, quant, thresholds, default_on):
        buf += s(t["id"]) + struct.pack("<ffB", thr, float(scale), 1 if on else 0) + q.tobytes()
    with open(os.path.join(out_dir, "tags.bin"), "wb") as f:
        f.write(bytes(buf))
    with open(os.path.join(out_dir, "tags.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("id\tcategory\ten\tnl\n")
        for t in vocab:
            f.write(f"{t['id']}\t{t['category']}\t{t['en']}\t{t['nl']}\n")


def cmd_verify(a):
    vocab = read_vocab()
    data = open(os.path.join(a.out_dir, "tags.bin"), "rb").read()
    if data[:8] != MAGIC:
        raise SystemExit("bad magic")
    dim, n = struct.unpack_from("<ii", data, 8)
    off = 16

    def s():
        nonlocal off
        (ln,) = struct.unpack_from("<H", data, off)
        v = data[off + 2:off + 2 + ln].decode("utf-8")
        off += 2 + ln
        return v

    img, txt, vsha = s(), s(), s()
    if (dim, n, img, txt, vsha) != (DIM, len(vocab), IMAGE_MODEL_VERSION, TEXT_MODEL_VERSION, sha256_file(VOCAB)[:12]):
        raise SystemExit(f"header mismatch: {(dim, n, img, txt, vsha)}")
    for t in vocab:
        if s() != t["id"]:
            raise SystemExit("tag order mismatch")
        off += 9 + dim
    if off != len(data):
        raise SystemExit("trailing bytes")
    names = [line.split("\t")[0] for line in open(os.path.join(a.out_dir, "tags.tsv"), encoding="utf-8").read().splitlines()[1:]]
    if names != [t["id"] for t in vocab]:
        raise SystemExit("tags.tsv order mismatch")
    print(json.dumps({"ok": True, "tags": n, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("select")
    s.add_argument("--classes", required=True, help="oidv7-class-descriptions.csv")
    s.add_argument("--labels", required=True, action="append", help="*-annotations-human-imagelabels.csv (repeatable)")
    s.add_argument("--images", required=True, action="append", nargs=2, metavar=("SPLIT", "CSV"),
                   help="S3 split name and an image list with ImageID,License columns (repeatable)")
    s.add_argument("--out", default=MANIFEST)
    e = sub.add_parser("encode")
    e.add_argument("--tokenizer", required=True, help="gemma_tokenizer.model (pinned in models.json)")
    e.add_argument("--work", required=True)
    c = sub.add_parser("calibrate")
    c.add_argument("--cache", required=True, help="licensed image cache from fetch_images.py")
    c.add_argument("--work", required=True)
    c.add_argument("--report", default=os.path.join(ROOT, "docs", "visual-search", "results", "tags0_calibration.json"))
    c.add_argument("--out-dir", default=OUT_DIR)
    v = sub.add_parser("verify")
    v.add_argument("--out-dir", default=OUT_DIR)
    a = ap.parse_args()
    {"select": cmd_select, "encode": cmd_encode, "calibrate": cmd_calibrate, "verify": cmd_verify}[a.cmd](a)


if __name__ == "__main__":
    main()
