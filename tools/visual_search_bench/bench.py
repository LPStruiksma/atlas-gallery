#!/usr/bin/env python3
"""VS-S0 benchmark: size, CPU latency and query-by-image retrieval quality.

Every image in the licensed set is used as a query against all others.
For a query with label L, a result is relevant when it carries L too
(Open Images human-verified image-level labels). We report macro mAP and
P@10 over labels, the same for a pose-heavy subset, and per-label AP.

Pose fusion: fused = semantic + w * (pose_sim - mean_pose_sim) when both
images have a detected body; otherwise the semantic score alone.

Usage:
  bench.py --images ~/.cache/atlas-vs/images --set licensed.csv \
      --onnx-dir ~/.cache/atlas-vs/onnx --zoo-dir ~/.cache/atlas-vs/zoo \
      --work ~/.cache/atlas-vs/work --out results.json
"""
import argparse
import csv
import json
import os
import statistics
import sys
import time

import numpy as np
import onnxruntime as ort
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pose_descriptor as pd  # noqa: E402

POSE_HEAVY = ["Cartwheel", "Handstand", "Somersault", "Yoga", "Ballet", "Karate",
              "Judo", "Wrestling", "High jump", "Pole vault", "Meditation", "Hug",
              "Fencing", "Boxing", "Archery", "Juggling", "Trampolining", "Selfie"]
FUSION_WEIGHTS = [0.0, 0.1, 0.2, 0.3, 0.5]


def session(path, threads):
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = 1
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])


def preprocess(path, spec):
    im = Image.open(path).convert("RGB")
    s = spec["input"]
    interp = Image.BICUBIC if spec["interp"] == "bicubic" else Image.BILINEAR
    if spec["resize"] == "shortest_crop":
        w, h = im.size
        k = s / min(w, h)
        im = im.resize((max(s, round(w * k)), max(s, round(h * k))), interp)
        w, h = im.size
        l, t = (w - s) // 2, (h - s) // 2
        im = im.crop((l, t, l + s, t + s))
    else:
        im = im.resize((s, s), interp)
    x = np.asarray(im, dtype=np.float32) / 255.0
    x = (x - np.array(spec["mean"], np.float32)) / np.array(spec["std"], np.float32)
    return x.transpose(2, 0, 1)


def embed_all(onnx_path, spec, paths, cache):
    if os.path.exists(cache):
        return np.load(cache)
    sess = session(onnx_path, os.cpu_count())
    out = []
    for i in range(0, len(paths), 32):
        batch = np.stack([preprocess(p, spec) for p in paths[i:i + 32]])
        out.append(sess.run(None, {"pixels": batch})[0])
    e = np.concatenate(out).astype(np.float32)
    np.save(cache, e)
    return e


def latency(onnx_path, spec, threads, runs=30):
    sess = session(onnx_path, threads)
    x = np.random.rand(1, 3, spec["input"], spec["input"]).astype(np.float32)
    for _ in range(5):
        sess.run(None, {"pixels": x})
    t = []
    for _ in range(runs):
        t0 = time.perf_counter()
        sess.run(None, {"pixels": x})
        t.append((time.perf_counter() - t0) * 1000)
    return round(statistics.median(t), 1)


def poses_all(zoo_dir, paths, cache):
    if os.path.exists(cache):
        z = np.load(cache)
        return z["desc"], z["present"], z["ms"]
    import cv2
    from blazepose import BlazePose
    bp = BlazePose(os.path.join(zoo_dir, "person_detection_mediapipe_2023mar.onnx"),
                   os.path.join(zoo_dir, "pose_estimation_mediapipe_2023mar.onnx"), threads=1)
    desc = np.zeros((len(paths), 2, pd.DIM), np.float32)
    present = np.zeros(len(paths), bool)
    ms = []
    for i, p in enumerate(paths):
        rgb = cv2.cvtColor(cv2.imread(p), cv2.COLOR_BGR2RGB)
        t0 = time.perf_counter()
        world = bp.world_landmarks(rgb)
        ms.append((time.perf_counter() - t0) * 1000)
        if world is not None:
            desc[i] = pd.descriptor(world)
            present[i] = True
    ms = np.asarray(ms, np.float32)
    np.savez(cache, desc=desc, present=present, ms=ms)
    return desc, present, ms


def evaluate(score, labels, classes):
    """score: (N, N) similarity; labels: list of sets. Returns per-class AP/P@10."""
    n = len(labels)
    s = score.copy()
    np.fill_diagonal(s, -np.inf)
    order = np.argsort(-s, axis=1)
    per = {c: {"ap": [], "p10": []} for c in classes}
    for q in range(n):
        ranked = order[q][:-1]  # drop self (ranked last)
        for c in labels[q]:
            rel = np.fromiter((c in labels[j] for j in ranked), bool, n - 1)
            if rel.sum() == 0:
                continue
            hits = np.cumsum(rel)
            prec = hits / np.arange(1, n)
            per[c]["ap"].append(float((prec * rel).sum() / rel.sum()))
            per[c]["p10"].append(float(rel[:10].mean()))
    out = {c: {"ap": float(np.mean(v["ap"])), "p10": float(np.mean(v["p10"])), "queries": len(v["ap"])}
           for c, v in per.items() if v["ap"]}
    return out


