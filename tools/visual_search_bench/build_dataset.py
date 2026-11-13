#!/usr/bin/env python3
"""Select a licensed action/pose retrieval set from Open Images V7.

Only human-verified positive image-level labels are used. The output
manifest lists Open Images ImageIDs and their benchmark class(es); it holds
no pixels, author names or URLs to personal pages. Images are fetched at run
time by fetch_images.py and are never committed.

Usage:
  build_dataset.py --labels train-action-labels.csv \
      --classes oidv7-class-descriptions.csv --per-class 40 --out manifest.csv
"""
import argparse
import collections
import csv
import random

# Action/pose classes for the query-by-image benchmark. Several share scenes
# on purpose (gymnastics hall, beach, snow, water) so a model that only
# matches the backdrop is penalised.
ACTIONS = [
    "Cartwheel", "Handstand", "Somersault", "Juggling", "Archery",
    "Ballet", "Boxing", "Fencing", "High jump", "Pole vault", "Hug",
    "Yoga", "Surfing", "Kitesurfing", "Skateboarding", "Rock climbing",
    "Skiing", "Snowboarding", "Sledding", "Ice skating", "Swimming",
    "Cycling", "Running", "Walking", "Sitting", "Eating", "Reading",
    "Selfie", "Golf", "Tennis", "Karate", "Wrestling", "Judo", "Kayaking",
    "Rowing", "Trampolining", "Meditation",
    # "Dance" is left out: 87k loose positives, it overlaps every pose class.
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--labels", required=True)
    ap.add_argument("--classes", required=True)
    ap.add_argument("--per-class", type=int, default=40)
    ap.add_argument("--min-class", type=int, default=25)
    ap.add_argument("--seed", type=int, default=468)
    ap.add_argument("--available", help="Open Images image list (e.g. train-images-boxable-with-rotation.csv); only these ids are hosted on the public S3 mirror")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    available = None
    if a.available:
        with open(a.available, newline="") as f:
            available = {r["ImageID"] for r in csv.DictReader(f)}

    names = {}
    with open(a.classes, newline="") as f:
        for r in csv.DictReader(f):
            names[r["LabelName"].strip()] = r["DisplayName"].strip()
    mid_to_action = {m: n for m, n in names.items() if n in ACTIONS}

    by_image = collections.defaultdict(set)
    with open(a.labels, newline="") as f:
        for row in csv.reader(f):
            if len(row) < 4 or row[0] == "ImageID":
                continue
            if float(row[3]) < 1.0:
                continue
            act = mid_to_action.get(row[2])
            if act and (available is None or row[0] in available):
                by_image[row[0]].add(act)

    per_class = collections.defaultdict(list)
    for img, acts in by_image.items():
        for act in acts:
            per_class[act].append(img)

    rng = random.Random(a.seed)
    chosen = {}
    for act in sorted(per_class):
        imgs = sorted(per_class[act])
        if len(imgs) < a.min_class:
            continue
        rng.shuffle(imgs)
        for img in imgs[: a.per_class]:
            chosen[img] = by_image[img]

    kept = {act for acts in chosen.values() for act in acts}
    with open(a.out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["ImageID", "Labels"])
        for img in sorted(chosen):
            w.writerow([img, "|".join(sorted(chosen[img] & kept))])
    counts = collections.Counter(act for acts in chosen.values() for act in acts)
    for act, n in sorted(counts.items()):
        print(f"{act}\t{n}\t(available {len(per_class[act])})")
    print(f"images\t{len(chosen)}")


if __name__ == "__main__":
    main()
