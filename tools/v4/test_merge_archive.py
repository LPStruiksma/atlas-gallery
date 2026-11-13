"""V4 merge archive / durable restore: fictional SQLite fixtures only."""
from pathlib import Path
import importlib.util
import json
import sys
import tempfile
import types

for module in ('cv2','numpy'):
    sys.modules[module]=types.ModuleType(module)
script=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_merge_archive_test',script)
v4=importlib.util.module_from_spec(spec);spec.loader.exec_module(v4)

with tempfile.TemporaryDirectory(prefix='atlas-v4-fictional-merges-') as root:
    v4.STATE=Path(root);v4.DB=v4.STATE/'fictional.sqlite3';v4.DB_READY_PATH=None
    c=v4.connect()
    with c:
        for pid,name,note in [(1,'Target','keep'),(2,'Source','secret note'),
                              (3,'Another',''),(4,'Empty','')]:
            c.execute('INSERT INTO people(id,name,note,created_at) VALUES(?,?,?,?)',
                      (pid,name,note,'fictional-day'))
        for fid,pid in [(11,1),(12,2),(13,2),(14,3),(15,None),(16,None),(17,None)]:
            c.execute('INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)',
                      (fid,f'/fake/photos/f{fid}.jpg',500,1,'done'))
            c.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,person_id,review)
                VALUES(?,?,?,?,?,?,?,?,?,?)""",
                (fid,fid,0,0,0,64,64,bytes(512),pid,'confirmed' if pid is not None else 'unreviewed'))
        c.execute('INSERT INTO rejected(face_id,person_id) VALUES(?,?)',(15,2))
        c.execute('UPDATE faces SET suggested_person_id=?,suggested_score=? WHERE id=?',(2,.88,16))
        c.execute("""INSERT INTO auto_matches(face_id,person_id,score,model_version,batch_id,created_at)
           VALUES(?,?,?,?,?,?)""",(17,2,.94,'fictional-model','fictional-batch','fictional-day'))
        c.execute('INSERT INTO person_covers(person_id,face_id,updated_at) VALUES(?,?,?)',
                  (2,12,'fictional-day'))

    def state():
        return {
            'profiles':[tuple(r) for r in c.execute('SELECT id,name,note,merged_into FROM people ORDER BY id')],
            'faces':[tuple(r) for r in c.execute('''SELECT id,person_id,review,cluster_id,ignored,
                         suggested_person_id,suggested_score FROM faces ORDER BY id''')],
            'covers':[tuple(r) for r in c.execute('SELECT person_id,face_id,updated_at FROM person_covers ORDER BY person_id')],
            'rejected':[tuple(r) for r in c.execute('SELECT * FROM rejected ORDER BY face_id')],
            'auto':[tuple(r) for r in c.execute('SELECT face_id,person_id,score FROM auto_matches')]
        }
    initial=state()
    assert sorted(p['id'] for p in v4.list_people(c))==[1,2,3,4]
    assert v4.api_status(c)['people']==4

    merged=v4.merge_people_profiles(c,1,2)
    assert merged['count']==2 and len(merged['undo_token'])==32
    assert c.execute('SELECT merged_into FROM people WHERE id=2').fetchone()[0]==1
    assert sorted(p['id'] for p in v4.list_people(c))==[1,3,4]
    assert v4.api_status(c)['people']==3
    assert [p['id'] for p in v4.merged_people_for_target(c,1)]==[2]
    assert c.execute('SELECT person_id FROM faces WHERE id=12').fetchone()[0]==1
    assert c.execute('SELECT face_id FROM person_covers WHERE person_id=1').fetchone()[0]==12
    assert c.execute('SELECT face_id FROM person_covers WHERE person_id=2').fetchone()[0]==12
    assert c.execute('SELECT 1 FROM rejected WHERE face_id=15 AND person_id=2').fetchone()
    assert c.execute('SELECT suggested_person_id FROM faces WHERE id=16').fetchone()[0]==2
    assert c.execute('SELECT person_id FROM auto_matches WHERE face_id=17').fetchone()[0]==2
    assert c.execute('PRAGMA foreign_key_check').fetchall()==[]

    # Archived People cannot reappear via normal identity-creation or review APIs.
    for call in (
        lambda:v4.assign_faces(c,2,[17]),
        lambda:v4.attach_group_to_person(c,2,100,1),
        lambda:v4.rename_person(c,2,'Archived renamed'),
        lambda:v4.set_person_cover(c,2,12),
        lambda:v4.reject_suggestions(c,2,[16]),
        lambda:v4.merge_people_profiles(c,1,2),
        lambda:v4.merge_people_profiles(c,2,3),
    ):
        try:call()
        except (ValueError,TypeError):pass
        else:raise AssertionError('Archived profile accepted an unsafe modification')

    # Multi-source merges remain independent and preserve their original covers.
    second=v4.merge_people_profiles(c,1,3)
    assert second['count']==1
    assert sorted(p['id'] for p in v4.merged_people_for_target(c,1))==[2,3]
    assert v4.restore_profile_merge(c,3,1)==1
    assert c.execute('SELECT merged_into FROM people WHERE id=3').fetchone()[0] is None
    assert c.execute('SELECT person_id FROM faces WHERE id=14').fetchone()[0]==3

    # Undo is a convenient one-use shortcut, NOT the only restoration path.
    assert v4.undo_manual_review(c,merged['undo_token'])==2
    assert state()==initial,'Merge undo must recover identities, profiles, covers and history exactly'
    try:v4.undo_manual_review(c,merged['undo_token'])
    except ValueError as exc:assert 'already used' in str(exc)
    else:raise AssertionError('Replayed merge undo token')
    assert v4.merged_people_for_target(c,1)==[]

    # Restoration continues to work after bounded shortcut undo token eviction.
    persistent=v4.merge_people_profiles(c,1,2)
    with c:c.execute('DELETE FROM manual_review_undo WHERE token=?',(persistent['undo_token'],))
    assert v4.restore_profile_merge(c,2,1)==2
    assert state()==initial

    def blocked_mutation(mutate, fragment):
        token=v4.merge_people_profiles(c,1,2)['undo_token']
        with c:mutate()
        changed=state()
        try:v4.restore_profile_merge(c,2,1)
        except ValueError as exc:assert fragment in str(exc),str(exc)
        else:raise AssertionError('Stale merge was restored over newer work')
        assert state()==changed, 'Failed restore changed database'
        # Explicit synthetic reset to the original fixture, without real media.
        with c:
            c.execute('DELETE FROM person_covers WHERE person_id=1')
            c.execute("UPDATE faces SET review='confirmed',person_id=2 WHERE id IN (12,13)")
            c.execute('UPDATE people SET merged_into=NULL WHERE id=2')
            c.execute('DELETE FROM person_merges WHERE source_id=2')
            c.execute('UPDATE manual_review_undo SET undone_at=? WHERE token=?',('reset',token))
        assert state()==initial,'Synthetic guard fixture reset failed'

    blocked_mutation(lambda:c.execute("UPDATE faces SET review='newer' WHERE id=12"),
                     'faces changed')
    blocked_mutation(lambda:c.execute('UPDATE faces SET person_id=3 WHERE id=13'),
                     'faces changed')
    blocked_mutation(lambda:c.execute('UPDATE person_covers SET face_id=11 WHERE person_id=1'),
                     'portrait changed')

    # Restoring an empty profile should preserve its name without inventing faces.
    empty=v4.merge_people_profiles(c,1,4)
    assert empty['count']==0
    assert v4.restore_profile_merge(c,4,1)==0
    assert state()==initial

    # Protect existing source-of-other-merges lineage from multi-hop ambiguity.
    v4.merge_people_profiles(c,2,4)
    try:v4.merge_people_profiles(c,1,2)
    except ValueError as exc:assert 'already merged' in str(exc)
    else:raise AssertionError('Allowed source archive with attached merged descendants')
    assert v4.restore_profile_merge(c,4,2)==0

    # A new profile's undo must not delete an archived merge's target.
    created=v4.create_person(c,'Fictional new person',undo=True)
    v4.merge_people_profiles(c,created['person_id'],4)
    try:v4.undo_manual_review(c,created['undo_token'])
    except ValueError as exc:assert 'merge history' in str(exc)
    else:raise AssertionError('Deleted new person while dependent archive existed')

    evidence=[r[0] for r in c.execute('SELECT snapshot FROM manual_review_undo')]
    assert all('/fake/photos/' not in s and 'secret note' not in s and 'embedding' not in s for s in evidence)
    assert len(evidence)<=12
    assert c.execute('PRAGMA foreign_key_check').fetchall()==[]
    c.close()

source=script.read_text(encoding='utf-8')
assert "elif path=='/api/person/restore'" in source
assert "elif path=='/api/people/merged'" in source
assert "const receipt=await post('/api/person/merge'" in source
assert "if(lastUndoToken===undoAtSubmit)keepReviewUndo(receipt)" in source
assert "Restore merged','restoremerge'" in source
assert "DELETE FROM people WHERE id=?',(source,)" not in source
print('PASS: fictional archived merges, permanent restoration, history, covers, stale protection, replay and no private payload')