def rerank_p10(sem, psim, present, labels, classes, w, k=50, pose_prior=0.3):
    """'Emphasise pose' control: re-rank the semantic top-k by
    sem + w * (pose_sim - pose_prior). Queries without a body keep the
    semantic order. Returns per-class P@10."""
    per = {c: [] for c in classes}
    s = sem.copy()
    np.fill_diagonal(s, -np.inf)
    for q in range(len(labels)):
        cand = np.argsort(-s[q])[:k]
        score = s[q, cand]
        if present[q]:
            score = score + w * np.nan_to_num(psim[q, cand] - pose_prior, nan=-pose_prior)
        top = cand[np.argsort(-score)][:10]
        for c in labels[q]:
            per[c].append(float(np.mean([c in labels[j] for j in top])))
    return {c: float(np.mean(v)) for c, v in per.items() if v}


def summary(per, subset=None):
    keys = [c for c in per if subset is None or c in subset]
    return {"map": round(float(np.mean([per[c]["ap"] for c in keys])), 4),
            "p10": round(float(np.mean([per[c]["p10"] for c in keys])), 4),
            "classes": len(keys)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", required=True)
    ap.add_argument("--set", required=True)
    ap.add_argument("--onnx-dir", required=True)
    ap.add_argument("--zoo-dir", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", action="append")
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=True)

    rows = list(csv.DictReader(open(a.set, newline="")))
    paths = [os.path.join(a.images, r["ImageID"] + ".jpg") for r in rows]
    labels = [set(r["Labels"].split("|")) for r in rows]
    classes = sorted({c for ls in labels for c in ls})
    specs = json.load(open(os.path.join(HERE, "models.json")))
    export = json.load(open(os.path.join(a.onnx_dir, "export_report.json")))

    desc, present, pose_ms = poses_all(a.zoo_dir, paths, os.path.join(a.work, "pose.npz"))
    psim = pd.similarity_matrix(desc, present)
    pose_mean = float(np.nanmean(psim))
    psim_c = np.nan_to_num(psim - pose_mean, nan=0.0)

    det_lat = {}
    for f in ["person_detection_mediapipe_2023mar.onnx", "pose_estimation_mediapipe_2023mar.onnx"]:
        sess = session(os.path.join(a.zoo_dir, f), 1)
        i = sess.get_inputs()[0]
        shape = [1 if not isinstance(d, int) else d for d in i.shape]
        x = np.random.rand(*shape).astype(np.float32)
        for _ in range(5):
            sess.run(None, {i.name: x})
        t = []
        for _ in range(30):
            t0 = time.perf_counter()
            sess.run(None, {i.name: x})
            t.append((time.perf_counter() - t0) * 1000)
        det_lat[f] = round(statistics.median(t), 1)

    result = {
        "set": {"images": len(rows), "classes": len(classes), "pose_heavy": POSE_HEAVY,
                "pose_detected": int(present.sum())},
        "host": {"cpu_count": os.cpu_count(), "onnxruntime": ort.__version__},
        "pose": {"onnx_ms_1thread": det_lat,
                 "pipeline_ms_median_1thread": round(float(np.median(pose_ms)), 1),
                 "pose_only": {"all": summary(evaluate(np.nan_to_num(psim, nan=-2.0), labels, classes)),
                               "pose_heavy": summary(evaluate(np.nan_to_num(psim, nan=-2.0), labels, classes), POSE_HEAVY)}},
        "models": {},
    }
    for name, spec in specs.items():
        if "loader" not in spec or (a.only and name not in a.only):
            continue
        entry = {"export": export.get(name)}
        for prec in ["fp32", "int8"]:
            path = os.path.join(a.onnx_dir, f"{name}.{prec}.onnx")
            if not os.path.exists(path):
                continue
            emb = embed_all(path, spec, paths, os.path.join(a.work, f"{name}.{prec}.npy"))
            sem = emb @ emb.T
            r = {"latency_ms_b1": {"1thread": latency(path, spec, 1), "4threads": latency(path, spec, 4)}}
            for w in FUSION_WEIGHTS:
                per = evaluate(sem + w * psim_c, labels, classes)
                key = "semantic" if w == 0 else f"fused_w{w}"
                r[key] = {"all": summary(per), "pose_heavy": summary(per, POSE_HEAVY)}
                if w == 0:
                    r["per_class_ap"] = {c: round(v["ap"], 3) for c, v in per.items()}
            r["pose_rerank_top50_p10"] = {}
            for w in (0.15, 0.3):
                pr = rerank_p10(sem, psim, present, labels, classes, w)
                keys = [c for c in pr if c in POSE_HEAVY]
                r["pose_rerank_top50_p10"][f"w{w}"] = {
                    "all": round(float(np.mean(list(pr.values()))), 4),
                    "pose_heavy": round(float(np.mean([pr[c] for c in keys])), 4),
                    "per_class": {c: round(v, 3) for c, v in pr.items()}}
            if prec == "int8":
                fp = np.load(os.path.join(a.work, f"{name}.fp32.npy"))
                r["cos_to_fp32_mean"] = round(float((fp * emb).sum(1).mean()), 4)
            entry[prec] = r
            print(name, prec, r["latency_ms_b1"], r["semantic"], flush=True)
        result["models"][name] = entry
    with open(a.out, "w") as f:
        json.dump(result, f, indent=2)


if __name__ == "__main__":
    main()
