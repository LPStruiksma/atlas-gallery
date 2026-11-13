#!/usr/bin/env python3
"""Synthetic-only, local-file regression; no Android, keystores or photos."""
import csv
from pathlib import Path
import os
import sqlite3
import tempfile

from consolidate_native_people_capture import consolidate_offline_capture
from validate_native_people_backup import inspect_snapshot


def rejected(call, reason):
    try:
        call()
    except (ValueError, OSError) as e:
        assert reason.lower() in str(e).lower(), (reason, type(e).__name__, str(e))
        return
    raise AssertionError("Unsafe offline operation should have failed: " + reason)


with tempfile.TemporaryDirectory(prefix="atlas-native-capture-test-") as root:
    folder = Path(root)
    capture = folder / "captured.db"
    private = folder / "protected"
    private.mkdir(mode=0o700)
    out = private / "standalone.db"

    # A live SQLite connection is left open ONLY to make realistic WAL files
    # in this isolated fixture. No writes occur during the copied-file test.
    # This is not a procedure for capturing the owner's on-phone database.
    db = sqlite3.connect(capture)
    assert db.execute("PRAGMA journal_mode=WAL").fetchone()[0] == "wal"
    db.execute("PRAGMA wal_autocheckpoint=0")
    db.execute("""CREATE TABLE assignments(
        face_id INTEGER PRIMARY KEY, person_id INTEGER NOT NULL,
        person_name TEXT NOT NULL, image_path TEXT NOT NULL)""")
    db.executemany("INSERT INTO assignments VALUES (?,?,?,?)", [
        (101, 7, "Example, One", "/storage/emulated/0/DCIM/family.jpg"),
        (102, 8, "Björk Example", "/storage/emulated/0/DCIM/family.jpg"),
        (103, 7, "Example, One", "/storage/emulated/0/DCIM/another.jpg"),
    ])
    db.commit()
    wal = Path(str(capture) + "-wal")
    shm = Path(str(capture) + "-shm")
    assert wal.is_file() and shm.is_file(), "Fixture did not create uncheckpointed WAL"

    originals = {p: p.read_bytes() for p in (capture, wal, shm)}
    rejected(lambda: consolidate_offline_capture(capture, out, min_rows=3),
             "acknowledgment")
    result = consolidate_offline_capture(
        capture, out, min_rows=3, offline_capture_acknowledged=True)
    assert (result["rows"], result["people"], result["distinct_image_paths"]) == (3, 2, 2)
    assert out.stat().st_mode & 0o777 == 0o600
    assert inspect_snapshot(out, min_rows=3)["valid"]
    assert all(p.read_bytes() == contents for p, contents in originals.items()), (
        "Offline source files were modified by the backup reader")
    assert not Path(str(out) + "-wal").exists(), "Published standalone copy has WAL sidecars"
    with sqlite3.connect(out) as copy:
        rows = copy.execute("SELECT face_id,person_id,person_name,image_path FROM assignments ORDER BY face_id").fetchall()
    assert rows == [
        (101, 7, "Example, One", "/storage/emulated/0/DCIM/family.jpg"),
        (102, 8, "Björk Example", "/storage/emulated/0/DCIM/family.jpg"),
        (103, 7, "Example, One", "/storage/emulated/0/DCIM/another.jpg")
    ], "Committed WAL data or many-to-many membership was lost"

    rejected(lambda: consolidate_offline_capture(
        capture, out, min_rows=3, offline_capture_acknowledged=True),
        "exists")
    rejected(lambda: consolidate_offline_capture(
        capture, private / "too-small.db", min_rows=5,
        offline_capture_acknowledged=True), "too few")

    public = folder / "world-readable"
    public.mkdir(mode=0o755)
    rejected(lambda: consolidate_offline_capture(
        capture, public / "unsafe.db", min_rows=1,
        offline_capture_acknowledged=True), "other users")

    bad = folder / "orphan.db"
    with sqlite3.connect(bad) as example:
        example.execute("CREATE TABLE foo(id INTEGER)")
    Path(str(bad) + "-shm").write_bytes(b"orphan")
    rejected(lambda: consolidate_offline_capture(
        bad, private / "orphan-snapshot.db", min_rows=1,
        offline_capture_acknowledged=True), "orphan")

    journal = Path(str(capture) + "-journal")
    journal.write_bytes(b"unsettled")
    rejected(lambda: consolidate_offline_capture(
        capture, private / "journal-snapshot.db", min_rows=1,
        offline_capture_acknowledged=True), "journal")
    journal.unlink()

    link = folder / "symlink.db"
    link.symlink_to(capture)
    rejected(lambda: consolidate_offline_capture(
        link, private / "linked.db", min_rows=1,
        offline_capture_acknowledged=True), "symbolic")

    # Cleaning up fixture connections may checkpoint WAL, but the real
    # capture bytes were already independently compared above.
    db.close()

print("PASS: synthetic WAL rows recovered, private standalone file, source untouched, fail-closed cases")
