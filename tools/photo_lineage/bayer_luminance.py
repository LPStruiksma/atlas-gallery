"""Research-only deterministic 2x2 Bayer mosaic -> grayscale adapter.

Input is *already decoded* uint16 visible sensor pixels. No file, RAW decoder,
network, uploads, identity inference or source modifications. Not a faithful
camera-color render and never evidence of same shutter origin.
"""
from __future__ import annotations

import hashlib
from collections.abc import Iterable

import cv2
import numpy as np

MAX_RAW_PIXELS = 48_000_000
MIN_SIDE = 96
MAX_SIDE = 1024
MAX_FRAMES = 10
BAYER_PATTERNS = frozenset(("RGGB", "BGGR", "GRBG", "GBRG"))
# BT.709-like approximate scene-luminance weights, with two greens shared.
_WEIGHTS = {"R": .2126, "G": .3576, "B": .0722}


def bayer_to_grayscale(raw: np.ndarray, *, pattern: str,
                       black_levels: tuple[int, int, int, int],
                       white_level: int, max_side: int = 960) -> np.ndarray:
    """Area-decimate 2x2 Bayer cells, black-correct, return uint8 luma.

    The four black levels correspond to top-left, top-right, bottom-left,
    bottom-right SENSOR CFA positions, not rawpy color-index numbering.
    Missing metadata, unusual CFA, invalid ranges and giant inputs abstain.
    """
    if (not isinstance(raw, np.ndarray) or raw.ndim != 2 or raw.dtype != np.uint16
            or raw.shape[0] < MIN_SIDE or raw.shape[1] < MIN_SIDE
            or raw.shape[0] * raw.shape[1] > MAX_RAW_PIXELS
            or raw.shape[0] > 12_000 or raw.shape[1] > 12_000):
        raise ValueError("Expected bounded 2D native-endian uint16 Bayer pixels")
    if type(pattern) is not str or pattern not in BAYER_PATTERNS:
        raise ValueError("Only explicit 2x2 RGGB/BGGR/GRBG/GBRG Bayer supported")
    if (type(black_levels) is not tuple or len(black_levels) != 4
            or type(white_level) is not int or not 1 <= white_level <= 65535
            or any(type(b) is not int or not 0 <= b < white_level for b in black_levels)):
        raise ValueError("Require four sensor-position black levels below white level")
    if type(max_side) is not int or not 128 <= max_side <= MAX_SIDE:
        raise ValueError("max_side must be integer 128..1024")

    h, w = raw.shape
    cell_h, cell_w = h // 2, w // 2
    ratio = min(1.0, max_side / max(cell_h, cell_w))
    out_h, out_w = max(1, int(round(cell_h * ratio))), max(1, int(round(cell_w * ratio)))
    if min(out_h, out_w) < 48:
        raise ValueError("Output too narrow for geometric comparison")
    total = np.zeros((out_h, out_w), dtype=np.float32)
    for index, color in enumerate(pattern):
        # Each CFA position has one distinct per-position black level.
        # Area averaging on *each color plane* avoids nearest-cell aliasing.
        plane = raw[index // 2:2 * cell_h:2, index % 2:2 * cell_w:2]
        if plane.shape != (out_h, out_w):
            down = cv2.resize(plane, (out_w, out_h), interpolation=cv2.INTER_AREA)
        else:
            down = plane
        corrected = (down.astype(np.float32) - black_levels[index]) / (
            white_level - black_levels[index])
        np.clip(corrected, 0, 1, out=corrected)
        total += _WEIGHTS[color] * corrected
    return np.rint(np.clip(total * 255.0, 0, 255)).astype(np.uint8)


def audit_grayscale_byte_aliases(grays: Iterable[np.ndarray]) -> dict:
    """Read no files; fail closed on exactly matching normalized grayscale.

    A matching preview is only an ambiguity flag, not proof of identical raw
    capture. Distinct normalized bytes do NOT establish different shutters.
    """
    if isinstance(grays, (np.ndarray, bytes, str)):
        raise ValueError("Expected an iterable of distinct grayscale frames")
    try:
        iterator = iter(grays)
    except TypeError:
        raise ValueError("Expected an iterable of grayscale frames") from None
    hashes = set()
    count = 0
    for item in iterator:
        count += 1
        if count > MAX_FRAMES:
            raise ValueError("At most 10 normalized frames per audit")
        if (not isinstance(item, np.ndarray) or item.ndim != 2
                or item.dtype != np.uint8 or not item.flags.c_contiguous
                or not 48 <= item.shape[0] <= MAX_SIDE
                or not 48 <= item.shape[1] <= MAX_SIDE):
            raise ValueError("Unacceptable normalized grayscale preview")
        digest = hashlib.sha256(item.tobytes()).digest()
        if digest in hashes:
            raise ValueError("Normalized grayscale byte alias; source shutter uncertain")
        hashes.add(digest)
    if count < 2:
        raise ValueError("Require at least two normalized frame previews")
    return {
        "preview_count": count,
        "normalized_grayscale_byte_aliases_detected": False,
        "physical_shutter_independence_verified": False,
        "camera_colorimetry_validated": False,
        "actual_raw_decode_performed": False,
        "source_media_modified_or_uploaded": False,
        "limitations": "Distinct normalized previews are not evidence of independent camera exposures; Bayer conversion is a fixed research proxy, not a camera-color or RAW decoder validation.",
    }
