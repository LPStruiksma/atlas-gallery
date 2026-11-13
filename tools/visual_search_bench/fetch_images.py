#!/usr/bin/env python3
"""Fetch the benchmark images listed in a manifest into a local cache.

Each image's licence is checked against Open Images' own per-image metadata
(column License) before it is used; anything that is not CC BY 2.0 is
dropped. Images are downscaled to --max-side and kept only under --cache,
outside the repository.

Usage:
  fetch_images.py --manifest manifest.csv --metadata image-meta.csv \
      --cache ~/.cache/atlas-vs/images --out licensed.csv
"""
import argparse
import concurrent.futures as cf
import csv
import io
import os
import urllib.request

from PIL import Image, ImageOps

S3 = "https://open-images-dataset.s3.amazonaws.com/{split}/{id}.jpg"
ALLOWED = ("creativecommons.org/licenses/by/2.0",)


def fetch(img_id, split, cache, max_side):
    path = os.path.join(cache, img_id + ".jpg")
    if os.path.exists(path):
        return path
    with urllib.request.urlopen(S3.format(split=split, id=img_id), timeout=60) as r:
        data = r.read()
    im = Image.open(io.BytesIO(data))
    im = ImageOps.exif_transpose(im).convert("RGB")
    im.thumbnail((max_side, max_side), Image.BICUBIC)
    tmp = path + ".part"
    im.save(tmp, "JPEG", quality=92)
    os.replace(tmp, path)
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--metadata", required=True, help="Open Images *-images-with-rotation.csv rows for these ids")
    ap.add_argument("--split", default="train")
    ap.add_argument("--cache", required=True)
    ap.add_argument("--max-side", type=int, default=640)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.cache, exist_ok=True)

    lic = {}
    with open(a.metadata, newline="") as f:
        for r in csv.DictReader(f):
            lic[r["ImageID"]] = r["License"]
    rows = list(csv.DictReader(open(a.manifest, newline="")))
    ok = [r for r in rows if any(x in lic.get(r["ImageID"], "") for x in ALLOWED)]
    print(f"manifest {len(rows)}; licence CC BY 2.0 confirmed {len(ok)}")

    kept = []
    with cf.ThreadPoolExecutor(16) as ex:
        futs = {ex.submit(fetch, r["ImageID"], a.split, a.cache, a.max_side): r for r in ok}
        for fu in cf.as_completed(futs):
            r = futs[fu]
            try:
                fu.result()
                kept.append(r)
            except Exception as e:  # keep going; report what failed
                print(f"skip {r['ImageID']}: {type(e).__name__}: {e}")
    kept.sort(key=lambda r: r["ImageID"])
    with open(a.out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["ImageID", "Labels", "License"])
        w.writeheader()
        for r in kept:
            w.writerow({"ImageID": r["ImageID"], "Labels": r["Labels"], "License": lic[r["ImageID"]]})
    print(f"fetched {len(kept)}")


if __name__ == "__main__":
    main()
