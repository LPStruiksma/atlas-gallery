#!/usr/bin/env python3
"""Pure synthetic integration checks. No installed app or private photos."""
import contextlib
from io import StringIO
from pathlib import Path
import shutil
import sqlite3
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).parent))
from verify_native_restore_evidence import verify, main


def reject(fn):
    try:
        fn()
    except (ValueError, OSError):
        return
    raise AssertionError('Unsafe offline restore evidence accepted')


def make(root):
    (root / 'databases').mkdir()
    db = root / 'databases/atlas_native_people.db'
    with sqlite3.connect(db) as sql:
        sql.execute('CREATE TABLE assignments(face_id INTEGER PRIMARY KEY, person_id INTEGER NOT NULL, person_name TEXT NOT NULL, image_path TEXT NOT NULL)')
        sql.executemany('INSERT INTO assignments VALUES(?,?,?,?)', [
            (1, 4, 'Fictional Name', '/storage/emulated/0/DCIM/fake_a.jpg'),
            (2, 4, 'Fictional Name', '/storage/emulated/0/DCIM/fake_b.jpg'),
            (3, 9, 'Another Name', '/storage/emulated/0/DCIM/fake_b.jpg')])
    (root / 'shared_prefs').mkdir()
    (root / 'shared_prefs/atlas.xml').write_text('<map><boolean name="synthetic" value="true"/></map>')
    (root / 'files/datastore').mkdir(parents=True)
    (root / 'files/datastore/demo.preferences_pb').write_bytes(b'fictional preferences')


