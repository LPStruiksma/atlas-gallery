"""ATLAS identity benchmark (IDS-2, issue #469).

    python3 -m tools.identity_bench              # fetch, run the fixed benchmark, sheets, report
    python3 -m tools.identity_bench self-test    # unit tests, synthetic tensors, no downloads
    python3 -m tools.identity_bench one --target T04 --identity 0213ccb4776a --candidate inswapper_128
    python3 -m tools.identity_bench screen       # all nine swap networks on the development split
    python3 -m tools.identity_bench explore --candidates inswapper_128+boost2,ghost_1_256+boost2
    python3 -m tools.identity_bench cost         # load time, stage time and peak memory, one process each
    python3 -m tools.identity_bench select       # re-derive the fixture manifest from the archives

Outputs go to --out (default: the cache, never the repository).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import subprocess
import sys
import time

import numpy as np

from . import fixtures, metrics, pipeline, sheets
from .pipeline import Config

VERSION = "identity-bench/1"
ALL_SWAPPERS = tuple(n for n in pipeline.SWAPPERS if n not in pipeline.LOCAL_MODELS)   # exports run by name
# One network per model family.  The first four are the screening winners
# (docs/identity-studio/BENCH.md); SimSwap screened last and is kept because the
# licence register ranks it as the only one open to private non-commercial use.
FINALISTS = ("inswapper_128", "alphaface_256", "ghost_1_256", "hyperswap_1a_256", "simswap_256")
ABLATION_BASE = "inswapper_128"
CONTROL_NONE = Config("control-none", mask="box", colour=False)
STABILITY_PAIRS, STABILITY_REPEATS, JITTER = 12, 4, 0.01


def final_candidates() -> list:
    """Finalists with and without restoration, two controls and three ablations."""
    out = [Config(name) for name in FINALISTS] + [Config(name, restore=True) for name in FINALISTS]
    out += [CONTROL_NONE, Config(ABLATION_BASE, mask="hard-box", colour=False)]
    out += [Config(ABLATION_BASE, mask="box"), Config(ABLATION_BASE, mask="parse-both"),
            Config(ABLATION_BASE, mask="mp"), Config(ABLATION_BASE, colour=False)]
    out += [Config(name, mask="mp") for name in FINALISTS[1:]]
    return out


def parse_label(label: str) -> Config:
    """The inverse of Config.label, e.g. "inswapper_128+boost2+mask-mp+gfpgan"."""
    name, *parts = label.split("+")
    options: dict = {}
    for part in parts:
        if part.startswith("boost"):
            options["boost"] = int(part[5:])
        elif part.startswith("mask-"):
            options["mask"] = part[5:]
        elif part == "no-colour":
            options["colour"] = False
        elif part == "gfpgan":
            options["restore"] = True
        else:
            raise ValueError(f"unknown candidate option: {part}")
    return Config(name, **options)


def iou(a, b) -> float:
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    w = max(0.0, min(ax + aw, bx + bw) - max(ax, bx))
    h = max(0.0, min(ay + ah, by + bh) - max(ay, by))
    union = aw * ah + bw * bh - w * h
    return w * h / union if union > 0 else 0.0


class Bench:
    """Holds the models, the identities and the gallery for one run."""

    JUDGES = ("judge", "inloop", "sface")   # research judge, the swappers' own encoder, the shipped model

    def __init__(self, out: str, log=print):
        self.out, self.log = out, log
        os.makedirs(out, exist_ok=True)
        self.tools = pipeline.Toolbox()
        self.embedders = {"judge": pipeline.Embedder("glintr100"), "inloop": self.tools.encoder,
                          "sface": pipeline.Embedder("sface")}
        self.identities: dict = {}
        self.targets: dict = {}
        self._gallery: dict | None = None

    # -- fixtures ---------------------------------------------------------

    def embed_all(self, image, marks) -> dict:
        return {name: e.embed(image, marks) for name, e in self.embedders.items()}

    def identity(self, identity: str) -> dict:
        """Reference set of one identity: raw encoder mean for the swappers, unit vectors for the judges."""
        if identity not in self.identities:
            vectors = {name: [] for name in self.JUDGES}
            images, used = [], []
            for file_id in fixtures.references(identity):
                image = fixtures.reference_image(file_id)
                faces = self.tools.detector.detect(image)
                if not faces:
                    continue
                for name, vector in self.embed_all(image, faces[0].marks).items():
                    vectors[name].append(vector)
                images.append(image)
                used.append(file_id)
            if len(used) < 2:
                raise RuntimeError(f"{identity}: fewer than two usable references")
            self.identities[identity] = dict(
                raw=np.mean(vectors["inloop"], axis=0), vectors=vectors, images=images, files=used,
                centroid={name: metrics.centroid(v) for name, v in vectors.items()},
                ceiling={name: metrics.leave_one_out(v) for name, v in vectors.items()})
        return self.identities[identity]

    def source_raw(self, identity: str, swapper: str) -> np.ndarray:
        """Mean raw embedding of the references in the encoder this swapper takes its source from."""
        ref = self.identity(identity)
        name = pipeline.source_encoder(swapper)
        if name == "arcface_w600k_r50":
            return ref["raw"]
        key = "raw:" + name
        if key not in ref:
            encoder = self.tools.get(name, lambda: pipeline.Embedder(name))
            ref[key] = np.mean([encoder.embed(image, self.tools.detector.detect(image)[0].marks)
                                for image in ref["images"]], axis=0)
        return ref[key]

    def target(self, target: fixtures.Target) -> dict:
        if target.id not in self.targets:
            image = fixtures.target_image(target, self.tools.detector.detect)
            face = fixtures.pick_face(target, self.tools.detector.detect(image))
            self.targets[target.id] = dict(
                image=image, face=face,
                vectors=None if face is None else self.embed_all(image, face.marks))
        return self.targets[target.id]

    def gallery(self, identities, distractors) -> dict:
        """Per judge: every benchmark identity plus the look-alike distractors (one document photo each)."""
        if self._gallery is None:
            gallery = {name: {} for name in self.JUDGES}
            for identity in identities:
                for name in self.JUDGES:
                    gallery[name][identity] = self.identity(identity)["centroid"][name]
            for identity in distractors:
                image = fixtures.load(f"flux/{identity}/doc")
                faces = self.tools.detector.detect(image)
                if faces:
                    for name, vector in self.embed_all(image, faces[0].marks).items():
                        gallery[name]["look-alike:" + identity] = metrics.unit(vector)
            self._gallery = gallery
        return self._gallery

    # -- one swap ---------------------------------------------------------

    def measure(self, target: fixtures.Target, identity: str, config: Config, gallery: dict | None,
                jitter=None, keep_image: bool = False) -> dict:
        t, ref = self.target(target), self.identity(identity)
        row = dict(target=target.id, identity=identity, candidate=config.label, tags=list(target.tags),
                   rendered_identity=identity in fixtures.RENDERED, status="ok")
        if t["face"] is None:
            row["status"] = "no-face-in-target"
            return row
        started = time.perf_counter()
        result = pipeline.swap_face(t["image"], t["face"].marks, self.source_raw(identity, config.swapper), config,
                                    self.tools, jitter=jitter)
        row["seconds"] = time.perf_counter() - started
        row["notes"] = result.notes
        row["edit_share"] = float((result.mask > 0).mean())
        row.update(metrics.outside_change(t["image"], result.image, result.mask))
        row.update(metrics.seam(t["image"], result.image, result.mask))
        row["sharpness"] = metrics.sharpness_ratio(t["image"], result.image, result.mask)
        faces = self.tools.detector.detect(result.image)
        match = max(faces, key=lambda f: iou(f.box, t["face"].box), default=None)
        if match is None or iou(match.box, t["face"].box) < 0.3:
            row["status"] = "face-lost-after-swap"
        else:
            row["landmark_shift"] = metrics.landmark_shift(t["face"].marks, match.marks)
            for name, vector in self.embed_all(result.image, match.marks).items():
                row[name + "_ref"] = metrics.cosine(vector, ref["centroid"][name])
                row[name + "_orig"] = metrics.cosine(vector, t["vectors"][name])
                row[name + "_ceiling"] = ref["ceiling"][name]
                if gallery is not None:
                    full = dict(gallery[name])
                    full["original person"] = metrics.unit(t["vectors"][name])
                    rank, wrong, margin = metrics.rank_of(vector, full, identity)
                    row[name + "_rank"], row[name + "_confused_with"], row[name + "_margin"] = rank, wrong, margin
        if keep_image:
            row["_image"], row["_mask"] = result.image, result.mask
        return row

    def save(self, row: dict, target: fixtures.Target, config: Config) -> str:
        """Write the output image with its provenance record next to it."""
        import cv2
        folder = os.path.join(self.out, "images", f"{row['target']}__{row['identity']}")
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, config.label + ".png")
        cv2.imwrite(path, row["_image"])
        with open(path, "rb") as f:
            digest = hashlib.sha256(f.read()).hexdigest()
        record = dict(
            kind="ai-edit/identity-bench", tool=VERSION, synthetic_faces_only=True,
            output=dict(file=os.path.basename(path), sha256=digest),
            source=dict(target=target.id, recipe=target.recipe,
                        files=[dict(id=f, sha256=fixtures.FILES[f][4]) for f in target.files]),
            reference=dict(identity=row["identity"],
                           files=[dict(id=f, sha256=fixtures.FILES[f][4]) for f in self.identity(row["identity"])["files"]]),
            engine=dict(candidate=config.label, swapper=config.swapper, mask=config.mask,
                        colour_match=config.colour, restoration="gfpgan_1.4" if config.restore else None,
                        weights={n: pipeline.MODELS[n]["sha256"] for n in self._weights(config)}),
            edit_share=row.get("edit_share"), untouched_pixels_changed=row.get("outside_changed_px"))
        with open(path[:-4] + ".provenance.json", "w") as f:
            json.dump(record, f, indent=1)
        return path

    @staticmethod
    def _weights(config: Config) -> list:
        names = ["arcface_w600k_r50"]
        if config.swapper in pipeline.MODELS:
            names.append(config.swapper)
            kind = pipeline.SWAPPERS[config.swapper][0]
            if kind in ("simswap", "ghost"):
                names.append("crossface_" + kind)
        if config.mask.startswith("parse"):
            names.append("bisenet_resnet_34")
        if config.mask == "mp":
            names.append("selfie_multiclass_256")
        if config.restore:
            names.append("gfpgan_1.4")
        return names


# ---------------------------------------------------------------- runs

def public(row: dict) -> dict:
    return {k: v for k, v in row.items() if not k.startswith("_")}


def run(out: str, candidates: list, targets, identities, rendered, distractors, log=print,
        stability: bool = True, make_sheets: bool = True) -> dict:
    import cv2
    bench = Bench(out, log)
    by_id = {t.id: t for t in targets}
    pair_list = fixtures.pairs(targets, identities, rendered)
    gallery = bench.gallery(list(identities) + list(rendered), distractors)
    rows, stability_rows, images = [], [], {}
    for config in candidates:
        started = time.perf_counter()
        for target_id, identity in pair_list:
            row = bench.measure(by_id[target_id], identity, config, gallery, keep_image=True)
            if "_image" in row:
                path = bench.save(row, by_id[target_id], config)
                images.setdefault((target_id, identity), {})[config.label] = path
            rows.append(public(row))
        if stability and config.swapper in FINALISTS and config == Config(config.swapper):
            stability_rows += _stability(bench, by_id, pair_list[:STABILITY_PAIRS], config, gallery)
        bench.tools.drop(config.swapper)
        log(f"{config.label}: {len(pair_list)} swaps in {time.perf_counter() - started:.0f} s")
    results = dict(
        version=VERSION, thresholds=metrics.THRESHOLDS, candidates=[c.label for c in candidates],
        pairs=pair_list, rows=rows, stability=stability_rows,
        ceilings={i: bench.identity(i)["ceiling"] for i in list(identities) + list(rendered)},
        gallery_size=len(gallery["judge"]) + 1,
        models={n: dict(sha256=m["sha256"], size=m["size"], licence=m["licence"]) for n, m in pipeline.MODELS.items()})
    with open(os.path.join(out, "results.json"), "w") as f:
        json.dump(results, f, indent=1)
    if make_sheets:
        folder = os.path.join(out, "sheets")
        os.makedirs(folder, exist_ok=True)
        keys = {}
        shown = [c.label for c in candidates if c.swapper in FINALISTS and c.mask == "parse" and c.colour]
        for (target_id, identity), paths in images.items():
            t = bench.target(by_id[target_id])
            tiles = {label: cv2.imread(paths[label]) for label in shown if label in paths}
            sheet_id = f"{target_id}__{identity}"
            sheet, keys[sheet_id] = sheets.contact_sheet(
                sheet_id.split("__")[0] + "  (which letter looks most like the reference person, and most natural?)",
                t["image"], bench.identity(identity)["images"], tiles, t["face"].box, sheet_id)
            cv2.imwrite(os.path.join(folder, sheet_id + ".jpg"), sheet, [cv2.IMWRITE_JPEG_QUALITY, 92])
        with open(os.path.join(out, "sheet-key.json"), "w") as f:   # keep away from raters
            json.dump(keys, f, indent=1)
        with open(os.path.join(folder, "ratings.csv"), "w") as f:
            f.write(sheets.rating_form(sorted(keys)))
    with open(os.path.join(out, "report.md"), "w") as f:
        f.write(report(results))
    return results


def _stability(bench: Bench, by_id: dict, pair_list: list, config: Config, gallery: dict) -> list:
    """Same swap repeated: byte determinism, then sensitivity to a 1 % landmark jitter."""
    out = []
    for n, (target_id, identity) in enumerate(pair_list):
        target = by_id[target_id]
        t = bench.target(target)
        if t["face"] is None:
            continue
        base = bench.measure(target, identity, config, gallery, keep_image=True)
        again = bench.measure(target, identity, config, gallery, keep_image=True)
        eye = float(np.linalg.norm(t["face"].marks[1] - t["face"].marks[0]))
        rng = np.random.default_rng(469_000 + n)
        scores, diffs = [base.get("judge_ref")], []
        for _ in range(STABILITY_REPEATS):
            moved = bench.measure(target, identity, config, gallery, keep_image=True,
                                  jitter=rng.normal(0.0, JITTER * eye, (5, 2)))
            scores.append(moved.get("judge_ref"))
            diffs.append(metrics.inside_difference(base["_image"], moved["_image"], np.maximum(base["_mask"], moved["_mask"])))
        scores = [s for s in scores if s is not None]
        out.append(dict(candidate=config.label, target=target_id, identity=identity,
                        deterministic=bool(np.array_equal(base["_image"], again["_image"])),
                        judge_ref_std=float(np.std(scores)) if scores else float("nan"),
                        judge_ref_range=float(np.ptp(scores)) if scores else float("nan"),
                        inside_diff=float(np.mean(diffs))))
    return out


# ---------------------------------------------------------------- report

def aggregate(rows: list) -> dict:
    """One candidate's rows -> the aggregate numbers the tables print."""
    scored = [r for r in rows if "judge_ref" in r]
    attempted = [r for r in rows if r["status"] != "no-face-in-target"]
    n = max(len(attempted), 1)
    mean = lambda key, src=scored: metrics.summary([r.get(key) for r in src])["mean"]
    judge = metrics.summary([r["judge_ref"] for r in scored])
    same = metrics.THRESHOLDS["judge_same_person"]
    return dict(
        n=len(attempted), lost=sum(r["status"] == "face-lost-after-swap" for r in attempted),
        judge_mean=judge["mean"], judge_median=judge["median"], judge_p10=judge["p10"], judge_min=judge["min"],
        verified_share=sum(r["judge_ref"] >= same for r in scored) / n,
        rank1=sum(r.get("judge_rank") == 1 for r in scored) / n,
        retention=metrics.summary([r["judge_ref"] / r["judge_ceiling"] for r in scored])["mean"],
        judge_orig=mean("judge_orig"), inloop_mean=mean("inloop_ref"), sface_mean=mean("sface_ref"),
        sface_rank1=sum(r.get("sface_rank") == 1 for r in scored) / n,
        outside_changed_px=sum(r.get("outside_changed_px", 0) for r in attempted),
        outside_max_diff=max([r.get("outside_max_diff", 0) for r in attempted] or [0]),
        outside_psnr=min([r.get("outside_psnr", float("inf")) for r in attempted] or [float("inf")]),
        outside_ssim=min([r.get("outside_ssim", 1.0) for r in attempted] or [1.0]),
        seam_gradient=mean("seam_gradient", attempted), seam_step=mean("seam_step", attempted),
        sharpness=metrics.summary([r.get("sharpness") for r in attempted])["median"],
        landmark_shift=mean("landmark_shift"), seconds=mean("seconds", attempted),
        edit_share=mean("edit_share", attempted))


