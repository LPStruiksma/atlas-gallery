#!/usr/bin/env python3
"""IDS-12F (#1091): the first real 20-pair full-head cohort run, scored against IDS-12A.

Four steps, each resumable:

    fixtures  generate the synthetic people (one PNG per person, skipped when present)
    freeze    compute the predeclared edit fences from the targets and write cohort.json
              (commit it BEFORE running any pair)
    run       per pair: the candidate (FLUX.2 klein 4B + BFS head adapter) inside the frozen
              fence, the paired face-only baseline, and every automatic measurement; one
              checkpoint file per pair, so a restart loses at most the pair in flight
    export    write the committed rows (quality.py format), the run log summary and the
              blinded side-by-side sheets with their key in a separate file

The pure helpers (plan, fence hashing, row building, sheet order) need only the standard
library and are what ``test_run_cohort.py`` covers. Heavy dependencies load lazily. No
weights, fixtures or raw outputs enter the repository: keep ``--work`` outside git.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(1, str(HERE.parents[2]))  # repository root, for tools.identity_bench.pipeline (baseline)
import probe  # noqa: E402  (same directory; the IDS-3 engines, judges and classification)
import quality  # noqa: E402  (the frozen IDS-12A evaluator)

COHORT_PATH = HERE / "cohort.json"
RESULTS = HERE / "results" / "ids12f"
SCHEMA = "atlas.identity_bench.head.cohort/1"
UNIT = "IDS-12F (#1091)"

LICENCE = {
    "cleared": True,
    "source": "synthetic: generated at run time by run_cohort.py from the frozen prompt and seed (FLUX.2 klein 4B, Apache-2.0)",
    "licence": "synthetic image, no person depicted; generator weights Apache-2.0",
    "permitted_use": "benchmark fixture for the ATLAS identity bench; not shipped",
}

# --------------------------------------------------------------------------
# The frozen plan. The first six people reuse the IDS-3 prompts and seeds
# (probe.FIXTURES); the rest were written for this unit before any output.
# Prompts never name a real person.
# --------------------------------------------------------------------------
_IDS3_SEEDS = {name: probe.FIXTURE_SEED + i for i, name in enumerate(probe.FIXTURES)}
NEW_PEOPLE = {
    "tgt_4": ("waist-up photo of a 35 year old man with short curly black hair and a trimmed beard wearing a green hoodie, "
              "standing on a train platform, seen in left side profile looking straight ahead to the left, "
              "a train and station signs blurred behind him", 1091),
    "tgt_5": ("waist-up photo of a 45 year old woman with dark blonde hair tied in a high bun, wearing a white blouse, "
              "standing in a crowded street market, head turned three-quarters to the left, "
              "colourful fruit stalls and people behind her", 1092),
    "tgt_6": ("waist-up photo of a 25 year old woman with a short brown pixie haircut wearing a black leather jacket, "
              "standing in front of a dense green hedge with small white flowers, facing the camera, slight smile", 1093),
    "tgt_7": ("waist-up photo of a 33 year old man with long dark hair tied back in a low ponytail, wearing a grey "
              "high-neck turtleneck sweater, sitting on a sofa in a living room, head turned three-quarters to the right, "
              "bookshelf and plant behind him", 1094),
    "tgt_8": ("waist-up photo of a 65 year old woman with a straight silver-grey chin-length bob wearing a patterned silk "
              "scarf around her neck and a navy coat, standing by a lake, seen in right side profile looking to the right, "
              "water and reeds behind her", 1095),
    "ref_d": ("head and shoulders portrait photo of a 24 year old East Asian man with short straight black hair with a side "
              "parting, clean shaven, dark brown eyes, neutral expression, looking at the camera, plain white wall behind him", 1096),
    "ref_e": ("head and shoulders portrait photo of a 42 year old South Asian woman with very long straight black hair worn "
              "loose over both shoulders, brown eyes, small smile, looking at the camera, plain pale green wall behind her", 1097),
}
PEOPLE = {name: (prompt, _IDS3_SEEDS[name]) for name, prompt in probe.FIXTURES.items()}
PEOPLE.update(NEW_PEOPLE)

# Intended pose, declared from the prompt before generation. The frozen pose_tag is measured
# from the generated target at freeze time (pose_tag_from_yaw), before any pair runs.
TARGET_POSE = {"tgt_1": "frontal", "tgt_2": "frontal", "tgt_6": "frontal",
               "tgt_3": "three_quarter", "tgt_5": "three_quarter", "tgt_7": "three_quarter",
               "tgt_4": "profile", "tgt_8": "profile"}
REFERENCE_HAIR = {"ref_a": "long_curly", "ref_b": "bald", "ref_c": "afro",
                  "ref_d": "short_straight", "ref_e": "long_straight"}
TARGET_HAIR = {"tgt_1": "short_straight", "tgt_2": "long_straight", "tgt_3": "short_straight",
               "tgt_4": "short_curly", "tgt_5": "bun", "tgt_6": "short_straight",
               "tgt_7": "ponytail", "tgt_8": "bob"}
# Busy background or collar/scarf at the neck: the boundary the head must meet is hard.
HARD_BOUNDARY_TARGETS = {"tgt_3", "tgt_4", "tgt_5", "tgt_6", "tgt_7", "tgt_8"}

# (pair id, target, reference). 20 distinct combinations; no combination repeats.
PAIRS = (
    ("f01", "tgt_1", "ref_a"), ("f02", "tgt_1", "ref_c"), ("f03", "tgt_1", "ref_e"),
    ("f04", "tgt_2", "ref_b"), ("f05", "tgt_2", "ref_a"), ("f06", "tgt_2", "ref_d"),
    ("f07", "tgt_3", "ref_c"), ("f08", "tgt_3", "ref_d"), ("f09", "tgt_3", "ref_b"),
    ("f10", "tgt_4", "ref_a"), ("f11", "tgt_4", "ref_d"),
    ("f12", "tgt_5", "ref_b"), ("f13", "tgt_5", "ref_e"),
    ("f14", "tgt_6", "ref_c"), ("f15", "tgt_6", "ref_d"), ("f16", "tgt_6", "ref_e"),
    ("f17", "tgt_7", "ref_a"), ("f18", "tgt_7", "ref_c"),
    ("f19", "tgt_8", "ref_b"), ("f20", "tgt_8", "ref_e"),
)
SEED_BASE = 109100  # per-pair seed = SEED_BASE + pair index
BLIND_SEED = 1091  # frozen random order of the blinded sheets

CANDIDATE_QUANT = "Q4_0"
CANDIDATE_ENGINE = "klein4b-Q4_0-headlora"
BASELINE = "ghost_1_256+mask-mp"  # the bench's face-only pipeline with the licence-clean MediaPipe mask
BASELINE_LICENCE = ("GHOST 1 generator Apache-2.0 per upstream; MediaPipe mask Apache-2.0; identity encoder "
                    "arcface_w600k_r50 and CrossFace converter are bench-only per docs/identity-studio/LICENCES.md")

# Fence recipe (pure geometry on the target's face box and head mask, fixed before any output).
FENCE_RECIPE = ("fence = (head-parse mask of the target dilated by 0.25 x face width) UNION (ellipse centred 0.10 x face "
                "height above the face-box centre, semi-axes 1.1 x face width and 1.3 x face height), INTERSECTED with "
                "probe.head_crop_box(scale 3.4); 1 inside, 0 outside; hash = SHA-256 of the row-major uint8 bytes")
SCALE_METHOD = ("head span = distance from the eye midpoint to the mouth-corner midpoint (YuNet landmarks), "
                "the same definition on target and result")
SFACE_SAME = probe.SFACE_SAME_IDENTITY  # 0.363, OpenCV's published SFace threshold
GLINT_SAME = 0.40  # the identity bench's pre-registered per-swap bar for glintr100 (BENCH.md A1)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def plan_pairs() -> list[dict]:
    """The cohort pairs without run-time hashes. Pure; the freeze adds fixture and fence hashes."""
    out = []
    for i, (pid, tgt, ref) in enumerate(PAIRS):
        hair_change = REFERENCE_HAIR[ref] != TARGET_HAIR[tgt]
        out.append({
            "pair_id": pid, "target_id": tgt, "reference_id": ref, "baseline_id": f"{pid}:{BASELINE}",
            "engine": CANDIDATE_ENGINE, "seed": SEED_BASE + i,
            "intended_pose": TARGET_POSE[tgt], "hair_shape": REFERENCE_HAIR[ref], "target_hair": TARGET_HAIR[tgt],
            "hairline_change": hair_change, "hard_boundary": tgt in HARD_BOUNDARY_TARGETS,
            "model_sha256": probe.DOWNLOADS[f"klein-{CANDIDATE_QUANT}.gguf"][1],
            "adapter_sha256": probe.DOWNLOADS["loras/bfs_head_v1.safetensors"][1],
            "licence": dict(LICENCE),
        })
    return out


def pairs_digest(pairs: list[dict]) -> str:
    """Canonical digest of the frozen pairs, so any later edit to cohort.json is detectable."""
    return sha256_bytes(json.dumps(pairs, sort_keys=True, separators=(",", ":")).encode())


FRONTAL_MAX_YAW = 0.30  # probe.POSE_TOLERANCE; the yaw proxy is nose offset over eye distance
PROFILE_MIN_YAW = 1.00


def pose_tag_from_yaw(yaw) -> str:
    """Pose tag measured on the generated target: frontal, three_quarter or profile (no landmarks = profile)."""
    if yaw is None:
        return "profile"
    a = abs(float(yaw))
    return "frontal" if a < FRONTAL_MAX_YAW else ("three_quarter" if a < PROFILE_MIN_YAW else "profile")


def hard_case_ids(pairs: list[dict]) -> dict:
    return {
        "profile_or_three_quarter": [p["pair_id"] for p in pairs if p.get("pose_tag", p.get("intended_pose")) in quality.PROFILE_TAGS],
        "hairline_change": [p["pair_id"] for p in pairs if p["hairline_change"]],
        "hard_boundary": [p["pair_id"] for p in pairs if p["hard_boundary"]],
        "reference_hair_shapes": sorted({p["hair_shape"] for p in pairs}),
    }


def fence_hash_from_bits(bits) -> str:
    """Same digest quality.check_outside_fence computes: SHA-256 of the 0/1 bytes."""
    return quality.sha256_hex(bits)


def sheet_order(pair_ids: list[str], seed: int = BLIND_SEED) -> dict:
    """Frozen blinded order: for each pair, which side the candidate is shown on."""
    rng = random.Random(seed)
    return {pid: ("candidate", "baseline") if rng.random() < 0.5 else ("baseline", "candidate") for pid in pair_ids}


def checkpoint_path(work: Path, pid: str) -> Path:
    return work / "pairs" / f"{pid}.json"


def write_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text)
    os.replace(tmp, path)


# --------------------------------------------------------------------------
# Step 1: fixtures
# --------------------------------------------------------------------------
def cmd_fixtures(args) -> int:
    work = Path(args.work)
    out = work / "fixtures"
    out.mkdir(parents=True, exist_ok=True)
    log_path = out / "fixtures_log.json"
    log = json.loads(log_path.read_text()) if log_path.exists() else {"items": {}}
    todo = [n for n in PEOPLE if not (out / f"{n}.png").exists()]
    if not todo:
        print("all fixtures present")
        return 0
    engine = probe.KleinEngine(Path(args.models), CANDIDATE_QUANT, lora=False, threads=args.threads)
    log.update({"engine": engine.name, "host": probe.host_info(), "load_s": round(engine.load_s, 1)})
    for name in todo:
        prompt, seed = PEOPLE[name]
        img, secs = engine.text_to_image(prompt + probe.STYLE, probe.FIXTURE_SIZE, seed)
        img.save(out / f"{name}.png")
        log["items"][name] = {"seconds": round(secs, 1), "steps": list(engine.steps), "seed": seed,
                              "sha256": probe.sha256(out / f"{name}.png")}
        log["peak_rss_mb"] = probe.peak_rss_mb()
        write_atomic(log_path, json.dumps(log, indent=1))
        print(name, log["items"][name], flush=True)
    return 0


# --------------------------------------------------------------------------
# Step 2: freeze (fences from the targets only; written before any pair runs)
# --------------------------------------------------------------------------
def compute_fence(judge, target):
    """The predeclared edit fence for one target, from the target alone (FENCE_RECIPE)."""
    cv2, np = judge.cv2, judge.np
    f = judge.face(target)
    if f is None:
        raise ValueError("no face in target; cannot declare a fence")
    h, w = target.shape[:2]
    x, y, bw, bh = (float(v) for v in f[:4])
    head = np.isin(judge.parse(target, f), judge.HEAD).astype(np.uint8)
    k = int(0.25 * bw) | 1
    grown = cv2.dilate(head, np.ones((k, k), np.uint8))
    ell = np.zeros((h, w), np.uint8)
    cv2.ellipse(ell, (int(round(x + bw / 2)), int(round(y + bh / 2 - 0.10 * bh))),
                (int(round(1.1 * bw)), int(round(1.3 * bh))), 0, 0, 360, 1, -1)
    x0, y0, x1, y1 = probe.head_crop_box(f, w, h)
    box = np.zeros((h, w), np.uint8)
    box[y0:y1, x0:x1] = 1
    return ((grown | ell) & box).astype(np.uint8)


def _versions() -> dict:
    out = {"python": sys.version.split()[0]}
    for mod, name in (("stable_diffusion_cpp", "stable-diffusion-cpp-python"), ("onnxruntime", "onnxruntime"),
                      ("cv2", "opencv-python-headless"), ("numpy", "numpy"), ("PIL", "pillow")):
        try:
            from importlib import metadata
            out[name] = metadata.version(name)
        except Exception:  # noqa: BLE001  (a missing package is reported, not fatal)
            out[name] = "not installed"
    return out


def cmd_freeze(args) -> int:
    work, models = Path(args.work), Path(args.models)
    if COHORT_PATH.exists() and not args.force:
        print(f"{COHORT_PATH} exists; refusing to re-freeze (the cohort is locked once committed)", file=sys.stderr)
        return 1
    judge = probe.Judge(models)
    fx = work / "fixtures"
    log = json.loads((fx / "fixtures_log.json").read_text())
    people = {}
    for name, (prompt, seed) in PEOPLE.items():
        people[name] = {"prompt": prompt, "style": probe.STYLE, "seed": seed, "size": probe.FIXTURE_SIZE,
                        "sha256": probe.sha256(fx / f"{name}.png"),
                        "role": "target" if name.startswith("tgt") else "reference",
                        "ids3_recipe": name in probe.FIXTURES}
    fences = {}
    pairs = plan_pairs()
    for p in pairs:
        tgt = p["target_id"]
        if tgt not in fences:
            img = probe._read(fx / f"{tgt}.png")
            bits = compute_fence(judge, img)
            f = judge.face(img)
            yaw = None if f is None else judge._yaw(f)
            fences[tgt] = (fence_hash_from_bits(bits.tobytes()), int(bits.sum()), int(bits.size), yaw)
        p["target_yaw_proxy"] = fences[tgt][3]
        p["pose_tag"] = pose_tag_from_yaw(fences[tgt][3])
        p["target_sha256"], p["reference_sha256"] = people[tgt]["sha256"], people[p["reference_id"]]["sha256"]
        p["fence_hash"], p["fence_inside_pixels"], p["fence_total_pixels"] = fences[tgt][:3]
    components = {k: probe.DOWNLOADS[k][1] for k in (f"klein-{CANDIDATE_QUANT}.gguf", "qwen3-4b-Q4_K_M.gguf",
                                                     "flux2_vae.safetensors", "loras/bfs_head_v1.safetensors")}
    cohort = {
        "schema": SCHEMA, "unit": UNIT, "rubric_revision": quality.RUBRIC_REVISION,
        "rubric": "docs/identity-studio/HEAD_ACCEPTANCE.md",
        "frozen_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "frozen_before_results": True,
        "candidate": {"engine": CANDIDATE_ENGINE, "runtime": _versions().get("stable-diffusion-cpp-python"),
                      "steps": probe.KleinEngine.STEPS, "edge": 512, "crop": "probe.head_crop_box scale 3.4 (target), 3.0 (reference)",
                      "prompt": probe.HEAD_SWAP_PROMPT + " <lora:bfs_head_v1:1.0>", "components_sha256": components,
                      "paste": "edit_mask(target, raw) INTERSECTED with the frozen fence; every pixel outside the fence is the target's"},
        "baseline": {"id": BASELINE, "pipeline": "tools/identity_bench/pipeline.py swap_face, Config('ghost_1_256', mask='mp')",
                     "licence": BASELINE_LICENCE, "fence": "the same frozen fence is enforced on the baseline output"},
        "fence_recipe": FENCE_RECIPE, "scale_method": SCALE_METHOD,
        "pose_rule": f"pose_tag from the target's yaw proxy: |yaw| < {FRONTAL_MAX_YAW} frontal, < {PROFILE_MIN_YAW} three_quarter, else profile",
        "identity_methods": {"sface": f"SFace fp32 (opencv_zoo), frontal method, preserved if cosine to reference >= {SFACE_SAME}",
                             "glintr100": f"glintr100 (identity bench research judge), profile-capable method, preserved if cosine to reference >= {GLINT_SAME}"},
        "blind_seed": BLIND_SEED, "licence": LICENCE, "people": people,
        "fixture_generation": {"engine": log.get("engine"), "host": log.get("host")},
        "hard_cases": hard_case_ids(pairs), "pairs": pairs,
    }
    cohort["pairs_digest"] = pairs_digest(pairs)
    status, problems = quality.check_cohort(cohort)
    if status != quality.PASS:
        print("cohort fails the IDS-12A pre-registration checks:", problems, file=sys.stderr)
        return 1
    COHORT_PATH.write_text(json.dumps(cohort, indent=1) + "\n")
    print(COHORT_PATH, len(pairs), "pairs frozen;", cohort["hard_cases"])
    return 0


def load_cohort() -> dict:
    cohort = json.loads(COHORT_PATH.read_text())
    if pairs_digest(cohort["pairs"]) != cohort["pairs_digest"]:
        raise ValueError("cohort.json pairs changed after the freeze")
    return cohort


# --------------------------------------------------------------------------
# Step 3: run (resumable, one checkpoint per pair and stage)
# --------------------------------------------------------------------------
def run_pending(pair_ids: list[str], done, do_pair, only=None) -> list[str]:
    """Run do_pair(pid) for every pair whose checkpoint is missing. Returns the pairs run.

    done(pid) says whether the checkpoint exists. do_pair must write it atomically at the
    end, so an interrupted pair simply runs again and finished pairs are never repeated.
    """
    ran = []
    for pid in pair_ids:
        if (only and pid not in only) or done(pid):
            continue
        do_pair(pid)
        ran.append(pid)
    return ran


def head_span(face) -> float | None:
    """SCALE_METHOD: eye midpoint to mouth-corner midpoint, in pixels."""
    if face is None:
        return None
    rx, ry, lx, ly, _nx, _ny, mrx, mry, mlx, mly = (float(v) for v in face[4:14])
    dx, dy = (rx + lx) / 2 - (mrx + mlx) / 2, (ry + ly) / 2 - (mry + mly) / 2
    return round((dx * dx + dy * dy) ** 0.5, 3)


def fenced_composite(judge, target, raw, fence):
    """Blend the engine output inside (edit mask AND fence); outside the fence the target, byte for byte."""
    cv2, np = judge.cv2, judge.np
    em = judge.edit_mask(target, raw)
    m = fence if em is None else (em.astype(np.uint8) & fence)
    alpha = cv2.GaussianBlur(cv2.erode(m, np.ones((9, 9), np.uint8)).astype(np.float32), (0, 0), 4)[..., None]
    out = (alpha * raw + (1 - alpha) * target).round().astype(np.uint8)
    out[m == 0] = target[m == 0]
    return out, m


def _packed(img) -> list:
    """One integer per pixel (B, G, R bytes), so pixels line up with the fence for check_outside_fence."""
    np = __import__("numpy")
    a = img.astype(np.uint32)
    return ((a[..., 0] << 16) | (a[..., 1] << 8) | a[..., 2]).ravel().tolist()


class Measurer:
    """Every automatic measurement of one output, shared by candidate and baseline."""

    def __init__(self, models: Path):
        self.judge = probe.Judge(models)
        from tools.identity_bench import pipeline as bench  # noqa: PLC0415
        self.bench = bench
        self.glint = bench.Embedder("glintr100")

    def glint_cos(self, a, fa, b, fb) -> float:
        np = self.judge.np
        ea = self.bench.unit(self.glint.embed(a, np.asarray(fa[4:14], np.float64).reshape(5, 2)))
        eb = self.bench.unit(self.glint.embed(b, np.asarray(fb[4:14], np.float64).reshape(5, 2)))
        return round(float(ea @ eb), 3)

    def measure(self, target, reference, output, fence, mask) -> dict:
        j, np = self.judge, self.judge.np
        ft, fr, fo = j.face(target), j.face(reference), j.face(output)
        score = j.score(target, reference, output)
        out = {"score": score, "classification": probe.classify(score)}
        out["pose_kept"] = None if not score.get("face_found") or score.get("yaw") is None else bool(probe.pose_kept(score))
        out["target_head_span"], out["result_head_span"] = head_span(ft), head_span(fo)
        out["outside_fence"] = quality.check_outside_fence(_packed(target), _packed(output), fence.ravel().tolist())
        out["output_mask_hash"] = sha256_bytes(mask.astype(np.uint8).tobytes())
        out["identity"] = {"sface": score.get("id_to_reference") if score.get("face_found") else None,
                           "glintr100": self.glint_cos(output, fo, reference, fr) if fo is not None else None,
                           "glintr100_to_target": self.glint_cos(output, fo, target, ft) if fo is not None else None}
        return out


def _stage_path(work: Path, pid: str, stage: str) -> Path:
    return work / "pairs" / f"{pid}.{stage}.json"


STAGES = ("baseline", "candidate")


def stage_outputs(pid: str, stage: str) -> tuple[str, ...]:
    """Image files a finished stage leaves in the work directory's pairs folder."""
    return (f"{pid}.candidate.png", f"{pid}.candidate_raw.png") if stage == "candidate" else (f"{pid}.baseline.png",)


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def binding(cohort: dict, pair: dict, stage: str) -> dict:
    """What a checkpoint must be bound to: this frozen cohort, this pair and stage, its inputs and fence."""
    return {"cohort_digest": cohort["pairs_digest"], "pair_id": pair["pair_id"], "stage": stage,
            "target_sha256": pair["target_sha256"], "reference_sha256": pair["reference_sha256"],
            "seed": pair["seed"] if stage == "candidate" else None, "fence_hash": pair["fence_hash"]}


