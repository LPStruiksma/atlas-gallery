#!/usr/bin/env python3
"""VS-S2c (#468): calibrate the semantic "find similar" cosine bands.

Embeds the VS-S0 benchmark set (Open Images action/pose images, CC BY 2.0,
fetched at run time by fetch_images.py) with the exact int8 SigLIP 2 asset
that ships in the APK, stores every vector through the app's int8 codec, and
picks the constants of VisualQueryBands in VisualQueryModel.kt:

- STRONG and FLOOR: the lowest cosine t (on a 0.01 grid) from which on the
  same-class precision P(pair shares a label | cosine >= t) stays at or above
  the target (default 0.80 for STRONG, 0.50 for FLOOR) at every higher grid
  step that has at least --min-support pairs. A pair is "same class" when the
  two images share a human-verified Open Images label, as in bench.py.
- NEAR_DUPLICATE: the lowest grid t at which at most --distinct-rate of the
  pairs of DIFFERENT images (default 1e-5, about 10 of the ~975k pairs) would
  be hidden as copies. Synthetic edits of every image (re-encode, crop,
  exposure, down/up-scale) are reported as the recall of that cutoff. The set
  holds no true copies, so distinct pairs above it are likely burst-like
  neighbours, which the app also counts as near-duplicates.

Only aggregate numbers are written (to --out); images, the joined model and
embeddings stay under --work, outside the repository.

Usage (repository root; images from fetch_images.py):
  python3 -I tools/visual_search_bench/calibrate_bands.py \
      --images ~/.cache/atlas-vs/images --set ~/.cache/atlas-vs/data/licensed.csv \
      --work ~/.cache/atlas-vs/calibrate --out ~/.cache/atlas-vs/vs_s2c_calibration.json
"""
import argparse
import csv
import hashlib
import io
import json
import os
import sys

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageEnhance

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
ASSET_DIR = os.path.join(REPO, "app", "src", "main", "assets", "models", "siglip2")
PARTS = ["siglip2_b16_224_int8.onnx.part0", "siglip2_b16_224_int8.onnx.part1"]
# Whole-file SHA-256 of the shipped int8 model (SOURCE.md, SiglipModel.MODEL_VERSION).
MODEL_SHA256 = "74da3ac2b46e4292c4639affc3cca623c2d5551d012ead113b1486c22e0f5336"
GRID = [round(0.30 + 0.01 * i, 2) for i in range(70)]  # 0.30 .. 0.99
EDITS = ["jpeg_q60", "crop_90", "exposure_+15", "half_scale"]


def joined_model(work):
    path = os.path.join(work, "siglip2_b16_224_int8.onnx")
    if not os.path.exists(path):
        with open(path + ".part", "wb") as out:
            for part in PARTS:
                with open(os.path.join(ASSET_DIR, part), "rb") as f:
                    out.write(f.read())
        os.replace(path + ".part", path)
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if digest != MODEL_SHA256:
        sys.exit(f"model SHA-256 {digest} != shipped {MODEL_SHA256}")
    return path


def preprocess(im):
    """SiglipPreprocess.kt contract: RGB, 224x224 bilinear squash, (x/255-0.5)/0.5, CHW."""
    x = np.asarray(im.convert("RGB").resize((224, 224), Image.BILINEAR), np.float32) / 255.0
    return ((x - 0.5) / 0.5).transpose(2, 0, 1)


