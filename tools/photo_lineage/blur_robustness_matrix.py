"""Pinned public-camera blur ablation: review offers, not exposure identity.

Five CC0/public-domain photographs and two distinct-viewpoint Middlebury
photographs; no downloads, image output, model selection or source writes.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json

import cv2
import numpy as np

from .matcher import compare_arrays
from .middlebury_stereo_smoke import STEREO_SOURCES
from .public_domain_smoke import PINNED_PHOTOS, _bundled_root, _read_pinned_photo

SOURCES = ("astronaut", "camera", "chelsea", "clock", "coffee")
STEREO_NAMES = ("left_view", "right_view")
ALGORITHMS = ("orb", "sift", "akaze")
VERDICTS = ("candidate", "review", "unknown")
CONDITIONS = (("gaussian", 1.5), ("gaussian", 4.0), ("gaussian", 8.0),
              ("motion", 7), ("motion", 15), ("motion", 31))


def apply_blur(gray: np.ndarray, kind: str, strength: float | int) -> np.ndarray:
    """Six predeclared blur edits with fixed reflected edges and no source write."""
    if (not isinstance(gray, np.ndarray) or gray.ndim != 2
            or gray.dtype != np.uint8 or min(gray.shape) < 128
            or gray.size > 4_000_000 or not any(
                kind == k and type(strength) is type(n) and strength == n
                for k, n in CONDITIONS)):
        raise ValueError("Invalid bounded blur stimulus")
    if kind == "gaussian":
        return cv2.GaussianBlur(gray, (0, 0), strength,
                                borderType=cv2.BORDER_REFLECT_101)
    size = int(strength)
    kernel = np.zeros((size, size), dtype=np.float32)
    kernel[size // 2, :] = 1.0 / size
    return cv2.filter2D(gray, -1, kernel, borderType=cv2.BORDER_REFLECT_101)


def run(*, algorithm: str = "orb") -> dict:
    if type(algorithm) is not str or algorithm not in ALGORITHMS:
        raise ValueError("Unknown classical feature algorithm")
    if (set(SOURCES) != set(PINNED_PHOTOS)
            or set(STEREO_NAMES) != set(STEREO_SOURCES)
            or len(CONDITIONS) != 6 or len(set(CONDITIONS)) != 6):
        raise ValueError("Pinned public camera blur protocol changed")
    root = _bundled_root()
    images = {}
    for name in SOURCES:
        filename, expected, _credit = PINNED_PHOTOS[name]
        images[name] = _read_pinned_photo(root / filename, expected)
    for name in STEREO_NAMES:
        filename, expected = STEREO_SOURCES[name]
        images[name] = _read_pinned_photo(root / filename, expected)
    if (images[STEREO_NAMES[0]].shape != images[STEREO_NAMES[1]].shape
            or np.array_equal(images[STEREO_NAMES[0]], images[STEREO_NAMES[1]])):
        raise ValueError("Stereo sources must be distinct viewpoint images")
    results = {}
    for kind, strength in CONDITIONS:
        condition = f"{kind}_{strength:g}"
        positives = Counter()
        per_source = {}
        for source in (*SOURCES, *STEREO_NAMES):
            blur = apply_blur(images[source], kind, strength)
            verdict = compare_arrays(images[source], blur, algorithm=algorithm).verdict
            if verdict not in VERDICTS:
                raise ValueError("Unknown matcher verdict")
            per_source[source] = verdict
            positives[verdict] += 1
        # Distinct camera viewpoint, NOT a same-camera multi-shutter pair.
        negative = compare_arrays(
            images[STEREO_NAMES[0]], apply_blur(images[STEREO_NAMES[1]], kind, strength),
            algorithm=algorithm,
        ).verdict
        if negative not in VERDICTS:
            raise ValueError("Unknown matcher stereo verdict")
        results[condition] = {
            "same_source_edit_verdicts": {v: positives[v] for v in VERDICTS},
            "same_source_public_photo_verdicts": per_source,
            "distinct_viewpoint_stereo_verdict": negative,
            "same_source_reviewable_edits": positives["candidate"] + positives["review"],
            "same_source_photos_correlated": len(images),
            "distinct_viewpoint_scene_groups": 1,
        }
    return {
        "policy": "Experimental manual review diagnostic; no exposure identity certification or automatic grouping",
        "algorithm": algorithm,
        "source_photo_count": len(images),
        "independent_scene_groups_at_most": len(SOURCES) + 1,
        "public_source_credits": {name: PINNED_PHOTOS[name][2] for name in SOURCES},
        "stereo_rights_documented_in": "MIDDLEBURY_STEREO_SMOKE.md",
        "pinned_source_sha256_verified": True,
        "blur_conditions": [f"{kind}_{strength:g}" for kind, strength in CONDITIONS],
        "same_source_edit_comparisons": len(images) * len(CONDITIONS),
        "different_viewpoint_stereo_comparisons_correlated": len(CONDITIONS),
        "by_condition": results,
        "physical_shutter_independence_verified": False,
        "same_camera_distinct_shutter_negatives_tested": 0,
        "actual_photo_family_precision_or_recall_estimated": False,
        "originals_and_derivatives_saved_or_uploaded": False,
        "limitations": (
            "Only seven individually hash-pinned public photographs spanning six scene groups. "
            "Six edits per source and six stereo comparisons reuse photos and scene backgrounds: "
            "NOT independent Bernoulli trials or generalizable false-review statistics. "
            "No calibrated blur/noise model, true same-camera shutter negatives, "
            "local phone timing or production threshold changes."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--algorithm", choices=ALGORITHMS, default="orb")
    args = parser.parse_args()
    print(json.dumps(run(algorithm=args.algorithm), indent=2))


if __name__ == "__main__":
    main()