def validate_checkpoint(cohort: dict, pair: dict, stage: str, rec: dict, pairs_dir: Path) -> list[str]:
    """Problems that make a checkpoint unusable. Empty means bound to the frozen cohort and its outputs unchanged."""
    pid, probs = pair["pair_id"], []
    if not isinstance(rec, dict):
        return [f"{pid}.{stage}: checkpoint is not an object"]
    if rec.get("pair_id") != pid or rec.get("stage") != stage:
        probs.append(f"{pid}.{stage}: checkpoint names pair {rec.get('pair_id')!r} stage {rec.get('stage')!r}")
    if rec.get("binding") != binding(cohort, pair, stage):
        probs.append(f"{pid}.{stage}: binding missing or differs from the frozen cohort")
    if rec.get("status") == "ok":
        outs = rec.get("outputs_sha256")
        if not isinstance(outs, dict):
            probs.append(f"{pid}.{stage}: no recorded output hashes")
            outs = {}
        for name in stage_outputs(pid, stage):
            path = Path(pairs_dir) / name
            if name not in outs:
                probs.append(f"{pid}.{stage}: no recorded hash for {name}")
            elif not path.exists():
                probs.append(f"{pid}.{stage}: {name} is missing")
            elif file_sha256(path) != outs[name]:
                probs.append(f"{pid}.{stage}: {name} differs from its recorded hash")
        if stage == "candidate" and rec.get("files_sha256") != cohort["candidate"]["components_sha256"]:
            probs.append(f"{pid}.{stage}: model, text encoder, VAE or adapter file differs from the frozen components")
    elif rec.get("status") != "failed":
        probs.append(f"{pid}.{stage}: status {rec.get('status')!r} is neither ok nor failed")
    return probs