def edit(im, kind):
    if kind == "jpeg_q60":
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=60)
        return Image.open(io.BytesIO(buf.getvalue())).convert("RGB")
    if kind == "crop_90":
        w, h = im.size
        dx, dy = round(w * 0.05), round(h * 0.05)
        return im.crop((dx, dy, w - dx, h - dy))
    if kind == "exposure_+15":
        return ImageEnhance.Brightness(im).enhance(1.15)
    if kind == "half_scale":
        w, h = im.size
        return im.resize((max(1, w // 2), max(1, h // 2)), Image.BILINEAR).resize((w, h), Image.BILINEAR)
    raise ValueError(kind)


def int8_store(e):
    """VisualVectorCodec.encode then decode: per-vector symmetric int8, re-normalised."""
    u = e / np.linalg.norm(e, axis=1, keepdims=True)
    scale = np.abs(u).max(axis=1, keepdims=True) / 127.0
    q = np.clip(np.round(u / scale), -127, 127) * scale
    return (q / np.linalg.norm(q, axis=1, keepdims=True)).astype(np.float32)


def embed(sess, images, cache):
    if os.path.exists(cache):
        return np.load(cache)
    out = []
    for i in range(0, len(images), 32):
        batch = np.stack([preprocess(im()) for im in images[i:i + 32]])
        out.append(sess.run(None, {"pixels": batch})[0])
    e = np.concatenate(out).astype(np.float32)
    np.save(cache, e)
    return e


def cumulative(cos, rel, grid):
    """Per grid t: pairs with cosine >= t, same-class precision, same-class recall."""
    order = np.argsort(-cos)
    c, r = cos[order], rel[order].astype(np.int64)
    hits = np.cumsum(r)
    total_rel = int(r.sum())
    rows = []
    for t in grid:
        n = int(np.searchsorted(-c, -t, side="right"))  # count of cos >= t
        tp = int(hits[n - 1]) if n else 0
        rows.append({"t": t, "pairs": n, "precision": (tp / n) if n else None,
                     "recall": tp / total_rel})
    return rows


def lowest_stable(rows, target, min_support):
    """Lowest t whose precision, and that of every higher supported t, is >= target."""
    chosen = None
    for row in reversed(rows):
        if row["pairs"] < min_support:
            continue
        if row["precision"] >= target:
            chosen = row["t"]
        else:
            break
    return chosen


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", required=True)
    ap.add_argument("--set", required=True, help="licensed.csv from fetch_images.py")
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--strong-target", type=float, default=0.80)
    ap.add_argument("--related-target", type=float, default=0.50)
    ap.add_argument("--min-support", type=int, default=30)
    ap.add_argument("--distinct-rate", type=float, default=1e-5)
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=True)

    rows = list(csv.DictReader(open(a.set, newline="")))
    paths = [os.path.join(a.images, r["ImageID"] + ".jpg") for r in rows]
    labels = [set(r["Labels"].split("|")) for r in rows]
    n = len(rows)

    so = ort.SessionOptions()
    so.intra_op_num_threads = os.cpu_count()
    sess = ort.InferenceSession(joined_model(a.work), so, providers=["CPUExecutionProvider"])

    def opener(p, kind=None):
        return lambda: (edit(Image.open(p).convert("RGB"), kind) if kind else Image.open(p))

    base = int8_store(embed(sess, [opener(p) for p in paths], os.path.join(a.work, "base.npy")))
    edits = {k: int8_store(embed(sess, [opener(p, k) for p in paths], os.path.join(a.work, f"edit_{k}.npy")))
             for k in EDITS}

    sim = base @ base.T
    iu = np.triu_indices(n, 1)
    cos = sim[iu]
    lab_ids = sorted({c for ls in labels for c in ls})
    onehot = np.zeros((n, len(lab_ids)), bool)
    for i, ls in enumerate(labels):
        for c in ls:
            onehot[i, lab_ids.index(c)] = True
    rel = ((onehot.astype(np.int32) @ onehot.T.astype(np.int32)) > 0)[iu]

    curve = cumulative(cos, rel, GRID)
    strong = lowest_stable(curve, a.strong_target, a.min_support)
    floor = lowest_stable(curve, a.related_target, a.min_support)

    edit_cos = {k: np.einsum("ij,ij->i", base, e) for k, e in edits.items()}
    all_edit = np.concatenate(list(edit_cos.values()))
    distinct_max = float(cos.max())
    near = next((t for t in GRID if (cos >= t).mean() <= a.distinct_rate), 1.0)
    if strong is None or floor is None or not floor < strong < near:
        sys.exit(f"no consistent bands: FLOOR {floor} STRONG {strong} NEAR_DUPLICATE {near}")
    near_curve = [{"t": t, "distinct_pairs": int((cos >= t).sum()),
                   "edit_recall": round(float((all_edit >= t).mean()), 4)} for t in GRID if t >= 0.85]

    def band_stats(lo, hi):
        m = (cos >= lo) & (cos < hi)
        return {"range": [lo, hi], "pairs": int(m.sum()),
                "precision": float(rel[m].mean()) if m.any() else None}

    # Per query, as the user sees it: precision among shown hits (cosine >= FLOOR).
    per_query = []
    np.fill_diagonal(sim, -2.0)
    same = (onehot.astype(np.int32) @ onehot.T.astype(np.int32)) > 0
    for q in range(n):
        shown = (sim[q] >= floor) & (sim[q] < near)
        if shown.any():
            per_query.append(float(same[q][shown].mean()))

    result = {
        "unit": "VS-S2c (#468)",
        "model_sha256": MODEL_SHA256,
        "stored_codec": "VisualVectorCodec int8, per-vector scale",
        "images": n, "labels": len(lab_ids), "pairs": int(cos.size),
        "same_class_base_rate": float(rel.mean()),
        "targets": {"strong": a.strong_target, "related": a.related_target, "min_support": a.min_support,
                    "distinct_rate": a.distinct_rate},
        "chosen": {"STRONG": strong, "FLOOR": floor, "NEAR_DUPLICATE": near},
        "bands": {"strong": band_stats(strong, near), "related": band_stats(floor, strong),
                  "below_floor": band_stats(-1.0, floor)},
        "per_query_precision_shown": {
            "queries_with_hits": len(per_query),
            "mean": float(np.mean(per_query)) if per_query else None,
            "median": float(np.median(per_query)) if per_query else None},
        "distinct_pairs": {"max": distinct_max,
                           "p99": float(np.percentile(cos, 99)), "p99_9": float(np.percentile(cos, 99.9)),
                           "at_or_above_near_duplicate": int((cos >= near).sum())},
        "edits": {k: {"min": float(v.min()), "p1": float(np.percentile(v, 1)),
                      "median": float(np.median(v)), "below_near_duplicate": int((v < near).sum())}
                  for k, v in edit_cos.items()},
        "edits_all": {"recall_at_near_duplicate": float((all_edit >= near).mean()),
                      "p1": float(np.percentile(all_edit, 1))},
        "near_duplicate_curve": near_curve,
        "curve": [{**r, "precision": None if r["precision"] is None else round(r["precision"], 4),
                   "recall": round(r["recall"], 4)} for r in curve],
    }
    with open(a.out, "w") as f:
        json.dump(result, f, indent=1)
    print(json.dumps({k: result[k] for k in ("images", "pairs", "same_class_base_rate", "chosen", "bands",
                                             "per_query_precision_shown", "distinct_pairs", "edits", "edits_all")}, indent=1))


if __name__ == "__main__":
    main()
