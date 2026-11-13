#!/usr/bin/env python3
"""Synthetic-only complete offline recovery bundle proofs; no device or media."""
from contextlib import redirect_stdout
import hashlib
from io import StringIO
import os
from pathlib import Path
import shutil
import sqlite3
import sys
import tempfile
from unittest.mock import patch
import check_offline_recovery_bundle as module

sys.path.insert(0, str(Path(__file__).parent))
from check_offline_recovery_bundle import check_bundle, main


def blocked(action):
    try:
        action()
    except (ValueError, OSError, sqlite3.DatabaseError):
        return
    raise AssertionError("Incomplete or incompatible recovery evidence was accepted")


def fixture(path):
    path.mkdir(mode=0o700)
    for relative in ("databases", "shared_prefs", "files", "files/datastore"):
        folder = path / relative
        folder.mkdir(exist_ok=True)
        folder.chmod(0o700)
    db = path/"databases/atlas_native_people.db"
    with sqlite3.connect(db) as con:
        con.execute("CREATE TABLE assignments(face_id INTEGER PRIMARY KEY,person_id INTEGER NOT NULL,person_name TEXT NOT NULL,image_path TEXT NOT NULL)")
        con.execute("CREATE INDEX path_assignments ON assignments(image_path)")
        con.execute("CREATE INDEX person_assignments ON assignments(person_id)")
        con.execute("PRAGMA user_version=1")
        con.executemany("INSERT INTO assignments VALUES(?,?,?,?)", [
            (101, 4, "Purely fictional A", "/storage/emulated/0/DCIM/fake-a.jpg"),
            (102, 4, "Purely fictional A", "/storage/emulated/0/DCIM/fake-b.jpg"),
            (103, 9, "Purely fictional B", "/storage/emulated/0/DCIM/fake-b.jpg")
        ])
    (path/"shared_prefs/atlas.xml").write_text("<map><boolean name='fictional' value='true'/></map>")
    (path/"files/datastore/atlas.preferences_pb").write_bytes(b"fictional preferences only")
    for member in path.rglob("*"):
        member.chmod(0o700 if member.is_dir() else 0o600)


