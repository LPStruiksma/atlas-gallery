"""Plan B probe: can the shipped SFace drive GHOST through a small adapter?  (IDS-16, issue #507)

GHOST 1's generator reads the raw 512-d embedding of its own identity
backbone, which #505 found to be InsightFace's research-only
`ms1mv3_arcface_r100_fp16`.  This probe fits a small adapter from the shipped
SFace embedding (128-d, Apache-2.0) to that 512-d space on synthetic faces
outside the benchmark, then runs GHOST on the bench's 58 photoreal pairs with
four sources and measures each with the research judge:

- `own`:       the true backbone embedding (GHOST as its authors run it);
- `crossface`: FaceFusion's converter from w600k_r50 (the IDS-2 path, for reference);
- `sface-linear`, `sface-mlp`: SFace through the fitted adapter.

The research-only backbone is used here only as the bench-time target of the
fit, as its register row allows.  Nothing is committed; whether an adapter
fitted this way could ship is a licence question answered in
docs/identity-studio/IDENTITY_PATH_B.md, not here.

    python3 -m tools.identity_bench.planb.encoder_swap_probe            # collect, fit, swap, report
    python3 -m tools.identity_bench.planb.encoder_swap_probe --pairs 12 # quicker, first 12 pairs
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np

from tools.identity_bench import fixtures, metrics, pipeline
from tools.identity_bench.provenance import compare_backbone as cb

SFACE_DIM, GHOST_DIM = 128, 512


# ---------------------------------------------------------------- adapters

def unit(x: np.ndarray) -> np.ndarray:
    x = np.asarray(x, np.float64)
    return x / np.maximum(np.linalg.norm(x, axis=-1, keepdims=True), 1e-12)


class LinearAdapter:
    """Ridge regression from unit SFace vectors to unit GHOST vectors, rescaled to GHOST's mean norm."""

    name = "sface-linear"

    def __init__(self, ridge: float = 1e-2):
        self.ridge = ridge

    def fit(self, x: np.ndarray, y: np.ndarray) -> "LinearAdapter":
        xu = np.hstack([unit(x), np.ones((len(x), 1))])
        yu = unit(y)
        a = xu.T @ xu + self.ridge * len(x) * np.eye(xu.shape[1])
        self.w = np.linalg.solve(a, xu.T @ yu)
        self.norm = float(np.linalg.norm(y, axis=1).mean())
        return self

    def __call__(self, x: np.ndarray) -> np.ndarray:
        xu = np.hstack([unit(np.atleast_2d(x)), np.ones((len(np.atleast_2d(x)), 1))])
        return unit(xu @ self.w) * self.norm


class MlpAdapter:
    """One hidden layer, trained with Adam on 1 - cosine to the GHOST vector."""

    name = "sface-mlp"

    def __init__(self, hidden: int = 1024, epochs: int = 300, lr: float = 1e-3, decay: float = 1e-4,
                 batch: int = 256, seed: int = 507):
        self.hidden, self.epochs, self.lr, self.decay, self.batch, self.seed = hidden, epochs, lr, decay, batch, seed

    def _forward(self, x):
        h = x @ self.w1 + self.b1
        a = np.maximum(h, 0)
        return h, a, a @ self.w2 + self.b2

    def fit(self, x: np.ndarray, y: np.ndarray, log=None) -> "MlpAdapter":
        rng = np.random.default_rng(self.seed)
        x, t = unit(x), unit(y)
        d_in, d_out = x.shape[1], t.shape[1]
        self.w1 = rng.normal(0, np.sqrt(2 / d_in), (d_in, self.hidden))
        self.b1 = np.zeros(self.hidden)
        self.w2 = rng.normal(0, np.sqrt(1 / self.hidden), (self.hidden, d_out))
        self.b2 = np.zeros(d_out)
        params = [self.w1, self.b1, self.w2, self.b2]
        m = [np.zeros_like(p) for p in params]
        v = [np.zeros_like(p) for p in params]
        step = 0
        for epoch in range(self.epochs):
            order = rng.permutation(len(x))
            for i in range(0, len(x), self.batch):
                idx = order[i:i + self.batch]
                xb, tb = x[idx], t[idx]
                h, a, out = self._forward(xb)
                norm = np.maximum(np.linalg.norm(out, axis=1, keepdims=True), 1e-12)
                u = out / norm
                cos = (u * tb).sum(1, keepdims=True)
                # d(1 - cos)/d out = -(tb - cos * u) / norm
                g_out = -(tb - cos * u) / norm / len(idx)
                grads = [None] * 4
                grads[3] = g_out.sum(0)
                grads[2] = a.T @ g_out
                g_a = g_out @ self.w2.T
                g_h = g_a * (h > 0)
                grads[1] = g_h.sum(0)
                grads[0] = xb.T @ g_h
                step += 1
                for k, (p, g) in enumerate(zip(params, grads)):
                    if k in (0, 2):
                        g = g + self.decay * p
                    m[k] = 0.9 * m[k] + 0.1 * g
                    v[k] = 0.999 * v[k] + 0.001 * g * g
                    p -= self.lr * (m[k] / (1 - 0.9 ** step)) / (np.sqrt(v[k] / (1 - 0.999 ** step)) + 1e-8)
            if log and (epoch + 1) % 100 == 0:
                log(f"  mlp epoch {epoch + 1}: train cosine {cosine_rows(self._raw(x), t).mean():.3f}")
        self.norm = float(np.linalg.norm(y, axis=1).mean())
        return self

    def _raw(self, x):
        return self._forward(x)[2]

    def __call__(self, x: np.ndarray) -> np.ndarray:
        return unit(self._raw(unit(np.atleast_2d(x)))) * self.norm


