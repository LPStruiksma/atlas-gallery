#!/usr/bin/env python3
"""Build the NLS-0 relational text-to-image benchmark from Open Images V7.

Each query in data/nls0_queries.json is a description (English + Dutch) plus a
list of constraints over Open Images' human annotations of the validation and
test splits: human-verified image-level labels (positive AND negative
verifications), exhaustive person boxes (Person/Man/Woman/Boy/Girl) and face
boxes. For every image and query each constraint evaluates to True, False or
unknown (not annotated), and the image becomes:

  pos  every constraint True;
  hn   exactly one constraint False and all the others True: a hard negative
       that differs from the description in one crucial way (its "crux" is
       recorded, e.g. two people vs one, church vs other interior);
  neg  at least one constraint False (not hard);
  ---  otherwise unknown and left out of that query's ranking.

Every judged image is therefore right by construction (condensed-list
evaluation; Sakai 2007), even though most annotations are incomplete.
The gallery is the union of all queries' positives and hard negatives plus a
seeded sample of other people/scene images as distractors. Only CC BY 2.0
images are kept (Open Images per-image metadata, re-checked at fetch time).

Writes data/nls0_manifest.csv (ImageID, Split) and data/nls0_judgments.csv
(QueryID, ImageID, Kind, Crux); IDs and labels only, never pixels, authors or
URLs. Usage (inputs are the public Open Images CSVs, see run_nls0.sh):
  build_relational.py --oi-dir ~/.cache/atlas-vs/data --out-dir tools/visual_search_bench/data
"""
import argparse
import collections
import csv
import json
import os
import random

HERE = os.path.dirname(os.path.abspath(__file__))
SPLITS = {"train": ("oidv7-train-annotations-human-imagelabels.csv", "oidv6-train-annotations-bbox.csv",
                    "train-images-boxable-with-rotation.csv"),
          "validation": ("oidv7-val-annotations-human-imagelabels.csv", "validation-annotations-bbox.csv",
                         "validation-images-with-rotation.csv"),
          "test": ("oidv7-test-annotations-human-imagelabels.csv", "test-annotations-bbox.csv",
                   "test-images-with-rotation.csv")}
CC_BY = "creativecommons.org/licenses/by/2.0"
PERSON = {"Person", "Man", "Woman", "Boy", "Girl"}
SIG_AREA = 0.015   # a person box at least 1.5% of the frame counts as a depicted person
SMALL_AREA = 0.003  # 0.3-1.5%: a background figure; makes an exact count unknown


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        yield from csv.DictReader(f)


def iou(a, b):
    ix = max(0.0, min(a[1], b[1]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[2], b[2]))
    inter = ix * iy
    u = (a[1] - a[0]) * (a[3] - a[2]) + (b[1] - b[0]) * (b[3] - b[2]) - inter
    return inter / u if u > 0 else 0.0


class Image:
    __slots__ = ("id", "split", "labels", "people", "small", "group", "faces")

    def __init__(self, img_id, split):
        self.id, self.split = img_id, split
        self.labels = {}      # display name -> 1/0 (human verified)
        self.people = []      # [(cls, box)] significant, deduplicated
        self.small = 0
        self.group = False
        self.faces = []