with tempfile.TemporaryDirectory() as tmp:
    home = Path(tmp)
    original, restored = home/"backup", home/"isolated-restored"
    fixture(original)
    shutil.copytree(original, restored)
    for member in restored.rglob("*"):
        member.chmod(0o700 if member.is_dir() else 0o600)
    digest = hashlib.sha256((original/"databases/atlas_native_people.db").read_bytes()).hexdigest()
    before = {member.relative_to(original).as_posix(): member.read_bytes()
              for member in original.rglob("*") if member.is_file()}

    good = check_bundle(original, restored, expected_rows=3,
                        expected_settings_files=2, expected_backup_sha256=digest.upper())
    assert good["offline_bundle_consistency"] and good["strict_file_inventory_checked"]
    assert good["logical_people_and_settings_parity_checked"]
    assert good["people_rows"] == 3 and good["settings_files"] == 2
    for name in ("phone_capture_origin_authenticated", "real_android_restore_verified",
                 "saf_permissions_verified", "candidate_apk_signer_verified",
                 "safe_to_uninstall", "release_go"):
        assert good[name] is False
    assert "fictional" not in str(good).lower() and "fake-a" not in str(good)
    assert before == {member.relative_to(original).as_posix(): member.read_bytes()
                      for member in original.rglob("*") if member.is_file()}

    previous = sys.argv
    try:
        sys.argv = ["check", str(original), str(restored),
                    "--expected-rows", "3", "--expected-settings-files", "2",
                    "--expected-backup-sha256", digest]
        with redirect_stdout(StringIO()) as output:
            assert main() == 0
        assert '"release_go": false' in output.getvalue()
        assert "fictional" not in output.getvalue().lower()
    finally:
        sys.argv = previous

    blocked(lambda: check_bundle(original, restored, expected_rows=4,
                                 expected_settings_files=2, expected_backup_sha256=digest))
    blocked(lambda: check_bundle(original, restored, expected_rows=2,
                                 expected_settings_files=2, expected_backup_sha256=digest))
    blocked(lambda: check_bundle(original, restored, expected_rows=3,
                                 expected_settings_files=3, expected_backup_sha256=digest))
    blocked(lambda: check_bundle(original, restored, expected_rows=3,
                                 expected_settings_files=2, expected_backup_sha256="invalid"))
    blocked(lambda: check_bundle(original, restored, expected_rows=3,
                                 expected_settings_files=2, expected_backup_sha256="f"*64))
    blocked(lambda: check_bundle(original, original, expected_rows=3,
                                 expected_settings_files=2, expected_backup_sha256=digest))

    setting = restored/"shared_prefs/atlas.xml"
    setting.write_text("<map>changed settings</map>")
    blocked(lambda: check_bundle(original, restored, expected_rows=3,
                                 expected_settings_files=2, expected_backup_sha256=digest))
    setting.write_bytes(before["shared_prefs/atlas.xml"])

    target = restored/"databases/atlas_native_people.db"
    with sqlite3.connect(target) as con:
        con.execute("UPDATE assignments SET person_name='different fictional value' WHERE face_id=102")
    blocked(lambda: check_bundle(original, restored, expected_rows=3,
                                 expected_settings_files=2, expected_backup_sha256=digest))
    shutil.copy2(original/"databases/atlas_native_people.db", target)
    target.chmod(0o600)

    unknown = original/"files/future_identity_state.bin"
    unknown.write_bytes(b"fictional new private records")
    unknown.chmod(0o600)
    also_unknown = restored/"files/future_identity_state.bin"
    also_unknown.write_bytes(unknown.read_bytes())
    also_unknown.chmod(0o600)
    blocked(lambda: check_bundle(original, restored, expected_rows=3,
                                 expected_settings_files=2, expected_backup_sha256=digest))
    unknown.unlink()
    also_unknown.unlink()

    setting.unlink()
    os.link(original/"shared_prefs/atlas.xml", setting)
    blocked(lambda: check_bundle(original, restored, expected_rows=3,
                                 expected_settings_files=2, expected_backup_sha256=digest))
    setting.unlink()
    setting.write_bytes(before["shared_prefs/atlas.xml"])
    setting.chmod(0o600)

    sidecar = original/"databases/atlas_native_people.db-wal"
    sidecar.write_bytes(b"fictional uncheckpointed rows")
    sidecar.chmod(0o600)
    blocked(lambda: check_bundle(original, restored, expected_rows=3,
                                 expected_settings_files=2, expected_backup_sha256=digest))
    sidecar.unlink()

    assert check_bundle(original, restored, expected_rows=3,
                        expected_settings_files=2, expected_backup_sha256=digest)["both_snapshots_stable_during_checks"]

    # Emulate an input changing *after* verify() read the correct rows.
    # Before this patch, only source DB was re-hashed; a changed restored DB
    # or setting at the end of logical parity could incorrectly be accepted.
    real_verify = module.verify
    restored_db = restored/"databases/atlas_native_people.db"
    def mutate_restored_db(*args, **kwargs):
        result = real_verify(*args, **kwargs)
        with sqlite3.connect(restored_db) as con:
            con.execute("UPDATE assignments SET person_name='AFTER CHECK' WHERE face_id=102")
        return result
    with patch.object(module, "verify", side_effect=mutate_restored_db):
        blocked(lambda: check_bundle(original, restored, expected_rows=3,
                                     expected_settings_files=2, expected_backup_sha256=digest))
    shutil.copy2(original/"databases/atlas_native_people.db", restored_db)
    restored_db.chmod(0o600)

    restored_setting = restored/"shared_prefs/atlas.xml"
    def mutate_restored_setting(*args, **kwargs):
        result = real_verify(*args, **kwargs)
        restored_setting.write_text("<map>late-changing setting</map>")
        restored_setting.chmod(0o600)
        return result
    with patch.object(module, "verify", side_effect=mutate_restored_setting):
        blocked(lambda: check_bundle(original, restored, expected_rows=3,
                                     expected_settings_files=2, expected_backup_sha256=digest))
    restored_setting.write_bytes(before["shared_prefs/atlas.xml"])
    restored_setting.chmod(0o600)

    future = restored/"files/new-future-private-key.bin"
    def add_unknown_file(*args, **kwargs):
        result = real_verify(*args, **kwargs)
        future.write_bytes(b"fictional extra file introduced during comparison")
        future.chmod(0o600)
        return result
    with patch.object(module, "verify", side_effect=add_unknown_file):
        blocked(lambda: check_bundle(original, restored, expected_rows=3,
                                     expected_settings_files=2, expected_backup_sha256=digest))
    future.unlink()
    assert check_bundle(original, restored, expected_rows=3,
                        expected_settings_files=2, expected_backup_sha256=digest)["offline_bundle_consistency"]

print("PASS: complete offline inventory/People parity, both-copy stability, adversarial mid-check mutations and NO-GO")