def cosine_rows(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    return (unit(a) * unit(b)).sum(-1)


def split_by_identity(labels: list, share: float = 0.2, seed: int = 507) -> tuple:
    """Train and validation index arrays with no identity on both sides."""
    ids = sorted(set(labels))
    rng = np.random.default_rng(seed)
    held = set(rng.choice(ids, max(1, int(round(share * len(ids)))), replace=False))
    lab = np.array(labels)
    val = np.isin(lab, list(held))
    return np.where(~val)[0], np.where(val)[0]


# ---------------------------------------------------------------- training faces

def excluded_identities() -> set:
    """Every identity the benchmark measures, compares against or swaps onto."""
    out = set(fixtures.IDENTITIES) | set(fixtures.DISTRACTORS)
    for target in fixtures.TARGETS:
        if target.identity:
            out.add(target.identity)
        for file_id in target.files:
            if file_id.startswith("flux/"):
                out.add(file_id.split("/")[1])
    return out


def training_members(limit: int | None = None) -> list:
    """(identity, archive member) for the FLUXSynID pool minus the benchmark's identities."""
    index = fixtures.archive_index("flux")
    skip = excluded_identities()
    out = []
    for identity in fixtures.flux_pool():
        if identity in skip:
            continue
        for kind in fixtures.FLUX_KINDS:
            out.append((identity, fixtures.flux_member(index, identity, kind)))
    return out[:limit] if limit else out


def pool_image(member: str) -> np.ndarray:
    import cv2
    size, offset = fixtures.archive_index("flux")[member]
    head = fixtures._head("flux", fixtures.FLUX_POOL_BYTES)
    data = fixtures.member_bytes(head[offset:offset + fixtures._span(size)], size)
    return cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)


class Encoders:
    """SFace (shipped) and GHOST's own backbone (bench-only), on the same landmarks."""

    def __init__(self):
        self.detector = pipeline.Detector()
        self.sface = pipeline.Embedder("sface")
        self.ghost = cb.OnnxEmbedder(cb.iresnet_onnx(cb.read(cb.fetch("ghost_backbone"), "pth")).SerializeToString())

    def both(self, image: np.ndarray, marks) -> tuple:
        crop, _ = pipeline.align(image, marks, "arcface_112_v2", 112)
        return self.sface.embed(image, marks), self.ghost.embed(crop[None])[0]

    def faces(self, image: np.ndarray, flip: bool = True) -> list:
        """(sface, ghost) for the largest face, and for its mirror image."""
        out = []
        for img in ([image, image[:, ::-1].copy()] if flip else [image]):
            faces = self.detector.detect(img)
            if faces:
                out.append(self.both(img, faces[0].marks))
        return out


