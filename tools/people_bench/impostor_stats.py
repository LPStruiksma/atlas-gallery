#!/usr/bin/env python3
"""Aggregate cosine statistics of the shipped face pair on licensed photos.

Open Images has no identity labels, so this measures what it can prove:

- impostor pairs: two faces from photos by DIFFERENT Flickr authors (different
  people, bar the rare celebrity), and two faces in the SAME photo (certainly
  different people, and the hard case: relatives stand together);
- same-author pairs from different photos: a mixture of the same person seen
  again and strangers. Subtracting the cross-author curve scaled to the low
  tail estimates the same-person curve.

Prints and writes aggregates only.
"""
import argparse
import csv
import json

import numpy as np


def load(parts):
    v, img, side, score, norm, author = [], [], [], [], [], []
    base = 0
    for work, licensed, meta in parts:
        z = np.load(work + "/faces.npz")
        ids = [r["ImageID"] for r in csv.DictReader(open(licensed, newline=""))]
        want = set(ids)
        auth = {}
        with open(meta, newline="") as f:
            for r in csv.DictReader(f):
                if r["ImageID"] in want:
                    auth[r["ImageID"]] = r["AuthorProfileURL"] or r["Author"]
        v.append(z["v"]); side.append(z["side"]); score.append(z["score"]); norm.append(z["norm"])
        img.append(z["img"] + base)
        author += [auth.get(ids[i], "?" + ids[i]) for i in z["img"]]
        base += len(ids)
    names = {a: i for i, a in enumerate(sorted(set(author)))}
    return (np.concatenate(v), np.concatenate(img), np.concatenate(side), np.concatenate(score),
            np.concatenate(norm), np.array([names[a] for a in author]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--part", nargs=3, action="append", metavar=("WORK", "LICENSED", "META"), required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    v, img, side, score, norm, author = load(a.part)
    n = len(v)
    edges = np.round(np.arange(-0.2, 1.0001, 0.02), 2)
    hist = {k: np.zeros(len(edges) - 1, np.int64) for k in ("cross_author", "same_photo", "same_author")}
    small = side < 40
    hist_small = np.zeros(len(edges) - 1, np.int64)   # cross-author, both faces under 40 px
    hist_big = np.zeros(len(edges) - 1, np.int64)     # cross-author, both faces 80 px or more
    big = side >= 80
    for s in range(0, n, 2048):
        e = min(n, s + 2048)
        sim = v[s:e] @ v.T
        ii = np.arange(s, e)[:, None]
        jj = np.arange(n)[None, :]
        upper = jj > ii
        same_img = img[s:e, None] == img[None, :]
        same_auth = author[s:e, None] == author[None, :]
        hist["cross_author"] += np.histogram(sim[upper & ~same_auth], edges)[0]
        hist["same_photo"] += np.histogram(sim[upper & same_img], edges)[0]
        hist["same_author"] += np.histogram(sim[upper & same_auth & ~same_img], edges)[0]
        hist_small += np.histogram(sim[upper & ~same_auth & small[s:e, None] & small[None, :]], edges)[0]
        hist_big += np.histogram(sim[upper & ~same_auth & big[s:e, None] & big[None, :]], edges)[0]

    def tail(h):
        c = h[::-1].cumsum()[::-1]
        return {f"{edges[i]:.2f}": float(c[i] / max(1, h.sum())) for i in range(len(h)) if 0.2 <= edges[i] <= 0.7}

    out = {
        "faces": int(n), "photos_with_faces": int(len(set(img.tolist()))), "authors": int(author.max() + 1),
        "face_side_px_quartiles": [float(x) for x in np.percentile(side, [10, 25, 50, 75, 90])],
        "pairs": {k: int(h.sum()) for k, h in hist.items()},
        "share_at_or_above": {k: tail(h) for k, h in hist.items()},
        "cross_author_small_faces": {"pairs": int(hist_small.sum()), "share_at_or_above": tail(hist_small)},
        "cross_author_large_faces": {"pairs": int(hist_big.sum()), "share_at_or_above": tail(hist_big)},
        "histogram_edges": [float(x) for x in edges],
        "histograms": {k: h.tolist() for k, h in hist.items()},
    }
    json.dump(out, open(a.out, "w"), indent=1)
    print(json.dumps({k: out[k] for k in ("faces", "photos_with_faces", "authors", "face_side_px_quartiles", "pairs")}))
    print("t      cross_author  same_photo   same_author  cross_small   cross_large")
    for t in ("0.20", "0.26", "0.30", "0.32", "0.36", "0.38", "0.40", "0.42", "0.46", "0.50", "0.56", "0.60", "0.70"):
        s = out["share_at_or_above"]
        print(t, *(f"{x[t]:12.2e}" for x in (s["cross_author"], s["same_photo"], s["same_author"],
              out["cross_author_small_faces"]["share_at_or_above"], out["cross_author_large_faces"]["share_at_or_above"])))


if __name__ == "__main__":
    main()
