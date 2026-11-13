#!/usr/bin/env python3
"""Lossless, local-only native People recovery export from a verified SQLite copy.

Not a tool for acquiring a phone backup. No data transmitted. Outputs a CSV
for ATLAS Native's existing "Import once" flow (not for Termux V4's database).
"""
from __future__ import annotations

import argparse
import csv
import os
from pathlib import Path
import stat
import sqlite3
from urllib.parse import quote

from validate_native_people_backup import inspect_snapshot


HEADER = ["person_id", "person_name", "image_path", "face_id", "confidence"]


def _unlink_our_incomplete_export(destination: Path, own_identity: tuple[int, int] | None) -> None:
    """Never remove a preexisting or substituted path when an export fails."""
    if own_identity is None:
        return
    try:
        existing = destination.lstat()
        if (stat.S_ISREG(existing.st_mode) and
                (existing.st_dev, existing.st_ino) == own_identity):
            destination.unlink()
    except FileNotFoundError:
        pass


def export_recovery(source: Path, destination: Path, min_rows: int = 1) -> dict:
    check = inspect_snapshot(source, min_rows=min_rows)
    if source.resolve() == destination.resolve():
        raise ValueError("Output cannot overwrite the original backup")
    if destination.exists() or destination.is_symlink():
        raise ValueError("Recovery export already exists; refusing overwrite")
    if (not destination.parent.is_dir() or destination.parent.is_symlink()
            or stat.S_IMODE(destination.parent.stat().st_mode) & 0o077):
        raise ValueError("Recovery CSV requires an existing private output directory (0700)")

    uri = "file:" + quote(str(source.resolve()), safe="/") + "?mode=ro&immutable=1"
    count = 0
    own_identity: tuple[int, int] | None = None
    # The fifth field is intentionally BLANK, never a fabricated confidence.
    # Native PeopleRepository only checks column count and ignores confidence.
    try:
        with sqlite3.connect(uri, uri=True) as db:
            # O_EXCL blocks overwrites and symlink targets. The file begins
            # mode 0600 even under a permissive process umask; no brief 0644
            # exposure of names/photo paths is possible.
            fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            created_stat = os.fstat(fd)
            own_identity = (created_stat.st_dev, created_stat.st_ino)
            with os.fdopen(fd, "w", newline="", encoding="utf-8") as output:
                writer = csv.writer(output, lineterminator="\n")
                writer.writerow(HEADER)
                for pid, name, path, fid in db.execute(
                    "SELECT person_id, person_name, image_path, face_id FROM assignments ORDER BY face_id"
                ):
                    writer.writerow((pid, name, path, fid, ""))
                    count += 1
        if count != check["rows"]:
            raise ValueError("Backup row count changed during recovery export")
        # immutable=1 promises the SQLite source is quiescent; verify that
        # the promise actually held across the entire export. A changed
        # identity with equal row count is otherwise invisible.
        final = inspect_snapshot(source, min_rows=count)
        if (final["sha256"] != check["sha256"] or
                final["rows"] != check["rows"] or
                final["people"] != check["people"]):
            raise ValueError("Offline People source changed during recovery export")
        # The result cannot attest to data if the created path was replaced
        # during writing or its permissions relaxed. A replacement is NOT
        # ours to delete, even in an error path.
        output_stat = destination.lstat()
        if (not stat.S_ISREG(output_stat.st_mode) or
                (output_stat.st_dev, output_stat.st_ino) != own_identity or
                stat.S_IMODE(output_stat.st_mode) & 0o077):
            raise ValueError("Recovery output path changed during export")
    except Exception:
        _unlink_our_incomplete_export(destination, own_identity)
        raise
    return dict(rows=count,people=check["people"],
                exported=True,
                note="Import once manually in new ATLAS only after authorized migration")


def main():
    parser = argparse.ArgumentParser(description="Locally convert offline native People backup into manual recovery CSV")
    parser.add_argument("database", type=Path)
    parser.add_argument("output_csv", type=Path)
    parser.add_argument("--min-rows", type=int, default=1)
    args = parser.parse_args()
    try:
        details = export_recovery(args.database, args.output_csv, args.min_rows)
    except (ValueError, OSError, sqlite3.DatabaseError) as exc:
        parser.exit(2, "RECOVERY BLOCKED: " + type(exc).__name__ + "\n")
    print(f"Created local recovery CSV: {details['rows']} face assignments; {details['people']} People.")
    print("Contains private identity names and photo paths. Keep it offline and protected.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