def collect(encoders: Encoders, limit: int | None = None, log=print) -> dict:
    xs, ys, labels = [], [], []
    members = training_members(limit)
    for i, (identity, member) in enumerate(members):
        for s, g in encoders.faces(pool_image(member)):
            xs.append(s)
            ys.append(g)
            labels.append(identity)
        if log and (i + 1) % 200 == 0:
            log(f"  collected {i + 1}/{len(members)} photos")
    return dict(x=np.array(xs), y=np.array(ys), labels=labels, photos=len(members),
                identities=len(set(labels)))


def features_path(limit: int | None = None) -> str:
    return os.path.join(pipeline.cache_dir(), "runs", "planb", f"features{'' if limit is None else f'-{limit}'}.npz")


def cached_collect(encoders: Encoders, limit: int | None = None, log=print) -> dict:
    """collect(), kept in the local cache (synthetic faces' vectors only) so adapters can be re-fit cheaply."""
    path = features_path(limit)
    if os.path.exists(path):
        z = np.load(path)
        labels = [str(v) for v in z["labels"]]
        return dict(x=z["x"], y=z["y"], labels=labels, photos=int(z["photos"]), identities=len(set(labels)))
    data = collect(encoders, limit, log)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    np.savez(path, x=data["x"], y=data["y"], labels=np.array(data["labels"]), photos=data["photos"])
    return data


def learning_curve(data: dict, shares=(0.25, 0.5, 0.75, 1.0), ridges=(1e-3, 1e-2, 1e-1, 1.0)) -> list:
    """Validation cosine of the linear adapter against the number of training identities, best ridge each."""
    train, val = split_by_identity(data["labels"])
    ids = sorted({data["labels"][i] for i in train})
    out = []
    for share in shares:
        keep = set(ids[:max(2, int(round(share * len(ids))))])
        rows = [i for i in train if data["labels"][i] in keep]
        best = max((float(cosine_rows(LinearAdapter(r).fit(data["x"][rows], data["y"][rows])(data["x"][val]),
                                      data["y"][val]).mean()), r) for r in ridges)
        out.append(dict(identities=len(keep), faces=len(rows), validation_cosine=best[0], ridge=best[1]))
    return out


# ---------------------------------------------------------------- swaps through GHOST

class FixedSourceSwapper(pipeline.Swapper):
    """GHOST with the source vector set from outside instead of through CrossFace."""

    def __init__(self, name: str = "ghost_1_256"):
        super().__init__(name)
        self.current = None

    def source_vector(self, raw_embedding):
        if self.current is None:
            return super().source_vector(raw_embedding)
        return np.asarray(self.current, np.float32).reshape(1, -1)


def identity_sources(bench, encoders: Encoders, identity: str, adapters: list) -> dict:
    """Per source kind, the mean vector over the identity's reference photos."""
    ref = bench.identity(identity)
    own, sf = [], []
    for image in ref["images"]:
        faces = encoders.detector.detect(image)
        s, g = encoders.both(image, faces[0].marks)
        own.append(g)
        sf.append(s)
    out = {"own": np.mean(own, axis=0), "crossface": None}
    for adapter in adapters:
        out[adapter.name] = np.mean(adapter(np.array(sf)), axis=0)
    return out


def swap_run(adapters: list, encoders: Encoders, n_pairs: int | None = None, log=print) -> list:
    from tools.identity_bench.__main__ import Bench
    bench = Bench(os.path.join(pipeline.cache_dir(), "runs", "planb"), log=lambda *_: None)
    swapper = FixedSourceSwapper()
    bench.tools._cache["ghost_1_256"] = swapper
    config = pipeline.Config("ghost_1_256")
    pairs = [(t, i) for t, i in fixtures.pairs() if i not in fixtures.RENDERED]
    pairs = pairs[:n_pairs] if n_pairs else pairs
    gallery = bench.gallery(fixtures.IDENTITIES, fixtures.DISTRACTORS)
    targets = {t.id: t for t in fixtures.TARGETS}
    sources, rows = {}, []
    for k, (target_id, identity) in enumerate(pairs):
        if identity not in sources:
            sources[identity] = identity_sources(bench, encoders, identity, adapters)
        for kind, vector in sources[identity].items():
            swapper.current = vector
            row = bench.measure(targets[target_id], identity, config, gallery)
            row["source"] = kind
            rows.append({k2: v for k2, v in row.items() if not k2.startswith("_")})
        if log and (k + 1) % 10 == 0:
            log(f"  swapped {k + 1}/{len(pairs)} pairs")
    swapper.current = None
    return rows


