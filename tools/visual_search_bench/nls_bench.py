#!/usr/bin/env python3
"""NLS-0 benchmark: text-to-image retrieval in the shipped SigLIP 2 space.

Ranks the licensed gallery (data/nls0_manifest.csv) for every query in
data/nls0_queries.json, in English and Dutch, and scores the ranking against
data/nls0_judgments.csv (see build_relational.py). The ranking is over the
whole gallery; scoring uses only the images judged for that query
(condensed lists), so unannotated images neither help nor hurt.

Metrics per query, then macro means over queries:
  P@10, R@50, nDCG@10 (binary gain), AP, and the hard-negative error rate:
  the share of (positive, hard negative) pairs where the hard negative scores
  at least as high, i.e. 1 - AUC over the one-difference pairs.

Methods:
  cosine        plain <text, image> with the shipped int8 image vectors;
  structured    the top 100 re-scored with on-device signals parsed from the
                query (people count from YuNet + the MediaPipe person
                detector, standing/sitting from BlazePose, negation by
                subtracting the negated phrase, and person crops re-embedded
                with the same image tower).

Usage:
  nls_bench.py --images ~/.cache/atlas-vs/nls0_images --onnx-dir ~/.cache/atlas-vs/onnx \
      --work ~/.cache/atlas-vs/nls0_work --out results.json
"""
import argparse
import collections
import csv
import hashlib
import json
import os
import re
import statistics
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "models")
sys.path.insert(0, HERE)
import text_tower as tt  # noqa: E402

SHIPPED_SHA = "74da3ac2b46e4292c4639affc3cca623c2d5551d012ead113b1486c22e0f5336"
RERANK_K = 100


# ---------------------------------------------------------------- metrics

def crux_kind(crux):
    """Groups a hard negative's crux into the kind of difference it tests."""
    if crux.startswith("people="):
        return "count"
    if " Man" in crux or " Woman" in crux:
        return "gender composition"
    if crux in ("side_by_side", "not side_by_side"):
        return "spatial (beside / behind)"
    if crux == "from_behind":
        return "viewpoint (from behind)"
    if crux.startswith("not "):
        return "negation"
    return "object/action/scene label"


def query_metrics(scores, judged, crux=None):
    """scores: dict ImageID -> score over the gallery; judged: dict ImageID -> kind;
    crux: optional dict ImageID -> crux of each hard negative."""
    ids = [i for i in judged if i in scores]
    order = sorted(ids, key=lambda i: -scores[i])
    rel = np.array([judged[i] == "pos" for i in order], bool)
    npos = int(rel.sum())
    if npos == 0:
        return None
    hits = np.cumsum(rel)
    ap = float((hits / np.arange(1, len(rel) + 1))[rel].mean())
    gains = rel[:10] / np.log2(np.arange(2, min(10, len(rel)) + 2))
    ideal = (1 / np.log2(np.arange(2, min(10, npos) + 2))).sum()
    pos_s = np.array([scores[i] for i in ids if judged[i] == "pos"])
    hn_s = np.array([scores[i] for i in ids if judged[i] == "hn"])
    hn_err = float((hn_s[None, :] >= pos_s[:, None]).mean()) if len(hn_s) else None
    by_kind = {}
    if crux:
        groups = collections.defaultdict(list)
        for i in ids:
            if judged[i] == "hn":
                groups[crux_kind(crux[i])].append(scores[i])
        by_kind = {k: float((np.array(v)[None, :] >= pos_s[:, None]).mean()) for k, v in groups.items()}
    return {"p10": float(rel[:10].mean()), "r50": float(rel[:50].sum() / npos),
            "ndcg10": float(gains.sum() / ideal), "ap": ap, "hn_err": hn_err,
            "hn_in_top10": float(np.mean([judged[i] == "hn" for i in order[:10]])),
            "n_pos": npos, "n_hn": int(len(hn_s)), "n_judged": len(ids),
            "chance_p10": npos / len(ids), "hn_err_by_kind": by_kind}


def macro(per):
    out = {}
    for k in ("p10", "r50", "ndcg10", "ap", "hn_err", "hn_in_top10", "chance_p10"):
        v = [m[k] for m in per.values() if m and m.get(k) is not None]
        out[k] = round(float(np.mean(v)), 4) if v else None
    out["queries"] = sum(1 for m in per.values() if m)
    kinds = collections.defaultdict(list)
    for m in per.values():
        for k, v in (m or {}).get("hn_err_by_kind", {}).items():
            kinds[k].append(v)
    out["hn_err_by_kind"] = {k: {"err": round(float(np.mean(v)), 4), "queries": len(v)} for k, v in sorted(kinds.items())}
    return out


