#!/usr/bin/env python3
"""Pure-stdlib synthetic corpus proof: deterministic, PNG-valid, bounded, safe."""
import csv
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import zlib

sys.path.insert(0, str(Path(__file__).parent))
from generate_viewer_fixtures import PNG, generate, limits


def reject(action):
    try:
        action()
    except (ValueError, OSError):
        return
    raise AssertionError("Unsafe fixture operation accepted")


def decode_png(data):
    assert data.startswith(PNG)
    offset = len(PNG)
    compressed = b""
    dimensions = None
    while offset < len(data):
        size = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4: offset + 8]
        chunk = data[offset + 8: offset + 8 + size]
        expected = struct.unpack_from(">I", data, offset + 8 + size)[0]
        assert zlib.crc32(kind + chunk) & 0xffffffff == expected
        offset += size + 12
        if kind == b"IHDR":
            dimensions = struct.unpack(">II", chunk[:8])
        elif kind == b"IDAT":
            compressed += chunk
        elif kind == b"IEND":
            assert offset == len(data)
    assert dimensions is not None
    width, height = dimensions
    raw = zlib.decompress(compressed)
    assert len(raw) == height * (1 + width * 3)
    assert all(raw[y * (1 + width * 3)] == 0 for y in range(height))
    return dimensions, raw


with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    a, b, c = root / "a", root / "b", root / "c"
    old = os.umask(0)
    try:
        result = generate(a, rows=140, images=6, width=32, height=24, seed=41)
    finally:
        os.umask(old)
    again = generate(b, rows=140, images=6, width=32, height=24, seed=41)
    changed = generate(c, rows=140, images=6, width=32, height=24, seed=42)
    assert result["synthetic_only"] and not result["physical_device_measured"]
    assert result["catalogue_sha256"] == again["catalogue_sha256"]
    assert result["catalogue_sha256"] != changed["catalogue_sha256"]
    assert (a / "manifest.json").read_bytes() == (b / "manifest.json").read_bytes()
    assert a.stat().st_mode & 0o777 == 0o700
    assert all(p.stat().st_mode & 0o777 == 0o600 for p in a.iterdir() if p.is_file())
    manifest = json.loads((a / "manifest.json").read_text())
    assert manifest["materialized_pngs"] == 6
    assert manifest["images"][0]["pattern"] == "bands"
    assert manifest["images"][1]["pattern"] == "noise"
    for i in range(6):
        one = (a / f"synthetic-{i:06d}.png").read_bytes()
        other = (b / f"synthetic-{i:06d}.png").read_bytes()
        assert one == other
        assert decode_png(one)[0] == (32, 24)
    with (a / "catalogue.csv").open(newline="") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 140 and all(v["synthetic_uri"].startswith("synthetic://") for v in rows)
    assert rows[0]["materialized"] == "1" and rows[6]["materialized"] == "0"
    assert rows[113]["is_video"] == "1"
    assert (a / "COMPLETE.synthetic-only").exists()
    reject(lambda: generate(a, rows=2, images=1, width=8, height=8, seed=1))
    reject(lambda: limits(150001, 0, 20, 20, 2))
    reject(lambda: limits(100, 2, 5000, 20, 2))
    reject(lambda: limits(16, 16, 3840, 2160, 2))
    reject(lambda: limits(100, 101, 64, 64, 2))
    linked = root / "linked"
    linked.symlink_to(a, target_is_directory=True)
    reject(lambda: generate(linked, rows=1, images=1, width=8, height=8, seed=1))
    share = root / "DCIM"
    share.mkdir()
    reject(lambda: generate(share/"bad", rows=1, images=1, width=8, height=8, seed=1))
    assert not (share/"bad").exists()

    # 150k metadata-only records are feasible without allocating pixel data.
    mass = generate(root/"mass", rows=150000, images=0, width=32, height=24, seed=41)
    assert mass["catalogue_rows"] == 150000 and mass["pngs"] == 0
    assert (root/"mass"/"catalogue.csv").stat().st_size > 10000000

print("PASS: 150k synthetic metadata, PNG pixel/CRC, byte-identical repeat, privacy, bounds and no overwrite")
