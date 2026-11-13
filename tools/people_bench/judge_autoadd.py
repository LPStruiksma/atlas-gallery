#!/usr/bin/env python3
"""Judge "add a later face to a named person without asking" (JVM bench, -Dautoadd=DIR).

A rule adds a later face to its best named person when the mean over the
person's faces, the best single match and the lead over the second-best
person all reach their cut; of two faces of one photo only the better one
joins the same person. The reference model then judges each added face by
its median cosine to the person's faces: under 0.15 WRONG, 0.15 to 0.30
UNSURE, else right. "found" is the share of the later faces the reference
model places with a named person (median 0.30 or more) that the rule added
to that person. The last line judges the app's own rule (matches.txt) the same
way. Prints aggregates only.

Usage: judge_autoadd.py DIR WORK
"""
import os
import sys

import numpy as np

d, work = sys.argv[1], sys.argv[2]
o = np.load(work + "/oracle.npy")
people = [np.array(line.split(), int) for line in open(d + "/people.txt")]
late = np.loadtxt(d + "/late.txt", ndmin=2)
face, best = late[:, 0].astype(int), late[:, 1].astype(int)
mean, second, top, photo = late[:, 2], late[:, 3], late[:, 4], late[:, 5].astype(int)
# Reference median cosine of every later face to every named person.
ref = np.stack([np.median(o[face] @ o[m].T, axis=1) for m in people], axis=1)
home = ref.argmax(1)
belongs = ref.max(1) >= 0.30
own = ref[np.arange(len(face)), best]
print(f"named people {len(people)}, later faces {len(face)}, of which the reference places "
      f"{int(belongs.sum())} with a named person")
print(" mean>= best>= lead>=  added wrong unsure  wrong%  found%")
for tm in (0.35, 0.40, 0.45, 0.50, 0.55):
    for tb in (0.0, 0.50, 0.60):
        for lead in (0.0, 0.10):
            ok = (mean >= tm) & (top >= tb) & (mean - second >= lead)
            keep = {}
            for k in np.flatnonzero(ok):
                key = (photo[k], best[k])
                if key not in keep or mean[k] > mean[keep[key]]:
                    keep[key] = k
            k = np.array(sorted(keep.values()), int)
            wrong = int((own[k] < 0.15).sum())
            unsure = int(((own[k] >= 0.15) & (own[k] < 0.30)).sum())
            found = int((belongs[k] & (home[k] == best[k])).sum())
            print(f"  {tm:.2f}  {tb:.2f}  {lead:.2f}  {len(k):5d} {wrong:5d} {unsure:6d}  "
                  f"{100 * wrong / max(1, len(k)):5.2f}%  {100 * found / max(1, belongs.sum()):5.1f}%")

# The app's own rule (NamedPersonMatches), when the bench wrote its reading.
# It also reads the unnamed faces of the first half (lone faces and pairs).
path = d + "/matches.txt"
if os.path.exists(path) and os.path.getsize(path):
    m = np.loadtxt(path, ndmin=2)
    f, p = m[:, 0].astype(int), m[:, 1].astype(int)
    score = np.array([np.median(o[a] @ o[people[b]].T) for a, b in zip(f, p)])
    row = {int(x): i for i, x in enumerate(face)}
    is_late = np.array([int(x) in row for x in f])
    k = np.array([row[int(x)] for x in f[is_late]], int)
    found = int((belongs[k] & (home[k] == p[is_late])).sum())
    for name, part in (("later faces", is_late), ("unnamed faces of the first half", ~is_late)):
        print(f"app rule (NamedPersonMatches), {name}: added {int(part.sum())}, "
              f"wrong {int((score[part] < 0.15).sum())}, "
              f"unsure {int(((score[part] >= 0.15) & (score[part] < 0.30)).sum())}"
              + (f", found {100 * found / max(1, belongs.sum()):.1f}%" if name == "later faces" else ""))