def _fmt(value, digits=3) -> str:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return "n/a"
    if isinstance(value, float) and math.isinf(value):
        return "inf"
    return f"{value:.{digits}f}" if isinstance(value, float) else str(value)


def _table(header: list, body: list) -> str:
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    lines += ["| " + " | ".join(str(c) for c in row) + " |" for row in body]
    return "\n".join(lines) + "\n"


def report(results: dict) -> str:
    """Markdown report of a run: every number BENCH.md quotes comes from here."""
    rows, labels = results["rows"], results["candidates"]
    photo = [r for r in rows if not r["rendered_identity"]]
    track = [r for r in rows if r["rendered_identity"]]
    of = lambda label, src: [r for r in src if r["candidate"] == label]
    agg = {label: aggregate(of(label, photo)) for label in labels}
    hard = next((agg[l]["seam_gradient"] for l in labels if "mask-hard-box" in l), float("nan"))
    out = [f"# Identity bench report ({results['version']})\n",
           f"Pairs: {len(results['pairs'])} ({len({p[0] for p in results['pairs']})} targets); rank-1 gallery size "
           f"{results['gallery_size']} (identities, look-alikes and the target's original person).\n",
           "## Photoreal identities: identity\n",
           _table(["candidate", "n", "judge mean", "median", "p10", "min", "over 0.40", "rank-1", "retention",
                   "to original", "in-loop encoder", "SFace mean", "SFace rank-1", "face lost"],
                  [[l, a["n"], _fmt(a["judge_mean"]), _fmt(a["judge_median"]), _fmt(a["judge_p10"]), _fmt(a["judge_min"]),
                    _fmt(a["verified_share"], 2), _fmt(a["rank1"], 2), _fmt(a["retention"], 2), _fmt(a["judge_orig"]),
                    _fmt(a["inloop_mean"]), _fmt(a["sface_mean"]), _fmt(a["sface_rank1"], 2), a["lost"]]
                   for l, a in agg.items()]),
           "## Photoreal identities: image\n",
           _table(["candidate", "changed px outside", "max diff outside", "min PSNR outside", "min SSIM outside",
                   "seam gradient", "seam step", "sharpness (median)", "landmark shift", "edit share", "s per swap"],
                  [[l, a["outside_changed_px"], a["outside_max_diff"], _fmt(a["outside_psnr"], 1), _fmt(a["outside_ssim"], 4),
                    _fmt(a["seam_gradient"], 2), _fmt(a["seam_step"], 2), _fmt(a["sharpness"], 2),
                    _fmt(a["landmark_shift"]), _fmt(a["edit_share"]), _fmt(a["seconds"], 2)] for l, a in agg.items()]),
           "## Acceptance\n",
           f"Hard-edge control seam gradient: {_fmt(hard, 2)}.\n"]
    body = []
    for label, a in agg.items():
        if label.startswith("control") or "mask-hard-box" in label:
            continue
        v = metrics.verdict(a, hard)
        body.append([label] + ["n/a" if v[k] is None else "pass" if v[k] else "FAIL" for k in v])
    out.append(_table(["candidate", "A1 identity", "A2 rank-1", "A3 retention", "A4 untouched", "A5 seam", "all"], body))
    tags = sorted({t for r in photo for t in r["tags"]})
    main = [l for l in labels if not l.startswith("control") and "mask-" not in l and "no-colour" not in l]
    out.append("## By hard case (judge mean / rank-1 share, n)\n")
    body = []
    for tag in tags:
        line = [tag]
        for label in main:
            a = aggregate([r for r in of(label, photo) if tag in r["tags"]])
            line.append(f"{_fmt(a['judge_mean'], 2)} / {_fmt(a['rank1'], 2)} ({a['n']})")
        body.append(line)
    out.append(_table(["tag"] + main, body))
    out.append("## Paired differences in judge similarity (same pairs, 95 % bootstrap)\n")
    keyed = lambda label: {(r["target"], r["identity"]): r["judge_ref"] for r in of(label, photo) if "judge_ref" in r}
    plain = [l for l in main if "gfpgan" not in l]
    best = max(plain, key=lambda l: agg[l]["judge_mean"]) if plain else None
    body = []
    for label in main:
        other = label.replace("+gfpgan", "") if "gfpgan" in label else best
        if other == label or other is None:
            continue
        a, b = keyed(label), keyed(other)
        common = sorted(set(a) & set(b))
        d = metrics.paired_bootstrap([a[k] for k in common], [b[k] for k in common])
        body.append([label, other, len(common), _fmt(d["mean"]), f"{_fmt(d['low'])} .. {_fmt(d['high'])}"])
    out.append(_table(["candidate", "minus", "pairs", "mean difference", "95 % interval"], body))
    if track:
        out.append("## Rendered identities (DigiFace-1M references), reported apart\n")
        body = []
        for label in main:
            a = aggregate(of(label, track))
            body.append([label, a["n"], _fmt(a["judge_mean"]), _fmt(a["verified_share"], 2), _fmt(a["rank1"], 2), _fmt(a["retention"], 2)])
        out.append(_table(["candidate", "n", "judge mean", "over 0.40", "rank-1", "retention"], body))
    if results.get("stability"):
        out.append("## Stability (first 12 pairs, 4 repeats with 1 % landmark jitter)\n")
        body = []
        for label in sorted({s["candidate"] for s in results["stability"]}):
            s = [x for x in results["stability"] if x["candidate"] == label]
            body.append([label, len(s), sum(x["deterministic"] for x in s),
                         _fmt(float(np.mean([x["judge_ref_std"] for x in s]))),
                         _fmt(float(np.max([x["judge_ref_range"] for x in s]))),
                         _fmt(float(np.mean([x["inside_diff"] for x in s])), 2)])
        out.append(_table(["candidate", "pairs", "byte-identical reruns", "mean std of judge", "worst range", "mean pixel change inside"], body))
    out.append("## Failures (photoreal identities, finalists)\n")
    body = []
    same = metrics.THRESHOLDS["judge_same_person"]
    for r in photo:
        if r["candidate"] not in main:
            continue
        reasons = []
        if r["status"] != "ok":
            reasons.append(r["status"])
        elif r["judge_ref"] < same:
            reasons.append(f"judge {r['judge_ref']:.2f} under {same}")
        if r.get("judge_rank", 1) != 1:
            reasons.append(f"rank {r['judge_rank']}, confused with {r['judge_confused_with']}")
        if reasons:
            body.append([r["candidate"], r["target"], ",".join(r["tags"]), r["identity"], "; ".join(reasons)])
    out.append(_table(["candidate", "target", "tags", "identity", "why"], body) if body else "None.\n")
    return "\n".join(out)


