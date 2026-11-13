"""Automatic ID regression on SYNTHETIC normalized face vectors; no photographs."""
from pathlib import Path
import importlib.util
import tempfile
import sys
import types
import numpy as np
for mod in ('cv2',):
    sys.modules[mod]=types.ModuleType(mod)
source=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_auto',source)
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

def vector(x, y=0):
    v=np.zeros(128,dtype=np.float32)
    v[0]=x;v[1]=y
    v/=max(float(np.linalg.norm(v)),1e-8)
    return v.tobytes()

with tempfile.TemporaryDirectory() as root:
    m.STATE=Path(root)
    m.DB=m.STATE/'synthetic.sqlite3'
    m.CONFIG=m.STATE/'settings.json'
    m.DB_READY_PATH=None
    c=m.connect()
    with c:
        for pid in (1,2,3):
            c.execute('INSERT INTO people(id,name,created_at) VALUES(?,?,?)',(pid,f'Person {pid}','today'))
        for image in range(1,32):
            c.execute("INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)",
                      (image,f'/synthetic/{image}.jpg',1000,12345,'done'))
        def add(fid,image,embedding,person=None,quality=.95,confidence=.99,face_index=0):
            c.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,confidence,
                      embedding,quality,person_id,review) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                      (fid,image,face_index,1,1,80,80,confidence,embedding,quality,person,
                       'confirmed' if person is not None else 'unreviewed'))
        for k in range(1,5):
            add(k,k,vector(1),1)
            add(k+10,k+10,vector(0,1),2)
        add(20,20,vector(1))     # clear known person
        add(21,21,vector(0,1))   # other known person
        add(22,22,vector(.7,.7)) # ambiguous
        add(23,23,vector(1),quality=.01) # low-quality
        add(24,1,vector(1),face_index=1)      # same photo as confirmed identity: never auto-link
        # A single-photo manually confirmed DIFFERENT identity with an almost
        # identical embedding must count as a false match if linked to Person 1.
        # This is the key danger a known-person-only holdout never measured.
        add(30,30,vector(1),3)
        add(25,25,vector(1))     # explicitly rejected same-person suggestion
        c.execute('INSERT INTO rejected(face_id,person_id) VALUES(?,?)',(25,1))
        # Two detections from a single photo look identical; don't guess.
        add(26,26,vector(1))
        c.execute("INSERT INTO faces(id,image_id,face_index,x,y,w,h,confidence,embedding,quality,review) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                  (27,26,1,10,10,70,70,.99,vector(1),.95,'unreviewed'))
    before=[tuple(r) for r in c.execute('SELECT id,person_id,review FROM faces ORDER BY id')]
    evaluation=m.evaluate_auto_identity(c,limit=50)
    assert evaluation['held_out']==2,evaluation
    assert evaluation['correct']==2 and evaluation['wrong']==0,evaluation
    assert evaluation['precision']==1.0 and evaluation['coverage']==1.0,evaluation
    assert evaluation['zero_error_upper_bound'] is not None
    assert evaluation['unknown_held_out']==1,evaluation
    assert evaluation['false_unknown_links']==1,evaluation
    assert evaluation['unknown_false_link_rate']==1.0,evaluation
    assert before==[tuple(r) for r in c.execute('SELECT id,person_id,review FROM faces ORDER BY id')], 'Evaluation wrote source labels'
    preview=m.auto_identity_candidates(c,cap=100,commit=False)
    assert [tuple(r) for r in c.execute('SELECT id,person_id,review FROM faces ORDER BY id')]==before,'preview wrote to face DB'
    assert set(r['face_id'] for r in preview['candidates'])=={20,21},preview
    result=m.auto_identity_candidates(c,cap=100,commit=True)
    assert result['linked']==2,result
    linked={r['id']:(r['person_id'],r['review']) for r in c.execute('SELECT * FROM faces WHERE id>=20')}
    assert linked[20]==(1,'auto') and linked[21]==(2,'auto'),linked
    for k in (22,23,24,25,26,27):
        assert linked[k]==(None,'unreviewed'),(k,linked[k])
    assert c.execute('SELECT count(*) FROM auto_matches').fetchone()[0]==2
    # Automatically assigned faces NEVER seed later automatic suggestions.
    with c:
        c.execute("UPDATE faces SET review='auto' WHERE id=3")
    with c:
        c.execute("UPDATE faces SET review='confirmed' WHERE id=21")
    reverted=m.undo_last_auto_batch(c)
    assert reverted==1, reverted
    assert tuple(c.execute('SELECT person_id,review FROM faces WHERE id=20').fetchone())==(None,'unreviewed')
    assert tuple(c.execute('SELECT person_id,review FROM faces WHERE id=21').fetchone())==(2,'confirmed')
    assert c.execute('SELECT 1 FROM rejected WHERE face_id=20 AND person_id=1').fetchone()
    assert m.undo_last_auto_batch(c)==0,'Undo must not change manually corrected faces'
    retry=m.auto_identity_candidates(c,face_ids=[20],commit=True)
    assert retry['linked']==0,'Undo rejection must prevent immediate auto-reattach'
    # Repeated historical backfill advances a persistent cursor. Preview mode
    # must not skip candidate faces before a later actual run.
    before_checkpoint=m.config().get('auto_backfill_state')
    preview_backfill=m.auto_identity_backfill(c,limit=2,commit=False)
    assert preview_backfill['examined']==2
    assert m.config().get('auto_backfill_state')==before_checkpoint
    first_backfill=m.auto_identity_backfill(c,limit=2,commit=True)
    first_cursor=m.config()['auto_backfill_state']['cursor']
    assert first_backfill['examined']==2 and first_cursor>=20
    second_backfill=m.auto_identity_backfill(c,limit=2,commit=True)
    second_cursor=m.config()['auto_backfill_state']['cursor']
    assert second_cursor>first_cursor, (first_cursor,second_cursor)
    # A manual identification changes the reference fingerprint and forces
    # a fresh pass over previously checked unassigned faces.
    with c:
        c.execute("UPDATE faces SET person_id=1,review='confirmed' WHERE id=23")
    restarted=m.auto_identity_backfill(c,limit=2,commit=True)
    assert restarted['examined']==2
    assert m.config()['auto_backfill_state']['cursor']<second_cursor
    c.close()
print('PASS: strong auto-ID, differing identities, ambiguity, quality, same-photo, duplicate faces, reject, undo, manual override and no cascade')
