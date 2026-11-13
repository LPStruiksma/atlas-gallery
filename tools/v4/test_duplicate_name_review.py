"""Offline V4 identity-review safeguards: synthetic names, faces and clusters only."""
from pathlib import Path
import importlib.util
import sqlite3
import sys
import tempfile
import types

for module in ('cv2', 'numpy'):
    sys.modules[module] = types.ModuleType(module)
path=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_review_guard',path)
v4=importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)

with tempfile.TemporaryDirectory(prefix='atlas-v4-reviewed-synthetic-') as directory:
    v4.STATE=Path(directory)
    v4.DB=v4.STATE/'people.sqlite3'
    v4.DB_READY_PATH=None
    db=v4.connect()
    with db:
        db.execute("INSERT INTO people(id,name,created_at) VALUES(1,'Alice','now')")
        for face_id in range(1,6):
            db.execute("INSERT INTO images(id,path,size,mtime_ns) VALUES(?,?,?,?)",
                       (face_id,f'/synthetic/face-{face_id}.jpg',100,1))
            db.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,person_id,cluster_id,ignored)
                       VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                       (face_id,face_id,0,1,1,40,40,bytes(512),
                        1 if face_id==4 else None,7 if face_id<=4 else None,
                        1 if face_id==5 else 0))
        db.execute("INSERT INTO clusters(id,count,representative,created_at) VALUES(7,3,1,'now')")

    def faces():
        return [tuple(row) for row in db.execute(
            'SELECT id,person_id,cluster_id,ignored,review FROM faces ORDER BY id')]
    def people():
        return [tuple(row) for row in db.execute('SELECT id,name FROM people ORDER BY id')]
    original=faces()
    assert v4.normalized_person_name('  ＡＬＩＣＥ  ')=='alice'
    assert v4.normalized_person_name('A\u030alice')==v4.normalized_person_name('Ålice')

    for label in [' alice ', 'ＡＬＩＣＥ', 'Alice']:
        try:
            v4.create_person(db,label)
        except ValueError as error:
            assert 'Name already exists' in str(error)
        else:
            raise AssertionError('Duplicate name silently created')
        assert len(people())==1 and faces()==original, 'Reject must be atomic'

    for expected in [4,2]:
        try:
            v4.create_person(db,'Bea',cluster_id=7,expected_count=expected)
        except ValueError as error:
            assert 'Group changed since review' in str(error)
        else:
            raise AssertionError('Stale review count committed')
        assert faces()==original and len(people())==1

    try:
        v4.create_person(db,'Bea',face_ids=[1,4])
    except ValueError as error:
        assert 'already assigned' in str(error)
    else:
        raise AssertionError('Overwrote confirmed other person')
    assert faces()==original

    try:
        v4.create_person(db,'Bea',face_ids=[5])
    except ValueError as error:
        assert 'hidden' in str(error)
    else:
        raise AssertionError('Reactivated an intentionally hidden face')
    assert faces()==original

    second=v4.create_person(db,'Bea',cluster_id=7,expected_count=3)
    assert db.execute('SELECT name FROM people WHERE id=?',(second,)).fetchone()[0]=='Bea'
    assert [row[1] for row in faces()][:3]==[second]*3
    assert faces()[3][1]==1,'Already confirmed person may never be overwritten'
    assert faces()[4][1] is None,'Hidden face must not be assigned'

    homonym=v4.create_person(db,'   ALICE ',allow_duplicate_name=True)
    assert db.execute('SELECT name FROM people WHERE id=?',(homonym,)).fetchone()[0]=='ALICE'
    assert len([row for row in people() if v4.normalized_person_name(row[1])=='alice'])==2
    try:
        v4.create_person(db,'Alice')
    except ValueError: pass
    else: raise AssertionError('Multiple existing homonyms must still block silent duplicates')
    assert db.execute("SELECT COUNT(*) FROM events WHERE action='create_person'").fetchone()[0]==2
    db.close()

print('PASS: NFKC/casefold names, explicit homonyms, stale review atomicity, mixed-group and hidden-face protection')
