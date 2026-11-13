#!/usr/bin/env python3
"""Construct an offline, reproducible candidate roster from frozen opaque lists.

Inputs are separately collected probe IDs and eligible gallery identity IDs,
never model-ranked score CSVs. No photos, biometrics, models, network or
People database are used. Matching hashes verify file identity, NOT that
the supplied gallery is independent, complete or legally usable.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys

PROBE_FIELDS = ("split", "probe_uid")
GALLERY_FIELDS = ("split", "candidate_uid")
ROSTER_FIELDS = ("split", "probe_uid", "candidate_uid")
SPLITS = ("calibration", "holdout")
TOKEN = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
HASH = re.compile(r"[0-9a-f]{64}\Z")
MAX_INPUT_BYTES = 4 * 1024 * 1024
MAX_PROBES = 10000
MAX_GALLERY_CANDIDATES = 2000
MAX_ROSTER_ROWS = 100000
RESERVED = frozenset({"NONE", "UNKNOWN", "UNREVIEWED"})


def _check_token(token: object) -> bool:
    return (isinstance(token, str) and TOKEN.fullmatch(token) is not None
            and token not in RESERVED)


def _validated_rows(rows, fields):
    if not isinstance(rows, (list, tuple)):
        raise ValueError("Expected bounded opaque manifest rows")
    for row in rows:
        if not isinstance(row, dict) or set(row) != set(fields):
            raise ValueError("Unexpected manifest columns")
        if (row["split"] not in SPLITS or
                not _check_token(row[fields[1]])):
            raise ValueError("Malformed opaque manifest token")


def build_frozen_roster(probe_rows, gallery_rows) -> tuple[list[dict], dict]:
    """Cartesian eligible gallery per split, *never* ranked by any model.

    This intentionally cannot express per-probe eligibility exclusions or
    different historical gallery versions. Reject unsuitable studies rather
    than using a supposedly exhaustive roster with silent filters.
    """
    if (not isinstance(probe_rows, (list, tuple))
            or not 2 <= len(probe_rows) <= MAX_PROBES
            or not isinstance(gallery_rows, (list, tuple))
            or len(gallery_rows) > MAX_GALLERY_CANDIDATES):
        raise ValueError("Unbounded probe/gallery manifests")
    _validated_rows(probe_rows, PROBE_FIELDS)
    _validated_rows(gallery_rows, GALLERY_FIELDS)
    probes = {s: set() for s in SPLITS}
    galleries = {s: set() for s in SPLITS}
    global_probes, global_candidates = set(), set()
    for row in probe_rows:
        uid, split = row["probe_uid"], row["split"]
        if uid in global_probes:
            raise ValueError("Duplicate or cross-split probe identifier")
        global_probes.add(uid)
        probes[split].add(uid)
    for row in gallery_rows:
        uid, split = row["candidate_uid"], row["split"]
        if uid in global_candidates:
            raise ValueError("Duplicate or cross-split gallery identity")
        global_candidates.add(uid)
        galleries[split].add(uid)
    if any(not probes[split] for split in SPLITS):
        raise ValueError("Both study partitions need explicit probes")
    if sum(len(probes[s]) * max(1, len(galleries[s])) for s in SPLITS) > MAX_ROSTER_ROWS:
        raise ValueError("Candidate roster exceeds bounded row budget")
    rows = [
        {"split": split, "probe_uid": probe, "candidate_uid": candidate}
        for split in SPLITS
        for probe in sorted(probes[split])
        for candidate in (sorted(galleries[split]) or ["NONE"])
    ]
    aggregate = {
        split: {
            "probe_count": len(probes[split]),
            "eligible_gallery_identities": len(galleries[split]),
            "roster_rows": len(probes[split]) * max(1, len(galleries[split])),
            "no_candidate_probes": len(probes[split]) if not galleries[split] else 0,
        } for split in SPLITS
    }
    return rows, {
        "status": "deterministic_opaque_roster_built",
        "source_manifests_are_independently_verified": False,
        "gallery_exhaustiveness_proven": False,
        "per_probe_eligibility_verified": False,
        "auto_confirm_allowed": False,
        "by_split": aggregate,
    }


def canonical_csv_bytes(rows: list[dict]) -> bytes:
    out = io.StringIO(newline="")
    writer = csv.DictWriter(out, fieldnames=ROSTER_FIELDS, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return out.getvalue().encode("utf-8")


def read_pinned_csv(path: Path, expected_hash: str, fields: tuple[str, str]):
    """Bounded one-file read; refuse symlinks, unpinned CSVs and extra columns."""
    if not isinstance(expected_hash, str) or not HASH.fullmatch(expected_hash):
        raise ValueError("Full lowercase SHA256 pin required")
    if path.is_symlink() or not path.is_file():
        raise ValueError("Expected a regular non-symlink local CSV")
    if not 0 < path.stat().st_size <= MAX_INPUT_BYTES:
        raise ValueError("Manifest input outside file-size budget")
    with path.open("rb") as handle:
        raw = handle.read(MAX_INPUT_BYTES + 1)
    if (not 0 < len(raw) <= MAX_INPUT_BYTES
            or hashlib.sha256(raw).hexdigest() != expected_hash):
        raise ValueError("Manifest bytes do not match SHA256 pin")
    try:
        text = raw.decode("utf-8-sig")
        reader = csv.DictReader(io.StringIO(text, newline=""), strict=True)
        if reader.fieldnames != list(fields):
            raise ValueError("Unexpected CSV header")
        rows = list(reader)
        _validated_rows(rows, fields)
    except (UnicodeError, csv.Error) as exc:
        raise ValueError("Unsafe or invalid CSV input") from None
    return rows


def _safe_create(path: Path, data: bytes) -> None:
    """Create restricted 0600 output, never follow/overwrite user files."""
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probes", required=True, type=Path)
    parser.add_argument("--probes-sha256", required=True)
    parser.add_argument("--gallery", required=True, type=Path)
    parser.add_argument("--gallery-sha256", required=True)
    parser.add_argument("--out", type=Path,
                        help="Optional restricted 0600 CSV, refusing overwrite")
    args = parser.parse_args(argv)
    try:
        probes = read_pinned_csv(args.probes, args.probes_sha256, PROBE_FIELDS)
        gallery = read_pinned_csv(args.gallery, args.gallery_sha256, GALLERY_FIELDS)
        rows, result = build_frozen_roster(probes, gallery)
        encoded = canonical_csv_bytes(rows)
        result["canonical_roster_sha256"] = hashlib.sha256(encoded).hexdigest()
        result["csv_written"] = False
        if args.out is not None:
            _safe_create(args.out, encoded)
            result["csv_written"] = True
        print(json.dumps(result, sort_keys=True, indent=2))
        return 0
    except (OSError, ValueError, TypeError):
        # Avoid any local file path, filename or opaque ID in logs.
        print("Frozen roster creation refused; input or output safety gate failed",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
