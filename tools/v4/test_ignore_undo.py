"""V4 Hide Undo: all images and names below are synthetic."""
from pathlib import Path
import importlib.util
import sys
import tempfile
import types

for name in ('cv2','numpy'):sys.modules[name]=types.ModuleType(name)
script=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_hide_test',script)
v4=importlib.util.module_from_spec(spec);spec.loader.exec_module(v4)
with tempfile.TemporaryDirectory(prefix='atlas-v4-fake-hide-') as folder:
    v4.STATE=Path(folder);v4.DB=v4.STATE/'fake.sqlite3';v4.DB_READY_PATH=None
    db=v4.connect()
    with db:
        db.execute("INSERT INTO people(id,name,created_at) VALUES(7,'Fiction','now')")
        for fid,pid,hidden,cluster in [(1,None,0,42),(2,None,0,42),
                                      (3,7,0,None),(4,None,1,None),
                                      (5,None,0,None)]:
            db.execute('INSERT INTO images(id,path,size,mtime_ns) VALUES(?,?,?,?)',
                       (fid,f'/fictional/{fid}.jpg',200,1))
            db.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,person_id,ignored,cluster_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                (fid,fid,0,1,1,60,60,bytes(512),pid,hidden,cluster))
        db.execute("INSERT INTO clusters(id,count,representative,created_at) VALUES(42,2,1,'now')")
    def state():
        return (
            [tuple(r) for r in db.execute('''SELECT id,person_id,review,ignored,cluster_id,
                         suggested_person_id,suggested_score FROM faces ORDER BY id''')],
            [tuple(r) for r in db.execute('SELECT * FROM clusters ORDER BY id')]
        )
    original=state()
    def refused(ids,reason):
        before=state()
        try:v4.ignore_faces(db,ids,undo=True)
        except ValueError as exc:assert reason in str(exc),str(exc)
        else:raise AssertionError('Unsafe hide accepted')
        assert state()==before
    refused([1,3],'assigned')
    refused([4],'assigned')
    refused([1,404],'no longer exist')
    assert db.execute('SELECT COUNT(*) FROM manual_review_undo').fetchone()[0]==0
    undo=v4.ignore_faces(db,[1],undo=True)
    assert undo['count']==1 and len(undo['undo_token'])==32
    assert db.execute('SELECT COUNT(*) FROM clusters').fetchone()[0]==0
    assert db.execute('SELECT cluster_id FROM faces WHERE id=2').fetchone()[0] is None
    assert db.execute('SELECT person_id FROM faces WHERE id=3').fetchone()[0]==7
    assert v4.undo_manual_review(db,undo['undo_token'])==1
    assert state()==original,'Provisional group must return after Hide Undo'
    try:v4.undo_manual_review(db,undo['undo_token'])
    except ValueError as exc:assert 'already used' in str(exc)
    else:raise AssertionError('Undo replay accepted')
    stale=v4.ignore_faces(db,[1],undo=True)
    with db:db.execute('UPDATE faces SET person_id=7 WHERE id=2')
    changed=state()
    try:v4.undo_manual_review(db,stale['undo_token'])
    except ValueError as exc:assert 'changed since assignment' in str(exc)
    else:raise AssertionError('Stale Undo overwrote later assignment')
    assert state()==changed
    with db:db.execute('UPDATE faces SET person_id=NULL WHERE id=2')
    assert v4.undo_manual_review(db,stale['undo_token'])==1
    assert state()==original
    ungrouped=v4.ignore_faces(db,[5],undo=True)
    assert v4.undo_manual_review(db,ungrouped['undo_token'])==1
    assert state()==original
    assert v4.ignore_faces(db,[])==0
    assert v4.ignore_faces(db,[],undo=True)=={'count':0,'undo_token':None}
    snapshots=[r[0] for r in db.execute('SELECT snapshot FROM manual_review_undo')]
    assert all('/fictional/' not in s and 'embedding' not in s and 'Fiction' not in s for s in snapshots)
    assert db.execute('PRAGMA foreign_key_check').fetchall()==[]
    db.close()
code=script.read_text('utf-8')
assert "elif path=='/api/face/ignore':v=ignore_faces(c,body.get('face_ids',[]),undo=True,request_id=body.get('request_id'))" in code
assert "finishDirectReviewedPost('/api/face/ignore',{face_ids:ids},ids,'Faces hidden · Undo is available',origin)" in code
assert "for(const id of submittedIds)selected.delete(id)" in code
assert "async function matchesPage()" in code and "Ignore selected','ignore','secondary')" in code
print('PASS: synthetic hide safety, entire provisional group Undo, exact stale rejection and no private data')
