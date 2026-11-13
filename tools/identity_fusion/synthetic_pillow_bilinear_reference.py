#!/usr/bin/env python3
"""Exact four-probe Pillow-12.3-style bilinear goldens with only Python stdlib.

NOT a general-purpose image processor or a claim of byte parity for
arbitrary photos/geometry/Pillow releases. No files or pretrained models.
Fixed 22-bit weights, horizontal then vertical, and per-pass rounding
match the four previously independently SHA256-pinned fictional probes.
"""
from __future__ import annotations

import hashlib

from benchmark_resize_parity import PROBES, TARGET, artificial_rgb

FIXED_BITS = 22
ROUNDING = 1 << (FIXED_BITS - 1)
MULTIPLIER = 1 << FIXED_BITS
VALID_CASES = {(name, width, height) for name, width, height in PROBES}


def _weights(source_length: int, destination_length: int):
    scale = source_length / destination_length
    support_scale = max(scale, 1.0)
    output = []
    for target_index in range(destination_length):
        center = (target_index + .5) * scale
        low = max(0, int(center - support_scale + .5))
        high = min(source_length, int(center + support_scale + .5))
        coefficients = [
            max(0.0, 1.0 - abs((source_index - center + .5) / support_scale))
            for source_index in range(low, high)
        ]
        total = sum(coefficients)
        if total <= 0:
            raise ValueError("Unexpected empty Pillow synthetic filter")
        output.append((low, [int(coefficient / total * MULTIPLIER + .5)
                             for coefficient in coefficients]))
    return output


def _resample_x(source: bytes, source_width: int, height: int,
                target_width: int) -> bytes:
    weights = _weights(source_width, target_width)
    resized = bytearray(target_width * height * 3)
    for y in range(height):
        for x, (start, coefficients) in enumerate(weights):
            for color in range(3):
                value = ROUNDING + sum(
                    source[3 * (y * source_width + start + j) + color] * coefficient
                    for j, coefficient in enumerate(coefficients)
                )
                resized[3 * (y * target_width + x) + color] = min(
                    255, max(0, value >> FIXED_BITS))
    return bytes(resized)


def _resample_y(source: bytes, width: int, source_height: int,
                target_height: int) -> bytes:
    weights = _weights(source_height, target_height)
    resized = bytearray(width * target_height * 3)
    for y, (start, coefficients) in enumerate(weights):
        for x in range(width):
            for color in range(3):
                value = ROUNDING + sum(
                    source[3 * ((start + j) * width + x) + color] * coefficient
                    for j, coefficient in enumerate(coefficients)
                )
                resized[3 * (y * width + x) + color] = min(
                    255, max(0, value >> FIXED_BITS))
    return bytes(resized)


def synthetic_pillow_bilinear(name: str, width: int, height: int) -> bytes:
    """Generate ONLY four preregistered synthetic cases, never photo bytes."""
    if (type(name) is not str or type(width) is not int or type(height) is not int
            or (name, width, height) not in VALID_CASES):
        raise ValueError("Only source-pinned fictional RGB resize probes supported")
    source = artificial_rgb(name, width, height)
    dst_width, dst_height = TARGET
    if width != dst_width:
        source = _resample_x(source, width, height, dst_width)
    if height != dst_height:
        source = _resample_y(source, dst_width, height, dst_height)
    return source


if __name__ == "__main__":
    import json
    from audit_intel_resize_goldens import REFERENCE_SHA256

    result = {}
    for name, width, height in PROBES:
        digest = hashlib.sha256(
            synthetic_pillow_bilinear(name, width, height)).hexdigest()
        result[name] = digest == REFERENCE_SHA256[name]["resized_rgb"]
    print(json.dumps({
        "synthetic_stdlib_four_reference_hashes_match": all(result.values()),
        "patterns": result,
        "real_photos_or_models_tested": False,
        "production_approved": False,
    }, sort_keys=True))
    raise SystemExit(0 if all(result.values()) else 2)
