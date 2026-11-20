#!/usr/bin/env python3
"""Deterministically generate Atlas's original parametric 33³ LUTs."""
from __future__ import annotations

import argparse
import hashlib
import json
import zlib
from dataclasses import dataclass, asdict
from pathlib import Path

SIZE = 33

@dataclass(frozen=True)
class Look:
    id: str
    name: str
    contrast: float
    saturation: float
    warmth: float = 0.0
    fade: float = 0.0
    lift: tuple[float, float, float] = (0.0, 0.0, 0.0)

LOOKS = (
    Look("atlas-clear", "Clear Air", 1.04, 1.03),
    Look("atlas-hearth", "Hearth", 1.03, 1.05, 0.035),
    Look("atlas-drift", "Blue Drift", 1.02, 0.94, -0.035),
    Look("atlas-moss", "Moss Path", 1.04, 0.90, -0.008, lift=(-0.005, 0.012, -0.006)),
    Look("atlas-amber", "Amber Hour", 1.06, 1.08, 0.055),
    Look("atlas-slate", "Slate", 1.09, 0.55, -0.018),
    Look("atlas-linen", "Linen", 0.94, 0.82, 0.022, 0.045),
    Look("atlas-orchard", "Orchard", 1.02, 1.10, 0.012, lift=(0.004, 0.008, -0.006)),
    Look("atlas-coast", "Quiet Coast", 0.98, 0.91, -0.025, 0.018),
    Look("atlas-copper", "Copper Leaf", 1.08, 0.96, 0.042, lift=(0.012, -0.004, -0.012)),
    Look("atlas-dawn", "Dawn Glass", 0.96, 0.88, 0.018, 0.028, (0.008, 0.002, 0.012)),
    Look("atlas-pine", "Pine Shade", 1.07, 0.86, -0.014, lift=(-0.008, 0.008, 0.002)),
    Look("atlas-bloom", "Soft Bloom", 0.93, 1.02, 0.018, 0.055),
    Look("atlas-ink", "Atlas Ink", 1.12, 0.0, -0.005),
)

def _clamp(x: float) -> float: return max(0.0, min(1.0, x))

def transform(look: Look, rgb: tuple[float, float, float]) -> tuple[float, float, float]:
    # Rec.709 luma, then bounded saturation/contrast/warmth. Neutral inputs remain near-neutral.
    y = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
    c = [y + look.saturation * (v - y) for v in rgb]
    c = [0.5 + look.contrast * (v - 0.5) for v in c]
    c[0] += look.warmth; c[2] -= look.warmth
    c = [(1.0 - look.fade) * v + look.fade * (0.5 + (i - 1) * 0.004) + look.lift[i]
         for i, v in enumerate(c)]
    return tuple(_clamp(v) for v in c)

def lut_bytes(look: Look) -> bytes:
    # Compact lossless container: magic, uint8 edge size, zlib-compressed RGB uint8.
    raw = bytearray()
    for b in range(SIZE):
        for g in range(SIZE):
            for r in range(SIZE):
                for value in transform(look, (r / 32.0, g / 32.0, b / 32.0)):
                    raw.append(round(value * 255))
    return b"ATLASLUT1" + bytes((SIZE,)) + zlib.compress(bytes(raw), level=9)

def generate(output: Path) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    entries = []
    for look in LOOKS:
        data = lut_bytes(look)
        (output / f"{look.id}.lut").write_bytes(data)
        entries.append({**asdict(look), "size": SIZE, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})
    catalogue = {"format": "atlas-lut-v1", "looks": entries}
    (output / "catalogue.json").write_text(json.dumps(catalogue, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return catalogue

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    generate(args.output)

if __name__ == "__main__": main()
