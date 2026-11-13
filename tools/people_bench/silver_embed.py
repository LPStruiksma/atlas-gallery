#!/usr/bin/env python3
"""Embed saved crops with a stronger reference face model, in resumable chunks.

The reference model (for example InsightFace w600k_r50, research licence) is
used ONLY on the bench machine to make silver identity labels for licensed
photos. It is never shipped and nothing it produces is committed.
"""
import argparse
import os

import numpy as np
import onnxruntime as ort

ap = argparse.ArgumentParser()
ap.add_argument("--work", required=True)
ap.add_argument("--model", required=True)
ap.add_argument("--chunk", type=int, default=512)
a = ap.parse_args()
crops = np.load(os.path.join(a.work, "crops_rgb.npy"), mmap_mode="r")
sess = ort.InferenceSession(a.model, providers=["CPUExecutionProvider"])
name = sess.get_inputs()[0].name
os.makedirs(os.path.join(a.work, "oracle"), exist_ok=True)
for s in range(0, len(crops), a.chunk):
    out = os.path.join(a.work, "oracle", f"{s:08d}.npy")
    if os.path.exists(out):
        continue
    x = (np.asarray(crops[s:s + a.chunk], np.float32).transpose(0, 3, 1, 2) - 127.5) / 127.5
    o = np.concatenate([sess.run(None, {name: x[i:i + 32]})[0] for i in range(0, len(x), 32)])
    o /= np.linalg.norm(o, axis=1, keepdims=True)
    np.save(out + ".tmp.npy", o.astype(np.float32))
    os.replace(out + ".tmp.npy", out)
    print(f"{min(len(crops), s + a.chunk)}/{len(crops)}", flush=True)
parts = sorted(os.listdir(os.path.join(a.work, "oracle")))
np.save(os.path.join(a.work, "oracle.npy"), np.concatenate([np.load(os.path.join(a.work, "oracle", p)) for p in parts]))
print("done")
