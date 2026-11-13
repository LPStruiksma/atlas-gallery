"""Fictional proof of atomic, durable direct face-review request replay.

Executes production SQLite code, without reading photos or an installed
People database. Every name, image path and face is generated in temp SQLite.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import importlib.util
import sqlite3
import sys
import tempfile
import threading
import types

for module in ('cv2','numpy'):
    sys.modules[module]=types.ModuleType(module)
source=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_atomic_review_fixture',source)
v4=importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)


def refused(action,text):
    try:action()
    except ValueError as error:
        assert text in str(error),(text,str(error))
    else:raise AssertionError('Expected refusal '+text)


def counts(c):
    return {t:c.execute('SELECT COUNT(*) FROM '+t).fetchone()[0]
            for t in ('faces','events','rejected','manual_review_undo','review_write_requests')}


with tempfile.TemporaryDirectory(prefix='atlas-v4-reviewed-idempotency-fixture-') as root:
    v4.STATE=Path(root)
    v4.DB=v4.STATE/'fictional.sqlite3'
    v4.DB_READY_PATH=None
    c=v4.connect()
    assert 'review_write_requests' in {
        row['name'] for row in c.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    person=v4.create_person(c,'Fictional Profile for Human Review')
    assert person>0
    with c:
        for fid in range(1,26):
            c.execute('INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)',
                      (fid,'/fictional/gallery-%02d.jpg'%fid,8,1,'ready'))
            c.execute('''INSERT INTO faces(
                id,image_id,face_index,x,y,w,h,embedding,quality,person_id,review,
                suggested_person_id,suggested_score)
                VALUES(?,?,0,0,0,1,1,?,1.0,?,?,?,?)''',
                (fid,fid,bytes(512),
                 person if fid in (5,6) else None,
                 'confirmed' if fid in (5,6) else 'unreviewed',
                 person if fid in (1,2,3,4) else None,
                 .9 if fid in (1,2,3,4) else None))
    methods=[
        ('accept_suggestions','a'*32,
         lambda key,ids:v4.accept_suggestions(c,person,ids,undo=True,request_id=key),
         [1,2]),
        ('reject_suggestions','b'*32,
         lambda key,ids:v4.reject_suggestions(c,person,ids,undo=True,request_id=key),
         [3,4]),
        ('unassign_faces','c'*32,
         lambda key,ids:v4.unassign_faces(c,person,ids,undo=True,request_id=key),
         [5,6]),
        ('ignore_faces','d'*32,
         lambda key,ids:v4.ignore_faces(c,ids,undo=True,request_id=key),
         [7,8]),
        ('assign_faces','e'*32,
         lambda key,ids:v4.assign_faces(c,person,ids,undo=True,request_id=key),
         [9,10]),
    ]

    # A read-only lookup for a key never submitted means UNKNOWN, not rollback.
    assert v4.review_write_request_status(c,'0'*32)=={'status':'unknown'}
    for invalid in ('',None,3,{},'A'*32,'a'*31,'a'*33):
        refused(lambda invalid=invalid:v4.review_write_request_status(c,invalid),
                'Invalid face review status request ID')

    first_receipts={}
    for action,key,invoke,ids in methods:
        before=counts(c)
        result=invoke(key,ids)
        assert result['count']==len(ids)
        assert len(result['undo_token'])==32
        assert result.get('idempotent_replay') is None
        first_receipts[action]=result
        status={'status':'recorded_not_undone','count':len(ids),
                'undo_token':result['undo_token']}
        before_read=c.total_changes
        for _ in range(4):
            assert v4.review_write_request_status(c,key)==status
        assert c.total_changes==before_read, 'Read-only lookup mutated SQLite'
        expected=counts(c)
        assert expected['events']==before['events']+1
        assert expected['manual_review_undo']==before['manual_review_undo']+1
        assert expected['review_write_requests']==before['review_write_requests']+1
        face_rows=[tuple(r) for r in c.execute(
            'SELECT id,person_id,review,ignored,suggested_person_id FROM faces ORDER BY id')]
        for _ in range(7):
            replay=invoke(key,list(reversed(ids)))
            assert replay=={**result,'idempotent_replay':True}
        assert counts(c)==expected,'Replay duplicated write, Undo, audit or rejection'
        assert face_rows==[tuple(r) for r in c.execute(
            'SELECT id,person_id,review,ignored,suggested_person_id FROM faces ORDER BY id')]
        refused(lambda:invoke(key,[ids[0]]),'different details')

    # Identical key across an entirely DIFFERENT review route is rejected,
    # even if the exact reviewed faces happen to overlap.
    refused(lambda:v4.ignore_faces(c,[15],undo=True,request_id='a'*32),
            'different details')
    refused(lambda:v4.assign_faces(c,person,[15],undo=True,request_id='b'*32),
            'different details')
    for bad in ('', 'A'*32, 'a'*31, 'a'*33,True,7,{}):
        refused(lambda bad=bad:v4.ignore_faces(c,[16],undo=True,request_id=bad),
                'Invalid face review request ID')
    refused(lambda:v4.ignore_faces(c,[16],request_id='1'*32,undo=False),
            'requires Undo tracking')

    # Cross-connection race on the SAME key serializes through BEGIN
    # IMMEDIATE. No second face assignment, rejected row, Undo or audit.
    key='6'*32
    barrier=threading.Barrier(2)
    def concurrent_hide():
        other=v4.connect()
        try:
            barrier.wait(timeout=10)
            return v4.ignore_faces(other,[11,12],undo=True,request_id=key)
        finally:other.close()
    before=counts(c)
    with ThreadPoolExecutor(max_workers=2) as pool:
        pending=[pool.submit(concurrent_hide) for _ in range(2)]
        results=[task.result(timeout=20) for task in pending]
    assert sorted(bool(x.get('idempotent_replay')) for x in results)==[False,True]
    assert results[0]['undo_token']==results[1]['undo_token']
    after=counts(c)
    assert after['events']==before['events']+1
    assert after['manual_review_undo']==before['manual_review_undo']+1
    assert after['review_write_requests']==before['review_write_requests']+1

    # A failing ledger insertion rolls back *everything*, even if faces,
    # audit and Undo were already staged within the same transaction.
    key='f'*32
    with c:
        c.execute('''CREATE TRIGGER synthetic_fail_review BEFORE INSERT
            ON review_write_requests WHEN NEW.request_id='ffffffffffffffffffffffffffffffff'
            BEGIN SELECT RAISE(ABORT,'synthetic ledger insert failed'); END''')
    before=counts(c)
    old_faces=[tuple(r) for r in c.execute(
        'SELECT id,ignored,person_id,review FROM faces ORDER BY id')]
    try:v4.ignore_faces(c,[13,14],undo=True,request_id=key)
    except sqlite3.IntegrityError as error:
        assert 'synthetic ledger insert failed' in str(error)
    else:raise AssertionError('Expected SQLite transaction to fail completely')
    assert counts(c)==before
    assert old_faces==[tuple(r) for r in c.execute(
        'SELECT id,ignored,person_id,review FROM faces ORDER BY id')]
    with c:c.execute('DROP TRIGGER synthetic_fail_review')
    repaired=v4.ignore_faces(c,[13,14],undo=True,request_id=key)
    assert repaired['count']==2

    # Undo consumes its original one-use token. Replay may not falsely
    # report that reviewed tags are current after a proven Undo.
    hide=first_receipts['ignore_faces']
    assert v4.undo_manual_review(c,hide['undo_token'])==2
    assert v4.review_write_request_status(c,'d'*32)=={
        'status':'undone','count':2}
    refused(lambda:v4.ignore_faces(c,[7,8],undo=True,request_id='d'*32),
            'already undone')
    assert all(c.execute('SELECT ignored FROM faces WHERE id=?',(fid,)).fetchone()[0]==0
               for fid in (7,8))

    # The durable ledger never keeps face IDs, image paths, names or
    # biometric payloads. SHA-256 is not a human-readable profile record.
    record=dict(c.execute('SELECT * FROM review_write_requests WHERE request_id=?',
                          ('a'*32,)).fetchone())
    assert set(record)=={'request_id','payload_digest','action','count','undo_token','created_at'}
    assert len(record['payload_digest'])==64
    assert not any(x in repr(record) for x in ('Fictional','/fictional','face_ids','embedding'))

    # Only 12 Undo records are retained. After further operations they can
    # rotate away, and the ledger must NOT assert that the old review is
    # still in effect or offer a phantom Undo.
    for n in range(14):v4.create_person(c,'Fictional Filler '+str(n),undo=True)
    assert v4.review_write_request_status(c,'a'*32)=={
        'status':'recorded_unknown','count':2}
    refused(lambda:v4.accept_suggestions(
        c,person,[1,2],undo=True,request_id='a'*32),
        'Undo history expired')

    # Old callers without operation keys retain their original semantics.
    legacy=v4.ignore_faces(c,[21],undo=True)
    assert legacy['count']==1 and len(legacy['undo_token'])==32
    assert c.execute('SELECT COUNT(*) FROM review_write_requests').fetchone()[0]==7
    assert not c.execute('PRAGMA foreign_key_check').fetchall()
    assert c.execute('PRAGMA quick_check').fetchone()[0]=='ok'
    c.close()

print('PASS: five reviewed write routes, 35 exact replays, cross-action collisions, rejected keys, concurrent SQLite writers, rollback after staged Undo, consumed/rotated Undo history, privacy-minimal immutable request ledger')