# ---------------------------------------------------------------- query parsing (EN + NL)

NUM = {"one": 1, "a single": 1, "alone": 1, "één": 1, "alleen": 1, "two": 2, "twee": 2, "a couple": 2,
       "three": 3, "drie": 3, "four": 4, "vier": 4}
SINGULAR = r"\b(a|one|someone|een|iemand|eén)\s+(person|man|woman|persoon|man|vrouw|bride|bruid)\b|\bsomeone\b|\biemand\b"
NONE = r"\b(no people|without people|empty|zonder mensen|leeg|lege|with no people)\b"
CROWD = r"\b(crowd|crowded|menigte|druk|drukke)\b"
STAND = r"\b(standing|stand|staan|staat|staand)\b"
SIT = r"\b(sitting|sit|seated|zit|zitten|zittend)\b"
NEG = r"\b(?:without|with no|zonder|geen)\s+([a-zà-ÿ ]+?)(?:[.,]|$| in | on | op )"


def parse(text):
    t = text.lower()
    out = {}
    if re.search(NONE, t):
        out["count"] = 0
    else:
        hits = [(m.start(), n) for w, n in NUM.items() for m in re.finditer(rf"\b{w}\b", t)]
        persons = len(re.findall(SINGULAR, t))
        if hits:
            out["count"] = min(hits)[1]  # the first number named: "two people, one behind the other" -> 2
        elif persons >= 2:
            out["count"] = persons     # "a woman ... beside a man" -> 2
        elif re.search(CROWD, t):
            out["count"] = "many"
        elif persons == 1:
            out["count"] = 1
    if re.search(STAND, t):
        out["pose"] = "standing"
    elif re.search(SIT, t):
        out["pose"] = "sitting"
    m = re.search(NEG, t + ".")
    if m:
        out["negated"] = m.group(1).strip()
    return out


# ---------------------------------------------------------------- image side

def shipped_image_model():
    d = os.path.join(ASSETS, "siglip2")
    parts = sorted((p for p in os.listdir(d) if ".part" in p), key=lambda p: int(p.rsplit("part", 1)[1]))
    data = b"".join(open(os.path.join(d, p), "rb").read() for p in parts)
    if hashlib.sha256(data).hexdigest() != SHIPPED_SHA:
        raise SystemExit("shipped SigLIP 2 asset hash mismatch")
    return data


def session(model, threads):
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = 1
    return ort.InferenceSession(model, so, providers=["CPUExecutionProvider"])


def preprocess(im):
    from PIL import Image
    x = np.asarray(im.convert("RGB").resize((224, 224), Image.BILINEAR), np.float32) / 255.0
    return ((x - 0.5) / 0.5).transpose(2, 0, 1)


def embed_images(sess, items, cache):
    if os.path.exists(cache):
        return np.load(cache)
    out = []
    for i in range(0, len(items), 32):
        out.append(sess.run(None, {"pixels": np.stack([preprocess(im()) for im in items[i:i + 32]])})[0])
    e = np.concatenate(out).astype(np.float32) if out else np.zeros((0, 768), np.float32)
    np.save(cache, e)
    return e


def detect_people(paths, cache):
    """Per image: YuNet faces, MediaPipe person boxes, BlazePose thigh angle."""
    if os.path.exists(cache):
        return json.load(open(cache))
    import cv2
    from blazepose import BlazePose
    yunet = os.path.join(ASSETS, "yunet", [f for f in os.listdir(os.path.join(ASSETS, "yunet")) if f.endswith(".onnx")][0])
    bp = BlazePose(os.path.join(ASSETS, "blazepose", "person_detection_mediapipe_2023mar.onnx"),
                   os.path.join(ASSETS, "blazepose", "pose_estimation_mediapipe_2023mar.onnx"), threads=1)
    out = {}
    for p in paths:
        bgr = cv2.imread(p)
        h, w = bgr.shape[:2]
        fd = cv2.FaceDetectorYN.create(yunet, "", (w, h), 0.6, 0.3, 5000)
        _, faces = fd.detect(bgr)
        faces = [] if faces is None else [f[:4].tolist() for f in faces if f[2] * f[3] >= 0.0004 * w * h]
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        persons = person_boxes(bp, rgb)
        world = bp.world_landmarks(rgb)
        thigh = None
        if world is not None:
            # landmarks 23/24 hips, 25/26 knees (y down): angle of the thigh from vertical
            v = (world[25] + world[26]) / 2 - (world[23] + world[24]) / 2
            thigh = float(np.degrees(np.arctan2(np.hypot(v[0], v[2]), abs(v[1]) + 1e-6)))
        out[os.path.basename(p)[:-4]] = {"faces": faces, "persons": persons, "thigh": thigh, "w": w, "h": h}
    json.dump(out, open(cache, "w"))
    return out


