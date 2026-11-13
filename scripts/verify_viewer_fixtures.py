#!/usr/bin/env python3
"""Independently verify an OFFLINE synthetic ATLAS PNG/catalogue corpus.

Strictly read-only. Does not access MediaStore, adb, phone files or the web.
Never echoes corpus paths, media identifiers or untrusted file contents.
"""
import argparse
import csv
import json
from pathlib import Path
import re
import struct

from generate_viewer_fixtures import PNG, limits, sha

HEX = re.compile(r"^[0-9a-fA-F]{64}$")
FIELDS = ("media_id", "synthetic_uri", "relative_path", "taken_at_ms",
          "size_bytes", "is_video", "materialized")


def _regular(path, *, max_bytes):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > max_bytes:
        raise ValueError("Invalid or oversized synthetic corpus member")


def verify(root: Path, *, expected_manifest_sha256: str | None = None) -> dict:
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Synthetic corpus directory is missing or linked")
    complete = root/"COMPLETE.synthetic-only"
    manifest_file, csv_file = root/"manifest.json", root/"catalogue.csv"
    _regular(complete, max_bytes=1024)
    if complete.read_bytes() != b"Inert synthetic metadata and invented PNGs only\n":
        raise ValueError("Synthetic corpus completion marker invalid")
    _regular(manifest_file, max_bytes=1024*1024)
    digest = sha(manifest_file)
    if expected_manifest_sha256 is not None:
        if not HEX.fullmatch(expected_manifest_sha256) or digest != expected_manifest_sha256.lower():
            raise ValueError("Synthetic manifest independent SHA-256 mismatch")
    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) or manifest.get("fixture_version") != 1 or manifest.get("synthetic_only") is not True:
        raise ValueError("Unsupported synthetic fixture manifest")
    rows, count, width, height, seed = (
        manifest.get("catalogue_rows"), manifest.get("materialized_pngs"),
        manifest.get("width"), manifest.get("height"), manifest.get("seed"))
    if any(type(v) is not int for v in (rows, count, width, height, seed)):
        raise ValueError("Invalid synthetic fixture dimensions")
    if limits(rows, count, width, height, seed) != manifest.get("decoded_rgb_budget_bytes"):
        raise ValueError("Decoded synthetic data budget mismatch")
    images = manifest.get("images")
    if not isinstance(images, list) or len(images) != count:
        raise ValueError("Synthetic image manifest incomplete")
    expected_paths = {complete.name, manifest_file.name, csv_file.name}
    for i, entry in enumerate(images):
        if not isinstance(entry, dict) or set(entry) != {"file", "sha256", "size", "pattern"}:
            raise ValueError("Malformed synthetic image entry")
        name = f"synthetic-{i:06d}.png"
        if (entry["file"] != name or not HEX.fullmatch(str(entry["sha256"]))
                or entry["pattern"] != ("noise" if i % 2 else "bands")
                or type(entry["size"]) is not int or entry["size"] <= 0):
            raise ValueError("Unexpected synthetic image provenance")
        image = root/name
        _regular(image, max_bytes=40*1024*1024)
        if image.stat().st_size != entry["size"] or sha(image) != entry["sha256"].lower():
            raise ValueError("Synthetic PNG file differs from pinned manifest")
        with image.open("rb") as f:
            head = f.read(33)
        if (not head.startswith(PNG) or head[12:16] != b"IHDR"
                or len(head) != 33 or struct.unpack(">II", head[16:24]) != (width, height)
                or head[24:29] != b"\x08\x02\x00\x00\x00"):
            raise ValueError("Synthetic PNG header invalid")
        expected_paths.add(name)
    actual_paths = set()
    for item in root.iterdir():
        if item.is_symlink() or not item.is_file():
            raise ValueError("Unknown linked/directory fixture member")
        actual_paths.add(item.name)
    if actual_paths != expected_paths:
        raise ValueError("Synthetic corpus has missing or unexpected files")

    _regular(csv_file, max_bytes=48*1024*1024)
    if not HEX.fullmatch(str(manifest.get("catalogue_sha256"))) or sha(csv_file) != manifest["catalogue_sha256"].lower():
        raise ValueError("Synthetic catalogue checksum mismatch")
    with csv_file.open(encoding="utf-8", newline="") as f:
        records = csv.reader(f)
        if tuple(next(records, [])) != FIELDS:
            raise ValueError("Unexpected synthetic catalogue schema")
        actual_count = 0
        for i, record in enumerate(records):
            if i >= rows:
                raise ValueError("Synthetic catalogue has surplus rows")
            materialized = i < count
            video = not materialized and i % 113 == 0
            ext = "png" if materialized else ("mp4" if video else "jpg")
            name = f"synthetic-{i:06d}.{ext}"
            size = images[i]["size"] if materialized else 100000 + (i * 7919 + seed) % 24000000
            expected = (
                str(i), f"synthetic://atlas/{name}", "DCIM/ATLAS-SYNTHETIC/",
                str(1577836800000 + i*1379), str(size),
                str(int(video)), str(int(materialized))
            )
            if tuple(record) != expected:
                raise ValueError("Inconsistent synthetic catalogue row")
            actual_count += 1
    if actual_count != rows:
        raise ValueError("Synthetic catalogue is truncated")
    return {
        "fixture_verified": True, "synthetic_only": True, "catalogue_rows": rows,
        "materialized_pngs": count, "manifest_sha256": digest,
        "real_device_benchmark": False, "release_go": False,
        "note": "Read-only fixture integrity proof, not Android image rendering evidence"
    }


def main():
    parser = argparse.ArgumentParser(description="Read-only verification of complete synthetic viewer corpus")
    parser.add_argument("fixture_directory", type=Path)
    parser.add_argument("--expected-manifest-sha256")
    a = parser.parse_args()
    try:
        report = verify(a.fixture_directory, expected_manifest_sha256=a.expected_manifest_sha256)
    except (ValueError, OSError, KeyError, TypeError, UnicodeError, json.JSONDecodeError):
        parser.exit(2, "CORPUS NO-GO: missing, changed, incomplete or unsupported synthetic fixture\n")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
