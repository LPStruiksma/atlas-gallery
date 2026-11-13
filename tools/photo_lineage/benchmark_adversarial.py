"""Reproducible, offline, synthetic condition-specific Photo DNA evaluation.

This tests *source-exposure hypotheses*, not human identities or photographs.
Synthesized scene siblings are only proxies for different camera exposures:
actual independent exposures require a separately approved evaluation set.
No files, private media, cloud APIs, or cache databases are touched.

Run from repository root:
    python -m tools.photo_lineage.benchmark_adversarial --seeds 6 --algorithm orb
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import json
import time

import cv2
import numpy as np

from .benchmark import rotate, textured
from .matcher import compare_arrays

ALGORITHMS = ("orb", "sift", "akaze")
POSITIVE_CONDITIONS = (
    "resize", "gamma", "jpeg_low_quality", "crop_25pct",
    "rotation_crop", "sharpen", "overlaid_date_stamp",
)
NEGATIVE_CONDITIONS = (
    "independent_scene", "shared_caption", "different_exposure_small_subject",
    "different_exposure_medium_subject", "different_exposure_large_subject",
)


def _jpeg(gray: np.ndarray, quality: int = 25) -> np.ndarray:
    encoded_ok, jpeg = cv2.imencode(".jpg", gray, [cv2.IMWRITE_JPEG_QUALITY, quality])
    if not encoded_ok:
        raise RuntimeError("synthetic JPEG encoding failed")
    result = cv2.imdecode(jpeg, cv2.IMREAD_GRAYSCALE)
    if result is None:
        raise RuntimeError("synthetic JPEG decoding failed")
    return result


def _changed_subject(gray: np.ndarray, *, side: int, seed: int) -> np.ndarray:
    """Simulate exposure-specific foreground differences over the same static scene.

    NOTE: This is an intentionally artificial proxy; genuine burst photos also
    change lighting, viewpoint, sensor noise, motion blur and backgrounds.
    """
    target = gray.copy()
    h, w = target.shape
    if side >= min(h, w):
        raise ValueError("subject larger than frame")
    y = (h - side) // 2
    x = (w - side) // 2
    # A different foreground over identical background: an adversarial proxy.
    patch = cv2.resize(textured(seed + 105000, size=600), (side, side), interpolation=cv2.INTER_AREA)
    target[y:y+side, x:x+side] = patch
    return target


def make_cases(seed: int, size: int = 600):
    """Return (condition, true_same_source_exposure, original, edited) tuples.

    The negative 'different_exposure' conditions are hard surrogate negatives,
    *not* physically captured independent frames. They should never be used to
    claim that the system accurately discriminates real burst exposures.
    """
    if size < 400 or size > 900:
        raise ValueError("Synthetic frame size must be 400..900")
    if seed < 0 or seed > 100000:
        raise ValueError("seed must be 0..100000")
    base = textured(seed, size)
    other = textured(seed + 100000, size)
    gamma = np.uint8(np.clip((base.astype(np.float32) / 255) ** .58 * 255, 0, 255))
    detail = cv2.GaussianBlur(base, (0, 0), 1.7)
    sharpen = cv2.addWeighted(base, 1.6, detail, -.6, 7)
    text_overlay = base.copy()
    cv2.putText(text_overlay, "2008-10-16", (50, 80), cv2.FONT_HERSHEY_SIMPLEX,
                1.3, 250, 3, cv2.LINE_AA)
    same_caption = other.copy()
    caption_base = base.copy()
    cv2.putText(caption_base, "ATLAS", (85, 300), cv2.FONT_HERSHEY_COMPLEX, 2, 240, 5)
    cv2.putText(same_caption, "ATLAS", (85, 300), cv2.FONT_HERSHEY_COMPLEX, 2, 240, 5)
    return [
        ("resize", True, base, cv2.resize(base, (410, 410))),
        ("gamma", True, base, gamma),
        ("jpeg_low_quality", True, base, _jpeg(base)),
        ("crop_25pct", True, base, base[size//4:3*size//4, size//4:3*size//4].copy()),
        ("rotation_crop", True, base, rotate(base, 41)[size//7:-size//7, size//7:-size//7].copy()),
        ("sharpen", True, base, sharpen),
        ("overlaid_date_stamp", True, base, text_overlay),
        ("independent_scene", False, base, other),
        ("shared_caption", False, caption_base, same_caption),
        ("different_exposure_small_subject", False, base, _changed_subject(base, side=70, seed=seed)),
        ("different_exposure_medium_subject", False, base, _changed_subject(base, side=160, seed=seed)),
        ("different_exposure_large_subject", False, base, _changed_subject(base, side=300, seed=seed)),
    ]


def evaluate(*, seeds: int = 6, algorithm: str = "orb", include_cases: bool = False) -> dict:
    """Measure strong-candidate rates and unknown abstention by edit condition.

    Descriptive fixed-generator rates, NOT calibrated probabilities or real-world
    generalization measurements.
    """
    if not 1 <= seeds <= 30:
        raise ValueError("seeds must be in 1..30")
    if algorithm not in ALGORITHMS:
        raise ValueError(f"Choose algorithm from {ALGORITHMS}")
    aggregates = defaultdict(lambda: {"same_source_exposure": None, "cases": 0,
                                  "candidate": 0, "review": 0, "unknown": 0,
                                  "total_elapsed_ms": 0.0})
    details = []
    for seed in range(700, 700 + seeds):
        for condition, same_exposure, a, b in make_cases(seed):
            start = time.perf_counter()
            result = compare_arrays(a, b, algorithm=algorithm)
            elapsed = (time.perf_counter() - start) * 1000
            row = aggregates[condition]
            row["same_source_exposure"] = same_exposure
            row["cases"] += 1
            row[result.verdict] += 1
            row["total_elapsed_ms"] += elapsed
            if include_cases:
                details.append({"seed": seed, "condition": condition, "expected_same_exposure": same_exposure,
                                "verdict": result.verdict, "inliers": result.inliers,
                                "local_consistency": result.local_consistency,
                                "gradient_correlation": result.gradient_correlation,
                                "elapsed_ms": round(elapsed, 2)})
    summary = {}
    for condition, data in sorted(aggregates.items()):
        cases = data["cases"]
        summary[condition] = {
            "expected_same_exposure": data["same_source_exposure"],
            "cases": cases, "candidate": data["candidate"],
            "review": data["review"], "unknown": data["unknown"],
            "candidate_rate": round(data["candidate"] / cases, 4),
            "abstention_rate": round(data["unknown"] / cases, 4),
            "mean_pair_ms": round(data["total_elapsed_ms"] / cases, 1),
        }
    total_true = sum(x["cases"] for x in summary.values() if x["expected_same_exposure"])
    total_false = sum(x["cases"] for x in summary.values() if not x["expected_same_exposure"])
    strong_true = sum(x["candidate"] for x in summary.values() if x["expected_same_exposure"])
    strong_false = sum(x["candidate"] for x in summary.values() if not x["expected_same_exposure"])
    return {
        "policy": "synthetic fixed-generator diagnostics; human review only; NO deletion or same-exposure guarantee",
        "algorithm": algorithm, "seeds": seeds,
        "same_exposure_synthetic_pairs": total_true,
        "different_exposure_proxy_pairs": total_false,
        "strong_candidates_on_positive_edits": strong_true,
        "strong_candidates_on_negative_proxies": strong_false,
        "condition_results": summary,
        **({"case_results": details} if include_cases else {}),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seeds", type=int, default=6)
    parser.add_argument("--algorithm", choices=ALGORITHMS, default="orb")
    parser.add_argument("--include-cases", action="store_true")
    args = parser.parse_args()
    print(json.dumps(evaluate(seeds=args.seeds, algorithm=args.algorithm,
                              include_cases=args.include_cases), indent=2))


if __name__ == "__main__":
    main()