def person_boxes(bp, rgb, score=0.5):
    import cv2
    from blazepose import DET_SIZE
    h, w = rgb.shape[:2]
    ratio = min(DET_SIZE / h, DET_SIZE / w)
    nh, nw = int(h * ratio), int(w * ratio)
    img = cv2.resize(rgb.astype(np.float32) / 127.5 - 1.0, (nw, nh))
    left, top = (DET_SIZE - nw) // 2, (DET_SIZE - nh) // 2
    canvas = np.zeros((DET_SIZE, DET_SIZE, 3), np.float32)
    canvas[top:top + nh, left:left + nw] = img
    outs = bp.det.run(None, {bp.det_in: canvas.transpose(2, 0, 1)[None]})
    reg, cls = outs[bp.det_reg][0], outs[bp.det_cls][0, :, 0]
    s = 1 / (1 + np.exp(-np.clip(cls.astype(np.float64), -100, 100)))
    scale = max(w, h)
    pad = np.array([left, top], np.float32) / ratio
    # each detection's hip centre and full-body radius give a body box
    boxes = []
    for i in range(len(s)):
        if s[i] < score:
            continue
        kp = (reg[i, 4:12].reshape(4, 2) / DET_SIZE + bp.anchors[i]) * scale - pad
        hip, full = kp[0], kp[1]
        r = float(np.linalg.norm(hip - full))
        boxes.append((float(s[i]), [float(hip[0] - r * 0.6), float(hip[1] - r), float(hip[0] + r * 0.6), float(hip[1] + r)]))
    if not boxes:
        return []
    xywh = [[b[0], b[1], b[2] - b[0], b[3] - b[1]] for _, b in boxes]
    keep = np.asarray(cv2.dnn.NMSBoxes(xywh, [sc for sc, _ in boxes], score, 0.3)).reshape(-1)
    return [boxes[i][1] for i in keep]


def people_count(d):
    return max(len(d["faces"]), len(d["persons"]))


def crops(path, det, k=2):
    """The k largest detected bodies (faces expanded to an upper body), as lazy PIL crops."""
    from PIL import Image
    w, h = det["w"], det["h"]
    boxes = list(det["persons"])
    for x, y, fw, fh in det["faces"]:
        boxes.append([x - fw * 1.5, y - fh * 0.5, x + fw * 2.5, y + fh * 5])
    boxes.sort(key=lambda b: -(b[2] - b[0]) * (b[3] - b[1]))
    out = []
    for b in boxes[:k]:
        x0, y0, x1, y1 = max(0, b[0]), max(0, b[1]), min(w, b[2]), min(h, b[3])
        if (x1 - x0) * (y1 - y0) < 0.02 * w * h:
            continue
        out.append(lambda b=(x0, y0, x1, y1): Image.open(path).crop(tuple(int(v) for v in b)))
    return out


# ---------------------------------------------------------------- structured rerank

def structured(q_vec, parsed, ids, base, dets, crop_vecs, neg_vec, w_count, w_pose, w_crop, w_neg):
    """Re-score the top RERANK_K of `base` (ImageID -> cosine)."""
    top = sorted(ids, key=lambda i: -base[i])[:RERANK_K]
    cos = np.array([base[i] for i in top])
    sd = cos.std() + 1e-6
    out = dict(base)
    for j, i in enumerate(top):
        s = cos[j]
        d = dets[i]
        want = parsed.get("count")
        if want is not None and w_count:
            n = people_count(d)
            if want == "many":
                agree = 1.0 if n >= 5 else (-0.5 if n <= 2 else 0.0)
            else:
                agree = 1.0 if n == want else -min(abs(n - want), 2) / 2
            s += w_count * sd * agree
        if parsed.get("pose") and w_pose and d["thigh"] is not None:
            sitting = d["thigh"] > 50
            standing = d["thigh"] < 30
            ok = (parsed["pose"] == "sitting" and sitting) or (parsed["pose"] == "standing" and standing)
            bad = (parsed["pose"] == "sitting" and standing) or (parsed["pose"] == "standing" and sitting)
            s += w_pose * sd * (1.0 if ok else (-1.0 if bad else 0.0))
        if w_crop and crop_vecs.get(i) is not None and len(crop_vecs[i]):
            s += w_crop * max(0.0, float((crop_vecs[i] @ q_vec).max()) - cos[j])
        if neg_vec is not None and w_neg:
            s -= w_neg * float(neg_vec[i])
        out[i] = s + 10.0  # keep the re-ranked block above the tail
    return out


