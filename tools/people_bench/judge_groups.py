#!/usr/bin/env python3
"""Judge the groups the JVM bench dumped (-Ddump=DIR) with the reference model.

A face in a group of 3 or more is WRONG when its median reference-model cosine
to the other members is under 0.15, UNSURE from 0.15 to 0.30 (two strangers
reach 0.29 once in 10,000 pairs under that model). Recall is over the silver
identities with 10 or more faces: the share of a person's faces in their
largest group. Prints aggregates only.

Usage: judge_groups.py DUMP_DIR WORK [WORK ...]   (same WORK order as export_vectors.py)
"""
import glob
import os
import sys

import numpy as np

dump, works = sys.argv[1], sys.argv[2:]
o = np.concatenate([np.load(w + "/oracle.npy") for w in works])
labs, base = [], 0
for w in works:
    lab = np.load(w + "/silver.npy")
    labs.append(np.where(lab >= 0, lab + base, -1))
    base += int(lab.max()) + 1
lab = np.concatenate(labs)
sizes = np.bincount(lab[lab >= 0])
big = np.where(sizes >= 10)[0]
print(f"faces {len(o)}; recurring people (10+ faces) {len(big)}")
print(f"{'policy':28s} {'groups3+':>8s} {'faces':>6s} {'wrong':>12s} {'unsure':>12s} {'recall':>6s} {'80%+ in one group':>18s}")
for path in sorted(glob.glob(dump + "/*.txt")):
    a = np.loadtxt(path, dtype=int)
    wrong = unsure = shown = groups = 0
    for c in np.unique(a):
        m = np.where(a == c)[0]
        if len(m) < 3:
            continue
        s = o[m] @ o[m].T
        np.fill_diagonal(s, np.nan)
        med = np.nanmedian(s, axis=1)
        groups += 1
        shown += len(m)
        wrong += int((med < 0.15).sum())
        unsure += int(((med >= 0.15) & (med < 0.30)).sum())
    rec = [np.bincount(a[lab == p]).max() / sizes[p] for p in big]
    print(f"{os.path.basename(path)[:-4]:28s} {groups:8d} {shown:6d} {wrong:5d} {100 * wrong / max(1, shown):5.2f}% "
          f"{unsure:5d} {100 * unsure / max(1, shown):5.2f}% {np.mean(rec):6.2f} {sum(r >= 0.8 for r in rec):9d}/{len(big)}")
