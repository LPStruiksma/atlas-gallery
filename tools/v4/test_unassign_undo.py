"""Unassign reviewed faces: fully synthetic reversible rejection/provenance tests."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import types

for name in ('cv2','numpy'):sys.modules[name]=types.ModuleType(name)
script=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_unassign_test',script)
v4=importlib.util.module_from_spec(spec);spec.loader.exec_module(v4)

with tempfile.TemporaryDirectory(prefix='atlas-v4-fictional-unassign-') as folder:
    v4.STATE=Path(folder);v4.DB=v4.STATE/'fictional.sqlite3';v4.DB_READY_PATH=None
    c=v4.connect()
    with c:
        c.execute("INSERT INTO people(id,name,created_at) VALUES(10,'Review Target','now')")
        c.execute("INSERT INTO people(id,name,created_at) VALUES(11,'Other','now')")
        for fid,pid,review in [(1,10,'confirmed'),(2,10,'auto'),(3,11,'confirmed'),
                               (4,None,'unreviewed'),(5,None,'unreviewed'),(6,10,'confirmed')]:
            c.execute("INSERT INTO images(id,path,size,mtime_ns) VALUES(?,?,?,?)",
                      (fid,f'/fake/no-media-{fid}.jpg',10,1))
            c.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,person_id,review)
                         VALUES(?,?,?,?,?,?,?,?,?,?)""",
                      (fid,fid,0,1,1,100,100,bytes(512),pid,review))
        c.execute('INSERT INTO rejected(face_id,person_id) VALUES(4,10)')
        c.execute("""INSERT INTO auto_matches(id,face_id,person_id,score,model_version,batch_id,created_at)
           VALUES(?,?,?,?,?,?,?)""",(7,2,10,.83,'fictional','batch-fake','now'))
        c.execute('INSERT INTO person_covers(person_id,face_id,updated_at) VALUES(?,?,?)',
                  (10,6,'now'))

    def state():
        return {
            'faces':[tuple(row) for row in c.execute('''SELECT id,person_id,review,cluster_id,ignored,
                suggested_person_id,suggested_score FROM faces ORDER BY id''')],
            'rejected':[tuple(row) for row in c.execute('SELECT face_id,person_id FROM rejected ORDER BY face_id,person_id')],
            'auto':[tuple(row) for row in c.execute('SELECT id,face_id,person_id,reverted_at FROM auto_matches ORDER BY id')],
            'covers':[tuple(row) for row in c.execute('SELECT person_id,face_id,updated_at FROM person_covers')],
        }
    baseline=state()
    def refused(pid,ids,msg):
        before=state()
        try:v4.unassign_faces(c,pid,ids,undo=True)
        except ValueError as err:assert msg in str(err),str(err)
        else:raise AssertionError('Accepted invalid reviewed faces')
        assert state()==before,'Refused operation changed live identity records'
    refused(10,[1,3],'another person')
    refused(10,[1,4],'another person')
    refused(10,[1,900],'no longer exist')
    refused(11,[1],'another person')
    refused(999,[1],'Unknown')
    assert c.execute('SELECT COUNT(*) FROM manual_review_undo').fetchone()[0]==0

    result=v4.unassign_faces(c,10,[1,2],undo=True)
    assert result['count']==2 and len(result['undo_token'])==32
    assert c.execute('SELECT person_id FROM faces WHERE id=1').fetchone()[0] is None
    assert c.execute('SELECT person_id FROM faces WHERE id=2').fetchone()[0] is None
    assert c.execute('SELECT person_id FROM faces WHERE id=3').fetchone()[0]==11
    assert c.execute('SELECT 1 FROM rejected WHERE face_id=2 AND person_id=10').fetchone()
    assert c.execute('SELECT reverted_at FROM auto_matches WHERE id=7').fetchone()[0] is not None
    assert c.execute('SELECT person_id FROM person_covers WHERE face_id=6').fetchone()[0]==10
    assert c.execute('SELECT 1 FROM rejected WHERE face_id=4 AND person_id=10').fetchone()
    assert v4.undo_manual_review(c,result['undo_token'])==2
    assert state()==baseline,'Must return both manually confirmed and historical auto tags, and historical rejection state'
    try:v4.undo_manual_review(c,result['undo_token'])
    except ValueError as exc:assert 'already used' in str(exc)
    else:raise AssertionError('Replayed undo accepted')

    manual=v4.unassign_faces(c,10,[1],undo=True)
    assert v4.undo_manual_review(c,manual['undo_token'])==1
    assert state()==baseline

    # Newer face modifications, rejection entries or auto-match changes must
    # block undo atomically; restore fixture state for the next synthetic case.
    for mutate,restore,reason in [
        (lambda:c.execute('UPDATE faces SET person_id=11 WHERE id=1'),
         lambda:c.execute('UPDATE faces SET person_id=NULL WHERE id=1'),
         'changed since assignment'),
        (lambda:c.execute('INSERT INTO rejected(face_id,person_id) VALUES(1,11)'),
         lambda:c.execute('DELETE FROM rejected WHERE face_id=1 AND person_id=11'),
         'history changed'),
        (lambda:c.execute("UPDATE auto_matches SET reverted_at='changed again' WHERE id=7"),
         lambda:c.execute('UPDATE auto_matches SET reverted_at=? WHERE id=7',(c.execute('SELECT reverted_at FROM auto_matches WHERE id=7').fetchone()[0],)),
         'history changed'),
    ]:
        token=v4.unassign_faces(c,10,[1,2],undo=True)['undo_token']
        stored=c.execute('SELECT reverted_at FROM auto_matches WHERE id=7').fetchone()[0]
        with c:mutate()
        changed=state()
        try:v4.undo_manual_review(c,token)
        except ValueError as exc:assert reason in str(exc),str(exc)
        else:raise AssertionError('Stale Undo overwrote newer review data')
        assert state()==changed
        with c:
            if reason=='history changed' and c.execute('SELECT reverted_at FROM auto_matches WHERE id=7').fetchone()[0]=='changed again':
                c.execute('UPDATE auto_matches SET reverted_at=? WHERE id=7',(stored,))
            else:restore()
        assert v4.undo_manual_review(c,token)==2
        assert state()==baseline

    assert v4.unassign_faces(c,10,[],undo=True)=={'count':0,'undo_token':None}
    history=[r[0] for r in c.execute('SELECT snapshot FROM manual_review_undo')]
    assert len(history)<=12
    assert all('/fake/' not in snapshot and 'embedding' not in snapshot and 'Review Target' not in snapshot
               for snapshot in history)
    assert c.execute('PRAGMA foreign_key_check').fetchall()==[]
    c.close()
source=script.read_text('utf-8')
assert "elif path=='/api/face/unassign':v=unassign_faces(c,body['person_id']" in source
assert "finishDirectReviewedPost('/api/face/unassign',{person_id:person,face_ids:ids},ids,'Tags removed · Undo available',origin)" in source
assert "if(!reviewWriteStillVisible(start))" in source
print('PASS: fictional owner-bound unassign, automatic rejection/history exact Undo, stale refusal, replay, audit and privacy')
