#!/usr/bin/env python3
"""Consolidate an ALREADY-CAPTURED, offline native People SQLite set.

Never talks to Android, invokes run-as, copies a live phone database, exports
identities, changes originals or authorizes an APK replacement/uninstall.
The caller must first capture the main DB and any WAL/SHM files consistently
with all writers stopped. A checksum of a copied DB alone does not prove this.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import sqlite3
import stat
import tempfile
from urllib.parse import quote

from validate_native_people_backup import inspect_snapshot


def _copy_stable(source: Path, dest: Path) -> None:
    if source.is_symlink() or not source.is_file():
        raise ValueError("Capture file missing or is a symbolic link")
    before = source.stat()
    if not stat.S_ISREG(before.st_mode):
        raise ValueError("Capture member is not a regular file")
    with source.open("rb") as inp, dest.open("xb") as out:
        shutil.copyfileobj(inp, out, length=1024 * 1024)
    after = source.stat()
    if (before.st_ino, before.st_size, before.st_mtime_ns) != (
        after.st_ino, after.st_size, after.st_mtime_ns
    ):
        raise ValueError("Capture changed during copying; stop writers and recapture")
    dest.chmod(0o600)


def consolidate_offline_capture(
    source: Path, destination: Path, *, min_rows: int = 1,
    offline_capture_acknowledged: bool = False
) -> dict:
    """Make one standalone output file without touching the offline source.

    The input folder must already hold a coherent snapshot. This cannot
    determine whether a phone process was stopped when it was obtained.
    """
    if not offline_capture_acknowledged:
        raise ValueError("Explicit offline-capture acknowledgment required")
    if min_rows < 1:
        raise ValueError("A positive independently known row minimum is required")
    if source.resolve() == destination.resolve():
        raise ValueError("Source and destination must differ")
    if destination.exists() or destination.is_symlink():
        raise ValueError("Destination already exists; refusing overwrite")
    if not destination.parent.is_dir() or destination.parent.is_symlink():
        raise ValueError("Use an existing real private output directory")
    if stat.S_IMODE(destination.parent.stat().st_mode) & 0o077:
        raise ValueError("Output directory is accessible to other users")
    if not source.is_file() or source.stat().st_size < 100:
        raise ValueError("Offline capture missing or implausibly small")
    if source.is_symlink():
        raise ValueError("Do not follow a symbolic link to private capture")
    if Path(str(source) + "-journal").exists():
        raise ValueError("Rollback journal present; coherent offline capture not established")
    wal = Path(str(source) + "-wal")
    shm = Path(str(source) + "-shm")
    if shm.exists() and not wal.exists():
        raise ValueError("Orphan SQLite SHM file; recapture the full stopped database set")

    # Stage *copies* together on the same filesystem. The WAL reader may update
    # its SHM index, so never open the actual offline source in SQLite.
    with tempfile.TemporaryDirectory(prefix=".atlas-native-recovery-", dir=destination.parent) as tmp:
        tmpdir = Path(tmp)
        staged = tmpdir / "atlas_native_people.db"
        _copy_stable(source, staged)
        if wal.exists():
            _copy_stable(wal, Path(str(staged) + "-wal"))
        if shm.exists():
            _copy_stable(shm, Path(str(staged) + "-shm"))

        backup = tmpdir / "standalone.db"
        uri = "file:" + quote(str(staged.resolve()), safe="/") + "?mode=ro"
        try:
            source_db = sqlite3.connect(uri, uri=True, timeout=3)
            target_db = sqlite3.connect(backup)
            try:
                source_db.backup(target_db, pages=100, sleep=0.05)
            finally:
                target_db.close()
                source_db.close()
            # Ensure the output uses a standalone rollback-journal format.
            normalized = sqlite3.connect(backup)
            try:
                normalized.execute("PRAGMA journal_mode=DELETE")
            finally:
                normalized.close()
            details = inspect_snapshot(backup, min_rows=min_rows)
        except sqlite3.DatabaseError:
            raise ValueError("Offline capture could not be restored consistently") from None
        backup.chmod(0o600)
        # Atomic create-if-absent with no TOCTOU overwrites, on the same FS.
        try:
            os.link(backup, destination)
        except FileExistsError:
            raise ValueError("Destination appeared during validation; refusing overwrite") from None

    return dict(
        valid=True, rows=details["rows"], people=details["people"],
        distinct_image_paths=details["distinct_image_paths"],
        sha256=details["sha256"], bytes=details["bytes"],
        note="Standalone offline copy validated; provenance, completeness and phone migration remain unproven"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert stopped/offline ATLAS native People SQLite+WAL capture into one private verified file")
    parser.add_argument("captured_db", type=Path)
    parser.add_argument("private_output_db", type=Path)
    parser.add_argument("--min-rows", type=int, required=True)
    parser.add_argument("--confirm-offline-capture", action="store_true",
                        help="Affirm that these are already captured files from a stopped, non-writing source")
    args = parser.parse_args()
    try:
        report = consolidate_offline_capture(
            args.captured_db, args.private_output_db,
            min_rows=args.min_rows, offline_capture_acknowledged=args.confirm_offline_capture
        )
    except (ValueError, OSError, sqlite3.DatabaseError):
        # Never echo source paths, private identity rows or OS diagnostics.
        parser.exit(2, "BACKUP BLOCKED: offline consistency, permissions or schema could not be verified. Do not migrate.\n")
    print(json.dumps(report, indent=2))
    print("This does NOT certify on-phone collection, restorable settings/SAF grants or safe uninstall.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
