"""Deterministic, synthetic-only comparison; never accesses personal media."""
from __future__ import annotations

import argparse
import json
import time

import cv2
import numpy as np

from .matcher import compare_arrays


def textured(seed: int, size: int = 740) -> np.ndarray:
    rng = np.random.default_rng(seed)
    noise = rng.normal(120, 38, (size, size))
    image = np.uint8(np.clip(cv2.GaussianBlur(noise.astype(np.float32), (3, 3), 0), 0, 255))
    for n in range(230):
        x, y = rng.integers(0, size, 2).tolist()
        rad = int(rng.integers(3, 23))
        color = int(rng.integers(15, 250))
        if n % 3 == 0:
            cv2.circle(image, (x, y), rad, color, int(rng.choice([1, 2, -1])))
        elif n % 3 == 1:
            cv2.line(image, (x, y), (min(size - 1, x + rad * 3), min(size - 1, y + rad)), color, 2)
        else:
            cv2.rectangle(image, (x, y), (min(size - 1, x + rad), min(size - 1, y + rad)), color, 2)
    for k in range(20):
        cv2.putText(image, f"{seed:x}-{k:02}", (int(rng.integers(0, max(1, size - 170))),
                    int(rng.integers(28, size - 15))), cv2.FONT_HERSHEY_SIMPLEX,
                    .5, int(rng.integers(10, 250)), 1, cv2.LINE_AA)
    return image


def rotate(a: np.ndarray, degrees: float) -> np.ndarray:
    h, w = a.shape
    angle = cv2.getRotationMatrix2D((w/2, h/2), degrees, 1.0)
    return cv2.warpAffine(a, angle, (w, h), borderMode=cv2.BORDER_CONSTANT, borderValue=127)


def cases() -> list[tuple[str, np.ndarray, np.ndarray, bool]]:
    a = textured(31)
    b = textured(47)
    crop = a[100:600, 130:640].copy()
    r = rotate(a, 33)[140:610, 100:620].copy()
    recolored_luminance = np.uint8(255 * np.power(a.astype(np.float32) / 255.0, 0.62))
    different_with_shared_logo = b.copy()
    cv2.putText(a, "ATLAS", (250, 365), cv2.FONT_HERSHEY_COMPLEX, 2, 255, 5)
    cv2.putText(different_with_shared_logo, "ATLAS", (250, 365), cv2.FONT_HERSHEY_COMPLEX, 2, 255, 5)
    grid1 = np.tile(np.uint8([[40, 40, 220, 220], [40, 40, 220, 220],
                              [220, 220, 40, 40], [220, 220, 40, 40]]), (185, 185))
    grid2 = np.roll(grid1, 40, axis=1).copy()
    blank = np.full((740, 740), 130, np.uint8)
    return [
        ("resized", a, cv2.resize(a, (510, 510)), True),
        ("crop_68pct", a, crop, True),
        ("arbitrary_rotation_and_crop", a, r, True),
        ("brightness_gamma_edit", a, recolored_luminance, True),
        ("other_photograph", a, b, False),
        ("shared_text_only", a, different_with_shared_logo, False),
        ("repetitive_grid", grid1, grid2, False),
        ("flat_uninformative", blank, blank.copy(), False),
    ]


def run(names: list[str] | None = None):
    selected = names or ["sift", "orb", "akaze"]
    results = []
    for algorithm in selected:
        for label, a, b, related in cases():
            started = time.perf_counter()
            outcome = compare_arrays(a, b, algorithm=algorithm)
            results.append({"algorithm": algorithm, "case": label, "expected_related": related,
                            "verdict": outcome.verdict, "inliers": outcome.inliers,
                            "hash_distance": outcome.global_hash_distance,
                            "gray_correlation": outcome.gray_correlation,
                            "gradient_correlation": outcome.gradient_correlation,
                            "elapsed_ms": round((time.perf_counter() - started) * 1000, 1)})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--algorithm", choices=["sift", "orb", "akaze", "all"], default="all")
    args = parser.parse_args()
    print(json.dumps(run(None if args.algorithm == "all" else [args.algorithm]), indent=2))


if __name__ == "__main__":
    main()