def cmd_run(args) -> int:
    import cv2
    work, models = Path(args.work), Path(args.models)
    cohort = load_cohort()
    pairs = {p["pair_id"]: p for p in cohort["pairs"]}
    fx = work / "fixtures"
    for name, person in cohort["people"].items():
        if probe.sha256(fx / f"{name}.png") != person["sha256"]:
            print(f"fixture {name} differs from the frozen cohort", file=sys.stderr)
            return 1
    m = Measurer(models)
    fences: dict = {}
    state: dict = {}

    def fence_for(tgt, target):
        if tgt not in fences:
            bits = compute_fence(m.judge, target)
            frozen = {p["target_id"]: p["fence_hash"] for p in pairs.values()}
            if fence_hash_from_bits(bits.tobytes()) != frozen[tgt]:
                raise ValueError(f"recomputed fence for {tgt} differs from the frozen hash")
            fences[tgt] = bits
        return fences[tgt]

    def engine():
        if "engine" not in state:
            state["engine"] = probe.KleinEngine(models, CANDIDATE_QUANT, lora=True, threads=args.threads)
            state["files"] = {k: probe.sha256(models / k) for k in cohort["candidate"]["components_sha256"]}
            if state["files"] != cohort["candidate"]["components_sha256"]:
                raise RuntimeError(f"loaded files differ from the frozen components: {state['files']}")
        return state["engine"]

    def bench_tools():
        if "tools" not in state:
            b = m.bench
            state["tools"] = (b.Toolbox(), b.Detector(), b.Embedder("arcface_w600k_r50"), b.Config("ghost_1_256", mask="mp"))
        return state["tools"]

    def do_pair(pid):
        p = pairs[pid]
        target, reference = probe._read(fx / f"{p['target_id']}.png"), probe._read(fx / f"{p['reference_id']}.png")
        fence = fence_for(p["target_id"], target)
        rec = {"pair_id": pid, "stage": args.stage, "binding": binding(cohort, p, args.stage), "binding_recorded": "at_run",
               "host": probe.host_info(), "versions": _versions(),
               "started": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        if args.stage == "candidate":
            engine()  # outside the per-pair try: a component mismatch stops the run instead of failing pairs
        t0 = time.time()
        try:
            if args.stage == "candidate":
                eng = engine()
                res = eng.swap(m.judge, target, reference, seed=int(p["seed"]))
                out, mask = fenced_composite(m.judge, target, res["raw"], fence)
                cv2.imwrite(str(work / "pairs" / f"{pid}.candidate_raw.png"), res["raw"])
                rec.update(seconds=res["seconds"], load_s=round(eng.load_s, 1), files_sha256=state["files"])
            else:
                toolbox, det, enc, cfg = bench_tools()
                ft, fr = det.detect(target), det.detect(reference)
                if not ft or not fr:
                    raise ValueError("baseline detector found no face in a fixture")
                raw_id = enc.embed(reference, fr[0].marks)
                sw = m.bench.swap_face(target, ft[0].marks, raw_id, cfg, toolbox)
                outside_changed = int(((sw.image != target).any(2) & (fence == 0)).sum())
                out = target.copy()
                out[fence == 1] = sw.image[fence == 1]
                mask = ((sw.mask > 0) & (fence == 1)).astype("uint8")
                rec.update(seconds={"total": round(time.time() - t0, 2)}, notes=sw.notes,
                           baseline_pixels_outside_fence_reset=outside_changed)
            cv2.imwrite(str(work / "pairs" / f"{pid}.{args.stage}.png"), out)
            rec["outputs_sha256"] = {n: file_sha256(work / "pairs" / n) for n in stage_outputs(pid, args.stage)}
            rec["measure"] = m.measure(target, reference, out, fence, mask)
            rec["status"] = "ok"
        except Exception as exc:  # noqa: BLE001  (a failed pair stays in N with its reason)
            rec["status"], rec["error"] = "failed", f"{type(exc).__name__}: {exc}"
        rec["wall_s"] = round(time.time() - t0, 1)
        rec["peak_rss_mb"] = probe.peak_rss_mb()
        write_atomic(_stage_path(work, pid, args.stage), json.dumps(rec, indent=1, default=float))
        cls = rec.get("measure", {}).get("classification", rec.get("error"))
        print(pid, args.stage, rec["status"], cls, rec["wall_s"], "s", rec["peak_rss_mb"], "MB", flush=True)

    def done(pid):
        path = _stage_path(work, pid, args.stage)
        if not path.exists():
            return False
        probs = validate_checkpoint(cohort, pairs[pid], args.stage, json.loads(path.read_text()), work / "pairs")
        if probs:  # fail closed: never resume past a stale or altered checkpoint
            raise RuntimeError("; ".join(probs))
        return True

    ran = run_pending(list(pairs), done, do_pair, args.pairs)
    print("ran", len(ran), "pairs")
    return 0


LEGACY_NOTE = ("backfilled on 2026-11-28 after review: these checkpoints were written before binding existed. "
               "Fixture hashes were checked against cohort.json when the run started and again now; the seed was "
               "taken from the frozen cohort and not echoed into the checkpoint; output hashes are of the files "
               "retained in the work directory, not hashes recorded at write time")


def bind_legacy(cohort: dict, pair: dict, stage: str, rec: dict, pairs_dir: Path, note: str = LEGACY_NOTE) -> dict:
    """Add binding and output hashes to a checkpoint written before they existed. Refuses anything it cannot vouch for."""
    pid = pair["pair_id"]
    if "binding" in rec:
        raise ValueError(f"{pid}.{stage}: already bound")
    if rec.get("pair_id") != pid or rec.get("stage") != stage:
        raise ValueError(f"{pid}.{stage}: checkpoint names another pair or stage")
    if stage == "candidate" and rec.get("status") == "ok" and rec.get("files_sha256") != cohort["candidate"]["components_sha256"]:
        raise ValueError(f"{pid}.{stage}: recorded component files differ from the frozen ones")
    out = dict(rec, binding=binding(cohort, pair, stage), binding_recorded=note)
    if rec.get("status") == "ok":
        out["outputs_sha256"] = {n: file_sha256(Path(pairs_dir) / n) for n in stage_outputs(pid, stage)}
    return out


def cmd_bind(args) -> int:
    work = Path(args.work)
    cohort = load_cohort()
    for name, person in cohort["people"].items():
        if file_sha256(work / "fixtures" / f"{name}.png") != person["sha256"]:
            print(f"fixture {name} differs from the frozen cohort", file=sys.stderr)
            return 1
    for p in cohort["pairs"]:
        for stage in STAGES:
            path = _stage_path(work, p["pair_id"], stage)
            if not path.exists():
                continue
            rec = json.loads(path.read_text())
            if "binding" in rec:
                continue
            write_atomic(path, json.dumps(bind_legacy(cohort, p, stage, rec, work / "pairs"), indent=1))
            print("bound", p["pair_id"], stage)
    return 0


# --------------------------------------------------------------------------
# Step 4: export (rows in the quality.py format, run log, blinded sheets)
# --------------------------------------------------------------------------
def _m(value, method):
    return {"status": "measured", "value": value, "method": method}


def _nm(reason):
    return {"status": "not_measured", "reason": reason}


VOTE_REASON = "two-judge blinded step is the follow-up unit; sheets and key are committed, votes are not collected here"


def identity_entries(pair: dict, ident: dict | None) -> list[dict]:
    """Per-method identity outcomes. SFace is the frontal method, glintr100 the profile-capable one."""
    out = []
    for source, threshold, capable in (("sface", SFACE_SAME, False), ("glintr100", GLINT_SAME, True)):
        v = None if ident is None else ident.get(source)
        entry = {"source": source, "profile_capable": capable, "threshold": threshold}
        if isinstance(v, (int, float)):
            entry.update(cosine_to_reference=v, outcome="preserved" if v >= threshold else "not_preserved")
        else:
            entry.update(outcome="abstain", reason="no face found in the output or a fixture by this method")
        out.append(entry)
    return out


def build_row(pair: dict, cand: dict | None, base: dict | None) -> dict:
    """One quality.py row for a frozen pair. Absent or failed stages become not_measured with a reason."""
    row = {k: pair[k] for k in ("pair_id", "target_id", "reference_id", "engine", "seed", "model_sha256",
                                "adapter_sha256", "pose_tag", "baseline_id")}
    row["licence_evidence"] = _m(dict(pair["licence"]), "frozen cohort licence; fixture bytes match cohort.json people[].sha256")
    row["claimed_success"] = _m(False, "bench run: nothing is labelled or surfaced to a user as a whole-head success")
    row["hair_votes"] = _nm(VOTE_REASON)
    row["seam_votes"] = _nm(VOTE_REASON)
    ok = cand is not None and cand.get("status") == "ok"
    why = "candidate stage not run" if cand is None else f"candidate failed: {cand.get('error')}"
    if not ok:
        for k in ("classification", "target_pose_preserved", "outside_fence", "target_head_span", "result_head_span",
                  "output_mask_hash", "identity", "head_scale_rel_error"):
            row[k] = _nm(why)
    else:
        ms = cand["measure"]
        row["classification"] = _m(ms["classification"], "probe.classify on the fenced output (IDS-3 taxonomy)")
        row["target_pose_preserved"] = (_m(ms["pose_kept"], f"probe.pose_kept: landmark yaw proxy within {probe.POSE_TOLERANCE}")
                                        if isinstance(ms["pose_kept"], bool) else _nm("no face or landmarks in the output; pose not inferred"))
        row["outside_fence"] = _m(ms["outside_fence"], "quality.check_outside_fence on packed BGR pixels and the frozen fence")
        row["output_mask_hash"] = _m(ms["output_mask_hash"], "SHA-256 of the uint8 paste mask (edit mask AND fence)")
        ts, rs = ms["target_head_span"], ms["result_head_span"]
        row["target_head_span"] = _m(ts, SCALE_METHOD) if isinstance(ts, (int, float)) and ts > 0 else _nm("no landmarks in the target")
        row["result_head_span"] = _m(rs, SCALE_METHOD) if isinstance(rs, (int, float)) else _nm("no face or landmarks in the output")
        if isinstance(ts, (int, float)) and ts > 0 and isinstance(rs, (int, float)):
            row["head_scale_rel_error"] = _m(round(abs(rs - ts) / ts, 4), "abs(result - target) / target head span")
        else:
            row["head_scale_rel_error"] = _nm("a head span is missing")
        row["identity"] = _m(identity_entries(pair, ms.get("identity")), "two scoring methods, cosine to the reference fixture")
        files = cand.get("files_sha256", {})
        row["model_file_sha256"] = _m(files.get(f"klein-{CANDIDATE_QUANT}.gguf"), "SHA-256 of the file loaded at run time")
        row["adapter_file_sha256"] = _m(files.get("loras/bfs_head_v1.safetensors"), "SHA-256 of the file loaded at run time")
        row["component_files_sha256"] = _m(dict(files), "SHA-256 of every candidate file loaded at run time (model, text encoder, VAE, adapter)")
        row["scores"] = {k: ms["score"].get(k) for k in ("id_to_reference", "id_to_target", "id_reference_vs_target",
                                                         "yaw", "hair_ratio", "hair_dE_to_reference", "hair_dE_to_target",
                                                         "target_head_unchanged_frac", "face_neck_dE")}
        row["scores"]["glintr100_to_target"] = ms["identity"].get("glintr100_to_target")
    if base is not None and base.get("status") == "ok":
        bm = base["measure"]
        row["baseline"] = _m({"classification": bm["classification"], "sface_to_reference": bm["identity"]["sface"],
                              "glintr100_to_reference": bm["identity"]["glintr100"],
                              "outside_fence_equal": bm["outside_fence"]["equal"]}, f"{BASELINE} through the same measurements")
    else:
        row["baseline"] = _nm("baseline stage not run" if base is None else f"baseline failed: {base.get('error')}")
    return row


def gate_summary(cohort: dict, rows: list[dict], report: dict) -> dict:
    """Per-gate pass/fail IDs with reasons, derived from the rows (the frozen evaluator reports counts only).

    Uses the evaluator's own definitions; it never reclassifies a row.
    """
    pids = [p["pair_id"] for p in cohort["pairs"]]
    by = {r["pair_id"]: r for r in rows}
    gates = {g["gate"]: g for g in report["gates"]}

    def val(r, k):
        state, v = quality.measured(r, k) if r is not None else ("invalid", None)
        return v if state == "ok" else None

    def why_not(r, k):
        f = (r or {}).get(k)
        return f.get("reason") if isinstance(f, dict) and f.get("status") == "not_measured" else "missing or invalid"

    whole = {"passed": [], "failed": {}}
    fence = {"passed": [], "failed": {}}
    scale = {"errors": {}, "not_measured": {}}
    for pid in pids:
        r = by.get(pid)
        cls, pose = val(r, "classification"), val(r, "target_pose_preserved")
        reasons = []
        if cls is None:
            reasons.append(f"classification not measured: {why_not(r, 'classification')}")
        elif cls != "full_head":
            reasons.append(f"classification={cls}")
        if pose is None:
            reasons.append(f"target pose not measured: {why_not(r, 'target_pose_preserved')}")
        elif pose is not True:
            reasons.append("target pose not preserved")
        if reasons:
            whole["failed"][pid] = reasons
        else:
            whole["passed"].append(pid)
        of = val(r, "outside_fence")
        if isinstance(of, dict) and of.get("equal") is True:
            fence["passed"].append(pid)
        else:
            fence["failed"][pid] = "outside-fence pixels differ" if isinstance(of, dict) else why_not(r, "outside_fence")
        err = val(r, "head_scale_rel_error")
        if isinstance(err, (int, float)):
            scale["errors"][pid] = err
        else:
            scale["not_measured"][pid] = why_not(r, "head_scale_rel_error")
    ident = {}
    for pid in pids:
        entries = val(by.get(pid), "identity") or []
        capable = sorted({e["source"] for e in entries if e.get("profile_capable") is True and e.get("outcome") in ("preserved", "not_preserved")})
        ident[pid] = {"profile_capable_sources": capable,
                      "outcomes": {e["source"]: e.get("outcome") for e in entries},
                      "reason": None if len(capable) >= quality.EXPECTED_JUDGES else
                      f"{len(capable)} profile-capable source(s); the rubric needs {quality.EXPECTED_JUDGES}"}
    return {
        "note": "Derived from rows.json with the frozen evaluator's definitions; lists the IDs behind each gate count.",
        "rubric_revision": report["rubric_revision"], "verdict": report["verdict"], "denominator": len(pids),
        "whole_head_and_pose": dict(status=gates["whole_head_and_pose"]["status"], threshold=quality.WHOLE_HEAD_POSE_MIN, **whole),
        "edit_fence": dict(status=gates["edit_fence"]["status"], **fence),
        "scale": dict(status=gates["scale"]["status"], threshold=quality.SCALE_MEDIAN_MAX,
                      median=gates["scale"]["median_abs_rel_error"], **scale),
        "new_hair_hairline": {"status": gates["new_hair_hairline"]["status"],
                              "not_measured": {pid: why_not(by.get(pid), "hair_votes") for pid in pids}},
        "neck_seam": {"status": gates["neck_seam"]["status"],
                      "not_measured": {pid: why_not(by.get(pid), "seam_votes") for pid in pids}},
        "profile_identity": {"status": gates["profile_identity"]["status"], "pairs": ident},
    }


def jpeg_has_metadata(data: bytes) -> bool:
    """True if a JPEG carries EXIF/XMP (APP1+), comments or anything beyond JFIF APP0."""
    if data[:2] != b"\xff\xd8":
        return True
    i = 2
    while i + 4 <= len(data):
        if data[i] != 0xFF:
            return True
        marker = data[i + 1]
        if marker == 0xDA:  # start of scan: no more header segments
            return False
        length = int.from_bytes(data[i + 2:i + 4], "big")
        if marker == 0xFE or 0xE1 <= marker <= 0xEF:
            return True
        i += 2 + length
    return True


def _sheet(cohort, pair, work, sides, edge=320):
    import cv2
    import numpy as np
    fx = work / "fixtures"
    target, reference = probe._read(fx / f"{pair['target_id']}.png"), probe._read(fx / f"{pair['reference_id']}.png")
    judge_face = _SHEET_JUDGE["j"].face
    ft, fr = judge_face(target), judge_face(reference)
    tb = probe.head_crop_box(ft, target.shape[1], target.shape[0])
    rb = probe.head_crop_box(fr, reference.shape[1], reference.shape[0], scale=3.0)
    imgs = {s: (probe._read(work / "pairs" / f"{pair['pair_id']}.{s}.png") if (work / "pairs" / f"{pair['pair_id']}.{s}.png").exists() else None)
            for s in ("candidate", "baseline")}
    cells = [("target", target[tb[1]:tb[3], tb[0]:tb[2]]), ("reference", reference[rb[1]:rb[3], rb[0]:rb[2]])]
    for label, side in zip(("A", "B"), sides):
        img = imgs[side]
        cells.append((label, None if img is None else img[tb[1]:tb[3], tb[0]:tb[2]]))
    row = []
    for label, img in cells:
        cell = np.full((edge, edge, 3), 40, np.uint8) if img is None else cv2.resize(img, (edge, edge), interpolation=cv2.INTER_AREA)
        cv2.rectangle(cell, (0, 0), (edge, 22), (0, 0, 0), -1)
        cv2.putText(cell, f"{pair['pair_id']} {label}", (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1, cv2.LINE_AA)
        row.append(cell)
    return np.hstack(row)


_SHEET_JUDGE: dict = {}


def cmd_export(args) -> int:
    import cv2
    work = Path(args.work)
    cohort = load_cohort()
    pids = [p["pair_id"] for p in cohort["pairs"]]
    stage = lambda pid, s: json.loads(_stage_path(work, pid, s).read_text()) if _stage_path(work, pid, s).exists() else None  # noqa: E731
    probs = [f"fixture {n} differs from the frozen cohort" for n, person in cohort["people"].items()
             if file_sha256(work / "fixtures" / f"{n}.png") != person["sha256"]]
    provenance = {"cohort_digest": cohort["pairs_digest"], "fixtures_sha256_verified": not probs, "pairs": {}}
    for p in cohort["pairs"]:
        for s in STAGES:
            rec = stage(p["pair_id"], s)
            if rec is None:
                continue
            probs += validate_checkpoint(cohort, p, s, rec, work / "pairs")
            provenance["pairs"].setdefault(p["pair_id"], {})[s] = {
                "status": rec.get("status"), "binding": rec.get("binding"), "binding_recorded": rec.get("binding_recorded"),
                "checkpoint_sha256": file_sha256(_stage_path(work, p["pair_id"], s)),
                "outputs_sha256": rec.get("outputs_sha256"), "started": rec.get("started"),
                **({"components_sha256": rec.get("files_sha256")} if s == "candidate" else {})}
    if probs:  # fail closed: no rows or sheets from unbound or altered evidence
        print("refusing to export:", *probs, sep="\n  ", file=sys.stderr)
        return 1
    rows = [build_row(p, stage(p["pair_id"], "candidate"), stage(p["pair_id"], "baseline")) for p in cohort["pairs"]]
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "rows.json").write_text(json.dumps(rows, indent=1) + "\n")
    report = quality.evaluate(cohort, rows)
    (RESULTS / "evaluator-report.json").write_text(json.dumps(report, indent=1, sort_keys=True) + "\n")
    (RESULTS / "gate-summary.json").write_text(json.dumps(gate_summary(cohort, rows, report), indent=1) + "\n")
    (RESULTS / "provenance.json").write_text(json.dumps(provenance, indent=1) + "\n")
    # Run log summary: host, runtime versions, wall time per pair.
    fxlog = json.loads((work / "fixtures" / "fixtures_log.json").read_text())
    per_pair = {}
    host = versions = None
    for pid in pids:
        c, b = stage(pid, "candidate"), stage(pid, "baseline")
        per_pair[pid] = {"candidate_wall_s": c and c.get("wall_s"), "candidate_status": c and c.get("status"),
                         "candidate_generate_s": c and c.get("seconds", {}).get("generate"),
                         "candidate_peak_rss_mb": c and c.get("peak_rss_mb"),
                         "baseline_wall_s": b and b.get("wall_s"), "baseline_status": b and b.get("status"),
                         "candidate_started": c and c.get("started")}
        host = host or (c and c.get("host"))
        versions = versions or (c and c.get("versions"))
    walls = [v["candidate_wall_s"] for v in per_pair.values() if v["candidate_wall_s"]]
    runlog = {"unit": UNIT, "host": host, "versions": versions, "cohort_digest": cohort["pairs_digest"],
              "fixtures": {k: {"seconds": v["seconds"], "seed": v["seed"]} for k, v in fxlog["items"].items()},
              "fixture_peak_rss_mb": fxlog.get("peak_rss_mb"), "pairs": per_pair,
              "candidate_wall_s_total": round(sum(walls), 1), "candidate_wall_s_median": walls and probe._median(walls)}
    (RESULTS / "run-log.json").write_text(json.dumps(runlog, indent=1) + "\n")
    # Blinded sheets; the key goes in its own file.
    _SHEET_JUDGE["j"] = probe.Judge(Path(args.models))
    order = sheet_order(pids, cohort["blind_seed"])
    (RESULTS / "sheets").mkdir(exist_ok=True)
    for p in cohort["pairs"]:
        sheet = _sheet(cohort, p, work, order[p["pair_id"]])
        cv2.imwrite(str(RESULTS / "sheets" / f"{p['pair_id']}.jpg"), sheet, [cv2.IMWRITE_JPEG_QUALITY, 85])
    key = {"blind_seed": cohort["blind_seed"], "note": "Keep away from the judges until both have voted.",
           "pairs": {pid: {"A": s[0], "B": s[1]} for pid, s in order.items()}}
    (RESULTS / "sheet-key.json").write_text(json.dumps(key, indent=1) + "\n")
    lines = ["pair_id,judge,hair_hairline_prefers(A|B|tie|abstain),neck_seam_prefers(A|B|tie|abstain),reason"]
    lines += [f"{pid},,,," for pid in pids]
    (RESULTS / "sheets" / "votes-template.csv").write_text("\n".join(lines) + "\n")
    print(quality.render_text(report))
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name, fn in (("fixtures", cmd_fixtures), ("freeze", cmd_freeze), ("run", cmd_run), ("bind", cmd_bind), ("export", cmd_export)):
        p = sub.add_parser(name)
        p.add_argument("--models", required=True, help="directory holding the probe's fetched weights")
        p.add_argument("--work", required=True, help="work directory for fixtures and outputs (outside git)")
        p.add_argument("--threads", type=int, default=os.cpu_count() or 4)
        p.set_defaults(fn=fn)
        if name == "freeze":
            p.add_argument("--force", action="store_true", help="overwrite an uncommitted cohort.json")
        if name == "run":
            p.add_argument("--stage", choices=("candidate", "baseline"), required=True)
            p.add_argument("--pairs", nargs="*")
    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
