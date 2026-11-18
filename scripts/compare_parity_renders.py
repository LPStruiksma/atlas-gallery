#!/usr/bin/env python3
"""Compare real, synthetic baseline/candidate PNG evidence without dependencies.

This tool validates evidence; it never captures or manufactures screenshots.  A
successful result means every captured pair is pixel-identical, not that missing
(`NOT_CAPTURED`) pairs have been proved visually equivalent.
"""
import argparse
import csv
import hashlib
import struct
import sys
import zlib
from pathlib import Path

SIGNATURE = b"\x89PNG\r\n\x1a\n"
MANIFEST_FIELDS = {"surface", "theme", "status", "commit_sha", "fixture", "png", "sha256", "reason"}


def fail(message):
    raise ValueError(message)


def read_manifest(directory, expected_sha):
    path = directory / "manifest.tsv"
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        if not reader.fieldnames or set(reader.fieldnames) != MANIFEST_FIELDS:
            fail(f"{path}: manifest columns must be exactly {sorted(MANIFEST_FIELDS)}")
        rows = {}
        for number, row in enumerate(reader, 2):
            key = (row["surface"], row["theme"])
            if not all(key) or key in rows:
                fail(f"{path}:{number}: empty or duplicate surface/theme")
            if row["theme"] not in {"light", "dark"}:
                fail(f"{path}:{number}: theme must be light or dark")
            if row["commit_sha"] != expected_sha:
                fail(f"{path}:{number}: commit SHA does not match requested revision")
            if row["fixture"] != "synthetic":
                fail(f"{path}:{number}: only synthetic fixtures are accepted")
            if row["status"] == "CAPTURED":
                if not row["png"] or len(row["sha256"]) != 64 or row["reason"]:
                    fail(f"{path}:{number}: malformed CAPTURED row")
            elif row["status"] == "NOT_CAPTURED":
                if row["png"] or row["sha256"] or not row["reason"]:
                    fail(f"{path}:{number}: NOT_CAPTURED requires a reason and no image")
            else:
                fail(f"{path}:{number}: unknown status {row['status']!r}")
            rows[key] = row
    if not rows:
        fail(f"{path}: empty manifest")
    return rows


def png_pixels(path, digest):
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != digest:
        fail(f"{path}: SHA-256 mismatch")
    if not raw.startswith(SIGNATURE):
        fail(f"{path}: bad PNG signature")
    offset, header, compressed, ended = len(SIGNATURE), None, bytearray(), False
    while offset < len(raw):
        if offset + 12 > len(raw): fail(f"{path}: truncated PNG chunk")
        size = struct.unpack(">I", raw[offset:offset + 4])[0]
        kind = raw[offset + 4:offset + 8]
        end = offset + 12 + size
        if end > len(raw): fail(f"{path}: truncated PNG payload")
        payload, crc = raw[offset + 8:offset + 8 + size], raw[offset + 8 + size:end]
        if struct.unpack(">I", crc)[0] != zlib.crc32(kind + payload) & 0xffffffff:
            fail(f"{path}: bad PNG CRC")
        if kind == b"IHDR": header = payload
        elif kind == b"IDAT": compressed.extend(payload)
        elif kind == b"IEND": ended = True
        offset = end
    if not ended or header is None or len(header) != 13:
        fail(f"{path}: incomplete PNG")
    width, height, depth, colour, compression, filtering, interlace = struct.unpack(">IIBBBBB", header)
    channels = {2: 3, 6: 4}.get(colour)
    if not channels or (depth, compression, filtering, interlace) != (8, 0, 0, 0):
        fail(f"{path}: requires non-interlaced 8-bit RGB or RGBA PNG")
    try: scan = zlib.decompress(bytes(compressed))
    except zlib.error as error: fail(f"{path}: invalid compressed pixels: {error}")
    stride, bpp, pos, previous, pixels = width * channels, channels, 0, bytearray(width * channels), bytearray()
    for _ in range(height):
        if pos + stride + 1 > len(scan): fail(f"{path}: truncated scanlines")
        mode, source = scan[pos], scan[pos + 1:pos + 1 + stride]
        pos += stride + 1
        row = bytearray(stride)
        for i, value in enumerate(source):
            left = row[i - bpp] if i >= bpp else 0
            up = previous[i]
            upper_left = previous[i - bpp] if i >= bpp else 0
            if mode == 0: predictor = 0
            elif mode == 1: predictor = left
            elif mode == 2: predictor = up
            elif mode == 3: predictor = (left + up) // 2
            elif mode == 4:
                p = left + up - upper_left
                distances = (abs(p - left), abs(p - up), abs(p - upper_left))
                predictor = (left, up, upper_left)[distances.index(min(distances))]
            else: fail(f"{path}: unknown PNG filter {mode}")
            row[i] = (value + predictor) & 255
        pixels.extend(row); previous = row
    if pos != len(scan): fail(f"{path}: trailing decompressed bytes")
    return width, height, channels, bytes(pixels)


def compare(baseline_dir, candidate_dir, baseline_sha, candidate_sha, output):
    baseline = read_manifest(baseline_dir, baseline_sha)
    candidate = read_manifest(candidate_dir, candidate_sha)
    if baseline.keys() != candidate.keys(): fail("baseline and candidate manifest pairs differ")
    report, changed_total = [], 0
    for key in sorted(baseline):
        before, after = baseline[key], candidate[key]
        if before["status"] != after["status"]:
            fail(f"{key}: asymmetric capture status")
        if before["status"] == "NOT_CAPTURED":
            report.append((*key, "NOT_CAPTURED", "", before["reason"] + " | " + after["reason"]))
            continue
        left = png_pixels(baseline_dir / before["png"], before["sha256"])
        right = png_pixels(candidate_dir / after["png"], after["sha256"])
        if left[:3] != right[:3]: fail(f"{key}: image dimensions or colour format differ")
        channels = left[2]
        changed = sum(left[3][i:i + channels] != right[3][i:i + channels]
                      for i in range(0, len(left[3]), channels))
        changed_total += changed
        report.append((*key, "IDENTICAL" if not changed else "DIFFERENT", str(changed), ""))
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(("surface", "theme", "result", "changed_pixels", "reason")); writer.writerows(report)
    if changed_total: fail(f"pixel comparison failed: {changed_total} changed pixels")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True); parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--baseline-sha", required=True); parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    for value in (args.baseline_sha, args.candidate_sha):
        if len(value) != 40 or any(c not in "0123456789abcdef" for c in value): parser.error("SHAs must be 40 lowercase hex characters")
    try: rows = compare(args.baseline, args.candidate, args.baseline_sha, args.candidate_sha, args.output)
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr); return 1
    print(f"PASS: {sum(r[2] == 'IDENTICAL' for r in rows)} identical pairs; {sum(r[2] == 'NOT_CAPTURED' for r in rows)} gaps")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
