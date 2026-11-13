#!/usr/bin/env python3
"""One read-only, fail-closed OFFLINE ATLAS native People/settings evidence gate.

Consumes TWO previously obtained owner-controlled local snapshot trees. This
script NEVER touches an Android device, creates backups, exports labels,
installs APKs or authorizes data clearing/uninstall. Counts are not provenance.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sqlite3

from audit_native_restore_inventory import audit, inventory
from validate_native_people_backup import inspect_snapshot
from verify_native_restore_evidence import DB, verify

DIGEST = re.compile(r"^[0-9a-fA-F]{64}$")


def check_bundle(
    backup: Path, restored: Path, *,
    expected_rows: int, expected_settings_files: int,
    expected_backup_sha256: str
) -> dict:
    if (type(expected_rows) is not int or expected_rows < 1 or
            type(expected_settings_files) is not int or expected_settings_files < 1):
        raise ValueError("Independently recorded People/settings counts required")
    if not isinstance(expected_backup_sha256, str) or not DIGEST.fullmatch(expected_backup_sha256):
        raise ValueError("Independent People DB SHA-256 must have 64 hex digits")
    # Check the entire supported private file inventory FIRST. Two equal copies
    # of an unsupported new private settings file are still not covered.
    audited = audit(backup, restored)
    if audited["settings_file_count"] != expected_settings_files:
        raise ValueError("Known settings inventory differs from independent observation")
    # Keep fingerprints of EVERY known private snapshot member across the
    # logical comparison, not only the source database. Otherwise a restored
    # database or settings file modified late could falsely pass.
    first_backup, first_restored = inventory(backup), inventory(restored)
    if first_backup.keys() != first_restored.keys():
        raise ValueError("Private member inventory changed during validation")
    if any(first_backup[k] != first_restored[k] for k in first_backup if k != DB.as_posix()):
        raise ValueError("Private settings differ during validation")
    # A hash checked against itself is meaningless. The owner must obtain the
    # expected SHA from an independently recorded, protected capture manifest.
    origin = inspect_snapshot(backup / DB, min_rows=expected_rows)
    if origin["rows"] != expected_rows or origin["sha256"] != expected_backup_sha256.lower():
        raise ValueError("People count or independent checksum does not match")
    # Pin the independent restored SQLite fingerprint too. Equality of
    # logical rows at one instant is not proof the restored copy stayed still.
    restored_initial = inspect_snapshot(restored / DB, min_rows=expected_rows)
    logical = verify(backup, restored, min_rows=expected_rows)
    if logical["people_rows"] != expected_rows or logical["settings_files_matched"] != expected_settings_files:
        raise ValueError("Offline People/settings logical parity incomplete")
    # Recheck all recognized private files (and unexpected new members),
    # both copies' SQLite integrity, exact row counts and both DB bytes after
    # the full logical check; any mid-audit mutation fails closed.
    last_backup, last_restored = inventory(backup), inventory(restored)
    if first_backup != last_backup or first_restored != last_restored:
        raise ValueError("An offline private file changed during combined recovery checks")
    final = inspect_snapshot(backup / DB, min_rows=expected_rows)
    restored_final = inspect_snapshot(restored / DB, min_rows=expected_rows)
    if (final["sha256"] != origin["sha256"] or final["rows"] != origin["rows"]
            or restored_final["sha256"] != restored_initial["sha256"]
            or restored_final["rows"] != restored_initial["rows"]
            or first_backup[DB.as_posix()] != final["sha256"]
            or first_restored[DB.as_posix()] != restored_final["sha256"]):
        raise ValueError("People source or restored SQLite changed during combined checks")
    return {
        "offline_bundle_consistency": True,
        "independently_supplied_counts_matched": True,
        "independently_supplied_people_checksum_matched": True,
        "people_rows": expected_rows,
        "settings_files": expected_settings_files,
        "strict_file_inventory_checked": True,
        "logical_people_and_settings_parity_checked": True,
        "both_snapshots_stable_during_checks": True,
        "phone_capture_origin_authenticated": False,
        "real_android_restore_verified": False,
        "saf_permissions_verified": False,
        "candidate_apk_signer_verified": False,
        "safe_to_uninstall": False,
        "release_go": False,
        "next_gate": "Authenticate phone-origin capture, real Android settings/grants restore, exact installed APK signer and owner choice."
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Combine offline People count, pinned checksum, private inventory and logical restore checks"
    )
    parser.add_argument("backup_tree", type=Path)
    parser.add_argument("restored_tree", type=Path)
    parser.add_argument("--expected-rows", type=int, required=True,
                        help="Exact independently recorded number of confirmed face assignments")
    parser.add_argument("--expected-settings-files", type=int, required=True,
                        help="Exact independently recorded count of protected settings files")
    parser.add_argument("--expected-backup-sha256", required=True,
                        help="Independently preserved SHA-256 of original standalone People DB")
    args = parser.parse_args()
    try:
        result = check_bundle(
            args.backup_tree, args.restored_tree,
            expected_rows=args.expected_rows,
            expected_settings_files=args.expected_settings_files,
            expected_backup_sha256=args.expected_backup_sha256
        )
    except (OSError, ValueError, sqlite3.DatabaseError):
        parser.exit(2, "OFFLINE NO-GO: People/settings recovery bundle integrity or independent count unverified\n")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
