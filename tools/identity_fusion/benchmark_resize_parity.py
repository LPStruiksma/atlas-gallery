#!/usr/bin/env python3
"""Synthetic-only RGB resize parity audit: Torchreid PIL vs OpenCV.

No file opens, user images, model downloads, embeddings or identity inference.
This is a diagnostic of *interpolation*, not a declaration of model parity.
"""
from __future__ import annotations

import argparse
import json
import math
import sys

TARGET = (128, 256)  # width, height
# Deliberately exercise both axes of sampling, including identity geometry.
PROBES = (
    ("gradient", 341, 173),
    ("checker", 199, 397),
    ("colorbars", 128, 256),
    ("impulse", 65, 83),
)
PATTERNS = frozenset({"gradient", "checker", "colorbars", "impulse"})


def artificial_rgb(pattern: str, width: int, height: int) -> bytes:
    """Construct an artificial byte pattern, never load an image."""
    if (pattern not in PATTERNS or type(width) is not int or
            type(height) is not int or not 32 <= width <= 512
            or not 32 <= height <= 512):
        raise ValueError("Unsupported bounded synthetic RGB probe")
    buf = bytearray(width * height * 3)
    for y in range(height):
        for x in range(width):
            if pattern == "gradient":
                rgb = ((13 * x + 7 * y) % 256,
                       (19 * x + 5 * y) % 256,
                       (3 * x + 23 * y) % 256)
            elif pattern == "checker":
                rgb = ((255, 7, 42) if (x // 7 + y // 7) % 2
                       else (0, 203, 255))
            elif pattern == "colorbars":
                rgb = ((255, 0, 0), (0, 255, 0),
                       (0, 0, 255), (127, 127, 127))[4 * x // width]
            else:
                rgb = (255, 12, 128) if (
                    abs(x - width // 2) <= 2 and abs(y - height // 2) <= 2
                ) else (0, 0, 0)
            i = 3 * (y * width + x)
            buf[i:i+3] = bytes(rgb)
    return bytes(buf)


def compare_bytes(expected: bytes, alternative: bytes, *,
                  pixel_count: int) -> dict:
    """Aggregate raw RGB channel differences without returning pixel data."""
    if (type(pixel_count) is not int or pixel_count <= 0
            or type(expected) is not bytes
            or type(alternative) is not bytes
            or len(expected) != pixel_count * 3
            or len(alternative) != len(expected)):
        raise ValueError("Invalid same-size synthetic RGB resize outputs")
    differences = sorted(abs(a - b) for a, b in zip(expected, alternative))
    n = len(differences)
    mismatched = sum(d != 0 for d in differences)
    return {
        "channel_values_compared": n,
        "different_channel_fraction": round(mismatched / n, 6),
        "mean_abs_channel_delta": round(sum(differences) / n, 4),
        "p95_abs_channel_delta": differences[math.ceil(n * .95) - 1],
        "max_abs_channel_delta": differences[-1],
        "exact_byte_parity": mismatched == 0,
    }


def audit_resize_pair(reference_resize, candidate_resize, probes=PROBES) -> dict:
    """Injected resize callbacks make this independent of any ML dependency.

    Contract: callback(width,height,rgb_bytes)->exact TARGET-sized RGB bytes.
    Output contains only aggregate divergences and public synthetic geometry.
    """
    if not callable(reference_resize) or not callable(candidate_resize):
        raise ValueError("Expected two local synthetic resize callbacks")
    if not isinstance(probes, (tuple, list)) or not 1 <= len(probes) <= 16:
        raise ValueError("Require bounded synthetic probe list")
    summaries = []
    for case in probes:
        if not isinstance(case, (tuple, list)) or len(case) != 3:
            raise ValueError("Malformed synthetic probe descriptor")
        pattern, w, h = case
        source = artificial_rgb(pattern, w, h)
        reference = reference_resize(w, h, source)
        candidate = candidate_resize(w, h, source)
        stats = compare_bytes(reference, candidate,
                              pixel_count=TARGET[0] * TARGET[1])
        summaries.append({"synthetic_pattern": pattern,
                          "source_width": w, "source_height": h, **stats})
    return {
        "status": "synthetic_resize_comparison_only",
        "target_width": TARGET[0],
        "target_height": TARGET[1],
        "reports": summaries,
        "no_real_media_processed": True,
        "no_model_accuracy_claim": True,
        "caveat": "Resize byte differences do not establish neural network "
                  "accuracy, export equivalence, Android timing or consented "
                  "identity evaluation.",
    }


def pillow_vs_opencv() -> dict:
    """Optional local Pillow+OpenCV+NumPy comparison, no image file access."""
    from PIL import Image, __version__ as pil_version
    import cv2
    import numpy as np

    def pillow(w, h, rgb):
        return Image.frombytes("RGB", (w, h), rgb).resize(
            TARGET, Image.Resampling.BILINEAR).tobytes()

    def opencv(w, h, rgb):
        # RGB planes are left UNCHANGED by cv2.resize; this isolates spatial
        # interpolation only, not the separate RGB-vs-BGR model convention.
        tensor = np.frombuffer(rgb, dtype=np.uint8).reshape(h, w, 3)
        return cv2.resize(tensor, TARGET, interpolation=cv2.INTER_LINEAR).tobytes()

    report = audit_resize_pair(pillow, opencv)
    report["reference"] = "Pillow PIL RGB bilinear (Torchreid evaluation Resize on PIL)"
    report["candidate"] = "OpenCV INTER_LINEAR RGB bytes"
    report["pillow_version"] = pil_version
    report["opencv_version"] = cv2.__version__
    report["numpy_version"] = np.__version__
    return report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args(argv)
    try:
        print(json.dumps(pillow_vs_opencv(), sort_keys=True, indent=2))
        return 0
    except (ImportError, ValueError, OSError, RuntimeError):
        print("Synthetic resize comparison unavailable or rejected",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
