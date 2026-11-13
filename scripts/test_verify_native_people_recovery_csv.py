#!/usr/bin/env python3
"""Synthetic private People CSV comparison: no phone, photos or credentials."""
import csv
from io import StringIO
import hashlib
import os
from pathlib import Path
import shutil
import sqlite3
import sys
import tempfile
from contextlib import redirect_stdout, redirect_stderr
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
import verify_native_people_recovery_csv as mod
from export_native_people_recovery import export_recovery


def deny(fn):
    try:
        fn()
    except (OSError, ValueError, csv.Error):
        return
    raise AssertionError("Incomplete or altered recovery CSV was accepted")


with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    db = root/"people.db"
    csvfile = root/"recovery.csv"
    records = [
        (7, 3, "Example, A", '/storage/emulated/0/DCIM/fake "image".jpg'),
        (8, 3, "Example, A", "/storage/emulated/0/DCIM/fake-other.jpg"),
        (9, 4, "Example Unicode é", "/storage/emulated/0/DCIM/fake-other.jpg")
    ]
    with sqlite3.connect(db) as c:
        c.execute("CREATE TABLE assignments(face_id INTEGER PRIMARY KEY, person_id INTEGER NOT NULL, person_name TEXT NOT NULL, image_path TEXT NOT NULL)")
        c.execute("PRAGMA user_version=1")
        c.executemany("INSERT INTO assignments VALUES (?,?,?,?)", records)
    before_db = db.read_bytes()
    old = os.umask(0o077)
    try:
        export_recovery(db, csvfile, min_rows=3)
    finally:
        os.umask(old)
    assert csvfile.stat().st_mode & 0o777 == 0o600
    original = csvfile.read_bytes()
    pinned = hashlib.sha256(before_db).hexdigest()
    good = mod.compare(db, csvfile, expected_rows=3, expected_db_sha256=pinned.upper())
    assert good["offline_csv_logically_lossless"] and good["rows_compared"] == 3
    assert not good["safe_to_import_into_populated_app"] and not good["release_go"]
    assert "Example" not in str(good)
    assert db.read_bytes() == before_db and csvfile.read_bytes() == original

    with redirect_stdout(StringIO()) as out:
        previous = sys.argv
        try:
            sys.argv = ["verify", str(db), str(csvfile),
                        "--expected-rows", "3", "--expected-db-sha256", pinned]
            assert mod.main() == 0
        finally:
            sys.argv = previous
    assert '"release_go": false' in out.getvalue() and "Example" not in out.getvalue()

    deny(lambda: mod.compare(db, csvfile, expected_rows=2, expected_db_sha256=pinned))
    deny(lambda: mod.compare(db, csvfile, expected_rows=4, expected_db_sha256=pinned))
    deny(lambda: mod.compare(db, csvfile, expected_rows=3, expected_db_sha256="e"*64))
    deny(lambda: mod.compare(db, csvfile, expected_rows=3, expected_db_sha256="invalid"))

    def rewrite(row_change):
        with csvfile.open(newline="", encoding="utf-8") as f:
            rows = list(csv.reader(f))
        row_change(rows)
        with csvfile.open("w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f, lineterminator="\n")
            writer.writerows(rows)
        csvfile.chmod(0o600)
        deny(lambda: mod.compare(db, csvfile, expected_rows=3, expected_db_sha256=pinned))
        csvfile.write_bytes(original)

    rewrite(lambda rows: rows.pop(2))              # Missing known face
    rewrite(lambda rows: rows.append(rows[-1]))   # Duplicate face
    rewrite(lambda rows: rows[1].__setitem__(1, "Altered person name"))
    rewrite(lambda rows: rows[1].__setitem__(2, "/storage/emulated/0/DCIM/other.jpg"))
    rewrite(lambda rows: rows[1].__setitem__(3, "999"))
    rewrite(lambda rows: rows[1].__setitem__(4, "0.99"))  # Fake confidence
    rewrite(lambda rows: rows[0].__setitem__(0, "wrong-header"))
    rewrite(lambda rows: rows.__setitem__(slice(1, 3), [rows[2], rows[1]]))

    csvfile.chmod(0o644)
    deny(lambda: mod.compare(db, csvfile, expected_rows=3, expected_db_sha256=pinned))
    csvfile.chmod(0o600)
    link = root/"link.csv"
    link.symlink_to(csvfile)
    deny(lambda: mod.compare(db, link, expected_rows=3, expected_db_sha256=pinned))
    link.unlink()
    hardlink = root/"hardlink.csv"
    os.link(csvfile, hardlink)
    deny(lambda: mod.compare(db, csvfile, expected_rows=3, expected_db_sha256=pinned))
    hardlink.unlink()

    # A byte mutation after parsing but before the last check must be caught.
    actual_digest = mod._digest
    calls = [0]
    def change_on_second_hash(path):
        calls[0] += 1
        if calls[0] == 2:
            with path.open("ab") as f:
                f.write(b"\nlate private mutation")
        return actual_digest(path)
    with patch.object(mod, "_digest", side_effect=change_on_second_hash):
        deny(lambda: mod.compare(db, csvfile, expected_rows=3, expected_db_sha256=pinned))
    csvfile.write_bytes(original)

    # A regular path can be replaced between the first digest and csv.reader.
    # A correct second hash is not enough if decoding opened another inode.
    original_digest_fn = mod._digest
    seen_digest_calls = [0]
    parked = root/"parked.csv"
    def replace_between_hash_and_reader(path):
        digest = original_digest_fn(path)
        seen_digest_calls[0] += 1
        if seen_digest_calls[0] == 1:
            path.rename(parked)
            shutil.copy2(parked, path)
            path.chmod(0o600)
        return digest
    with patch.object(mod, "_digest", side_effect=replace_between_hash_and_reader):
        deny(lambda: mod.compare(db, csvfile, expected_rows=3, expected_db_sha256=pinned))
    assert parked.read_bytes() == original
    parked.unlink()
    csvfile.write_bytes(original)
    csvfile.chmod(0o600)

    # Same bytes are not sufficient when the decoder's open FD itself was
    # replaced after identity pinning; post-yield descriptor identity matters.
    opened = mod._identity(csvfile)
    try:
        with mod._stable_csv_reader(csvfile, opened) as protected_stream:
            assert protected_stream.readline().startswith("person_id,")
            with csvfile.open("ab") as file:
                file.write(b"late-write")
            protected_stream.readline()
    except ValueError:
        pass
    else:
        raise AssertionError("CSV descriptor mutated during stream passed the audit")
    csvfile.write_bytes(original)
    csvfile.chmod(0o600)

    # Future columns cannot be silently exported or signed off.
    unknown = root/"future.db"
    shutil.copy2(db, unknown)
    with sqlite3.connect(unknown) as c:
        c.execute("ALTER TABLE assignments ADD COLUMN future_name_note TEXT")
    deny(lambda: mod.compare(unknown, csvfile, expected_rows=3,
                             expected_db_sha256=hashlib.sha256(unknown.read_bytes()).hexdigest()))

    assert mod.compare(db, csvfile, expected_rows=3,
                       expected_db_sha256=pinned)["offline_csv_logically_lossless"]
print("PASS: synthetic CSV exact assignments, lost/duplicated rows, identity tamper, future schema, links, race and no populated-import GO")
