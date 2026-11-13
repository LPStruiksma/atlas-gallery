#!/usr/bin/env python3
"""Offline tests using invented values."""
import importlib.util
import os
import shutil
from unittest.mock import patch
from pathlib import Path
import sqlite3
import tempfile

file = Path(__file__).with_name("validate_native_people_backup.py")
spec = importlib.util.spec_from_file_location("native_snapshot_guard", file)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp) / "example.sqlite3"
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE assignments(face_id INTEGER PRIMARY KEY, person_id INTEGER NOT NULL, person_name TEXT NOT NULL, image_path TEXT NOT NULL)")
        db.executemany("INSERT INTO assignments VALUES (?,?,?,?)", [
            (10, 2, "Example A", "/storage/emulated/0/DCIM/example-1.png"),
            (11, 2, "Example A", "/storage/emulated/0/DCIM/example-2.png"),
            (12, 3, "Example B", "/storage/emulated/0/DCIM/example-2.png")])
    before = path.read_bytes()
    good = mod.inspect_snapshot(path, min_rows=3)
    assert (good["rows"], good["people"], good["distinct_image_paths"]) == (3, 2, 2)
    assert len(good["sha256"]) == 64
    assert "Example A" not in str(good)
    assert path.read_bytes() == before
    def must_reject(fn):
        try:
            fn()
        except ValueError:
            return
        raise AssertionError("Should reject invalid example")
    must_reject(lambda: mod.inspect_snapshot(path, min_rows=4))

    # Future columns and auxiliary private tables are not recoverable using
    # the current four-column native Import Once CSV contract.
    def changed_sql(sql):
        candidate = Path(tmp) / "changed.db"
        shutil.copy2(path, candidate)
        with sqlite3.connect(candidate) as con:
            con.execute(sql)
        must_reject(lambda: mod.inspect_snapshot(candidate, min_rows=3))
        # The same validator is the mandatory gate for the manual CSV
        # recovery exporter: refuse to silently drop a new private column.
        from export_native_people_recovery import export_recovery
        recovery = Path(tmp) / "unsupported-recovery.csv"
        must_reject(lambda: export_recovery(candidate, recovery, min_rows=3))
        assert not recovery.exists()
        candidate.unlink()

    changed_sql("ALTER TABLE assignments ADD COLUMN secret_future_label TEXT")
    changed_sql("CREATE TABLE future_people_notes(id INTEGER PRIMARY KEY, note TEXT)")
    changed_sql("CREATE VIEW assignments_summary AS SELECT COUNT(*) FROM assignments")
    changed_sql("CREATE TRIGGER extra_data AFTER INSERT ON assignments BEGIN SELECT 1; END")
    changed_sql("PRAGMA user_version=2")
    changed_sql("ALTER TABLE assignments ADD COLUMN new_score REAL DEFAULT 1.0")

    production = Path(tmp) / "native-v1.db"
    shutil.copy2(path, production)
    with sqlite3.connect(production) as con:
        con.execute("PRAGMA user_version=1")
        con.execute("CREATE INDEX path_assignments ON assignments(image_path)")
        con.execute("CREATE INDEX person_assignments ON assignments(person_id)")
        con.execute("CREATE TABLE android_metadata(locale TEXT)")
        con.execute("INSERT INTO android_metadata VALUES('en_US')")
    assert mod.inspect_snapshot(production, min_rows=3)["valid"]
    production.unlink()


    # Native schema v2 (lane X) adds only a derived people_metadata marker that
    # the current app recreates on fresh install, so the four-column CSV still
    # preserves every user record. Accept exactly that shape and nothing more.
    def native_v2(extra_sql=(), version=2):
        candidate = Path(tmp) / "native-v2.db"
        shutil.copy2(path, candidate)
        with sqlite3.connect(candidate) as con:
            con.execute(f"PRAGMA user_version={version}")
            con.execute("CREATE INDEX path_assignments ON assignments(image_path)")
            con.execute("CREATE INDEX person_assignments ON assignments(person_id)")
            con.execute("CREATE TABLE android_metadata(locale TEXT)")
            con.execute("INSERT INTO android_metadata VALUES('en_US')")
            con.execute("CREATE TABLE people_metadata(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
            con.execute("INSERT INTO people_metadata(key, value) VALUES('schema_version', '2')")
            for sql in extra_sql:
                con.execute(sql)
        return candidate
    v2 = native_v2()
    assert mod.inspect_snapshot(v2, min_rows=3)["valid"]
    v2.unlink()
    for make_bad_v2 in (
        lambda: native_v2(["INSERT INTO people_metadata VALUES('hidden_label', 'Example A')"]),
        lambda: native_v2(["UPDATE people_metadata SET value='3'"]),
        lambda: native_v2(["DELETE FROM people_metadata"]),
        lambda: native_v2(version=1),
        lambda: native_v2(version=3),
    ):
        bad_v2 = make_bad_v2()
        must_reject(lambda: mod.inspect_snapshot(bad_v2, min_rows=3))
        bad_v2.unlink()
    wrong_columns = Path(tmp) / "native-v2-columns.db"
    shutil.copy2(path, wrong_columns)
    with sqlite3.connect(wrong_columns) as con:
        con.execute("PRAGMA user_version=2")
        con.execute("CREATE TABLE people_metadata(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL, note TEXT)")
        con.execute("INSERT INTO people_metadata(key, value) VALUES('schema_version', '2')")
    must_reject(lambda: mod.inspect_snapshot(wrong_columns, min_rows=3))
    wrong_columns.unlink()

    changed_sql("CREATE TABLE android_metadata(locale TEXT, hidden_secret TEXT)")
    # A settings-sidecar copied next to an otherwise valid main DB means the
    # main file may be missing committed WAL rows.
    shared = Path(tmp) / "linked.db"
    shared.symlink_to(path)
    must_reject(lambda: mod.inspect_snapshot(shared))
    shared.unlink()
    hardlinked = Path(tmp) / "hardlinked.db"
    os.link(path, hardlinked)
    must_reject(lambda: mod.inspect_snapshot(hardlinked))
    hardlinked.unlink()

    # The validator must reject even a file that was valid at the beginning
    # but mutated between its read-only SQLite inspection and final checksum.
    mutated = Path(tmp) / "mutated.db"
    shutil.copy2(path, mutated)
    original_hash = mod._hash_stable
    calls = [0]
    def tampering_hash(candidate, expected):
        calls[0] += 1
        if calls[0] == 2:
            with candidate.open("ab") as output:
                output.write(b"malicious appended bytes")
        return original_hash(candidate, expected)
    with patch.object(mod, "_hash_stable", side_effect=tampering_hash):
        must_reject(lambda: mod.inspect_snapshot(mutated, min_rows=3))
    mutated.unlink()

    sidecar = Path(str(path) + "-wal")
    sidecar.write_bytes(b"test")
    must_reject(lambda: mod.inspect_snapshot(path))
    sidecar.unlink()
    with sqlite3.connect(path) as db:
        db.execute("UPDATE assignments SET image_path='invalid' WHERE face_id=10")
    must_reject(lambda: mod.inspect_snapshot(path))
    path.write_bytes(b"invalid database" * 20)
    must_reject(lambda: mod.inspect_snapshot(path))
    path.unlink()
    must_reject(lambda: mod.inspect_snapshot(path))
print("PASS: strict schema, native v1, stable file evidence, WAL, links and no private output")