def text_latency(sess, tokens, threads_label):
    for _ in range(3):
        sess.run(None, {"tokens": tokens})
    t = []
    for _ in range(20):
        t0 = time.perf_counter()
        sess.run(None, {"tokens": tokens})
        t.append((time.perf_counter() - t0) * 1000)
    return round(statistics.median(t), 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", required=True)
    ap.add_argument("--onnx-dir", required=True)
    ap.add_argument("--weights-dir", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=True)
    data = os.path.join(HERE, "data")
    queries = json.load(open(os.path.join(data, "nls0_queries.json"), encoding="utf-8"))["queries"]
    # licensed.csv: the manifest rows whose CC BY 2.0 licence fetch_images.py re-checked and fetched
    lic_path = os.path.join(a.work, "licensed.csv")
    gallery = [r["ImageID"] for r in csv.DictReader(open(lic_path))]
    judged = collections.defaultdict(dict)
    cruxes = collections.defaultdict(dict)
    for r in csv.DictReader(open(os.path.join(data, "nls0_judgments.csv"))):
        judged[r["QueryID"]][r["ImageID"]] = r["Kind"]
        if r["Kind"] == "hn":
            cruxes[r["QueryID"]][r["ImageID"]] = r["Crux"]
    paths = [os.path.join(a.images, i + ".jpg") for i in gallery]

    # image vectors: the shipped int8 graph, and fp32 for the int8-vs-fp32 check
    from PIL import Image
    loaders = [lambda p=p: Image.open(p) for p in paths]
    img_int8 = embed_images(session(shipped_image_model(), os.cpu_count()), loaders, os.path.join(a.work, "img_int8.npy"))
    fp32_img = os.path.join(a.onnx_dir, "siglip2_b16_224.fp32.onnx")
    img_fp32 = embed_images(session(fp32_img, os.cpu_count()), loaders, os.path.join(a.work, "img_fp32.npy")) \
        if os.path.exists(fp32_img) else None
    index = {i: k for k, i in enumerate(gallery)}

    tok = tt.Tokenizer(os.path.join(a.weights_dir, "gemma_tokenizer.model"))
    text_models = {v: os.path.join(a.onnx_dir, f"siglip2_b16_224_text.{v}.onnx") for v in ("fp32", "int8", "int8_gather")}
    text_sess = {v: session(p, os.cpu_count()) for v, p in text_models.items() if os.path.exists(p)}

    def encode(texts, variant):
        return text_sess[variant].run(None, {"tokens": tok(texts)})[0]

    result = {"gallery": len(gallery), "queries": len(queries), "rerank_k": RERANK_K, "runs": {}}
    if img_fp32 is not None:
        result["image_int8_vs_fp32_cos_mean"] = round(float((img_int8 * img_fp32).sum(1).mean()), 4)
        result["image_int8_vs_fp32_cos_min"] = round(float((img_int8 * img_fp32).sum(1).min()), 4)

    def run(name, qvecs, imgs, lang):
        per = {}
        for q, v in zip(queries, qvecs):
            s = imgs @ v
            per[q["id"]] = query_metrics({i: float(s[index[i]]) for i in gallery}, judged[q["id"]], cruxes[q["id"]])
        result["runs"][name] = {"macro": macro(per), "per_query": {k: {kk: (round(vv, 4) if isinstance(vv, float) else vv)
                                                                      for kk, vv in m.items() if kk != "hn_err_by_kind"} if m else None
                                                                  for k, m in per.items()}}
        print(name, result["runs"][name]["macro"], flush=True)
        return per

    vec = {}
    for lang in ("en", "nl"):
        texts = [q[lang] for q in queries]
        for v in text_sess:
            vec[(lang, v)] = encode(texts, v)
            run(f"cosine/{lang}/text_{v}/img_int8", vec[(lang, v)], img_int8, lang)
        if img_fp32 is not None:
            run(f"cosine/{lang}/text_fp32/img_fp32", vec[(lang, "fp32")], img_fp32, lang)
        # control: shuffled query vectors (chance with this gallery)
        rng = np.random.default_rng(104)
        run(f"control_shuffled/{lang}", vec[(lang, "fp32")][rng.permutation(len(queries))], img_int8, lang)
    for v in ("int8", "int8_gather"):
        if ("en", v) in vec:
            c = np.concatenate([(vec[(l, v)] * vec[(l, "fp32")]).sum(1) for l in ("en", "nl")])
            result[f"text_{v}_vs_fp32_cos"] = {"min": round(float(c.min()), 4), "mean": round(float(c.mean()), 4)}
    c = (vec[("en", "fp32")] * vec[("nl", "fp32")]).sum(1)
    result["en_nl_query_cos"] = {"min": round(float(c.min()), 4), "mean": round(float(c.mean()), 4)}

    # structured rerank signals
    dets = detect_people(paths, os.path.join(a.work, "people.json"))
    crop_items, owners = [], []
    for i, p in zip(gallery, paths):
        for c in crops(p, dets[i]):
            crop_items.append(c)
            owners.append(i)
    cvec = embed_images(session(shipped_image_model(), os.cpu_count()), crop_items, os.path.join(a.work, "crops_int8.npy"))
    crop_vecs = collections.defaultdict(list)
    for o, v in zip(owners, cvec):
        crop_vecs[o].append(v)
    crop_vecs = {k: np.stack(v) for k, v in crop_vecs.items()}
    result["signals"] = {"images_with_face": sum(1 for d in dets.values() if d["faces"]),
                         "images_with_person_box": sum(1 for d in dets.values() if d["persons"]),
                         "images_with_pose": sum(1 for d in dets.values() if d["thigh"] is not None),
                         "crops": len(crop_items)}

    # count-signal accuracy against the benchmark's own people annotations is in the report
    configs = {
        "count": dict(w_count=1.0, w_pose=0, w_crop=0, w_neg=0),
        "count_x2": dict(w_count=2.0, w_pose=0, w_crop=0, w_neg=0),
        "pose": dict(w_count=0, w_pose=1.0, w_crop=0, w_neg=0),
        "crop": dict(w_count=0, w_pose=0, w_crop=1.0, w_neg=0),
        "negation": dict(w_count=0, w_pose=0, w_crop=0, w_neg=1.0),
        "all": dict(w_count=1.0, w_pose=1.0, w_crop=1.0, w_neg=1.0),
    }
    parsed_all = {}
    for lang in ("en", "nl"):
        qv = vec[(lang, "int8")] if (lang, "int8") in vec else vec[(lang, "fp32")]
        parsed = [parse(q[lang]) for q in queries]
        parsed_all[lang] = {q["id"]: p for q, p in zip(queries, parsed)}
        neg_texts = [p.get("negated") for p in parsed]
        for cname, cfg in configs.items():
            per = {}
            for q, v, p, nt in zip(queries, qv, parsed, neg_texts):
                s = img_int8 @ v
                base = {i: float(s[index[i]]) for i in gallery}
                neg_vec = None
                if nt:
                    nv = encode([nt], "fp32")[0]
                    ns = img_int8 @ nv
                    neg_vec = {i: float(ns[index[i]]) for i in gallery}
                    p = dict(p)
                scored = structured(v, p, gallery, base, dets, crop_vecs, neg_vec, **cfg)
                per[q["id"]] = query_metrics(scored, judged[q["id"]], cruxes[q["id"]])
            result["runs"][f"structured_{cname}/{lang}"] = {"macro": macro(per), "per_query": {
                k: {kk: (round(vv, 4) if isinstance(vv, float) else vv) for kk, vv in m.items() if kk != "hn_err_by_kind"} if m else None
                for k, m in per.items()}}
            print(f"structured_{cname}/{lang}", result["runs"][f"structured_{cname}/{lang}"]["macro"], flush=True)
    result["parsed"] = parsed_all

    # latency: query encoding, batch 1, host CPU
    one = tok(["a man and a woman standing in a doorway inside a church"])
    t0 = time.perf_counter()
    for _ in range(200):
        tok(["een man en een vrouw die in een deuropening van een kerk staan"])
    result["tokenizer_ms"] = round((time.perf_counter() - t0) / 200 * 1000, 3)
    result["text_latency_ms_b1"] = {v: {"1thread": text_latency(session(p, 1), one, 1),
                                        "4threads": text_latency(session(p, 4), one, 4)}
                                    for v, p in text_models.items() if os.path.exists(p)}
    sims = img_int8 @ vec[("en", "fp32")][0]
    t0 = time.perf_counter()
    for _ in range(20):
        img_int8 @ vec[("en", "fp32")][0]
    result["brute_force_ms_per_1k_images"] = round((time.perf_counter() - t0) / 20 * 1000 / len(gallery) * 1000, 4)
    del sims
    with open(a.out, "w") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)
    print("wrote", a.out)


if __name__ == "__main__":
    main()
