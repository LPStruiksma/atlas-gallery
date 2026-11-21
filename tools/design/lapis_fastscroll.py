"""Deterministic synthetic fast-scroll recording for the Lapis L4 sheet (#816).

Eleven distinct frames (1.4 s) of the Pictures screen: the month sphere is dragged down the rail in ten
100 ms steps (continuous thumb movement), the label and grid cross November -> August -> March, and the
last frame holds the settled state with the drag released for 400 ms. Identical frames are never
stored twice (the GIF writer would merge them), so every frame differs from its neighbour. Fictional gradient tiles only. This is a
frame-by-frame storyboard rendered by the generator, never a device screen recording.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

from PIL import Image

import lapis_kit as L

FRAME_MS = 100
SETTLED_MS = 400
THUMB_FRACTIONS = (0.08, 0.14, 0.22, 0.31, 0.40, 0.49, 0.58, 0.67, 0.76, 0.82, 0.82)
SETTLED_FRAMES = 1
MONTH_EDGES = ((0.30, "Nov", "November 2026", 1), (0.65, "Aug", "August 2026", 4), (1.01, "Mar", "March 2026", 9))
SCALE = 3  # 1080 x 2340 frame -> 360 x 780 GIF
GIF_NAME = "brand-l4-fast-scroll.gif"


def month_for(frac: float):
    for edge, short, long, seed in MONTH_EDGES:
        if frac < edge:
            return short, long, seed
    raise ValueError(frac)


def thumb_months() -> list[str]:
    return [month_for(f)[0] for f in THUMB_FRACTIONS]


def render_frames(gen, theme="light") -> list[Image.Image]:
    # imported here: lapis_screens pulls in the whole screen library
    import lapis_screens as S

    frames = []
    for i, frac in enumerate(THUMB_FRACTIONS):
        short, long, seed = month_for(frac)
        dragging = i < len(THUMB_FRACTIONS) - SETTLED_FRAMES
        s = gen.scr(theme)
        s.status_bar()
        y = S.title(s, "Pictures")
        S.square_grid(s, y, s.bottom, seed=seed, header=long)
        S.fast_thumb(s, frac, short, y + L.px(8), s.bottom - L.px(110), dragging=dragging)
        s.slab("Pictures")
        s.gesture_bar()
        small = s.img.convert("RGB").resize((L.DEVICE_W // SCALE, L.DEVICE_H // SCALE), Image.LANCZOS)
        frames.append(small)
    return frames


def save_gif(frames: list[Image.Image], path: Path) -> None:
    pal = [f.quantize(colors=128, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE) for f in frames]
    pal[0].save(path, save_all=True, append_images=pal[1:], duration=[FRAME_MS] * (len(frames) - 1) + [SETTLED_MS], loop=0, optimize=False,
                disposal=1)


def describe(path: Path) -> dict:
    with Image.open(path) as im:
        n = im.n_frames
        durations = []
        for k in range(n):
            im.seek(k)
            durations.append(int(im.info.get("duration", 0)))
        size = list(im.size)
    return {
        "file": path.name,
        "size": size,
        "frames": n,
        "duration_ms": sum(durations),
        "frame_ms": sorted(set(durations)),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "synthetic_only": True,
        "months": thumb_months(),
        "thumb_fractions": list(THUMB_FRACTIONS),
        "settled_frames": SETTLED_FRAMES,
    }


def problems(entry: dict, path: Path) -> list[str]:
    """Check the recording against its manifest entry and the invariants the sheet promises."""
    out = []
    if not path.is_file():
        return [f"missing {path}"]
    got = describe(path)
    for key in ("size", "frames", "duration_ms", "sha256"):
        if got[key] != entry.get(key):
            out.append(f"{key} {got[key]} != manifest {entry.get(key)}")
    if got["frames"] != len(THUMB_FRACTIONS) or not 1000 <= got["duration_ms"] <= 2000:
        out.append(f"needs {len(THUMB_FRACTIONS)} frames and 1.0-2.0 s")
    fr = entry.get("thumb_fractions", [])
    if any(b < a for a, b in zip(fr, fr[1:])) or len(set(fr)) < 8 or fr[-1] != fr[-2]:
        out.append("thumb must move continuously downward")
    if len(set(entry.get("months", []))) < 3:
        out.append("must cross three months")
    return out
