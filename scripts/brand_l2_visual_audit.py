#!/usr/bin/env python3
"""Produce an inspectable intentional-difference audit of real BRAND-L2 screenshots.

Download ATLAS-android-UI-evidence from the EXACT candidate workflow; get the
baseline SHA from its shard-0 'Baseline commit:' log line and candidate SHA from
the PR head. Never infer a baseline from an unrelated old screenshot. This
tool checks 24 mandatory PNGs, their dimensions/digests and compares pixels;
changed pixels are expected for rebranding, not a passing parity assertion.
"""
import argparse
import csv
import hashlib
import shutil
import tempfile
from pathlib import Path

import compare_parity_renders as parity

SURFACES = ("photos", "people", "viewer")
THEMES = ("light", "dark")
SCALES = ("100", "200")
SHA_CHARS = set("0123456789abcdef")


def audit(evidence, baseline_sha, candidate_sha, output):
    for value in (baseline_sha, candidate_sha):
        if len(value) != 40 or any(c not in SHA_CHARS for c in value):
            raise ValueError("Expected 40-character lowercase Git SHAs")
    if baseline_sha == candidate_sha:
        raise ValueError("Baseline and candidate must be different commits")
    with tempfile.TemporaryDirectory() as temp:
        roots = {}
        for revision, sha in (("before", baseline_sha), ("after", candidate_sha)):
            folder = Path(temp) / revision
            folder.mkdir()
            manifest = []
            for scale in SCALES:
                for theme in THEMES:
                    for surface in SURFACES:
                        name = f"brand-l2-{revision}-{surface}-{theme}-{scale}.png"
                        src = evidence / name
                        raw = src.read_bytes()
                        digest = hashlib.sha256(raw).hexdigest()
                        width, height, _, _ = parity.png_pixels(src, digest)
                        if (width, height) != (1080, 2340):
                            raise ValueError(f"{name}: not S24+ 1080x2340 ({width}x{height})")
                        shutil.copyfile(src, folder / name)
                        manifest.append({
                            "surface": f"{surface}-{scale}", "theme": theme, "status": "CAPTURED",
                            "commit_sha": sha, "fixture": "synthetic", "png": name,
                            "sha256": digest, "reason": "",
                        })
            with (folder / "manifest.tsv").open("w", newline="", encoding="utf-8") as f:
                writer = csv.DictWriter(f, fieldnames=sorted(parity.MANIFEST_FIELDS), delimiter="\t")
                writer.writeheader()
                writer.writerows(manifest)
            roots[revision] = folder
        # compare() deliberately raises if any pixels differ; it writes the
        # full TSV first. Only THIS expected difference is not an audit failure.
        try:
            parity.compare(roots["before"], roots["after"], baseline_sha,
                           candidate_sha, output)
        except ValueError as exc:
            if not str(exc).startswith("pixel comparison failed: "):
                raise
    with output.open(newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f, delimiter="\t"))
    if len(rows) != 12 or any(r["result"] == "NOT_CAPTURED" for r in rows):
        raise ValueError("Expected twelve real before/after state pairs")
    if not any(r["result"] == "DIFFERENT" for r in rows):
        raise ValueError("No Lapis visual difference detected")
    print(f"BRAND-L2 real pixel audit: {len(rows)} pairs; " +
          str(sum(r["result"] == "DIFFERENT" for r in rows)) +
          " changed; inspect every changed PNG pair. Report: " + str(output))
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--baseline-sha", required=True)
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        audit(args.evidence, args.baseline_sha, args.candidate_sha, args.output)
    except (OSError, ValueError) as exc:
        parser.exit(1, "BRAND-L2 audit rejected: " + str(exc) + "\n")


if __name__ == "__main__":
    main()
