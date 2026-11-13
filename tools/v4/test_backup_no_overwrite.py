"""V4 online backup output collision safety, entirely fictional People data."""
from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import sqlite3
from pathlib import Path
import stat
import sys
import tempfile
import types

sys.modules['cv2']=types.ModuleType('cv2')
sys.modules['numpy']=types.ModuleType('numpy')
source=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_no_overwrite',source)
v4=importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)

from verify_preview_backup import inspect_backup

with tempfile.TemporaryDirectory(prefix='atlas-v4-private-backup-test-') as root:
    root=Path(root)
    v4.STATE=root/'active-private-fixture'
    v4.DB=v4.STATE/'people.sqlite3'
    v4.DB_READY_PATH=None
    c=v4.connect()
    with c:
        c.execute("INSERT INTO people(id,name,created_at) VALUES(7,'Imaginary Person','now')")
        c.execute("INSERT INTO images(id,path,size,mtime_ns,state) VALUES (9,'/fictional/never-exists.jpg',10,1,'ready')")
        c.execute("INSERT INTO clusters(id,count,representative,created_at) VALUES(2,1,11,'now')")
        c.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,
          cluster_id,person_id,review) VALUES(11,9,0,0,0,10,10,?,2,7,'confirmed')""",
          (bytes(512),))
        c.execute("""INSERT INTO manual_review_undo(token,at,kind,person_id,snapshot)
            VALUES('fictional-undo','now','test',7,'synthetic only')""")
    c.close()
    previous=root/'atlas_v4_before_preview.sqlite3'
    stdout=io.StringIO()
    with redirect_stdout(stdout):v4.backup_database(previous)
    assert 'Backup:' in stdout.getvalue()
    assert stat.S_IMODE(previous.stat().st_mode)==0o600
    first=inspect_backup(previous)
    assert first['result']=='PASS'
    assert first['counts']['people']==1 and first['counts']['faces']==1
    assert first['counts']['manual_review_undo']==1
    standalone=sqlite3.connect(previous)
    try:
        assert standalone.execute('PRAGMA journal_mode').fetchone()[0]=='delete', (
            'New V4 backups must be standalone single-file SQLite, not WAL')
    finally:standalone.close()
    for _ in range(2):
        assert inspect_backup(previous)['result']=='PASS'
        assert not previous.with_name(previous.name+'-wal').exists()
        assert not previous.with_name(previous.name+'-shm').exists()
    original=hashlib.sha256(previous.read_bytes()).hexdigest()
    old_mtime=previous.stat().st_mtime_ns
    try:v4.backup_database(previous)
    except ValueError as e:assert 'already exists' in str(e)
    else:raise AssertionError('Duplicate backup overwritten without warning')
    assert hashlib.sha256(previous.read_bytes()).hexdigest()==original
    assert previous.stat().st_mtime_ns==old_mtime
    # A future backup can safely capture new People without losing the
    # independently verified earlier recovery point.
    c=v4.connect()
    with c:c.execute("INSERT INTO people(id,name,created_at) VALUES(8,'Fictional New','later')")
    c.close()
    newer=root/'atlas_v4_after_new_person.sqlite3'
    with redirect_stdout(io.StringIO()):v4.backup_database(newer)
    assert inspect_backup(previous)['counts']['people']==1
    assert inspect_backup(newer)['counts']['people']==2
    assert hashlib.sha256(previous.read_bytes()).hexdigest()==original
    # Never allow the live SQLite file itself to be used as a backup output.
    live_before=hashlib.sha256(v4.DB.read_bytes()).hexdigest()
    try:v4.backup_database(v4.DB)
    except ValueError:pass
    else:raise AssertionError('Destination accepted active SQLite file')
    assert hashlib.sha256(v4.DB.read_bytes()).hexdigest()==live_before
    # Failures after exclusive destination creation remove only that NEW
    # partially created file; unrelated earlier snapshots remain intact.
    failed=root/'no-incomplete-result.sqlite3'
    old_connect=v4.connect
    def broken_source():
        raise RuntimeError('Synthetic source open failure')
    v4.connect=broken_source
    try:
        try:v4.backup_database(failed)
        except RuntimeError:pass
        else:raise AssertionError('Injected backup failure was hidden')
    finally:v4.connect=old_connect
    assert not failed.exists(),'Partial new file remained after failed backup'
    assert hashlib.sha256(previous.read_bytes()).hexdigest()==original
    assert not (root/'missing-parent'/'unused.sqlite3').exists()

print('PASS: V4 backup uses exclusive new output, preserves old snapshots and '
      'undo rows, refuses live DB collision; simulated failure cleans only '
      'incomplete new file, synthetic verification/recovery succeeds')
