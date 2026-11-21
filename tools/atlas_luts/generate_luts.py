#!/usr/bin/env python3
"""Generate fourteen original Atlas 33-cubed LUT assets without third-party LUTs."""
from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import asdict, dataclass
from pathlib import Path

SIZE = 33
MAGIC = b"ATLASLUT1"


@dataclass(frozen=True)
class Look:
    id: str
    name: str
    contrast: float
    saturation: float
    warmth: float = 0.0
    fade: float = 0.0
    lift: tuple[float, float, float] = (0.0, 0.0, 0.0)
    preview_strength: float = 0.68


LOOKS = (
    Look("atlas-clear", "Clear Air", 1.04, 1.03, preview_strength=0.68),
    Look("atlas-hearth", "Hearth", 1.03, 1.05, 0.035, preview_strength=0.62),
    Look("atlas-drift", "Blue Drift", 1.02, 0.94, -0.035, preview_strength=0.62),
    Look("atlas-moss", "Moss Path", 1.04, 0.90, -0.008, lift=(-0.005, 0.012, -0.006), preview_strength=0.64),
    Look("atlas-amber", "Amber Hour", 1.06, 1.08, 0.055, preview_strength=0.48),
    Look("atlas-slate", "Slate", 1.09, 0.55, -0.018, preview_strength=0.50),
    Look("atlas-linen", "Linen", 0.94, 0.82, 0.022, 0.045, preview_strength=0.66),
    Look("atlas-orchard", "Orchard", 1.02, 1.10, 0.012, lift=(0.004, 0.008, -0.006), preview_strength=0.58),
    Look("atlas-coast", "Quiet Coast", 0.98, 0.91, -0.025, 0.018, preview_strength=0.64),
    Look("atlas-copper", "Copper Leaf", 1.08, 0.96, 0.042, lift=(0.012, -0.004, -0.012), preview_strength=0.54),
    Look("atlas-dawn", "Dawn Glass", 0.96, 0.88, 0.018, 0.028, (0.008, 0.002, 0.012), 0.68),
    Look("atlas-pine", "Pine Shade", 1.07, 0.86, -0.014, lift=(-0.008, 0.008, 0.002), preview_strength=0.62),
    Look("atlas-bloom", "Soft Bloom", 0.93, 1.02, 0.018, 0.055, preview_strength=0.62),
    Look("atlas-ink", "Atlas Ink", 1.12, 0.0, -0.005, preview_strength=0.28),
)


def _clamp(x: float) -> float:
    return max(0.0, min(1.0, x))


def transform(look: Look, rgb: tuple[float, float, float]) -> tuple[float, float, float]:
    # Rec.709 luma and bounded saturation, contrast, warmth, fade, lift in encoded sRGB.
    y = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
    c = [y + look.saturation * (v - y) for v in rgb]
    c = [0.5 + look.contrast * (v - 0.5) for v in c]
    c[0] += look.warmth
    c[2] -= look.warmth
    c = [(1.0 - look.fade) * v + look.fade * (0.5 + (i - 1) * 0.004) + look.lift[i]
         for i, v in enumerate(c)]
    return tuple(_clamp(v) for v in c)


def lut_bytes(look: Look) -> bytes:
    # Magic (9 bytes), dimension (uint8), raw RGB8 in B/G/R loop order (R fastest).
    # The 14 raw binary assets total 1,509,494 bytes and are compressed by the APK.
    raw = bytearray(MAGIC)
    raw.append(SIZE)
    for b in range(SIZE):
        for g in range(SIZE):
            for r in range(SIZE):
                for value in transform(look, (r / 32.0, g / 32.0, b / 32.0)):
                    raw.append(round(value * 255))
    return bytes(raw)


def generate(output: Path) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    entries = []
    for look in LOOKS:
        data = lut_bytes(look)
        (output / f"{look.id}.lut").write_bytes(data)
        entries.append({**asdict(look), "size": SIZE,
                        "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})
    catalogue = {"format": "atlas-lut-v1", "looks": entries}
    (output / "catalogue.json").write_text(
        json.dumps(catalogue, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return catalogue


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    generate(args.output)


if __name__ == "__main__":
    main()