def load(oi_dir, names, wanted):
    """Streams the annotation CSVs, keeping only the labels the queries use."""
    imgs = {}
    lic = {}
    for split, (lab, box, meta) in SPLITS.items():
        for r in read_csv(os.path.join(oi_dir, meta)):
            if CC_BY in r["License"]:
                lic[r["ImageID"]] = split
        for r in read_csv(os.path.join(oi_dir, lab)):
            if r["ImageID"] not in lic:
                continue
            n = names.get(r["LabelName"])
            if n not in wanted:
                continue
            im = imgs.get(r["ImageID"]) or imgs.setdefault(r["ImageID"], Image(r["ImageID"], split))
            im.labels[n] = 1 if float(r["Confidence"]) >= 0.5 else 0
        raw = collections.defaultdict(list)
        for r in read_csv(os.path.join(oi_dir, box)):
            n = names.get(r["LabelName"])
            if r["ImageID"] not in lic or n not in PERSON | {"Human face"}:
                continue
            b = (float(r["XMin"]), float(r["XMax"]), float(r["YMin"]), float(r["YMax"]))
            raw[r["ImageID"]].append((n, b, r["IsGroupOf"] == "1", r["IsDepiction"] == "1"))
        for img_id, boxes in raw.items():
            im = imgs.get(img_id) or imgs.setdefault(img_id, Image(img_id, split))
            sig = []
            for n, b, grp, dep in boxes:
                if n == "Human face":
                    im.faces.append(b)
                    continue
                if dep:  # statues, posters, paintings: not a depicted person
                    continue
                if grp:
                    im.group = True
                    continue
                area = (b[1] - b[0]) * (b[3] - b[2])
                if area >= SIG_AREA:
                    sig.append((n, b))
                elif area >= SMALL_AREA:
                    im.small += 1
            # Man/Woman/Boy/Girl and Person boxes on the same figure: keep the specific one.
            sig.sort(key=lambda x: x[0] == "Person")
            for n, b in sig:
                if all(iou(b, kb) < 0.6 for _, kb in im.people):
                    im.people.append((n, b))
    return imgs


# ---------------------------------------------------------------- constraints

def has_label(im, name):
    if name in PERSON and im.people:
        if any(n == name for n, _ in im.people):
            return True
    return im.labels.get(name) == 1 if name in im.labels else None


def people_known(im):
    """Exact number of depicted people, or None when the boxes cannot tell."""
    if im.people or im.small or im.group:
        if im.group or im.small:
            return None if len(im.people) < 6 else len(im.people)
        return len(im.people)
    if im.labels.get("Person") == 0:
        return 0
    return None


def evaluate(c, im):
    k = c["kind"]
    if k == "label":
        return has_label(im, c["name"])
    if k == "any":
        vals = [has_label(im, n) for n in c["names"]]
        if any(v is True for v in vals):
            return True
        return False if all(v is False for v in vals) else None
    if k == "not":
        v = evaluate(c["of"], im)
        return None if v is None else not v
    if k == "people":
        n = people_known(im)
        if n is not None:
            return c["min"] <= n <= c.get("max", c["min"])
        # a lower bound alone can still falsify an upper limit
        if len(im.people) > c.get("max", c["min"]) or (im.group and c.get("max", 99) < 6):
            return False
        return None
    if k == "gender":
        # exact adult composition among the depicted people, e.g. {"Man": 1, "Woman": 1}
        n = people_known(im)
        if n is None:
            return None
        want = c["counts"]
        if n != sum(want.values()):
            return False
        got = collections.Counter(cls for cls, _ in im.people)
        if got.get("Person", 0):
            # unknown gender for some: can it still match?
            for cls, w in want.items():
                if got.get(cls, 0) > w:
                    return False
            return None
        return all(got.get(cls, 0) == w for cls, w in want.items()) and sum(got.values()) == n
    if k == "side_by_side":
        # two depicted people at a similar depth, next to each other (beside vs one far behind the other)
        if len(im.people) != 2 or people_known(im) != 2:
            return None
        (_, a), (_, b) = im.people
        ha, hb = a[3] - a[2], b[3] - b[2]
        ratio = max(ha, hb) / min(ha, hb)
        voverlap = max(0.0, min(a[3], b[3]) - max(a[2], b[2])) / min(ha, hb)
        if ratio <= 1.5 and voverlap >= 0.6:
            return True
        if ratio >= 2.2:
            return False
        return None
    if k == "from_behind":
        # proxy viewpoint: depicted people, faces exhaustively annotated, none visible
        if not im.people:
            return None
        lab = im.labels.get("Human face")
        if lab == 0 and not im.faces:
            return True
        if im.faces:
            return False
        return None
    raise ValueError(k)


def _names(c):
    if c["kind"] == "label":
        return [c["name"]]
    if c["kind"] == "any":
        return c["names"]
    if c["kind"] == "not":
        return _names(c["of"])
    return []


def describe(c):
    k = c["kind"]
    if k == "label":
        return c["name"]
    if k == "any":
        return "|".join(c["names"])
    if k == "not":
        return "not " + describe(c["of"])
    if k == "people":
        return f"people={c['min']}" + (f"-{c['max']}" if c.get("max", c["min"]) != c["min"] else "")
    if k == "gender":
        return "+".join(f"{v} {k2}" for k2, v in c["counts"].items())
    return k


