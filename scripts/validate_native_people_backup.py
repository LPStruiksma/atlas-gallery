#!/usr/bin/env python3
"""Fail-closed, read-only validation of a CONSISTENT OFFLINE native People DB.

Never captures phone data, reads photographs, prints identity labels/paths, or
authorizes uninstall. Checks only an already-acquired standalone DB; neither
integrity_check nor identical hashes prove that capture was fresh/complete.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import stat
from urllib.parse import quote

SIDECARS = ("-wal", "-shm", "-journal")
MAX_DB_BYTES = 512 * 1024 * 1024
EXPECTED_COLUMNS = (
    ("face_id", "INTEGER", 0, None, 1, 0),
    ("person_id", "INTEGER", 1, None, 0, 0),
    ("person_name", "TEXT", 1, None, 0, 0),
    ("image_path", "TEXT", 1, None, 0, 0),
)


def _identity(info: os.stat_result) -> tuple:
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def _validate_member(path: Path) -> os.stat_result:
    try:
        before = path.lstat()
    except OSError:
        # Never allow OS diagnostic paths to escape a private validation API.
        raise ValueError("Offline People snapshot missing or unavailable") from None
    if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or
            not 100 <= before.st_size <= MAX_DB_BYTES or path.is_symlink()):
        raise ValueError("Missing, linked, shared or oversized offline People backup")
    if any(Path(str(path) + suffix).exists() or Path(str(path) + suffix).is_symlink()
           for suffix in SIDECARS):
        raise ValueError("SQLite sidecars found; standalone coherent capture required")
    return before


def _hash_stable(path: Path, expected: os.stat_result) -> str:
    """Read from a no-follow descriptor; reject path replacement and mutation."""
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    digest = hashlib.sha256()
    try:
        current = os.fstat(fd)
        if _identity(current) != _identity(expected):
            raise ValueError("Offline People backup changed before hashing")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        if _identity(os.fstat(fd)) != _identity(expected):
            raise ValueError("Offline People backup changed while hashing")
    finally:
        os.close(fd)
    if _identity(path.lstat()) != _identity(expected):
        raise ValueError("Offline People backup path replaced while hashing")
    return digest.hexdigest()


def validate_supported_schema(db: sqlite3.Connection) -> None:
    """Reject any future private data schema the current CSV cannot preserve.

    Existing application version 1 has exactly four assignments columns.
    Version 2 adds only a derived ``people_metadata`` marker holding exactly
    ``schema_version = '2'``; the app recreates it on a fresh install, so the
    four-column CSV still carries every user record. SQLite's ordinary
    indexes on known columns are safe to regenerate, while additional tables,
    views, virtual tables or triggers are NOT implicitly covered by the
    recovery format.
    """
    version = db.execute("PRAGMA user_version").fetchone()[0]
    if version not in (0, 1, 2):
        raise ValueError("Unsupported native People schema version")
    columns = tuple((r[1], r[2].upper(), r[3], r[4], r[5], r[6])
                    for r in db.execute("PRAGMA table_xinfo(assignments)"))
    if columns != EXPECTED_COLUMNS:
        raise ValueError("Unrecognized native People columns; recovery unsupported")
    objects = tuple(db.execute(
        "SELECT type,name,tbl_name FROM sqlite_master "
        "WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name"))
    if not any(kind == "table" and name == "assignments" for kind, name, _ in objects):
        raise ValueError("People assignment table missing")
    known_tables = ("assignments", "android_metadata") + (("people_metadata",) if version == 2 else ())
    for kind, name, table in objects:
        if kind == "table" and name in known_tables:
            continue
        if kind == "index" and table in known_tables:
            continue
        raise ValueError("Unknown native People object; migration format unverified")
    if version == 2:
        _validate_v2_metadata(db, objects)
    if any(kind == "table" and name == "android_metadata" for kind, name, _ in objects):
        meta = tuple((r[1], r[2].upper(), r[3], r[4], r[5], r[6])
                     for r in db.execute("PRAGMA table_xinfo(android_metadata)"))
        if meta != (("locale", "TEXT", 0, None, 0, 0),):
            raise ValueError("Unsupported Android SQLite metadata schema")


V2_METADATA_COLUMNS = (
    ("key", "TEXT", 1, None, 1, 0),
    ("value", "TEXT", 1, None, 0, 0),
)


def _validate_v2_metadata(db: sqlite3.Connection, objects: tuple) -> None:
    """Schema v2 must carry exactly the derived version marker, no user data."""
    if not any(kind == "table" and name == "people_metadata" for kind, name, _ in objects):
        raise ValueError("Native People schema v2 metadata missing")
    columns = tuple((r[1], r[2].upper(), r[3], r[4], r[5], r[6])
                    for r in db.execute("PRAGMA table_xinfo(people_metadata)"))
    if columns != V2_METADATA_COLUMNS:
        raise ValueError("Unrecognized native People metadata columns")
    rows = db.execute("SELECT key, value FROM people_metadata").fetchall()
    if rows != [("schema_version", "2")]:
        raise ValueError("Unexpected native People metadata; recovery unsupported")


def inspect_snapshot(path: Path, *, min_rows: int = 1) -> dict:
    if not isinstance(min_rows, int) or isinstance(min_rows, bool) or min_rows < 0:
        raise ValueError("Minimum expected row count must be nonnegative integer")
    before = _validate_member(path)
    before_sha = _hash_stable(path, before)
    uri = "file:" + quote(str(path.absolute()), safe="/") + "?mode=ro&immutable=1"
    try:
        # Connection context managers commit/rollback, not close; closing()
        # matters on private backup file handles and Windows platforms.
        with closing(sqlite3.connect(uri, uri=True, timeout=2)) as db:
            if db.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
                raise ValueError("SQLite integrity check failed")
            validate_supported_schema(db)
            count, people, images = db.execute(
                "SELECT COUNT(*), COUNT(DISTINCT person_id), COUNT(DISTINCT image_path) "
                "FROM assignments").fetchone()
            bad = db.execute("""SELECT COUNT(*) FROM assignments
                WHERE face_id<=0 OR person_id<=0 OR TRIM(person_name)=''
                   OR image_path NOT LIKE '/storage/emulated/0/%'""").fetchone()[0]
            if bad:
                raise ValueError("Invalid identity labels or photo paths in backup")
            if count < min_rows:
                raise ValueError("Too few records for independently known minimum")
    except sqlite3.DatabaseError:
        raise ValueError("Unreadable, corrupt or incompatible SQLite backup") from None
    # SQLite immutable=1 assumes bytes don't change. Verify that assumption
    # before trusting ANY count/metadata returned by the read-only connection.
    after_sha = _hash_stable(path, before)
    if before_sha != after_sha:
        raise ValueError("Offline People DB bytes changed during validation")
    _validate_member(path)
    return dict(
        valid=True, rows=count, people=people, distinct_image_paths=images,
        bytes=before.st_size, sha256=after_sha,
        note="Offline stable/recognized structural validation only; capture/restore NOT proven"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Privately inspect a standalone offline native People SQLite backup")
    parser.add_argument("database", type=Path)
    parser.add_argument("--min-rows", type=int, default=1)
    args = parser.parse_args()
    try:
        report = inspect_snapshot(args.database, min_rows=args.min_rows)
    except (ValueError, OSError):
        parser.exit(2, "BACKUP BLOCKED: incompatible, unstable or incomplete offline SQLite snapshot\n")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
