#!/usr/bin/env python3
"""Verify SAVED-1 committed PNG and manifest against the deterministic generator."""
import hashlib
import json
import re
import tempfile
from pathlib import Path

from PIL import Image, __version__ as PILLOW_VERSION

import saved1_board as board


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    manifest = json.loads(board.MANIFEST.read_text(encoding="utf-8"))
    artifact = board.MANIFEST.parent / manifest["artifact"]
    digest = manifest["sha256"]
    assert re.fullmatch(r"[0-9a-f]{64}", digest), "manifest digest not finalized"
    assert artifact.is_file(), "generated PNG missing from committed tree"
    assert sha(artifact) == digest, "committed PNG differs from manifest SHA-256"
    assert artifact.stat().st_size == manifest["byte_size"], "PNG byte count differs"
    assert manifest["pillow"] == PILLOW_VERSION, "reproduction requires the recorded Pillow version"
    for key, font_path in (("regular", board.FONT), ("bold", board.FONT_BOLD)):
        assert font_path.is_file(), f"missing source font: {font_path}"
        assert sha(font_path) == manifest["font_provenance"][key]["sha256"], f"source font {key} differs"
    with Image.open(artifact) as im:
        assert im.size == tuple(manifest["board_px"]) == (board.W, board.H)
        assert im.mode == "RGB"
        im.verify()
    assert manifest["phone_frame_px"] == [1080, 2340]
    assert manifest["panel_ids"] == list("ABCDEFG")
    assert len(manifest["target_rectangles_px"]) >= 6
    assert min(r[2] - r[0] for r in manifest["target_rectangles_px"]) >= 135
    assert min(r[3] - r[1] for r in manifest["target_rectangles_px"]) >= 135
    contract = " ".join(board.PANELS.values()).lower()
    required = ("keyboard", "duplicate", "failed save", "zero", "without deleting",
                "unsorted", "10k+", "talkback", "rtl", "retry")
    assert all(word in contract for word in required), "panel contract is incomplete"
    with tempfile.TemporaryDirectory() as td:
        regenerated = Path(td) / "regenerated.png"
        board.build(regenerated)
        assert sha(regenerated) == digest, "generator no longer reproduces committed PNG"
    print("saved1_check: PASS (committed PNG, manifest, fonts, reproducibility, geometry and A–G copy)")


if __name__ == "__main__":
    main()
