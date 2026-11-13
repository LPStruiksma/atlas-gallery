#!/usr/bin/env python3
"""Read-only offline People CSV roundtrip gate; NO phone or APK operations.

Requires a protected standalone native People DB and an already-exported CSV.
Streams exact assignments without printing personal names or media paths.
A passing file comparison is never permission to replace populated app data.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
from urllib.parse import quote

from export_native_people_recovery import HEADER
from validate_native_people_backup import inspect_snapshot

MAX_CSV_BYTES = 128 * 1024 * 1024
HEX = re.compile(r"^[0-9a-fA-F]{64}$")


def _stat_identity(info: os.stat_result) -> tuple:
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


@contextmanager
def _stable_csv_reader(path: Path, expected: tuple):
    """Bind CSV parsing to a verified no-follow FD, not a reopened pathname.

    The initial file digest and the later csv.reader must see the SAME inode.
    Replacements and ordinary mutations are rejected, including between
    initial hashing and text decoding.
    """
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    try:
        if _stat_identity(os.fstat(fd)) != expected or _identity(path) != expected:
            raise ValueError("Recovery CSV changed before decoding")
        with os.fdopen(fd, "r", encoding="utf-8", newline="", closefd=False) as stream:
            yield stream
        if _stat_identity(os.fstat(fd)) != expected or _identity(path) != expected:
            raise ValueError("Recovery CSV changed during decoding")
    finally:
        os.close(fd)


def _identity(path: Path) -> tuple:
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1
            or info.st_mode & 0o077 or path.is_symlink()
            or not 10 < info.st_size <= MAX_CSV_BYTES):
        raise ValueError("Recovery CSV is missing, linked, exposed or oversized")
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def _digest(path: Path) -> str:
    h = hashlib.sha256()
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    try:
        with os.fdopen(fd, "rb", closefd=False) as stream:
            for part in iter(lambda: stream.read(1024 * 1024), b""):
                h.update(part)
    finally:
        os.close(fd)
    return h.hexdigest()


def compare(db_path: Path, csv_path: Path, *, expected_rows: int,
            expected_db_sha256: str) -> dict:
    if type(expected_rows) is not int or expected_rows < 1:
        raise ValueError("Exact independent People row count required")
    if not isinstance(expected_db_sha256, str) or not HEX.fullmatch(expected_db_sha256):
        raise ValueError("Independent source SHA-256 must be 64 hexadecimal digits")
    if db_path.resolve() == csv_path.resolve():
        raise ValueError("Source People snapshot cannot be its own CSV")
    before_csv = _identity(csv_path)
    before_csv_digest = _digest(csv_path)
    validated = inspect_snapshot(db_path, min_rows=expected_rows)
    if (validated["rows"] != expected_rows
            or validated["sha256"] != expected_db_sha256.lower()):
        raise ValueError("People source differs from independently recorded receipt")
    uri = "file:" + quote(str(db_path.absolute()), safe="/") + "?mode=ro&immutable=1"
    with sqlite3.connect(uri, uri=True, timeout=2) as connection:
        cursor = connection.execute(
            "SELECT person_id, person_name, image_path, face_id "
            "FROM assignments ORDER BY face_id"
        )
        count = 0
        with _stable_csv_reader(csv_path, before_csv) as stream:
            records = csv.reader(stream, strict=True)
            if next(records, None) != HEADER:
                raise ValueError("Recovery CSV header not recognized")
            for record in records:
                row = cursor.fetchone()
                if row is None or len(record) != len(HEADER):
                    raise ValueError("Recovery CSV row count/shape differs from People snapshot")
                expected = [str(row[0]), row[1], row[2], str(row[3]), ""]
                if record != expected:
                    raise ValueError("Recovery CSV identity assignment differs from source")
                count += 1
            if cursor.fetchone() is not None or count != expected_rows:
                raise ValueError("Recovery CSV omits native People assignments")
    if _identity(csv_path) != before_csv or _digest(csv_path) != before_csv_digest:
        raise ValueError("Recovery CSV changed during audit")
    final = inspect_snapshot(db_path, min_rows=expected_rows)
    if final["sha256"] != validated["sha256"] or final["rows"] != validated["rows"]:
        raise ValueError("People source changed during CSV audit")
    return {
        "offline_csv_logically_lossless": True,
        "rows_compared": count,
        "confidence_fabricated": False,
        "csv_file_stable_during_check": True,
        "people_snapshot_stable_during_check": True,
        "csv_sha256": before_csv_digest,
        "phone_backup_provenance_verified": False,
        "destination_people_preserved": False,
        "android_import_tested": False,
        "safe_to_import_into_populated_app": False,
        "safe_to_uninstall": False,
        "release_go": False,
        "note": "Verified the provided CSV against a pinned source only; Native Import once replaces all existing assignments."
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="Privately compare an existing native People CSV with the complete pinned offline DB")
    ap.add_argument("source_db", type=Path)
    ap.add_argument("recovery_csv", type=Path)
    ap.add_argument("--expected-rows", type=int, required=True)
    ap.add_argument("--expected-db-sha256", required=True)
    args = ap.parse_args()
    try:
        result = compare(args.source_db, args.recovery_csv,
                         expected_rows=args.expected_rows,
                         expected_db_sha256=args.expected_db_sha256)
    except (OSError, ValueError, sqlite3.DatabaseError, csv.Error, UnicodeError):
        ap.exit(2, "RECOVERY CSV NO-GO: private source/CSV content, completeness or stability unverified\n")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
