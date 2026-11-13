#!/usr/bin/env python3
"""Offline recovery round-trip of invented People names, paths and many-to-many rows."""
import csv
import os
from pathlib import Path
import stat
import sqlite3
import tempfile
from unittest.mock import patch
import export_native_people_recovery as module
from export_native_people_recovery import export_recovery, HEADER

with tempfile.TemporaryDirectory() as root:
    db = Path(root) / "people.db"
    output = Path(root) / "recovery.csv"
    originals = [
        (300, 20, 'Alex, Sample', '/storage/emulated/0/DCIM/photo "one".jpg'),
        (301, 21, 'Björk Example', '/storage/emulated/0/DCIM/photo "one".jpg'),
        (302, 20, 'Alex, Sample', '/storage/emulated/0/DCIM/photo two.jpg')
    ]
    with sqlite3.connect(db) as con:
        con.execute("CREATE TABLE assignments(face_id INTEGER PRIMARY KEY,person_id INTEGER NOT NULL,person_name TEXT NOT NULL,image_path TEXT NOT NULL)")
        con.executemany("INSERT INTO assignments(face_id,person_id,person_name,image_path) VALUES (?,?,?,?)", originals)
    prior = db.read_bytes()
    # Test under deliberately unsafe process umask: output still starts 0600.
    old_umask = os.umask(0o000)
    try:
        result = export_recovery(db, output, min_rows=3)
    finally:
        os.umask(old_umask)
    assert stat.S_IMODE(output.stat().st_mode) == 0o600
    assert result["rows"] == 3 and result["people"] == 2
    assert db.read_bytes() == prior
    with output.open(newline="", encoding="utf-8") as stream:
        rows = list(csv.reader(stream))
    assert rows[0] == HEADER
    assert all(row[-1] == "" for row in rows[1:])  # Confidence not made up.
    recovered = [(int(row[3]), int(row[0]), row[1], row[2]) for row in rows[1:]]
    assert recovered == originals, (recovered, originals)
    # No output overwrite, even when previous CSV is corrupt or from another user.
    try:
        export_recovery(db, output)
    except ValueError as exc:
        assert "exists" in str(exc)
    else:
        raise AssertionError("Existing CSV overwritten")
    assert output.exists()
    try:
        export_recovery(db, db)
    except ValueError:
        pass
    else:
        raise AssertionError("Source backup overwritten")
    assert db.read_bytes() == prior
    assert len(list(csv.reader(output.open(encoding="utf-8")))) == 4
    # Public destinations must never receive CSVs containing names/paths.
    world = Path(root) / "public-dir"
    world.mkdir(mode=0o755)
    world.chmod(0o755)  # Test genuinely public permissions even if CI umask=077.
    public_output = world / "private-people.csv"
    try:
        export_recovery(db, public_output, min_rows=3)
    except ValueError as exc:
        assert "private output directory" in str(exc)
    else:
        raise AssertionError("World-readable recovery directory accepted")
    assert not public_output.exists()

    protected = Path(root) / "protected"
    protected.mkdir(mode=0o700)
    alias = Path(root) / "symlink-dir"
    alias.symlink_to(protected, target_is_directory=True)
    try:
        export_recovery(db, alias/"escaped.csv", min_rows=3)
    except ValueError as exc:
        assert "private output directory" in str(exc)
    else:
        raise AssertionError("Symlink destination directory accepted")
    assert not (protected/"escaped.csv").exists()

    # Same cardinality with CHANGED person membership is unsafe. Mutate the
    # fictional source between its initial and final stability inspections;
    # even a complete-looking 3-row CSV must be removed.
    fresh = Path(root)/"fresh.csv"
    real_inspect = module.inspect_snapshot
    attempts = [0]
    def source_changes_after_export(source, min_rows=1):
        attempts[0] += 1
        if attempts[0] == 2:
            with sqlite3.connect(source) as connection:
                connection.execute("UPDATE assignments SET person_name='SIMULATED LATE EDIT' WHERE face_id=301")
        return real_inspect(source, min_rows=min_rows)
    with patch.object(module, "inspect_snapshot", side_effect=source_changes_after_export):
        try:
            export_recovery(db, fresh, min_rows=3)
        except ValueError:
            pass
        else:
            raise AssertionError("Edited People source exported successfully")
    assert not fresh.exists()
    with sqlite3.connect(db) as connection:
        connection.execute("UPDATE assignments SET person_name=? WHERE face_id=301", (originals[1][2],))

    # If a different file replaces our newly-created path, cleanup MUST NOT
    # delete that unrelated inode, even if its name equals the target.
    replacement = Path(root)/"replacement.csv"
    parked = Path(root)/"original-synthetic-export.csv"
    calls = [0]
    def replace_export_path(source, min_rows=1):
        calls[0] += 1
        if calls[0] == 2:
            replacement.rename(parked)
            replacement.write_bytes(b"unrelated independently existing data")
            replacement.chmod(0o600)
        return real_inspect(source, min_rows=min_rows)
    with patch.object(module, "inspect_snapshot", side_effect=replace_export_path):
        try:
            export_recovery(db, replacement, min_rows=3)
        except ValueError:
            pass
        else:
            raise AssertionError("Substituted CSV inode erroneously accepted")
    assert replacement.read_bytes() == b"unrelated independently existing data"
    assert parked.exists()
    replacement.unlink()
    parked.unlink()

    # Output still exists but gains insecure access rights during export.
    loosened = Path(root)/"loosened.csv"
    calls = [0]
    def relax_export_permissions(source, min_rows=1):
        calls[0] += 1
        if calls[0] == 2:
            loosened.chmod(0o644)
        return real_inspect(source, min_rows=min_rows)
    with patch.object(module, "inspect_snapshot", side_effect=relax_export_permissions):
        try:
            export_recovery(db, loosened, min_rows=3)
        except ValueError:
            pass
        else:
            raise AssertionError("Public CSV permissions silently accepted")
    assert not loosened.exists()
print("PASS: private 0600 recovery CSV, post-export SQLite stability, no symlink/public inode, lossless rows")
