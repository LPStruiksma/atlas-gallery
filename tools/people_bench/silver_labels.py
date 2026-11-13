#!/usr/bin/env python3
"""Silver identity labels for author libraries, from the reference model's vectors
(silver_embed.py): average-linkage clustering inside each author's photos at a
reference-model cosine cut. Groups never span authors. Writes WORK/silver.npy
(-1 = seen once). Labels stay on the bench machine.

Usage: silver_labels.py WORK LICENSED META [--cut 0.42]
"""
import argparse
import sys

import numpy as np
from scipy.cluster.hierarchy import fcluster, linkage

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from impostor_stats import load  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("work"); ap.add_argument("licensed"); ap.add_argument("meta")
ap.add_argument("--cut", type=float, default=0.42)
a = ap.parse_args()
author = load([(a.work, a.licensed, a.meta)])[5]
o = np.load(a.work + "/oracle.npy")
assert len(o) == len(author)
label = -np.ones(len(o), int)
nxt = 0
for au in np.unique(author):
    idx = np.where(author == au)[0]
    if len(idx) < 2:
        continue
    f = fcluster(linkage(o[idx], "average", metric="cosine"), 1 - a.cut, "distance")
    for c in np.unique(f):
        m = idx[f == c]
        if len(m) >= 2:
            label[m] = nxt
            nxt += 1
np.save(a.work + "/silver.npy", label)
sizes = np.bincount(label[label >= 0])
print(f"faces {len(o)}, authors {len(np.unique(author))}, identities {len(sizes)}, "
      f"with 10+ faces {(sizes >= 10).sum()} holding {sizes[sizes >= 10].sum()} faces")