with tempfile.TemporaryDirectory() as tmp:
    backup, restored = Path(tmp)/'backup', Path(tmp)/'restored'
    backup.mkdir(); restored.mkdir(); make(backup)
    shutil.copytree(backup, restored, dirs_exist_ok=True)
    originals = [x.read_bytes() for x in backup.rglob('*') if x.is_file()]
    result = verify(backup, restored, min_rows=3)
    assert result['people_rows'] == 3 and result['settings_files_matched'] == 2
    assert not result['safe_to_uninstall'] and not result['android_restore_tested']
    assert 'Fictional Name' not in str(result) and 'synthetic' not in str(result)
    assert originals == [x.read_bytes() for x in backup.rglob('*') if x.is_file()]
    with contextlib.redirect_stdout(StringIO()) as out:
        old_argv = sys.argv
        try:
            sys.argv = ['verify', str(backup), str(restored), '--min-rows', '3']
            assert main() == 0
        finally:
            sys.argv = old_argv
    assert 'Fictional Name' not in out.getvalue()
    reject(lambda: verify(backup, backup, min_rows=3))
    reject(lambda: verify(backup, restored, min_rows=4))
    changed = restored/'shared_prefs/atlas.xml'
    changed.write_text('<map>tampered</map>')
    reject(lambda: verify(backup, restored, min_rows=3))
    shutil.copy2(backup/'shared_prefs/atlas.xml', changed)
    changed.unlink()
    reject(lambda: verify(backup, restored, min_rows=3))
    shutil.copy2(backup/'shared_prefs/atlas.xml', changed)
    db = restored/'databases/atlas_native_people.db'
    with sqlite3.connect(db) as sql:
        sql.execute("UPDATE assignments SET person_name='DIFFERENT' WHERE face_id=2")
    reject(lambda: verify(backup, restored, min_rows=3))
    shutil.copy2(backup/'databases/atlas_native_people.db', db)
    # Hardlinked output is not an independently restored copy.
    db.unlink(); db.hardlink_to(backup/'databases/atlas_native_people.db')
    reject(lambda: verify(backup, restored, min_rows=3))
    db.unlink(); shutil.copy2(backup/'databases/atlas_native_people.db', db)
    changed.unlink(); changed.symlink_to(backup/'shared_prefs/atlas.xml')
    reject(lambda: verify(backup, restored, min_rows=3))
    changed.unlink(); shutil.copy2(backup/'shared_prefs/atlas.xml', changed)
    spurious = restored/'shared_prefs/atlas.xml-wal'
    spurious.write_text('unexpected')
    reject(lambda: verify(backup, restored, min_rows=3))
    spurious.unlink()
    assert verify(backup, restored, min_rows=3)['offline_restore_parity']
    # Symlinked *ancestor* directories must not escape the captured tree.
    settings_parent = restored/'files'
    separate = restored/'files-off-tree'
    settings_parent.rename(separate)
    settings_parent.symlink_to(separate, target_is_directory=True)
    reject(lambda: verify(backup, restored, min_rows=3))
    settings_parent.unlink()
    separate.rename(settings_parent)
    # A nested "restored" directory does not represent an independent copy.
    nested = backup/'nested'
    nested.mkdir()
    reject(lambda: verify(backup, nested, min_rows=3))
    # Schema drift must not masquerade as a lossless restore even if the
    # familiar four identity columns and all 3 rows remain unchanged.
    sb, sr = Path(tmp)/'schema-backup', Path(tmp)/'schema-restore'
    shutil.copytree(backup, sb)
    shutil.copytree(restored, sr)
    sdba, sdbr = sb/'databases/atlas_native_people.db', sr/'databases/atlas_native_people.db'

    with sqlite3.connect(sdbr) as sql:
        sql.execute("ALTER TABLE assignments ADD COLUMN person_note TEXT DEFAULT 'PRIVATE'")
    reject(lambda: verify(sb, sr, min_rows=3))
    with sqlite3.connect(sdba) as sql:
        sql.execute("ALTER TABLE assignments ADD COLUMN person_note TEXT DEFAULT 'PRIVATE'")
    # Identical future schema on both sides still requires a NEW validated
    # recovery format: current CSV exporter will discard person_note.
    reject(lambda: verify(sb, sr, min_rows=3))
    shutil.copy2(backup/'databases/atlas_native_people.db', sdba)
    shutil.copy2(backup/'databases/atlas_native_people.db', sdbr)

    with sqlite3.connect(sdbr) as sql:
        sql.execute("CREATE TABLE new_identity_metadata(id INTEGER PRIMARY KEY, private_note TEXT)")
    reject(lambda: verify(sb, sr, min_rows=3))
    shutil.copy2(backup/'databases/atlas_native_people.db', sdbr)

    # The Android SQLiteOpenHelper schema version must agree, even if rows do.
    with sqlite3.connect(sdbr) as sql:
        sql.execute("PRAGMA user_version=2")
    reject(lambda: verify(sb, sr, min_rows=3))
    with sqlite3.connect(sdbr) as sql:
        sql.execute("PRAGMA user_version=1")
    reject(lambda: verify(sb, sr, min_rows=3))
    with sqlite3.connect(sdba) as sql:
        sql.execute("PRAGMA user_version=1")
    assert verify(sb, sr, min_rows=3)['offline_restore_parity']

    # Losing an index is a schema regression, not a complete DB roundtrip.
    with sqlite3.connect(sdba) as sql:
        sql.execute("CREATE INDEX path_assignments ON assignments(image_path)")
    reject(lambda: verify(sb, sr, min_rows=3))
    with sqlite3.connect(sdbr) as sql:
        sql.execute("CREATE INDEX path_assignments ON assignments(image_path)")
    assert verify(sb, sr, min_rows=3)['offline_restore_parity']

    # Android metadata is optional in plain SQLite fixtures, but must be
    # preserved exactly if present in an actual SQLiteOpenHelper database.
    with sqlite3.connect(sdba) as sql:
        sql.execute("CREATE TABLE android_metadata(locale TEXT)")
        sql.execute("INSERT INTO android_metadata VALUES ('en_US')")
    reject(lambda: verify(sb, sr, min_rows=3))
    with sqlite3.connect(sdbr) as sql:
        sql.execute("CREATE TABLE android_metadata(locale TEXT)")
        sql.execute("INSERT INTO android_metadata VALUES ('nl_NL')")
    reject(lambda: verify(sb, sr, min_rows=3))
    with sqlite3.connect(sdbr) as sql:
        sql.execute("UPDATE android_metadata SET locale='en_US'")
    assert verify(sb, sr, min_rows=3)['offline_restore_parity']

print('PASS: synthetic full schema/version/metadata parity, future-field refusal, identity/settings equality')

