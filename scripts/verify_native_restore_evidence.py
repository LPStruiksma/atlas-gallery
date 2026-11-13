#!/usr/bin/env python3
"""Read-only, private, OFFLINE comparison of a backup with a restored copy.

Two separate *directory trees*, already obtained by the owner, are compared.
Never accesses a phone, extracts data or emits identity rows/settings values.
Does not itself prove collection, Android restore, grant continuity or readiness
for destructive migration. No source files are created or changed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import stat

from validate_native_people_backup import inspect_snapshot

DB = Path('databases/atlas_native_people.db')
SETTINGS_ROOTS = (Path('shared_prefs'), Path('files/datastore'))
MAX_SETTINGS_FILES = 128
MAX_SETTINGS_BYTES = 16 * 1024 * 1024


def _private_regular(path: Path) -> None:
    if path.is_symlink() or not path.is_file():
        raise ValueError('Missing, nonregular or linked snapshot member')
    if not stat.S_ISREG(path.lstat().st_mode):
        raise ValueError('Snapshot member is not a regular file')


def _require_unlinked_ancestors(root: Path, relative: Path) -> None:
    """Never traverse a symlink *inside* either purported snapshot tree.

    Testing only the final datastore directory is insufficient: for example,
    root/files -> /other/location still reaches outside the snapshot.
    """
    current = root
    if current.is_symlink():
        raise ValueError('Linked snapshot root')
    for component in relative.parts:
        current = current / component
        if current.is_symlink():
            raise ValueError('Linked snapshot ancestor')


def _settings_files(root: Path) -> dict[str, Path]:
    """Inventory only known app-settings namespaces, rejecting links/deep trees."""
    found: dict[str, Path] = {}
    for part in SETTINGS_ROOTS:
        _require_unlinked_ancestors(root, part)
        folder = root / part
        if not folder.exists():
            continue
        if not folder.is_dir():
            raise ValueError('Settings namespace is not a directory')
        for entry in folder.iterdir():
            if entry.is_dir() or entry.is_symlink():
                raise ValueError('Nested/linked settings files are not supported')
            _private_regular(entry)
            if not entry.name or entry.name.startswith('.'):
                raise ValueError('Unexpected settings file')
            if part == Path('shared_prefs') and entry.suffix != '.xml':
                raise ValueError('Unexpected shared_prefs extension')
            if part == Path('files/datastore') and entry.suffix not in ('.preferences_pb', '.pb'):
                raise ValueError('Unexpected datastore extension')
            key = (part / entry.name).as_posix()
            found[key] = entry
            if len(found) > MAX_SETTINGS_FILES or entry.stat().st_size > MAX_SETTINGS_BYTES:
                raise ValueError('Settings inventory exceeds safe offline limits')
    return found


def _fingerprint_file(path: Path) -> bytes:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.digest()


def _people_schema_signature(path: Path) -> tuple:
    """Fail closed when a future People DB schema cannot be recovered losslessly.

    The current CSV recovery contract only preserves the four V0.2/V0.3
    assignment columns. A future field/table/trigger MUST have an independently
    reviewed recovery format instead of silently disappearing on restore.
    """
    import urllib.parse
    uri = 'file:' + urllib.parse.quote(str(path.resolve()), safe='/') + '?mode=ro&immutable=1'
    with sqlite3.connect(uri, uri=True, timeout=2) as db:
        version = db.execute('PRAGMA user_version').fetchone()[0]
        if version not in (0, 1):
            raise ValueError('Unsupported native People schema version')
        columns = tuple((row[1], row[2].upper(), row[3], row[4], row[5], row[6])
                        for row in db.execute('PRAGMA table_xinfo(assignments)'))
        expected = (
            ('face_id', 'INTEGER', 0, None, 1, 0),
            ('person_id', 'INTEGER', 1, None, 0, 0),
            ('person_name', 'TEXT', 1, None, 0, 0),
            ('image_path', 'TEXT', 1, None, 0, 0),
        )
        if columns != expected:
            raise ValueError('Unrecognized People columns; lossless recovery not established')
        schema = tuple(db.execute("""
            SELECT type, name, tbl_name, sql FROM sqlite_master
            WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name
        """))
        for kind, name, table, _sql in schema:
            if kind == 'table' and name in ('assignments', 'android_metadata'):
                continue
            if kind == 'index' and table in ('assignments', 'android_metadata'):
                continue
            raise ValueError('Unrecognized People DB schema object')
        has_android_metadata = any(kind == 'table' and name == 'android_metadata'
                                   for kind, name, _table, _sql in schema)
        # Android's optional metadata contains its locale; preserve its content
        # too rather than silently dropping that table from the restore proof.
        android_metadata = tuple(db.execute('SELECT * FROM android_metadata')) if has_android_metadata else ()
        return version, schema, android_metadata


def _people_equal(a: Path, b: Path) -> bool:
    # Logical (rather than raw SQLite-byte) parity includes every private field.
    # Results and names are not printed, retained or uploaded.
    import urllib.parse
    def open_ro(path: Path):
        uri = 'file:' + urllib.parse.quote(str(path.resolve()), safe='/') + '?mode=ro&immutable=1'
        return sqlite3.connect(uri, uri=True, timeout=2)
    with open_ro(a) as left, open_ro(b) as right:
        query = 'SELECT face_id, person_id, person_name, image_path FROM assignments ORDER BY face_id'
        la, rb = left.execute(query), right.execute(query)
        sentinel = object()
        while True:
            x, y = next(la, sentinel), next(rb, sentinel)
            if x is sentinel or y is sentinel:
                return x is sentinel and y is sentinel
            if x != y:
                return False


def verify(backup: Path, restored: Path, *, min_rows: int) -> dict:
    if min_rows < 1:
        raise ValueError('An independent expected People count is required')
    if backup.is_symlink() or restored.is_symlink() or not backup.is_dir() or not restored.is_dir():
        raise ValueError('Two existing nonlinked directories are required')
    backup_real, restored_real = backup.resolve(), restored.resolve()
    if backup_real == restored_real or backup_real in restored_real.parents or restored_real in backup_real.parents:
        raise ValueError('Backup and restored directories must be separate non-nested trees')
    left_db, right_db = backup / DB, restored / DB
    for root, db in ((backup, left_db), (restored, right_db)):
        _require_unlinked_ancestors(root, DB.parent)
        _private_regular(db)
    if os.path.samestat(left_db.stat(), right_db.stat()):
        raise ValueError('Backup and restored People DB are the same inode')
    left = inspect_snapshot(left_db, min_rows=min_rows)
    right = inspect_snapshot(right_db, min_rows=min_rows)
    if _people_schema_signature(left_db) != _people_schema_signature(right_db):
        raise ValueError('People schema or metadata differs after restore')
    if (left['rows'], left['people'], left['distinct_image_paths']) != (
        right['rows'], right['people'], right['distinct_image_paths']
    ) or not _people_equal(left_db, right_db):
        raise ValueError('People rows differ after offline restore')
    a, b = _settings_files(backup), _settings_files(restored)
    if not a or a.keys() != b.keys():
        raise ValueError('Settings missing or manifest differs between backup and restore')
    for key in a:
        if os.path.samestat(a[key].stat(), b[key].stat()):
            raise ValueError('A settings file is not an independent restored copy')
        if _fingerprint_file(a[key]) != _fingerprint_file(b[key]):
            raise ValueError('A persisted setting differs after restore')
    return {
        'offline_restore_parity': True,
        'people_rows': left['rows'],
        'people_profiles': left['people'],
        'settings_files_matched': len(a),
        'phone_backup_acquired': False,
        'android_restore_tested': False,
        'saf_grants_restored': False,
        'safe_to_uninstall': False,
        'next_gate': 'Confirm independent capture and actual isolated Android restore; no phone migration is authorized.'
    }


def main() -> int:
    parser = argparse.ArgumentParser(description='Privately compare an already-captured ATLAS People+settings backup and independently restored offline tree')
    parser.add_argument('backup_tree', type=Path)
    parser.add_argument('restored_tree', type=Path)
    parser.add_argument('--min-rows', type=int, required=True, help='Independent minimum confirmed face count')
    args = parser.parse_args()
    try:
        result = verify(args.backup_tree, args.restored_tree, min_rows=args.min_rows)
    except (ValueError, OSError, sqlite3.DatabaseError):
        parser.exit(2, 'NO-GO: offline People/settings restore parity unverified. Existing app must remain installed.\n')
    print(json.dumps(result, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