def aggregate(rows: list) -> dict:
    kinds = sorted({r["source"] for r in rows}, key=lambda k: ["own", "crossface", "sface-linear", "sface-mlp"].index(k)
                   if k in ("own", "crossface", "sface-linear", "sface-mlp") else 9)
    ok = [r for r in rows if r.get("status") == "ok"]
    by = {k: {(r["target"], r["identity"]): r for r in ok if r["source"] == k} for k in kinds}
    common = set.intersection(*(set(v) for v in by.values())) if by else set()
    out = {}
    for k in kinds:
        vals = [by[k][p] for p in sorted(common)]
        judge = [r["judge_ref"] for r in vals]
        out[k] = dict(n=len(vals), judge=metrics.summary(judge),
                      over_040=float(np.mean([j >= metrics.THRESHOLDS["judge_same_person"] for j in judge])) if judge else float("nan"),
                      rank1=float(np.mean([r.get("judge_rank") == 1 for r in vals])) if vals else float("nan"),
                      to_original=float(np.mean([r["judge_orig"] for r in vals])) if vals else float("nan"),
                      sface=float(np.mean([r["sface_ref"] for r in vals])) if vals else float("nan"))
        if k != "own" and "own" in by:
            out[k]["minus_own"] = metrics.paired_bootstrap(judge, [by["own"][p]["judge_ref"] for p in sorted(common)])
    return out


# ---------------------------------------------------------------- the run

def run(n_pairs: int | None = None, limit: int | None = None, log=print) -> dict:
    encoders = Encoders()
    log("collecting training faces (FLUXSynID pool minus every benchmark identity)")
    data = cached_collect(encoders, limit, log)
    train, val = split_by_identity(data["labels"])
    log(f"  {len(data['x'])} faces from {data['identities']} identities; {len(train)} train, {len(val)} validation")
    curve = learning_curve(data)
    for point in curve:
        log(f"  linear adapter, {point['identities']} training identities: validation cosine {point['validation_cosine']:.3f}")
    adapters, fit = [], {}
    for adapter in (LinearAdapter(), MlpAdapter()):
        if isinstance(adapter, MlpAdapter):
            adapter.fit(data["x"][train], data["y"][train], log)
        else:
            adapter.fit(data["x"][train], data["y"][train])
        fit[adapter.name] = dict(
            train_cosine=float(cosine_rows(adapter(data["x"][train]), data["y"][train]).mean()),
            validation_cosine=float(cosine_rows(adapter(data["x"][val]), data["y"][val]).mean()))
        log(f"  {adapter.name}: {fit[adapter.name]}")
        adapters.append(adapter)
    # refit on everything for the swaps (validation only chose nothing; it reports generalisation)
    for adapter in adapters:
        adapter.fit(data["x"], data["y"]) if isinstance(adapter, LinearAdapter) else adapter.fit(data["x"], data["y"])
    log("swapping the benchmark's photoreal pairs through GHOST")
    rows = swap_run(adapters, encoders, n_pairs, log)
    return dict(training=dict(faces=int(len(data["x"])), photos=data["photos"], identities=data["identities"],
                              train=int(len(train)), validation=int(len(val))),
                fit=fit, learning_curve=curve, results=aggregate(rows), rows=rows)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--pairs", type=int, help="only the first N photoreal pairs")
    parser.add_argument("--limit", type=int, help="only the first N training photos")
    parser.add_argument("--out", default=os.path.join(pipeline.cache_dir(), "runs", "planb", "probe.json"))
    args = parser.parse_args(argv)
    result = run(args.pairs, args.limit)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as f:
        json.dump(result, f, indent=1, default=float)
    for kind, r in result["results"].items():
        print(f"{kind:14s} n={r['n']} judge mean {r['judge']['mean']:.3f} rank-1 {r['rank1']:.2f} "
              f"over 0.40 {r['over_040']:.2f}" + (f"  minus own {r['minus_own']['mean']:+.3f} "
                                                    f"({r['minus_own']['low']:+.3f} .. {r['minus_own']['high']:+.3f})"
                                                    if "minus_own" in r else ""))
    print(f"written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
