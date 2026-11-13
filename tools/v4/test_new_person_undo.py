"""New People profile undo: exact synthetic recovery, stale-data refusal, no real media."""
from pathlib import Path
import importlib.util
import sys
import tempfile
import types

for module in ('cv2','numpy'):
    sys.modules[module]=types.ModuleType(module)
path=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_create_undo_synthetic',path)
v4=importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)

with tempfile.TemporaryDirectory(prefix='atlas-v4-undo-create-fake-') as folder:
    v4.STATE=Path(folder); v4.DB=v4.STATE/'synthetic.sqlite3'; v4.DB_READY_PATH=None
    db=v4.connect()
    with db:
        db.execute("INSERT INTO people(id,name,created_at) VALUES(9,'Known','now')")
        for face in range(1,10):
            db.execute("INSERT INTO images(id,path,size,mtime_ns) VALUES(?,?,?,?)",
                       (face,f'/fictional/face_{face}.png',100,1))
            db.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,person_id,cluster_id,ignored)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                (face,face,0,1,1,60,60,bytes(512),9 if face==4 else None,
                 7 if face<=4 else 8 if face<=8 else None,1 if face==8 else 0))
        db.execute("INSERT INTO clusters(id,count,representative,created_at) VALUES(7,3,1,'now')")
        db.execute("INSERT INTO clusters(id,count,representative,created_at) VALUES(8,3,5,'now')")

    def snapshot():
        return {
            'people':[tuple(r) for r in db.execute('SELECT id,name,note,created_at FROM people ORDER BY id')],
            'faces':[tuple(r) for r in db.execute('''SELECT id,person_id,cluster_id,ignored,review,
                    suggested_person_id,suggested_score FROM faces ORDER BY id''')],
            'groups':[tuple(r) for r in db.execute('SELECT id,count,representative,created_at FROM clusters ORDER BY id')]
        }
    original=snapshot()

    group=v4.create_person(db,'New Group',cluster_id=7,expected_count=3,undo=True)
    assert isinstance(group,dict) and len(group['undo_token'])==32,group
    assert db.execute("SELECT COUNT(*) FROM faces WHERE person_id=?",(group['person_id'],)).fetchone()[0]==3
    assert db.execute("SELECT person_id FROM faces WHERE id=4").fetchone()[0]==9
    assert v4.undo_manual_review(db,group['undo_token'])==3
    assert snapshot()==original,'Undo must restore group, named profiles, unassigned review flags'
    try:v4.undo_manual_review(db,group['undo_token'])
    except ValueError as exc:assert 'already used' in str(exc)
    else:raise AssertionError('Undo token replay succeeded')

    selected=v4.create_person(db,'Selected',face_ids=[5,6],undo=True)
    assert v4.undo_manual_review(db,selected['undo_token'])==2
    assert snapshot()==original,'Selected new-person undo lost provisional group metadata'

    empty=v4.create_person(db,'Empty',undo=True)
    assert v4.undo_manual_review(db,empty['undo_token'])==0
    assert snapshot()==original,'Empty People profile did not disappear'

    homonym=v4.create_person(db,'Known',face_ids=[1],allow_duplicate_name=True,undo=True)
    assert v4.undo_manual_review(db,homonym['undo_token'])==1
    assert snapshot()==original,'Genuine homonym undo damaged the prior named profile'

    def blocked(mutate,expected):
        entry=v4.create_person(db,'Guarded',face_ids=[9],undo=True)
        with db:mutate(entry['person_id'])
        changed=snapshot()
        try:v4.undo_manual_review(db,entry['undo_token'])
        except ValueError as error:assert expected in str(error),str(error)
        else:raise AssertionError('Unsafe undo succeeded')
        assert snapshot()==changed,'Rejected undo mutated a newer review'
        # Restore only this synthetic fixture explicitly; not an undo shortcut.
        with db:
            db.execute('DELETE FROM faces WHERE id=9')
            db.execute('DELETE FROM people WHERE id=?',(entry['person_id'],))
            db.execute("INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding) VALUES(9,9,0,1,1,60,60,?)",
                       (bytes(512),))

    blocked(lambda pid: db.execute('UPDATE people SET name=? WHERE id=?',('Renamed',pid)), 'changed since creation')
    blocked(lambda pid: db.execute('UPDATE faces SET review=? WHERE id=9',('other_review',)),
            'changed since assignment')
    blocked(lambda pid: db.execute('UPDATE faces SET person_id=? WHERE id=5',(pid,)),
            'gained or lost faces')

    cover=v4.create_person(db,'Cover Guard',face_ids=[9],undo=True)
    with db: db.execute('INSERT INTO person_covers(person_id,face_id,updated_at) VALUES(?,?,?)',
                       (cover['person_id'],9,'now'))
    try:v4.undo_manual_review(db,cover['undo_token'])
    except ValueError as exc: assert 'related review data' in str(exc)
    else:raise AssertionError('Undo discarded manual cover override')
    assert db.execute('SELECT COUNT(*) FROM people WHERE id=?',(cover['person_id'],)).fetchone()[0]==1

    history=db.execute('SELECT snapshot FROM manual_review_undo').fetchall()
    assert all('/fictional/' not in row[0] and 'embedding' not in row[0] and 'New Group' not in row[0] and 'Cover Guard' not in row[0] for row in history)
    assert len(history)<=12,'Review undo history must stay bounded'
    db.close()
print('PASS: new-profile/group/selection/homonym undo, exact restoration, stale ref/name/cover rejection, replay, bounded biometric-free history')
