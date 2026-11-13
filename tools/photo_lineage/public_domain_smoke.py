"""Offline CC0/public-domain photo benchmark using installed scikit-image assets.

Five precisely SHA-pinned example photographs. No downloads, uploads,
private media or filesystem writes. Tests same-source derived edits and
unrelated different-scene negatives; NO genuine same-scene shutter pairs.
"""
from __future__ import annotations

from collections import Counter
from io import BytesIO
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import stat

import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

from .heldout_eval import EDIT_NAMES, _derived_edits
from .matcher import compare_arrays

# Source and rights notes: https://scikit-image.org/docs/stable/api/skimage.data
# Exact known packaged-file hashes; no fallback when versions differ.
PINNED_PHOTOS = {
    "astronaut": ("astronaut.png",
                  "88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5",
                  "NASA / public domain"),
    "camera": ("camera.png",
               "b0793d2adda0fa6ae899c03989482bff9a42d3d5690fc7e3648f2795d730c23a",
               "Lav Varshney / CC0"),
    "chelsea": ("chelsea.png",
                "596aa1e7cb875eb79f437e310381d26b338a81c2da23439704a73c4651e8c4bb",
                "Stefan van der Walt / CC0"),
    "clock": ("clock_motion.png",
              "f029226b28b642e80113d86622e9b215ee067a0966feaf5e60604a1e05733955",
              "Stefan van der Walt / public domain"),
    "coffee": ("coffee.png",
               "cc02f8ca188b167c775a7101b5d767d1e71792cf762c33d6fa15a4599b5a8de7",
               "Rachel Michetti / CC0"),
}
SOURCE_DOCUMENTATION = "https://scikit-image.org/docs/stable/api/skimage.data"
MAX_SOURCE_BYTES = 2_000_000
MAX_SOURCE_PIXELS = 4_000_000


def _read_pinned_photo(path: Path, expected_sha: str) -> np.ndarray:
    """Load only a locally present non-symlinked PNG matching its byte pin."""
    if not isinstance(expected_sha, str) or re.fullmatch(r"[0-9a-f]{64}", expected_sha) is None:
        raise ValueError("Expected lowercase pinned SHA256")
    if path.is_symlink() or not path.is_file():
        raise ValueError("Pinned sample absent or symlinked; never download")
    before = path.stat()
    if (not stat.S_ISREG(before.st_mode)
            or not 0 < before.st_size <= MAX_SOURCE_BYTES):
        raise ValueError("Pinned source exceeds file-size quota")
    blob = path.read_bytes()
    if len(blob) != before.st_size or hashlib.sha256(blob).hexdigest() != expected_sha:
        raise ValueError("Pinned sample byte mismatch; no substitution")
    after = path.stat()
    fields = lambda obj: (obj.st_dev, obj.st_ino, obj.st_size, obj.st_mtime_ns, obj.st_ctime_ns)
    if fields(before) != fields(after):
        raise ValueError("Sample changed during reading")
    try:
        with Image.open(BytesIO(blob)) as photo:
            if (photo.format != "PNG" or min(photo.size) < 128
                    or photo.width * photo.height > MAX_SOURCE_PIXELS):
                raise ValueError("Unacceptable image format or dimensions")
            return np.asarray(ImageOps.exif_transpose(photo).convert("L"), dtype=np.uint8).copy()
    except (OSError, UnidentifiedImageError, Image.DecompressionBombError) as exc:
        raise ValueError("Undecodable pinned sample") from exc


def _bundled_root() -> Path:
    """Inspect scikit-image package path, not its network-backed data cache."""
    spec = importlib.util.find_spec("skimage.data")
    if spec is None or spec.origin is None:
        raise ValueError("Optional bundled scikit-image data unavailable")
    root = Path(spec.origin).parent
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Unsafe scikit-image sample directory")
    return root


def run(*, algorithm: str = "orb",
        sample_names: tuple[str, ...] | None = None) -> dict:
    if algorithm not in ("orb", "sift", "akaze"):
        raise ValueError("Unsupported classical matcher")
    names = tuple(sorted(PINNED_PHOTOS)) if sample_names is None else sample_names
    if (not isinstance(names, tuple) or not 2 <= len(names) <= 5
            or len(set(names)) != len(names)
            or any(type(n) is not str or n not in PINNED_PHOTOS for n in names)):
        raise ValueError("Only 2..5 distinct pinned public samples are allowed")
    root = _bundled_root()
    photos = {
        name: _read_pinned_photo(root / PINNED_PHOTOS[name][0], PINNED_PHOTOS[name][1])
        for name in names
    }
    positive = {edit: Counter() for edit in EDIT_NAMES}
    complete_sources = 0
    for photo in photos.values():
        edits = _derived_edits(photo)
        if set(edits) != set(EDIT_NAMES):
            raise ValueError("Heldout edit protocol changed")
        success = 0
        for edit in EDIT_NAMES:
            verdict = compare_arrays(photo, edits[edit], algorithm=algorithm).verdict
            if verdict not in ("candidate", "review", "unknown"):
                raise ValueError("Unexpected pair verdict")
            positive[edit][verdict] += 1
            success += verdict in ("candidate", "review")
        complete_sources += success == len(EDIT_NAMES)
    unrelated = Counter()
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            verdict = compare_arrays(photos[a], photos[b], algorithm=algorithm).verdict
            if verdict not in ("candidate", "review", "unknown"):
                raise ValueError("Unexpected pair verdict")
            unrelated[verdict] += 1
    return {
        "policy": ("Pinned offline public-domain/CC0 photographic smoke test. "
                   "Candidate is REVIEW only; no independent same-scene shutter "
                   "pairs, calibrated accuracy or automatic photo merges."),
        "algorithm": algorithm,
        "sample_count": len(names),
        "public_sample_names": sorted(names),
        "credit_and_license": {name: PINNED_PHOTOS[name][2] for name in names},
        "rights_notes": SOURCE_DOCUMENTATION,
        "pinned_bytes_verified": True,
        "originals_unchanged": True,
        "originals_or_derivatives_exported": False,
        "derived_edit_total": len(names) * len(EDIT_NAMES),
        "all_edits_reviewable_source_count": complete_sources,
        "edit_verdicts": {
            edit: {v: positive[edit].get(v, 0)
                   for v in ("candidate", "review", "unknown")}
            for edit in EDIT_NAMES
        },
        "unrelated_different_scene_pair_count": len(names) * (len(names) - 1) // 2,
        "unrelated_different_scene_verdicts": {
            v: unrelated.get(v, 0) for v in ("candidate", "review", "unknown")
        },
        "genuine_same_scene_independent_shutters_tested": 0,
        "real_camera_same_scene_accuracy_proven": False,
        "device_performance_measured": False,
        "limitations": (
            "Five small handpicked camera images, source-shared edits are "
            "correlated and cross-photo pairs show DIFFERENT scenes. Not "
            "representative of 150k galleries, independently captured same-scene "
            "photographs, crop/rotation recall at scale, or S24+ latency."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--algorithm", choices=("orb", "sift", "akaze"), default="orb")
    args = parser.parse_args()
    print(json.dumps(run(algorithm=args.algorithm), indent=2))


if __name__ == "__main__":
    main()
