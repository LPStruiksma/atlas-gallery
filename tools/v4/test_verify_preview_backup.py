"""V4 private-backup acceptance gate: fictional SQLite, no real People data."""
from contextlib import redirect_stderr,redirect_stdout
import hashlib
import io
from pathlib import Path
import shutil
import sqlite3
import tempfile
from unittest import mock

from verify_preview_backup import BackupGateError,inspect_backup,logical_digest,main,readonly,user_tables


def expect_no_go(path,contains):
    stderr=io.StringIO()
    with redirect_stderr(stderr):
        assert main(['--backup',str(path)])==1
    report=stderr.getvalue()
    assert 'NO-GO:' in report and contains in report,report
    assert '/fictional/' not in report and 'Secret Person' not in report,(
        'Verifier printed protected fixture values')


with tempfile.TemporaryDirectory(prefix='atlas-v4-backup-fixture-') as root:
    root=Path(root)
    backup=root/'online-v4-backup.sqlite3'
    c=sqlite3.connect(backup)
    c.execute('PRAGMA foreign_keys=ON')
    with c:
        c.executescript("""
          CREATE TABLE images(id INTEGER PRIMARY KEY,path TEXT NOT NULL);
          CREATE TABLE people(id INTEGER PRIMARY KEY,name TEXT NOT NULL);
          CREATE TABLE clusters(id INTEGER PRIMARY KEY,count INTEGER NOT NULL);
          CREATE TABLE faces(id INTEGER PRIMARY KEY,image_id INTEGER
              REFERENCES images(id), person_id INTEGER REFERENCES people(id),
              embedding BLOB NOT NULL);
          CREATE TABLE manual_review_undo(token TEXT PRIMARY KEY,
              snapshot TEXT NOT NULL);
          CREATE TABLE events(id INTEGER PRIMARY KEY,detail TEXT NOT NULL);
        """)
        c.execute("INSERT INTO people VALUES(7,?)",('Secret Person π',))
        c.execute("INSERT INTO images VALUES(9,?)",('/fictional/private-snapshot.jpg',))
        c.execute("INSERT INTO clusters VALUES(2,1)")
        c.execute("INSERT INTO faces VALUES(12,9,7,?)",(bytes(range(256))*2,))
        c.execute("INSERT INTO manual_review_undo VALUES('a','secret-undo')")
        c.execute("INSERT INTO events VALUES(4,?)",('private audit detail',))
    c.close()
    before_hash=hashlib.sha256(backup.read_bytes()).hexdigest()
    before_stat=backup.stat().st_mtime_ns
    result=inspect_backup(backup)
    assert result['result']=='PASS'
    assert result['isolated_restore_rehearsed'] and result['all_user_rows_equal']
    assert result['counts']['people']==1 and result['counts']['faces']==1
    assert result['counts']['manual_review_undo']==1
    assert result['checked_user_tables']==6
    out=io.StringIO()
    with redirect_stdout(out):
        assert main(['--backup',str(backup)])==0
    assert '"result": "PASS"' in out.getvalue()
    assert 'Secret Person' not in out.getvalue()
    assert '/fictional' not in out.getvalue()
    assert 'private audit detail' not in out.getvalue()
    assert not (root/'online-v4-backup.sqlite3-wal').exists()
    assert not (root/'online-v4-backup.sqlite3-shm').exists()
    # Older V4 backups can be self-contained but retain WAL journal mode in
    # their SQLite header. Repeated read-only validation must not generate
    # -shm/-wal companions that would later make this same file fail the gate.
    wal_backup=root/'old-finalized-wal-mode.sqlite3'
    shutil.copy2(backup,wal_backup)
    legacy=sqlite3.connect(wal_backup)
    assert legacy.execute('PRAGMA journal_mode=WAL').fetchone()[0]=='wal'
    legacy.close()
    assert not (root/'old-finalized-wal-mode.sqlite3-wal').exists()
    for _ in range(3):
        assert inspect_backup(wal_backup)['result']=='PASS'
        assert not (root/'old-finalized-wal-mode.sqlite3-wal').exists()
        assert not (root/'old-finalized-wal-mode.sqlite3-shm').exists()
    assert hashlib.sha256(backup.read_bytes()).hexdigest()==before_hash
    assert backup.stat().st_mtime_ns==before_stat
    assert not list(root.glob('atlas-v4-rehearsal-*')),(
        'Scratch private restore should not be left behind')
    # Two databases with identical counts but different personal details
    # must not pass a row-content equality check.
    second=root/'different-online-backup.sqlite3'
    shutil.copy2(backup,second)
    changed=sqlite3.connect(second)
    with changed:changed.execute("UPDATE people SET name='Other Fictional' WHERE id=7")
    changed.close()
    original_ro=readonly(backup)
    changed_ro=readonly(second)
    try:
        assert logical_digest(original_ro,user_tables(original_ro))!=(
            logical_digest(changed_ro,user_tables(changed_ro)))
    finally:
        original_ro.close();changed_ro.close()
    # Missing/corrupted and live-looking sources all fail without overwrites.
    missing=root/'missing.sqlite3'
    expect_no_go(missing,'regular finalized')
    wrong_name=root/'people.sqlite3'
    shutil.copy2(backup,wrong_name)
    expect_no_go(wrong_name,'refusing live-looking')
    tampered=root/'bad-format.sqlite3'
    tampered.write_bytes(b'Not SQLite '+backup.read_bytes()[11:])
    expect_no_go(tampered,'not a SQLite')
    incomplete=root/'missing-tables.sqlite3'
    v=sqlite3.connect(incomplete)
    with v:v.execute('CREATE TABLE dummy(id INTEGER PRIMARY KEY)')
    v.close()
    expect_no_go(incomplete,'required V4 People tables')
    # Intentional orphan in otherwise structurally valid synthetic input.
    orphan=root/'orphan.sqlite3'
    shutil.copy2(backup,orphan)
    v=sqlite3.connect(orphan)
    with v:v.execute("UPDATE faces SET person_id=999 WHERE id=12")
    v.close()
    expect_no_go(orphan,'foreign-key integrity')
    sidecar=root/'online-v4-backup.sqlite3-wal'
    sidecar.write_bytes(b'fictional sidecar')
    expect_no_go(backup,'WAL/SHM')
    sidecar.unlink()
    # Refuse a too-large database, and insufficient scratch disk. Fail
    # before creating a private temporary rehearsal copy.
    try:inspect_backup(backup,max_bytes=100)
    except BackupGateError as e:assert 'size outside' in str(e)
    else:raise AssertionError('Missing size cap')
    real_usage=shutil.disk_usage(tempfile.gettempdir())
    fake_usage=real_usage._replace(free=1)
    with mock.patch('verify_preview_backup.shutil.disk_usage',return_value=fake_usage):
        try:inspect_backup(backup)
        except BackupGateError as e:assert 'disk capacity' in str(e)
        else:raise AssertionError('Insufficient temp disk was accepted')
    assert hashlib.sha256(backup.read_bytes()).hexdigest()==before_hash
    assert backup.stat().st_mtime_ns==before_stat

print('PASS: owner-only V4 backup proof, exact row-recovery digest, private '
      'names/paths/embeddings never printed; corrupt, missing, orphan, WAL, '
      'size/disk, live-looking source all fail closed; original file unchanged')
