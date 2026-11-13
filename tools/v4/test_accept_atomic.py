"""V4 approved suggestion must still belong to that Person at the moment of write.

All paths/embeddings are synthetic; never opens installed/private People state.
"""
from pathlib import Path
import importlib.util
import sqlite3
import sys
import tempfile
import types

for name in ('cv2','numpy'):
    sys.modules[name]=types.ModuleType(name)
source=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_accept_atomic',source)
v4=importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)

with tempfile.TemporaryDirectory(prefix='atlas-v4-fake-atomic-accept-') as tmp:
    v4.STATE=Path(tmp);v4.DB=v4.STATE/'test.sqlite3';v4.DB_READY_PATH=None
    c=v4.connect()
    other=v4.connect()
    other.execute('PRAGMA busy_timeout=100')
    with c:
        for pid in (10,20,30):
            c.execute('INSERT INTO people(id,name,created_at) VALUES(?,?,?)',
                      (pid,'Imaginary #'+str(pid),'now'))
        for fid, owner, proposed, hidden in (
            (1,None,10,0),(2,None,10,0),(3,None,20,0),
            (4,None,None,0),(5,None,10,1),(6,10,10,0),
            (7,None,10,0),(8,None,10,0),(9,None,10,0),
            (10,None,30,0),
        ):
            c.execute('INSERT INTO images(id,path,size,mtime_ns) VALUES(?,?,?,?)',
                      (fid,f'/synthetic/nonexistent_{fid}.jpeg',100,1))
            c.execute("""INSERT INTO faces(
                id,image_id,face_index,x,y,w,h,embedding,person_id,
                suggested_person_id,suggested_score,ignored)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                (fid,fid,0,1,1,50,50,bytes(512),owner,proposed,
                 .82 if proposed is not None else None,hidden))
        c.execute('UPDATE people SET merged_into=20 WHERE id=30')

    def snapshot():
        return (
            [tuple(x) for x in c.execute("""SELECT id,person_id,review,ignored,
                    cluster_id,suggested_person_id,suggested_score
                    FROM faces ORDER BY id""")],
            [tuple(x) for x in c.execute(
                "SELECT token,person_id,undone_at FROM manual_review_undo ORDER BY token")],
        )
    def refuse(pid,ids,phrase):
        before=snapshot()
        try:
            v4.accept_suggestions(c,pid,ids,undo=True)
        except ValueError as e:
            assert phrase in str(e), f'Expected {phrase}: {e}'
        else:
            raise AssertionError('Unsafe acceptance succeeded')
        assert snapshot()==before,'Refusal must have no partial mutation/undo history'

    baseline=snapshot()
    refuse(10,[1,3],'Suggestion changed')
    refuse(10,[1,4],'Suggestion changed')
    refuse(10,[5],'hidden')
    refuse(10,[6],'already assigned')
    refuse(10,[999],'no longer exist')
    refuse(30,[10],'archived')
    assert snapshot()==baseline

    # Simulate suggestion replacement AFTER a user has selected a card,
    # but BEFORE the server begins assigning it. This is the old TOCTOU gap.
    delegate=v4.assign_faces
    def interleaved_accept(c0,pid,ids,undo=False,**options):
        assert options.get('required_suggestion_person_id')==pid
        with other:
            other.execute('UPDATE faces SET suggested_person_id=20 WHERE id=1')
        return delegate(c0,pid,ids,undo=undo,**options)
    v4.assign_faces=interleaved_accept
    expected=snapshot()
    try:
        try:v4.accept_suggestions(c,10,[1],undo=True)
        except ValueError as e:assert 'Suggestion changed' in str(e),str(e)
        else:raise AssertionError('Approved stale suggestion despite interleaved change')
    finally:
        v4.assign_faces=delegate
    assert c.execute('SELECT person_id FROM faces WHERE id=1').fetchone()[0] is None
    assert c.execute('SELECT suggested_person_id FROM faces WHERE id=1').fetchone()[0]==20
    assert c.execute('SELECT COUNT(*) FROM manual_review_undo').fetchone()[0]==0
    with other:
        other.execute('UPDATE faces SET suggested_person_id=10 WHERE id=1')
    assert snapshot()==baseline

    # Prove the selected set is validated after BEGIN IMMEDIATE: a distinct
    # SQLite connection cannot replace an accepted suggestion mid-review.
    original_scope=v4._review_scope
    checks=[]
    def inspect_write_lock(conn,ids):
        try:
            other.execute('UPDATE faces SET suggested_person_id=20 WHERE id=7')
        except sqlite3.OperationalError as e:
            assert 'locked' in str(e).lower(),str(e)
            checks.append('write lock active before undo snapshot')
            other.rollback()
        else:
            other.rollback()
            raise AssertionError('Another writer was admitted during confirmation')
        return original_scope(conn,ids)
    v4._review_scope=inspect_write_lock
    try:
        accepted=v4.accept_suggestions(c,10,[7],undo=True)
    finally:
        v4._review_scope=original_scope
    assert len(checks)==1
    assert accepted['count']==1 and len(accepted['undo_token'])==32
    assert c.execute('SELECT person_id FROM faces WHERE id=7').fetchone()[0]==10
    assert v4.undo_manual_review(c,accepted['undo_token'])==1
    assert snapshot()[0]==baseline[0]
    try:v4.undo_manual_review(c,accepted['undo_token'])
    except ValueError:pass
    else:raise AssertionError('Repeated approval undo must be refused')

    # Confirmation of a mixed stale batch must roll back every face and its
    # audit/undo rows, including if a database trigger aborts the second UPDATE.
    before=snapshot()
    c.execute("""CREATE TRIGGER synthetic_failure BEFORE UPDATE OF person_id
        ON faces WHEN NEW.id=9 AND NEW.person_id=10
        BEGIN SELECT RAISE(ABORT,'synthetic write abort'); END""")
    try:
        try:v4.accept_suggestions(c,10,[8,9],undo=True)
        except sqlite3.IntegrityError as e:
            assert 'synthetic write abort' in str(e)
        else:raise AssertionError('Synthetic transaction abort was ignored')
    finally:
        c.execute('DROP TRIGGER synthetic_failure')
    assert snapshot()==before,'Partial accept survived a failed second row'

    # Independent explicit manual assignment remains possible without a model
    # proposal, and successful multi-select approval restores exact flags.
    assert v4.assign_faces(c,10,[4])==1
    assert c.execute('SELECT person_id FROM faces WHERE id=4').fetchone()[0]==10
    valid=v4.accept_suggestions(c,10,[1,2],undo=True)
    assert valid['count']==2
    assert {x[0] for x in c.execute('SELECT person_id FROM faces WHERE id IN (1,2)')}=={10}
    assert c.execute('SELECT COUNT(*) FROM faces WHERE id IN (1,2) AND suggested_person_id IS NULL').fetchone()[0]==2
    assert v4.undo_manual_review(c,valid['undo_token'])==2
    assert c.execute('SELECT COUNT(*) FROM faces WHERE id IN (1,2) AND person_id IS NULL AND suggested_person_id=10').fetchone()[0]==2

    # No request can confirm machine guesses against an archived profile.
    assert c.execute('PRAGMA foreign_key_check').fetchall()==[]
    other.close();c.close()

print('PASS: synthetic suggestion approval validates inside single SQLite write transaction; '
      'interleaved stale, wrong-person, ignored, hidden, archived and absent rows fail closed; '
      'cross-connection lock, rollback-on-trigger, exact Undo and manual assign verified.')
