#!/usr/bin/env python3
"""Fail-closed 1080x2340 parity matrix assembled from actual UI-evidence PNGs.

No screenshot is generated or drawn here. The source images have been produced by
instrumented tests on synthetic fixtures, pulled from the emulator, and already
checked against scripts/ui-evidence.d/*.txt by ci_ui_evidence.sh.
"""
import csv
import hashlib
import re
import shutil
import struct
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONTRACT = ROOT / "scripts" / "parity-matrix.tsv"
SURFACES = (
    "pictures-default", "pictures-pinch-compact", "pictures-pinch-large",
    "pictures-original-ratio", "pictures-header-away", "albums-overview",
    "albums-detail", "people-overview", "people-named", "people-hidden",
    "viewer-photo", "viewer-details-people", "viewer-filmstrip",
    "search-person", "selection-more", "photo-dna", "declutter",
    "settings", "search-overview", "dialog-photo-details",
)
THEMES = ("light", "dark")
COLUMNS = ("surface", "theme", "required", "source_png", "source_test", "reason")
PNG_SIGNATURE = bytes.fromhex("89504e470d0a1a0a")
NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]*[.]png$")


def fail(message):
    raise ValueError("Parity matrix: " + message)


def read_contract():
    with CONTRACT.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        if reader.fieldnames != list(COLUMNS):
            fail("Unexpected contract columns")
        rows = list(reader)
    wanted = {(surface, theme) for surface in SURFACES for theme in THEMES}
    found = set()
    for row in rows:
        if None in row or any(v is None for v in row.values()):
            fail("Malformed TSV row")
        key = (row["surface"], row["theme"])
        if key not in wanted or key in found:
            fail("Unknown or duplicated surface/theme: " + repr(key))
        found.add(key)
        if row["required"] not in ("yes", "no"):
            fail("required must be yes/no: " + repr(key))
        if not row["source_test"].strip():
            fail("Missing source test for " + repr(key))
        if row["source_png"]:
            if not NAME.fullmatch(row["source_png"]):
                fail("Invalid source PNG name for " + repr(key))
            if row["reason"]:
                fail("Captured source cannot have a NOT_CAPTURED reason: " + repr(key))
        else:
            if row["required"] == "yes":
                fail("Mandatory surface has no screenshot source: " + repr(key))
            if not row["reason"].strip():
                fail("Missing NOT_CAPTURED reason for " + repr(key))
    if found != wanted or len(rows) != len(wanted):
        fail("Missing surface/theme rows: " + repr(sorted(wanted - found)))
    return rows


def png_digest(path):
    if not path.is_file():
        fail("Missing required source PNG " + path.name)
    raw = path.read_bytes()
    if (len(raw) <= 1024 or raw[:8] != PNG_SIGNATURE or
            raw[12:16] != b"IHDR"):
        fail("Not a real PNG " + path.name)
    width, height = struct.unpack(">II", raw[16:24])
    if (width, height) != (1080, 2340):
        fail(f"Wrong resolution in {path.name}: {width}x{height}")
    return hashlib.sha256(raw).hexdigest()


def build(evidence):
    rows = read_contract()
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT,
                                  text=True).strip()
    if not re.fullmatch("[0-9a-f]{40}", sha):
        fail("Cannot bind matrix to exact commit")
    prepared = []
    for row in rows:
        alias = "atlas-parity-" + row["surface"] + "-" + row["theme"] + "-1080x2340.png"
        source = evidence / row["source_png"] if row["source_png"] else None
        digest = png_digest(source) if source else ""
        prepared.append((row, alias, source, digest))
    dest = evidence / "parity-matrix"
    dest.mkdir(exist_ok=True)
    expected = {alias for _, alias, source, _ in prepared if source}
    extras = {p.name for p in dest.glob("atlas-parity-*.png")} - expected
    if extras:
        fail("Unexpected/stale parity aliases: " + repr(sorted(extras)))
    manifest = []
    captured = 0
    for row, alias, source, digest in prepared:
        if source:
            dst = dest / alias
            shutil.copyfile(source, dst)
            if hashlib.sha256(dst.read_bytes()).hexdigest() != digest:
                fail("Alias differs from verified source: " + alias)
            status = "CAPTURED"
            captured += 1
            expected_png = "parity-matrix/" + alias
        else:
            if (dest / alias).exists():
                fail("NOT_CAPTURED row has image: " + alias)
            status = "NOT_CAPTURED"
            expected_png = ""
        manifest.append({
            "commit_sha": sha, "source_test": row["source_test"],
            "surface": row["surface"], "theme": row["theme"],
            "width": "1080", "height": "2340",
            "fixture": "synthetic-only",
            "expected_png": expected_png, "source_png": row["source_png"],
            "sha256": digest, "status": status, "reason": row["reason"],
        })
    columns = ("commit_sha", "source_test", "surface", "theme",
               "width", "height", "fixture", "expected_png",
               "source_png", "sha256", "status", "reason")
    with (dest / "manifest.tsv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, delimiter="\t",
                                lineterminator="\n")
        writer.writeheader()
        writer.writerows(manifest)
    not_captured = len(manifest) - captured
    (dest / "coverage.txt").write_text(
        f"commit={sha}\ncaptured={captured}/{len(manifest)}\n"
        f"not_captured={not_captured}\n"
        "Only CAPTURED rows have real PNGs copied verbatim from emulator evidence.\n"
        "NOT_CAPTURED rows are explicit gaps, not screenshots.\n", encoding="utf-8")
    print(f"Parity matrix {sha}: {captured}/{len(manifest)} real PNGs, "
          f"{not_captured} explicitly NOT_CAPTURED, {len(SURFACES)} surfaces x 2 themes")
    if not_captured:
        print(f"::warning::Parity evidence is incomplete: {not_captured} surface/theme "
              "entries have no proven PNG (see parity-matrix/manifest.tsv).")


if __name__ == "__main__":
    try:
        if len(sys.argv) > 2:
            fail("Usage: verify_parity_matrix.py [ui-evidence-directory]")
        build(Path(sys.argv[1] if len(sys.argv) == 2 else "ui-evidence").resolve())
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        print("::error::" + str(exc), file=sys.stderr)
        sys.exit(1)
