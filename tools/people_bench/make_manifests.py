#!/usr/bin/env python3
"""Make the image manifests (ImageID,Labels) the bench fetches, from Open Images CSVs.

  faces    every image with a "Human face" box (/m/0dzct) that is not a depiction or a group
  authors  the face images of COUNT Flickr authors that have 120 to 450 of them with a
           CC BY 2.0 licence; authors are ordered by the SHA-256 of their profile URL
           (a fixed, arbitrary order) and taken from OFFSET; at most 300 images each

Usage:
  make_manifests.py faces   --bbox validation-annotations-bbox.csv --out val_manifest.csv
  make_manifests.py authors --bbox train-annotations-bbox.csv \
      --meta train-images-boxable-with-rotation.csv --count 48 --offset 0 --out auth_manifest.csv
"""
import argparse
import collections
import csv
import hashlib

FACE = "/m/0dzct"


def face_images(bbox):
    out = set()
    with open(bbox, newline="") as f:
        r = csv.reader(f)
        h = next(r)
        label, depiction, group = h.index("LabelName"), h.index("IsDepiction"), h.index("IsGroupOf")
        for row in r:
            if row[label] == FACE and row[depiction] == "0" and row[group] == "0":
                out.add(row[0])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=("faces", "authors"))
    ap.add_argument("--bbox", required=True)
    ap.add_argument("--meta")
    ap.add_argument("--count", type=int, default=48)
    ap.add_argument("--offset", type=int, default=0)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    faces = face_images(a.bbox)
    rows = []
    if a.mode == "faces":
        rows = [(i, "Human face") for i in sorted(faces)]
    else:
        by = collections.defaultdict(list)
        with open(a.meta, newline="") as f:
            for r in csv.DictReader(f):
                if r["ImageID"] in faces and "licenses/by/2.0" in r["License"]:
                    by[r["AuthorProfileURL"] or r["Author"]].append(r["ImageID"])
        cands = sorted((k for k, v in by.items() if 120 <= len(v) <= 450),
                       key=lambda k: hashlib.sha256(k.encode()).hexdigest())
        for n, author in enumerate(cands[a.offset:a.offset + a.count]):
            rows += [(i, f"author{a.offset + n:02d}") for i in sorted(by[author])[:300]]
    with open(a.out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["ImageID", "Labels"])
        w.writerows(rows)
    print(len(rows), "images")


if __name__ == "__main__":
    main()
