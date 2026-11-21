#!/usr/bin/env python3
"""Isolated SAVED-1 generator contract and determinism checks."""
import hashlib, json, tempfile
from pathlib import Path
from PIL import Image
import saved1_board as board

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    with tempfile.TemporaryDirectory() as td:
        a=Path(td)/"a.png"; b=Path(td)/"b.png"
        board.build(a); board.build(b)
        assert sha(a)==sha(b), "PNG generation is not deterministic"
        with Image.open(a) as im: assert im.size==(board.W,board.H) and im.mode=="RGB"
    manifest=json.loads(board.MANIFEST.read_text())
    assert manifest["phone_frame_px"]==[1080,2340]
    assert manifest["panel_ids"]==list("ABCDEFG")
    assert min(r[2]-r[0] for r in manifest["target_rectangles_px"])>=135
    assert min(r[3]-r[1] for r in manifest["target_rectangles_px"])>=135
    required=("keyboard","duplicate","failed save","zero","without deleting","Unsorted","10k+","TalkBack","RTL","Retry")
    contract=" ".join(board.PANELS.values())
    assert all(word.lower() in contract.lower() for word in required)
    print("saved1_check: PASS (determinism, 2520x6900 board, 1080x2340 frames, A–G pairs, >=48dp targets)")

if __name__=="__main__": main()
