"""Offline, opt-in decoded-pixel collision audit for curated Photo DNA benchmarks.

This is NOT a duplicate classifier: pixel-identical independent captures are
ambiguous too. Hash exactly decoded, EXIF-oriented RGBA pixels at full native
resolution (not a resized pHash), across *both* manifest partitions. A lossy
JPEG re-encode will usually evade this exact-only check.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import struct

from PIL import Image, ImageOps, UnidentifiedImageError

from .heldout_eval import _safe_path, _sha256, _validate_manifest
from .matcher import _open_nofollow_components

MAX_PIXELS = 95_000_000
STRIP_ROWS = 64


def _decoded_pixel_sha256(path: Path) -> str:
    """Full-resolution, orientation-normalized pixel digest in bounded strips.

    PIL may still decode an entire source frame internally; strips avoid an
    additional full-size RGBA byte buffer. This is offline research, not an
    Android batch scanner or low-memory guarantee.
    """
    digest = hashlib.sha256()
    try:
        # Path checks and earlier byte hashes are not capabilities: open the
        # source again without following any swapped ancestor or leaf symlink.
        fd = _open_nofollow_components(path)
        with os.fdopen(fd, "rb") as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode):
                raise ValueError("Pixel audit source is no longer a regular file")
            with Image.open(stream) as image:
                if (image.width < 1 or image.height < 1
                        or image.width * image.height > MAX_PIXELS):
                    raise ValueError("Image dimensions exceed audit safety limits")
                oriented = ImageOps.exif_transpose(image)
                width, height = oriented.size
                digest.update(b"ATLAS-RGBA-ORIENTED-V1\x00")
                digest.update(struct.pack(">II", width, height))
                for y in range(0, height, STRIP_ROWS):
                    # Bound additional RGBA strip memory, not PIL's native decode.
                    tile = oriented.crop((0, y, width, min(y + STRIP_ROWS, height)))
                    digest.update(tile.convert("RGBA").tobytes())
            after = os.fstat(stream.fileno())
            identity = lambda st: (st.st_dev, st.st_ino, st.st_size,
                                   st.st_mtime_ns, st.st_ctime_ns)
            if identity(before) != identity(after):
                raise ValueError("Source changed during decoded-pixel audit")
    except (OSError, UnidentifiedImageError, Image.DecompressionBombError) as exc:
        raise ValueError("Source cannot be safely decoded for pixel audit") from exc
    return digest.hexdigest()

def audit_staged_sources(manifest: str | Path, root: str | Path) -> dict:
    """Verify every curated capture in both splits without mutating files.

    SHA bytes are checked before and after decode, including unselected split.
    Group matching pixel digests; report aggregate counts only, no paths or
    image bytes. Non-identical pixels are NOT certified independent captures.
    """
    root, scenes = _validate_manifest(Path(manifest), Path(root), split=None)
    digests: dict[str, int] = {}
    captures = 0
    for _, members in scenes:
        for _, metadata in members:
            path = _safe_path(root, metadata["file"])
            if _sha256(path) != metadata["sha256"]:
                raise ValueError("Source byte integrity mismatch during pixel audit")
            pixel_sha = _decoded_pixel_sha256(path)
            if _sha256(path) != metadata["sha256"]:
                raise ValueError("Source changed while decoding for pixel audit")
            digests[pixel_sha] = digests.get(pixel_sha, 0) + 1
            captures += 1
    duplicates = [count for count in digests.values() if count > 1]
    return {
        "policy": "Offline curator-audit only; no image copies, identity merges or deletion",
        "all_declared_splits_audited": True,
        "scenes": len(scenes),
        "captures": captures,
        "decoded_pixel_collision_groups": len(duplicates),
        "captures_with_identical_decoded_pixels": sum(duplicates),
        "no_exact_pixel_collisions_observed": not duplicates,
        "source_exposure_independence_proven": False,
        "limitations": "Exact oriented RGBA only; lossy JPEG edits, crops and matching backgrounds can evade this check. Equal pixels cannot prove same shutter. Curator review is required.",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--root", required=True, type=Path)
    args = parser.parse_args()
    try:
        summary = audit_staged_sources(args.manifest, args.root)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    print(json.dumps(summary, indent=2))
    if not summary["no_exact_pixel_collisions_observed"]:
        parser.exit(2, "Ambiguous exact decoded-pixel source aliases: curate manifest before evaluation\n")


if __name__ == "__main__":
    main()
