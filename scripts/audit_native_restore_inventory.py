#!/usr/bin/env python3
"""Strict OFFLINE inventory of already captured ATLAS private recovery trees.

A *companion* to verify_native_restore_evidence.py, NOT a phone extractor.
Fails if any unaccounted private file/directory could be lost on restoration.
Never prints filenames, People names, media paths or file contents. Never writes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat

MAX_MEMBERS = 512
MAX_FILE_SIZE = 256 * 1024 * 1024
MAX_TOTAL_SIZE = 512 * 1024 * 1024
DIRECTORIES = frozenset((".", "databases", "shared_prefs", "files", "files/datastore"))
PEOPLE_DB = "databases/atlas_native_people.db"


def _allowed_file(rel: str) -> bool:
    if rel == PEOPLE_DB:
        return True
    parts = Path(rel).parts
    return (len(parts) == 2 and parts[0] == "shared_prefs" and
            parts[1].endswith(".xml") and not parts[1].startswith(".")) or (
            len(parts) == 3 and parts[:2] == ("files", "datastore") and
            parts[2].endswith((".preferences_pb", ".pb")) and
            not parts[2].startswith("."))


def _private_permissions(path: Path, *, directory: bool) -> None:
    info = path.lstat()
    if (not stat.S_ISDIR(info.st_mode) if directory else not stat.S_ISREG(info.st_mode)):
        raise ValueError("Unsupported private-tree member type")
    if info.st_mode & 0o077:
        raise ValueError("Sensitive snapshot member has nonprivate permissions")
    if not directory and info.st_nlink != 1:
        raise ValueError("Snapshot member is not an independent regular file")


def _digest_stable(path: Path) -> str:
    """Fingerprint only an already-vetted regular, nonlinked local snapshot."""
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
        raise ValueError("Unsafe file type or hardlink")
    if before.st_size > MAX_FILE_SIZE or before.st_size <= 0:
        raise ValueError("Empty or oversized private file")
    digest = hashlib.sha256()
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    try:
        actual = os.fstat(fd)
        if (actual.st_dev, actual.st_ino) != (before.st_dev, before.st_ino):
            raise ValueError("Private snapshot file was replaced")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            for piece in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(piece)
        after = path.lstat()
        initial = (before.st_dev, before.st_ino, before.st_size,
                   before.st_mtime_ns, before.st_ctime_ns)
        end = (after.st_dev, after.st_ino, after.st_size,
               after.st_mtime_ns, after.st_ctime_ns)
        if initial != end:
            raise ValueError("Private snapshot file changed during audit")
    finally:
        os.close(fd)
    return digest.hexdigest()


def inventory(root: Path) -> dict[str, str]:
    # Protect against symlink parents, not just links *inside* the snapshot.
    absolute = root.absolute()
    for ancestor in (absolute, *absolute.parents):
        if ancestor.is_symlink():
            raise ValueError("Linked private-tree root or ancestor")
    if not absolute.is_dir():
        raise ValueError("Private snapshot root missing")
    _private_permissions(absolute, directory=True)
    members: dict[str, str] = {}
    stack = [(absolute, ".")]
    total = 0
    visited = 0
    while stack:
        folder, rel_dir = stack.pop()
        with os.scandir(folder) as listing:
            for item in listing:
                visited += 1
                if visited > MAX_MEMBERS:
                    raise ValueError("Private tree is too large to audit")
                rel = item.name if rel_dir == "." else rel_dir + "/" + item.name
                entry = Path(item.path)
                if item.is_symlink():
                    raise ValueError("Linked private-tree entry")
                if item.is_dir(follow_symlinks=False):
                    if rel not in DIRECTORIES:
                        raise ValueError("Unrecognized private settings directory")
                    _private_permissions(entry, directory=True)
                    stack.append((entry, rel))
                    continue
                if not item.is_file(follow_symlinks=False) or not _allowed_file(rel):
                    raise ValueError("Unaccounted private file or unsafe member")
                _private_permissions(entry, directory=False)
                size = item.stat(follow_symlinks=False).st_size
                total += size
                if total > MAX_TOTAL_SIZE:
                    raise ValueError("Private tree exceeds offline audit cap")
                members[rel] = _digest_stable(entry)
    if PEOPLE_DB not in members:
        raise ValueError("Private native People database missing")
    if len(members) <= 1:
        raise ValueError("Settings presence not proven; separate explicit zero-settings evidence required")
    return members


def audit(backup: Path, restored: Path) -> dict:
    if backup.resolve() == restored.resolve():
        raise ValueError("Backup and restored tree are identical")
    if backup.resolve() in restored.resolve().parents or restored.resolve() in backup.resolve().parents:
        raise ValueError("Backup and restored tree are nested")
    a, b = inventory(backup), inventory(restored)
    if a.keys() != b.keys():
        raise ValueError("Private file inventory differs after restore")
    for name, digest in a.items():
        # SQLite backup API and VACUUM may change database bytes without
        # changing logical rows. verify_native_restore_evidence.py verifies
        # People *logical* parity separately, so don't compare DB bytes here.
        if name != PEOPLE_DB and b[name] != digest:
            raise ValueError("Persisted private settings differ after restore")
    return {
        "strict_inventory_verified": True,
        "known_private_file_count": len(a),
        "settings_file_count": len(a) - 1,
        "unaccounted_private_files": 0,
        "people_logical_parity_verified": False,
        "capture_provenance_verified": False,
        "android_restore_tested": False,
        "saf_grants_verified": False,
        "safe_to_uninstall": False,
        "release_go": False,
        "note": "Checks both supplied trees, not omitted data absent from both. Run the separate People logical parity gate."
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only strict inventory of two existing offline People/settings recovery trees")
    parser.add_argument("backup", type=Path)
    parser.add_argument("restored", type=Path)
    args = parser.parse_args()
    try:
        report = audit(args.backup, args.restored)
    except (OSError, ValueError):
        parser.exit(2, "NO-GO: private recovery inventory is incomplete, altered or unsupported\n")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
