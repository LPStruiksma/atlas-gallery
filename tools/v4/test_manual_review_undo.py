"""Synthetic V4 reviewed-assignment undo tests; no phone data or images."""
from pathlib import Path
import importlib.util
import sys
import tempfile
import types

for module in ('cv2','numpy'):
    sys.modules[module]=types.ModuleType(module)
path=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_atomic_undo',path)
v4=importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)

with tempfile.TemporaryDirectory(prefix='atlas-undo-fake-') as directory:
    v4.STATE=Path(directory);v4.DB=v4.STATE/'test.sqlite3';v4.DB_READY_PATH=None
    c=v4.connect()
    with c:
        for pid,name in [(1,'Known'),(2,'Other')]:
            c.execute('INSERT INTO people(id,name,created_at) VALUES(?,?,?)',(pid,name,'now'))
        for face_id in range(1,7):
            c.execute("INSERT INTO images(id,path,size,mtime_ns) VALUES(?,?,?,?)",
                      (face_id,f'/fake/face-{face_id}.jpg',100,1))
            c.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,person_id,cluster_id)
                VALUES(?,?,?,?,?,?,?,?,?,?)""",
                (face_id,face_id,0,1,1,80,80,bytes(512),2 if face_id==4 else None,
                 7 if face_id<=5 else None))
        c.execute("INSERT INTO clusters(id,count,representative,created_at) VALUES(7,4,1,'now')")
    def snapshot():
        return ([tuple(x) for x in c.execute(
            'SELECT id,person_id,cluster_id,ignored,review,suggested_person_id,suggested_score FROM faces ORDER BY id')],
            [tuple(x) for x in c.execute('SELECT id,count,representative,created_at FROM clusters ORDER BY id')])
    before=snapshot()
    result=v4.attach_group_to_person(c,1,7,4,undo=True)
    assert result['count']==4 and len(result['undo_token'])==32
    assert c.execute('SELECT COUNT(*) FROM clusters').fetchone()[0]==0
    assert c.execute('SELECT person_id FROM faces WHERE id=4').fetchone()[0]==2
    assert v4.undo_manual_review(c,result['undo_token'])==4, 'All four assigned faces restored'
    assert snapshot()==before, 'Must recover identities AND Discover cluster representation'
    try:v4.undo_manual_review(c,result['undo_token'])
    except ValueError as error:assert 'already used' in str(error)
    else:raise AssertionError('Replayed undo was allowed')
    assert snapshot()==before

    selected=v4.assign_faces(c,1,[1,2],undo=True)
    assert selected['count']==2
    # A later manual review must block stale undo, not erase newer tags.
    with c:c.execute("UPDATE faces SET review='reviewed_again' WHERE id=1")
    changed=snapshot()
    try:v4.undo_manual_review(c,selected['undo_token'])
    except ValueError as error:assert 'changed since assignment' in str(error)
    else:raise AssertionError('Stale review was overwritten')
    assert snapshot()==changed
    with c:c.execute("UPDATE faces SET review='confirmed' WHERE id=1")
    assert v4.undo_manual_review(c,selected['undo_token'])==2
    assert snapshot()==before, 'Restores all affected groups and outstanding faces'

    # A plain ungrouped face has no collateral changes.
    selected=v4.assign_faces(c,1,[6],undo=True)
    assert v4.undo_manual_review(c,selected['undo_token'])==1
    assert snapshot()==before
    # No source path, face embedding or photograph payload ever stored in undo.
    material=c.execute('SELECT snapshot FROM manual_review_undo ORDER BY rowid DESC LIMIT 1').fetchone()[0]
    assert '/fake/' not in material and 'embedding' not in material
    assert c.execute("SELECT COUNT(*) FROM events WHERE action='manual_review_undo'").fetchone()[0]==3
    c.close()
print('PASS: exact undo, cluster restoration, confirmed-tag protection, stale rejection, replay prevention, biometric-free history')
