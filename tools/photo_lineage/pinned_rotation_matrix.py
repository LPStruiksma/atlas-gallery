"""Offline fixed-angle rotation/crop diagnostic on five SHA-pinned public photos.

No source writes, downloads, private Gallery or source-exposure decisions.
Reports condition-specific REVIEWABLE vs UNKNOWN outcomes, never auto-links.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json

import cv2
import numpy as np

from .matcher import compare_arrays
from .public_domain_smoke import PINNED_PHOTOS, _bundled_root, _read_pinned_photo

SOURCES = ("astronaut", "camera", "chelsea", "clock", "coffee")
ANGLES = (0, 15, 45, 75, 90, 135, 180, 225, 270, 315)
ALGORITHMS = ("orb", "sift", "akaze")
VERDICTS = ("candidate", "review", "unknown")
MAX_SIDE = 520


def rotated_crop(gray: np.ndarray, degrees: int) -> np.ndarray:
    """Deterministic interpolated rotation plus central 75% square-ish crop."""
    if (type(degrees) is not int or degrees not in ANGLES
            or not isinstance(gray, np.ndarray) or gray.ndim != 2
            or gray.dtype != np.uint8 or min(gray.shape) < 128
            or gray.size > 4_000_000):
        raise ValueError("Unbounded or malformed public rotation stimulus")
    h, w = gray.shape
    matrix = cv2.getRotationMatrix2D((w / 2, h / 2), degrees, 1.0)
    turned = cv2.warpAffine(
        gray, matrix, (w, h), flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT, borderValue=127,
    )
    out = turned[h // 8:7 * h // 8, w // 8:7 * w // 8].copy()
    if min(out.shape) < 48 or out.shape[0] * out.shape[1] > 4_000_000:
        raise ValueError("Rotation crop output not eligible")
    return out


def run(*, algorithm: str = "orb") -> dict:
    """Exactly 50 correlated rotations on five externally pinned public images."""
    if type(algorithm) is not str or algorithm not in ALGORITHMS:
        raise ValueError("Unsupported classical matcher")
    if set(SOURCES) != set(PINNED_PHOTOS):
        raise ValueError("Pinned photographic source registry changed")
    if len(ANGLES) != len(set(ANGLES)) or len(ANGLES) != 10:
        raise ValueError("Fixed rotation protocol changed")
    root = _bundled_root()
    source_rows = {}
    angle_counts = {str(angle): Counter() for angle in ANGLES}
    totals = Counter()
    for label in SOURCES:
        filename, sha, _license = PINNED_PHOTOS[label]
        photo = _read_pinned_photo(root / filename, sha)
        if max(photo.shape) > MAX_SIDE:
            h, w = photo.shape
            factor = MAX_SIDE / max(h, w)
            photo = cv2.resize(
                photo, (int(w * factor), int(h * factor)),
                interpolation=cv2.INTER_AREA,
            )
        outcomes = {}
        for angle in ANGLES:
            result = compare_arrays(photo, rotated_crop(photo, angle), algorithm=algorithm)
            verdict = result.verdict
            if verdict not in VERDICTS:
                raise ValueError("Unexpected matcher verdict; no usable evidence")
            outcomes[str(angle)] = verdict
            angle_counts[str(angle)][verdict] += 1
            totals[verdict] += 1
        source_rows[label] = outcomes
    return {
        "policy": "Review-only Photo DNA diagnosis; never auto-group, infer exposure identity or delete",
        "algorithm": algorithm,
        "source_count": len(SOURCES),
        "source_license_credits": {source: PINNED_PHOTOS[source][2] for source in SOURCES},
        "source_image_sha256_checked": True,
        "angles_degrees": list(ANGLES),
        "angle_count_per_source": len(ANGLES),
        "source_angle_verdicts": source_rows,
        "angle_verdict_counts": {
            key: {v: value[v] for v in VERDICTS}
            for key, value in angle_counts.items()
        },
        "overall_correlated_edit_verdicts": {v: totals[v] for v in VERDICTS},
        "actual_separately_captured_shutters_compared": 0,
        "source_exposure_identity_verified": False,
        "representative_recall_or_precision": False,
        "images_or_derivatives_saved_or_uploaded": False,
        "limitations": (
            "Five selected public photos, ten deterministic transforms per source; "
            "within-source edits are correlated. No different-shutter hard negatives "
            "are tested. Motion-blurred images may abstain; no calibrated rates, "
            "source identity, Android CPU/RAM or automatic photo family decisions."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--algorithm", choices=ALGORITHMS, default="orb")
    args = parser.parse_args()
    print(json.dumps(run(algorithm=args.algorithm), indent=2))


if __name__ == "__main__":
    main()
