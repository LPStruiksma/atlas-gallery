"""Strict metadata-only conversion of an ALREADY OPEN rawpy-like Bayer decoder.

Never opens files, unpacks sensor pixels, downloads models, or accesses EXIF/GPS.
Only four color-index calls on the visible origin are needed. The owning
caller remains responsible for decoder sandboxing and legitimate media rights.
"""
from __future__ import annotations

from numbers import Integral

import numpy as np

from .bayer_luminance import BAYER_PATTERNS


def _safe_int(value: object, *, ceiling: int) -> int:
    if isinstance(value, bool) or not isinstance(value, Integral) or not 0 <= int(value) <= ceiling:
        raise ValueError("Invalid rawpy-like sensor calibration integer")
    return int(value)


def visible_bayer_calibration(decoder: object) -> dict:
    """Return exact kwargs for bayer_to_grayscale without reading a pixel.

    raw_pattern is relative to FULL raw image. Visible margin parity must be
    applied; rawpy black levels are indexed by raw_color index, NOT CFA slot.
    Reject unknown/X-Trans/Foveon/mixed/uncertain data and per-channel white
    levels that conflict with scalar white instead of guessing a mapping.
    """
    try:
        sizes = decoder.sizes
        top = _safe_int(sizes.top_margin, ceiling=8192)
        left = _safe_int(sizes.left_margin, ceiling=8192)
        layout = decoder.raw_pattern
        desc = decoder.color_desc
        blacks = decoder.black_level_per_channel
        white = _safe_int(decoder.white_level, ceiling=65535)
        camera_whites = decoder.camera_white_level_per_channel
        color_at = decoder.raw_color
        visible = decoder.sizes
    except (AttributeError, ValueError, TypeError, RuntimeError):
        raise ValueError("Unsupported or incomplete rawpy-like Bayer metadata") from None
    if (not isinstance(layout, np.ndarray) or layout.shape != (2, 2)
            or layout.dtype != np.uint8 or type(desc) is not bytes or len(desc) != 4
            or type(blacks) not in (list, tuple) or len(blacks) != 4
            or not callable(color_at) or white < 1
            or not all(hasattr(visible, name) for name in ("width", "height"))):
        raise ValueError("Require exact 2x2 rawpy Bayer metadata and scalar white")
    width = _safe_int(visible.width, ceiling=12000)
    height = _safe_int(visible.height, ceiling=12000)
    if width < 96 or height < 96 or width * height > 48_000_000:
        raise ValueError("Unacceptable visible sensor dimensions")
    positions = []
    try:
        for row in range(2):
            for col in range(2):
                index = _safe_int(color_at(top + row, left + col), ceiling=3)
                expected = _safe_int(layout[(top + row) % 2, (left + col) % 2], ceiling=3)
                if index != expected:
                    raise ValueError("RawPy color index and full-RAW CFA phase disagree")
                positions.append(index)
    except (ValueError, TypeError, RuntimeError, IndexError):
        raise ValueError("Uncertain visible Bayer CFA phase or color index") from None
    labels = "".join(chr(desc[index]) for index in positions)
    if labels not in BAYER_PATTERNS:
        raise ValueError("Non-RGGB visible CFA / unsupported color description")
    try:
        levels = tuple(_safe_int(blacks[index], ceiling=65535) for index in positions)
        if any(level >= white for level in levels):
            raise ValueError("Black level cannot reach white level")
        if camera_whites is not None:
            if (type(camera_whites) not in (tuple, list) or len(camera_whites) != 4
                    or any(_safe_int(level, ceiling=65535) != white
                           for level in camera_whites)):
                raise ValueError("Nonuniform camera saturation levels unsupported")
    except (ValueError, TypeError, IndexError):
        raise ValueError("Untrusted or unequal RAW white/black calibration") from None
    return {"pattern": labels, "black_levels": levels, "white_level": white}
