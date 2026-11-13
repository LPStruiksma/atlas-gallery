#!/usr/bin/env python3
"""Pack faces.npz parts into one big-endian file for the JVM bench (jvm/PeopleBench.kt):
int n, int dim, n*dim float32 raw unit vectors, n int photo, n int author, n int label.

label is the silver identity from silver_labels.py when WORK/silver.npy exists, else -1.
Usage: export_vectors.py OUT WORK LICENSED META [WORK LICENSED META ...]
"""
import os
import sys

import numpy as np

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from impostor_stats import load  # noqa: E402

out = sys.argv[1]
parts = [sys.argv[i:i + 3] for i in range(2, len(sys.argv), 3)]
v, img, side, score, norm, author = load(parts)
labels, base = [], 0
for work, _, _ in parts:
    n = len(np.load(work + "/faces.npz")["img"])
    path = work + "/silver.npy"
    if os.path.exists(path):
        lab = np.load(path)
        labels.append(np.where(lab >= 0, lab + base, -1))
        base += int(lab.max()) + 1
    else:
        labels.append(-np.ones(n, int))
label = np.concatenate(labels)
with open(out, "wb") as f:
    f.write(np.array([len(v), v.shape[1]], ">i4").tobytes())
    f.write(v.astype(">f4").tobytes())
    for arr in (img, author, label):
        f.write(np.asarray(arr).astype(">i4").tobytes())
print(len(v), "faces,", int((label >= 0).sum()), "with a silver label")
