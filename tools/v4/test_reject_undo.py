"""V4 suggestion-rejection Undo: synthetic identities and filenames only."""
from pathlib import Path
import importlib.util
import sys
import tempfile
import types

for mod in ('cv2','numpy'):sys.modules[mod]=types.ModuleType(mod)
script=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_reject_test',script)
v4=importlib.util.module_from_spec(spec);spec.loader.exec_module(v4)

with tempfile.TemporaryDirectory(prefix='atlas-v4-fake-suggest-') as directory:
    v4.STATE=Path(directory);v4.DB=v4.STATE/'fake.sqlite3';v4.DB_READY_PATH=None
    c=v4.connect()
    with c:
        for pid in (10,20):
            c.execute('INSERT INTO people(id,name,created_at) VALUES(?,?,?)',
                      (pid,f'Synthetic Person {pid}','now'))
        for fid,pid,suggestion,ignored in [
            (1,None,10,0),(2,None,10,0),(3,None,20,0),
            (4,10,10,0),(5,None,10,1),(6,None,None,0)
        ]:
            c.execute('INSERT INTO images(id,path,size,mtime_ns) VALUES(?,?,?,?)',
                      (fid,f'/fictional/{fid}.jpg',100,1))
            c.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,person_id,
                suggested_person_id,suggested_score,ignored) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                (fid,fid,0,1,1,50,50,bytes(512),pid,suggestion,.88 if suggestion else None,ignored))
        c.execute('INSERT INTO rejected(face_id,person_id) VALUES(?,?)',(2,10))

    def snapshot():
        return (
            [tuple(r) for r in c.execute('''SELECT id,person_id,ignored,review,cluster_id,
                         suggested_person_id,suggested_score FROM faces ORDER BY id''')],
            [tuple(r) for r in c.execute('SELECT face_id,person_id FROM rejected ORDER BY face_id,person_id')],
            [tuple(r) for r in c.execute('SELECT * FROM auto_matches ORDER BY id')]
        )
    baseline=snapshot()
    def refused(pid,ids,fragment):
        prior=snapshot()
        try:v4.reject_suggestions(c,pid,ids,undo=True)
        except ValueError as err:assert fragment in str(err),str(err)
        else:raise AssertionError('Accepted invalid suggestion rejection')
        assert snapshot()==prior,'Invalid rejection partially changed identity state'

    refused(10,[1,3],'another person')
    refused(10,[1,4],'another person')
    refused(10,[1,5],'another person')
    refused(10,[1,6],'another person')
    refused(10,[1,999],'no longer exist')
    refused(20,[1],'another person')
    refused(999,[1],'Unknown')
    assert c.execute('SELECT COUNT(*) FROM manual_review_undo').fetchone()[0]==0

    rejected=v4.reject_suggestions(c,10,[1,2],undo=True)
    assert rejected['count']==2 and len(rejected['undo_token'])==32
    assert c.execute('SELECT COUNT(*) FROM rejected WHERE person_id=10').fetchone()[0]==2
    assert c.execute('SELECT suggested_person_id FROM faces WHERE id=1').fetchone()[0] is None
    assert c.execute('SELECT suggested_score FROM faces WHERE id=2').fetchone()[0] is None
    assert c.execute('SELECT suggested_person_id FROM faces WHERE id=3').fetchone()[0]==20
    assert v4.undo_manual_review(c,rejected['undo_token'])==2
    assert snapshot()==baseline,'Undo must remove newly rejected pairs but preserve pre-existing decisions'
    try:v4.undo_manual_review(c,rejected['undo_token'])
    except ValueError as err:assert 'already used' in str(err)
    else:raise AssertionError('Replayed suggestion rejection Undo')

    # Changed face suggestions and third-party rejection entries block Undo.
    for mutate,reset,fragment in [
        (lambda:c.execute('UPDATE faces SET suggested_person_id=20 WHERE id=1'),
         lambda:c.execute('UPDATE faces SET suggested_person_id=NULL WHERE id=1'),
         'changed since assignment'),
        (lambda:c.execute('INSERT INTO rejected(face_id,person_id) VALUES(1,20)'),
         lambda:c.execute('DELETE FROM rejected WHERE face_id=1 AND person_id=20'),
         'history changed')
    ]:
        token=v4.reject_suggestions(c,10,[1,2],undo=True)['undo_token']
        with c:mutate()
        changed=snapshot()
        try:v4.undo_manual_review(c,token)
        except ValueError as err:assert fragment in str(err),str(err)
        else:raise AssertionError('Stale Undo overwrote later rejection history')
        assert snapshot()==changed
        with c:reset()
        assert v4.undo_manual_review(c,token)==2
        assert snapshot()==baseline

    assert v4.reject_suggestions(c,10,[],undo=True)=={'count':0,'undo_token':None}
    single=v4.reject_suggestions(c,10,[1,1],undo=True)
    assert single['count']==1
    assert v4.undo_manual_review(c,single['undo_token'])==1
    assert snapshot()==baseline
    records=[r[0] for r in c.execute('SELECT snapshot FROM manual_review_undo')]
    assert len(records)<=12
    assert all('Synthetic Person' not in s and '/fictional/' not in s and 'embedding' not in s for s in records)
    assert c.execute('PRAGMA foreign_key_check').fetchall()==[]
    c.close()

source=script.read_text('utf-8')
assert "elif path=='/api/person/reject':v=reject_suggestions(c,int(body['person_id']),body['face_ids'],undo=True,request_id=body.get('request_id'))" in source
assert "finishDirectReviewedPost('/api/person/'+a,{person_id:person,face_ids:ids},ids,a==='accept'?'Suggestions confirmed':'Suggestions rejected',origin)" in source
assert "keepReviewUndo(result);" in source
print('PASS: fictional rejection ownership, all-or-nothing atomicity, reversible suggestion/side-history, stale refusal and privacy')
