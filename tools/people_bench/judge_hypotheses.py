#!/usr/bin/env python3
"""Judge the "same person?" questions the JVM bench wrote (-Dhypotheses=DIR, with -Ddump=DIR).

A question about two groups is SAME when the median reference-model cosine
over their cross pairs is 0.30 or more, DIFFERENT under 0.15, else UNSURE.
"Split people" counts the pairs of groups (3+ faces with 2+ faces) the
reference model calls the same person: what there was to find. Prints
aggregates only.

Usage: judge_hypotheses.py HYPOTHESES_DIR DUMP_DIR WORK [WORK ...]
"""
import glob
import os
import sys

import numpy as np

hyp, dump, works = sys.argv[1], sys.argv[2], sys.argv[3:]
o = np.concatenate([np.load(w + "/oracle.npy") for w in works])
for path in sorted(glob.glob(hyp + "/*.txt")):
    name = os.path.basename(path)
    a = np.loadtxt(os.path.join(dump, name), dtype=int)
    rows = np.loadtxt(path, ndmin=2)
    members = {}
    for i, c in enumerate(a):
        members.setdefault(int(c), []).append(i)
    big = [m for m in members.values() if len(m) >= 3]
    two = [m for m in members.values() if len(m) == 2]
    split = 0
    for i, m in enumerate(big):
        for other in big[i + 1:] + two:
            split += np.median(o[m] @ o[other].T) >= 0.30
    same = unsure = diff = 0
    for ia, ib, _, _ in rows:
        t = np.median(o[members[a[int(ia)]]] @ o[members[a[int(ib)]]].T)
        same += t >= 0.30
        diff += t < 0.15
        unsure += 0.15 <= t < 0.30
    n = max(1, len(rows))
    print(f"{name[:-4]:14s} questions {len(rows):4d}: same {same} ({100 * same / n:.0f}%), "
          f"unsure {unsure} ({100 * unsure / n:.0f}%), different {diff} ({100 * diff / n:.0f}%); "
          f"split pairs to find {split}, found {same} ({100 * same / max(1, split):.0f}%)")
