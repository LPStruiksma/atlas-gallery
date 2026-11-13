#!/usr/bin/env python3
"""Invented fixture tampering scenarios; no real device or media."""
import csv
import hashlib
from pathlib import Path
import shutil
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).parent))
from generate_viewer_fixtures import generate
from verify_viewer_fixtures import verify


def deny(fn):
    try:
        fn()
    except (ValueError, OSError):
        return
    raise AssertionError("Tampered synthetic corpus accepted")


with tempfile.TemporaryDirectory() as tmp:
    d = Path(tmp)
    small = d / "small"
    generate(small, rows=140, images=6, width=32, height=24, seed=14)
    before = {p.name: p.read_bytes() for p in small.iterdir()}
    expected = hashlib.sha256(before["manifest.json"]).hexdigest()
    result = verify(small, expected_manifest_sha256=expected)
    assert result["fixture_verified"] and result["synthetic_only"]
    assert not result["real_device_benchmark"] and not result["release_go"]
    assert result["catalogue_rows"] == 140 and result["materialized_pngs"] == 6
    assert before == {p.name: p.read_bytes() for p in small.iterdir()}
    deny(lambda: verify(small, expected_manifest_sha256="f"*64))

    image = small / "synthetic-000001.png"
    image.write_bytes(image.read_bytes() + b"tampered")
    deny(lambda: verify(small))
    image.write_bytes(before[image.name])

    catalog = small / "catalogue.csv"
    catalog.write_bytes(before[catalog.name] + b"150,synthetic://extra\n")
    deny(lambda: verify(small))
    catalog.write_bytes(before[catalog.name])

    marker = small/"COMPLETE.synthetic-only"
    marker.unlink()
    deny(lambda: verify(small))
    marker.write_bytes(before[marker.name])

    unexpected = small / "personal-photo.jpg"
    unexpected.write_bytes(b"not a real picture")
    deny(lambda: verify(small))
    unexpected.unlink()

    # Existing regular member replaced by an external symlink.
    image.unlink()
    image.symlink_to(catalog)
    deny(lambda: verify(small))
    image.unlink()
    image.write_bytes(before[image.name])
    assert verify(small)["fixture_verified"]

    link = d/"linked"
    link.symlink_to(small, target_is_directory=True)
    deny(lambda: verify(link))

    # Large-catalogue metadata-only fixture is genuinely enumerated and
    # proven to contain exactly 150,000 contiguous synthetic rows.
    mass = d/"mass"
    generate(mass, rows=150000, images=0, width=16, height=16, seed=1)
    checked = verify(mass)
    assert checked["catalogue_rows"] == 150000 and checked["materialized_pngs"] == 0

print("PASS: read-only synthetic catalogue/PNG hashes, 150k contiguous rows, completeness and tamper refusal")