# ---------------------------------------------------------------- cost

def cost_one(label: str) -> dict:
    """In a fresh process: load one candidate's models and time five swaps of the first pair."""
    config = parse_label(label)
    name, restore = config.swapper, config.restore
    start_rss = pipeline.rss_mb()
    bench = Bench(os.path.join(pipeline.cache_dir(), "runs", "cost"), log=lambda *_: None)
    bench.embedders = {"inloop": bench.tools.encoder}
    Bench.JUDGES = ("inloop",)
    target = fixtures.TARGETS[3]
    identity = fixtures.IDENTITIES[0]
    bench.identity(identity), bench.target(target)
    bench.tools.meter = pipeline.StageMeter()
    bench.tools.swapper(name)
    bench.tools.selfie if config.mask == "mp" else bench.tools.parser
    if restore:
        bench.tools.restorer
    loads = dict(bench.tools.meter.rows)
    t, raw = bench.target(target), bench.source_raw(identity, config.swapper)
    pipeline.swap_face(t["image"], t["face"].marks, raw, config, bench.tools)   # warm-up
    bench.tools.meter = pipeline.StageMeter()
    for _ in range(5):
        pipeline.swap_face(t["image"], t["face"].marks, raw, config, bench.tools)
    stages = {k: dict(wall_ms=1000 * v["wall_s"] / v["calls"], cpu_ms=1000 * v["cpu_s"] / v["calls"],
                      peak_rss_mb=v["peak_rss_mb"]) for k, v in bench.tools.meter.rows.items()}
    return dict(candidate=label, threads=pipeline.THREADS, baseline_rss_mb=start_rss,
                load_ms={k: 1000 * v["wall_s"] for k, v in loads.items()}, stages=stages,
                swap_total_ms=sum(s["wall_ms"] for s in stages.values()),
                peak_rss_mb=max(s["peak_rss_mb"] for s in stages.values()),
                weights_mb={n: pipeline.MODELS[n]["size"] / 1e6 for n in Bench._weights(config)})


