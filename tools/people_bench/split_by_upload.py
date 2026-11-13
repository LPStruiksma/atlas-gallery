#!/usr/bin/env python3
"""Photo numbers of the earlier half of each author's uploads, one per line (for -Dearly=FILE).

The Flickr photo number in OriginalURL grows with upload time, so
this is a split by time as far as the data has one: the named people come
from an author's earlier uploads and the later faces from the later ones.
Photo numbers are the row numbers of LICENSED, as export_vectors.py writes them.

Usage: split_by_upload.py LICENSED META OUT
"""
import csv
import re
import sys

licensed, meta, out = sys.argv[1:4]
ids = [r["ImageID"] for r in csv.DictReader(open(licensed, newline=""))]
number = {image: i for i, image in enumerate(ids)}
by_author = {}
with open(meta, newline="") as f:
    for r in csv.DictReader(f):
        if r["ImageID"] in number:
            upload = int(re.search(r"/(\d+)_[0-9a-f]+_", r["OriginalURL"]).group(1))
            by_author.setdefault(r["AuthorProfileURL"] or r["Author"], []).append((upload, number[r["ImageID"]]))
early = []
for photos in by_author.values():
    photos.sort()
    early += [n for _, n in photos[:len(photos) // 2]]
open(out, "w").write("\n".join(map(str, sorted(early))))
print(len(early), "of", len(ids), "photos are in the earlier half of their author's uploads")
