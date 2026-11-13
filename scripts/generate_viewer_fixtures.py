#!/usr/bin/env python3
"""Invented, deterministic offline filmstrip fixtures; never accesses a phone."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import struct
import zlib

MAX_ROWS = 150000
MAX_IMAGES = 128
PNG = b"\x89PNG\r\n\x1a\n"


def limits(rows, images, width, height, seed):
    if not (1 <= rows <= MAX_ROWS and 0 <= images <= min(rows, MAX_IMAGES)
            and 1 <= width <= 4096 and 1 <= height <= 4096
            and width * height <= 12000000 and 0 <= seed < 2**32):
        raise ValueError("Invalid fixture parameters")
    size = images * width * height * 3
    if size > 256 * 1024 * 1024:
        raise ValueError("Decoded synthetic image budget exceeds 256 MiB")
    return size


def private_create(path):
    flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    return os.fdopen(os.open(path, flags, 0o600), "wb")


def chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(
        ">I", zlib.crc32(kind + payload) & 0xffffffff)


def make_png(path, width, height, seed, index):
    with private_create(path) as out:
        out.write(PNG)
        out.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)))
        z = zlib.compressobj(level=3)
        for y in range(height):
            if index % 2:
                rgb = hashlib.shake_256(
                    f"ATLAS:{seed}:{index}:{y}".encode()).digest(width * 3)
            else:
                rgb = bytes(((seed + index * 37 + y // 19) % 256,
                             (seed // 7 + index * 17 + y // 37) % 256,
                             (seed // 11 + index * 61 + y // 29) % 256)) * width
            data = z.compress(b"\0" + rgb)
            if data:
                out.write(chunk(b"IDAT", data))
        remaining = z.flush()
        if remaining:
            out.write(chunk(b"IDAT", remaining))
        out.write(chunk(b"IEND", b""))


def sha(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(65536), b""):
            h.update(data)
    return h.hexdigest()


def generate(root, rows=1000, images=64, width=320, height=240, seed=83):
    budget = limits(rows, images, width, height, seed)
    parts = [part.lower() for part in root.absolute().parts]
    if "dcim" in parts or ("storage" in parts and "emulated" in parts):
        raise ValueError("Do not create fixtures inside shared-media directories")
    if root.exists() or root.is_symlink() or not root.parent.is_dir() or root.parent.is_symlink():
        raise ValueError("Output must be an absent directory in a regular parent")
    root.mkdir(mode=0o700)
    made = []
    for i in range(images):
        name = f"synthetic-{i:06d}.png"
        target = root / name
        make_png(target, width, height, seed, i)
        made.append({"file": name, "sha256": sha(target), "size": target.stat().st_size,
                     "pattern": "noise" if i % 2 else "bands"})
    import io
    catalog = root / "catalogue.csv"
    with private_create(catalog) as raw, io.TextIOWrapper(raw, newline="", encoding="utf-8") as output:
        writer = csv.writer(output, lineterminator="\n")
        writer.writerow(("media_id", "synthetic_uri", "relative_path", "taken_at_ms",
                         "size_bytes", "is_video", "materialized"))
        for i in range(rows):
            exists = i < images
            video = not exists and i % 113 == 0
            suffix = "png" if exists else ("mp4" if video else "jpg")
            name = f"synthetic-{i:06d}.{suffix}"
            size = made[i]["size"] if exists else 100000 + (i * 7919 + seed) % 24000000
            writer.writerow((i, "synthetic://atlas/" + name, "DCIM/ATLAS-SYNTHETIC/",
                             1577836800000 + i * 1379, size, int(video), int(exists)))
    manifest = {"fixture_version": 1, "synthetic_only": True,
                "seed": seed, "catalogue_rows": rows, "materialized_pngs": images,
                "width": width, "height": height, "decoded_rgb_budget_bytes": budget,
                "catalogue_sha256": sha(catalog), "images": made}
    with private_create(root / "manifest.json") as out:
        out.write((json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode())
    with private_create(root / "COMPLETE.synthetic-only") as out:
        out.write(b"Inert synthetic metadata and invented PNGs only\n")
    return {"catalogue_rows": rows, "pngs": images, "catalogue_sha256": manifest["catalogue_sha256"],
            "synthetic_only": True, "physical_device_measured": False}


def main():
    ap = argparse.ArgumentParser(description="Create bounded offline synthetic gallery corpus")
    ap.add_argument("output", type=Path)
    ap.add_argument("--catalogue", type=int, default=1000)
    ap.add_argument("--images", type=int, default=64)
    ap.add_argument("--width", type=int, default=320)
    ap.add_argument("--height", type=int, default=240)
    ap.add_argument("--seed", type=int, default=83)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    try:
        budget = limits(args.catalogue, args.images, args.width, args.height, args.seed)
        if args.dry_run:
            report = {"synthetic_only": True, "dry_run": True,
                      "rows": args.catalogue, "pngs": args.images,
                      "decoded_rgb_budget_bytes": budget}
        else:
            report = generate(args.output, rows=args.catalogue, images=args.images,
                              width=args.width, height=args.height, seed=args.seed)
    except (ValueError, OSError):
        ap.exit(2, "SYNTHETIC CORPUS BLOCKED: invalid parameters or unsafe output\n")
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