def cost(out: str, labels: list) -> list:
    rows = []
    for label in labels:
        text = subprocess.run([sys.executable, "-m", "tools.identity_bench", "cost-one", label],
                              check=True, capture_output=True, text=True, cwd=pipeline.REPO).stdout
        rows.append(json.loads(text.strip().splitlines()[-1]))
        r = rows[-1]
        print(f"{label}: swap {r['swap_total_ms']:.0f} ms, peak RSS {r['peak_rss_mb']:.0f} MB")
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "cost.json"), "w") as f:
        json.dump(rows, f, indent=1)
    return rows


# ---------------------------------------------------------------- command line

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="python3 -m tools.identity_bench", description=__doc__.split("\n")[0])
    parser.add_argument("command", nargs="?", default="run",
                        choices=["run", "self-test", "one", "screen", "explore", "cost", "cost-one", "select",
                                 "fetch", "report"])
    parser.add_argument("label", nargs="?")
    parser.add_argument("--out", default=None)
    parser.add_argument("--target", default="T04")
    parser.add_argument("--identity", default=None)
    parser.add_argument("--candidate", default="inswapper_128", help="a candidate label, e.g. ghost_1_256+mask-mp")
    parser.add_argument("--candidates", default=None, help="comma-separated labels for explore and cost")
    args = parser.parse_args(argv)
    out = args.out or os.path.join(pipeline.cache_dir(), "runs", args.command)

    if args.command == "self-test":
        import unittest
        suite = unittest.defaultTestLoader.loadTestsFromName("tools.identity_bench.test_identity_bench")
        return 0 if unittest.TextTestRunner(verbosity=1).run(suite).wasSuccessful() else 1
    if args.command == "select":
        print(fixtures.manifest_source(fixtures.select()))
        return 0
    if args.command == "fetch":
        # Preflight the whole requested model set before fetching any fixtures.
        for name in pipeline.MODELS:
            pipeline.require_bench_model(name)
        for file_id in fixtures.FILES:
            fixtures.fetch(file_id)
        for name in pipeline.MODELS:
            pipeline.model_path(name)
        print(f"{len(fixtures.FILES)} fixture files and {len(pipeline.MODELS)} models verified")
        return 0
    if args.command == "cost-one":
        print(json.dumps(cost_one(args.label)))
        return 0
    if args.command == "cost":
        labels = (args.candidates.split(",") if args.candidates else
                  list(FINALISTS) + [n + "+gfpgan" for n in FINALISTS])
        # No earlier candidate may download weights before a later one is refused.
        for label in labels:
            parse_label(label)
        cost(out, labels)
        return 0
    if args.command == "report":
        with open(os.path.join(out if args.out else os.path.join(pipeline.cache_dir(), "runs", "run"), "results.json")) as f:
            print(report(json.load(f)))
        return 0
    if args.command == "one":
        # Bench(out) loads the research judge; reject bad candidates first.
        config = parse_label(args.candidate)
        bench = Bench(out)
        target = next(t for t in fixtures.TARGETS + fixtures.DEV_TARGETS if t.id == args.target)
        identity = args.identity or fixtures.IDENTITIES[0]
        gallery = bench.gallery(list(fixtures.IDENTITIES) + list(fixtures.RENDERED), fixtures.DISTRACTORS)
        row = bench.measure(target, identity, config, gallery, keep_image=True)
        if "_image" in row:
            print("output:", bench.save(row, target, config))
        print(json.dumps(public(row), indent=1))
        return 0
    if args.command == "explore":   # extra candidates on the same fixed pairs, after the fact
        results = run(out, [parse_label(label) for label in args.candidates.split(",")], fixtures.TARGETS,
                      fixtures.IDENTITIES, fixtures.RENDERED, fixtures.DISTRACTORS, stability=False, make_sheets=False)
    elif args.command == "screen":
        results = run(out, [Config(name) for name in ALL_SWAPPERS], fixtures.DEV_TARGETS, fixtures.DEV_IDENTITIES,
                      (), (), stability=False, make_sheets=False)
    else:
        results = run(out, final_candidates(), fixtures.TARGETS, fixtures.IDENTITIES, fixtures.RENDERED,
                      fixtures.DISTRACTORS)
    print(report(results))
    print(f"\nwritten to {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