def judge(q, im):
    vals = [evaluate(c, im) for c in q["constraints"]]
    if all(v is True for v in vals):
        return "pos", ""
    false = [i for i, v in enumerate(vals) if v is False]
    if len(false) == 1 and all(v is True for i, v in enumerate(vals) if i != false[0]):
        return "hn", describe(q["constraints"][false[0]])
    if false:
        return "neg", ""
    return None, ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--oi-dir", required=True)
    ap.add_argument("--queries", default=os.path.join(HERE, "data", "nls0_queries.json"))
    ap.add_argument("--out-dir", default=os.path.join(HERE, "data"))
    ap.add_argument("--max-pos", type=int, default=40)
    ap.add_argument("--max-hn", type=int, default=60)
    ap.add_argument("--distractors", type=int, default=2500)
    ap.add_argument("--seed", type=int, default=104)
    ap.add_argument("--stats", action="store_true", help="print availability only")
    a = ap.parse_args()

    names = {}
    for r in csv.reader(open(os.path.join(a.oi_dir, "oidv7-class-descriptions.csv"), encoding="utf-8")):
        if len(r) >= 2 and r[0] != "LabelName":
            names[r[0].strip()] = r[1].strip()
    queries = json.load(open(a.queries, encoding="utf-8"))["queries"]
    wanted = PERSON | {"Human face"} | {n for q in queries for c in q["constraints"] for n in _names(c)}
    imgs = load(a.oi_dir, names, wanted)
    rng = random.Random(a.seed)
    order = sorted(imgs)

    picked = {}
    gallery = set()
    for q in queries:
        buckets = collections.defaultdict(list)
        for img_id in order:
            kind, crux = judge(q, imgs[img_id])
            if kind in ("pos", "hn"):
                buckets[kind].append((img_id, crux))
        print(f"{q['id']}\tpos {len(buckets['pos'])}\thn {len(buckets['hn'])}\t"
              + ", ".join(f"{k}:{v}" for k, v in collections.Counter(c for _, c in buckets['hn']).most_common()))
        if a.stats:
            continue
        pos = buckets["pos"][:]
        rng.shuffle(pos)
        pos = sorted(pos[: a.max_pos])
        # hard negatives: spread over the cruxes so one easy crux cannot dominate
        by_crux = collections.defaultdict(list)
        for img_id, crux in buckets["hn"]:
            by_crux[crux].append(img_id)
        hn = []
        for crux in sorted(by_crux):
            rng.shuffle(by_crux[crux])
        while len(hn) < a.max_hn and any(by_crux.values()):
            for crux in sorted(by_crux):
                if by_crux[crux] and len(hn) < a.max_hn:
                    hn.append((by_crux[crux].pop(), crux))
        picked[q["id"]] = (pos, sorted(hn))
        gallery.update(i for i, _ in pos)
        gallery.update(i for i, _ in hn)
    if a.stats:
        return

    # distractors: images with people or one of the scene labels the queries use, not yet picked
    scene = wanted - PERSON - {"Human face"}
    pool = [i for i in order if i not in gallery and
            (imgs[i].people or any(imgs[i].labels.get(n) == 1 for n in scene))]
    rng.shuffle(pool)
    gallery.update(pool[: a.distractors])

    os.makedirs(a.out_dir, exist_ok=True)
    with open(os.path.join(a.out_dir, "nls0_manifest.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["ImageID", "Split"])
        for i in sorted(gallery):
            w.writerow([i, imgs[i].split])
    # judge every gallery image for every query (pos/hn/neg; unknowns omitted)
    with open(os.path.join(a.out_dir, "nls0_judgments.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["QueryID", "ImageID", "Kind", "Crux"])
        for q in queries:
            for i in sorted(gallery):
                kind, crux = judge(q, imgs[i])
                if kind is None:
                    continue
                # images that came in via another query or as distractors are judged too
                w.writerow([q["id"], i, kind, crux])
    print(f"gallery {len(gallery)}")


if __name__ == "__main__":
    main()
