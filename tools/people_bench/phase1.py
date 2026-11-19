#!/usr/bin/env python3
"""Judge the owner's Phase 1 path that PeopleBench -Dphase1=DIR played (#580).

For every recurring reference identity (10+ faces) that got a name: how many of
its faces sit under its name (named or joined on their own), how many sit in
unnamed groups the app asks about ("same person?" with that name), and how many
are loose with no question at all (in groups of 3+, pairs, or alone). A joined
face is WRONG when its median reference-model cosine to the person's named faces
is under 0.15 (as judge_groups.py). A question is TRUE when most of the group's
faces carry the person's reference identity. Prints aggregates only.

Usage: phase1.py DIR WORK      (DIR as given to -Dphase1, WORK as for judge_groups.py)
"""
import glob
import os
import sys

import numpy as np

root, work = sys.argv[1], sys.argv[2]
o = np.load(work + "/oracle.npy")
lab = np.load(work + "/silver.npy")
n = len(lab)
sizes = np.bincount(lab[lab >= 0])
for d in sorted(glob.glob(root + "/*/")):
    kind, ref, mean = [], np.zeros(n, int), np.zeros(n)
    for i, line in enumerate(open(d + "faces.txt").read().split("\n")):
        k, r, m = line.split()
        kind.append(k); ref[i] = int(r); mean[i] = float(m)
    kind = np.array(kind)
    who = dict(tuple(map(int, l.split())) for l in open(d + "named.txt").read().split("\n") if l)
    qs = [l.split() for l in open(d + "questions.txt").read().split("\n") if l]
    group_of = np.where(kind == "u", ref, -1)
    gsize = np.bincount(group_of[group_of >= 0])
    asked = {}  # group -> person asked about it
    for p, a, b, *_ in qs:
        if int(p) >= 0:
            asked.setdefault(group_of[int(a)], set()).add(int(p))
    # joined faces: wrong / unsure against the person's named faces
    wrong = unsure = joined = 0
    for p, ident in who.items():
        base = np.where((kind == "n") & (ref == p))[0]
        added = np.where((kind == "m") & (ref == p))[0]
        if len(added) == 0:
            continue
        med = np.median(o[added] @ o[base].T, axis=1)
        joined += len(added); wrong += int((med < 0.15).sum()); unsure += int(((med >= 0.15) & (med < 0.30)).sum())
    # questions about a named person: true when the group's majority identity is that person's
    tq = fq = 0
    for p, a, *_ in qs:
        if int(p) < 0:
            continue
        g = group_of[int(a)]
        m = lab[group_of == g]
        maj = np.bincount(m[m >= 0]).argmax() if (m >= 0).any() else -1
        tq += maj == who[int(p)]; fq += maj != who[int(p)]
    under = asked_f = loose3 = loose2 = alone = 0
    one = 0
    total = 0
    for p, ident in who.items():
        f = np.where(lab == ident)[0]
        total += len(f)
        mine = ((kind[f] != "u") & (ref[f] == p)).sum()
        under += mine
        lf = f[kind[f] == "u"]
        g = group_of[lf]
        q = np.array([p in asked.get(x, ()) for x in g], bool)
        asked_f += q.sum()
        rest = g[~q]
        loose3 += (gsize[rest] >= 3).sum(); loose2 += (gsize[rest] == 2).sum(); alone += (gsize[rest] == 1).sum()
        one += mine >= 0.9 * len(f)
    print(f"floor {os.path.basename(d.rstrip('/'))}: named people {len(who)} with {total} faces | "
          f"under the name {under} ({100 * under / total:.1f}%) | asked about {asked_f} | "
          f"loose, not asked: in groups of 3+ {loose3}, pairs {loose2}, alone {alone} | "
          f"people 90%+ under their name {one}/{len(who)} | joined on their own {joined}, wrong {wrong} "
          f"({100 * wrong / max(1, joined):.2f}%), unsure {unsure} | questions with a name true {tq} false {fq}")
