"""Atomic, durable Person-create replay across lost replies, concurrent connections and Undo.

All names/paths/faces are fictional. Tests the production V4 SQLite functions.
No real photographs, Termux process or private People DB are accessed.
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
spec=importlib.util.spec_from_file_location('atlas_v4_idempotent_create_synthetic',source)
v4=importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)


def expect_refusal(action,part):
    try:action()
    except ValueError as exc:
        assert part in str(exc),(part,str(exc))
    else:raise AssertionError('Expected fail-closed refusal: '+part)


def number(db,table):
    assert table in ('people','faces','events','manual_review_undo','person_create_requests')
    return db.execute('SELECT COUNT(*) FROM '+table).fetchone()[0]


with tempfile.TemporaryDirectory(prefix='atlas-v4-idempotent-create-fictional-') as root:
    v4.STATE=Path(root)
    v4.DB=v4.STATE/'fictional-people.sqlite3'
    v4.DB_READY_PATH=None
    db=v4.connect()
    assert 'person_create_requests' in {
        row['name'] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    with db:
        for fid in range(1,9):
            db.execute('INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)',
                       (fid,f'/fictional/image-{fid}.jpg',32,1,'ready'))
            db.execute('''INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,cluster_id)
                          VALUES(?,?,0,0,0,1,1,?,?)''',
                       (fid,fid,bytes(512),7 if fid<=4 else 8))
        db.execute("INSERT INTO clusters(id,count,representative,created_at) VALUES(7,4,1,'fictional')")
        db.execute("INSERT INTO clusters(id,count,representative,created_at) VALUES(8,4,5,'fictional')")

    # Status lookup is a strictly read-only, opaque-key recovery operation.
    assert v4.person_create_request_status(db,'9'*32)=={'status':'unknown'}
    expect_refusal(lambda:v4.person_create_request_status(db,'not-a-key'),
                   'Invalid Person create status request ID')

    key1='a'*32
    original=v4.create_person(db,'Fictional Ada',cluster_id=7,expected_count=4,
                              undo=True,request_id=key1)
    assert original['person_id']>0 and len(original['undo_token'])==32
    status=v4.person_create_request_status(db,key1)
    assert status=={'status':'committed','person_id':original['person_id'],
                    'undo_token':original['undo_token']}
    assert all(word not in str(status) for word in ('Fictional','image-','cluster_id'))
    # Reading status never mutates SQLite, events, Undo or private labels.
    status_read_changes=db.total_changes
    for _ in range(3):assert v4.person_create_request_status(db,key1)==status
    assert db.total_changes==status_read_changes
    assert number(db,'person_create_requests')==1
    before={table:number(db,table) for table in
            ('people','events','manual_review_undo','person_create_requests')}
    before_faces=[tuple(row) for row in db.execute(
        'SELECT id,person_id,cluster_id,review FROM faces ORDER BY id')]
    # All four faces were already assigned by the first committed request.
    # A replay MUST bypass later duplicate-name and stale-group validation.
    for _ in range(7):
        replay=v4.create_person(db,'Fictional Ada',cluster_id=7,expected_count=4,
                                undo=True,request_id=key1)
        assert replay=={**original,'idempotent_replay':True}
    assert {table:number(db,table) for table in before}==before
    assert before_faces==[tuple(row) for row in db.execute(
        'SELECT id,person_id,cluster_id,review FROM faces ORDER BY id')]
    assert db.execute('SELECT COUNT(*) FROM faces WHERE person_id=?',
                      (original['person_id'],)).fetchone()[0]==4

    # No database-stored names, face lists or image paths in the request ledger.
    ledger=dict(db.execute('SELECT * FROM person_create_requests WHERE request_id=?',
                           (key1,)).fetchone())
    assert set(ledger)=={'request_id','payload_digest','person_id','undo_token','created_at','invalidated_at'}
    assert ledger['invalidated_at'] is None
    assert 'Fictional' not in repr(ledger) and '/fictional' not in repr(ledger)
    assert len(ledger['payload_digest'])==64

    # Prevent a reused key from changing any material reviewed intent.
    for payload in (
        dict(name='Fictional Ada',cluster_id=8,expected_count=4),
        dict(name='Fictional Ada',cluster_id=7,expected_count=3),
        dict(name='Fictional Ada II',cluster_id=7,expected_count=4),
        dict(name='Fictional Ada',cluster_id=7,expected_count=4,
             allow_duplicate_name=True),
    ):
        expect_refusal(lambda payload=payload:v4.create_person(
            db,undo=True,request_id=key1,**payload),
            'different details')
    assert {table:number(db,table) for table in before}==before

    for bad in ('', 'A'*32, 'not-a-uuid', 'ab'*15, 'a'*33, 99, True, {}):
        expect_refusal(lambda bad=bad:v4.create_person(
            db,'Bad Key',undo=True,request_id=bad),
            'Invalid Create person request ID')
    assert number(db,'people')==1

    # Untagged empty profiles, selected-face paths and legacy clients still
    # work; independent EXPLICIT request keys are distinct user intentions.
    key2='b'*32
    selected=v4.create_person(db,'Fictional Bea',face_ids=[5,6],
                              undo=True,request_id=key2)
    selected_replay=v4.create_person(db,'Fictional Bea',face_ids=[6,5],
                                    undo=True,request_id=key2)
    assert selected_replay['person_id']==selected['person_id']
    assert selected_replay['idempotent_replay']
    assert number(db,'people')==2
    legacy=v4.create_person(db,'Fictional Legacy')
    assert isinstance(legacy,int)
    assert number(db,'person_create_requests')==2
    explicit_homonym=v4.create_person(db,'Fictional Ada',undo=True,
                                      allow_duplicate_name=True,request_id='c'*32)
    assert explicit_homonym['person_id']!=original['person_id']
    assert number(db,'people')==4

    # Atomic rollback even when request-ledger insertion aborts AFTER Person,
    # faces and Undo have been staged inside the same write transaction.
    with db:
        db.execute("""CREATE TRIGGER fictional_fail_request BEFORE INSERT
                      ON person_create_requests WHEN NEW.request_id='d'||substr('dddddddddddddddddddddddddddddddd',2)
                      BEGIN SELECT RAISE(ABORT,'fixture ledger failure'); END""")
    # No private fixtures or partial rows survive the commit-time failure.
    snapshot={table:number(db,table) for table in
              ('people','events','manual_review_undo','person_create_requests')}
    tagged=[tuple(row) for row in db.execute('SELECT id,person_id FROM faces ORDER BY id')]
    try:
        v4.create_person(db,'Fictional Failed',face_ids=[7],
                         undo=True,request_id='d'*32)
    except sqlite3.IntegrityError as error:
        assert 'fixture ledger failure' in str(error)
    else:raise AssertionError('Expected insertion trigger failure')
    assert snapshot=={table:number(db,table) for table in snapshot}
    assert tagged==[tuple(row) for row in db.execute('SELECT id,person_id FROM faces ORDER BY id')]
    with db:db.execute('DROP TRIGGER fictional_fail_request')
    recovered=v4.create_person(db,'Fictional Failed',face_ids=[7],
                               undo=True,request_id='d'*32)
    assert recovered['person_id']>0

    # Two independent connections racing on a single key serialize inside
    # BEGIN IMMEDIATE; only the FIRST transaction can create the identity.
    simultaneous='e'*32
    started=threading.Barrier(2)
    def concurrent_create():
        c=v4.connect()
        try:
            started.wait(timeout=10)
            return v4.create_person(c,'Fictional Concurrent',undo=True,
                                    request_id=simultaneous)
        finally:c.close()
    before_people=number(db,'people')
    before_events=number(db,'events')
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures=[pool.submit(concurrent_create) for _ in range(2)]
        results=[f.result(timeout=20) for f in futures]
    assert results[0]['person_id']==results[1]['person_id']
    assert sorted(r.get('idempotent_replay',False) for r in results)==[False,True]
    assert number(db,'people')==before_people+1
    assert number(db,'events')==before_events+1

    # Undo deletes the profile but retains its idempotency tombstone. A
    # lost/late request cannot re-create an undone Person with the old key.
    assert v4.undo_manual_review(db,original['undo_token'])==4
    assert v4.person_create_request_status(db,key1)=={'status':'undone'}
    assert db.execute('SELECT 1 FROM people WHERE id=?',
                      (original['person_id'],)).fetchone() is None
    before_people=number(db,'people')
    expect_refusal(lambda:v4.create_person(
        db,'Fictional Ada',cluster_id=7,expected_count=4,undo=True,
        request_id=key1),'already completed but profile was undone')
    assert number(db,'people')==before_people
    assert db.execute('SELECT COUNT(*) FROM faces WHERE person_id IS NULL').fetchone()[0]>=4

    # Replaying an older successful request after its limited Undo record
    # rotates away returns the same profile but NOT a fake usable Undo token.
    recorded=v4.create_person(db,'Fictional Old',undo=True,request_id='f'*32)
    for n in range(14):
        v4.create_person(db,'Fictional Filler '+str(n),undo=True)
    expired=v4.create_person(db,'Fictional Old',undo=True,request_id='f'*32)
    assert expired['person_id']==recorded['person_id']
    assert expired['undo_token'] is None
    assert expired['idempotent_replay']
    assert v4.person_create_request_status(db,'f'*32)=={
        'status':'committed','person_id':recorded['person_id'],'undo_token':None}
    assert number(db,'person_create_requests')==6

    # SQLite may reuse the largest deleted INTEGER PRIMARY KEY. The durable
    # request tombstone must not confuse that new profile with undone identity.
    tail=v4.create_person(db,'Fictional Final Tail',undo=True,request_id='1'*32)
    assert v4.undo_manual_review(db,tail['undo_token'])==0
    tombstone=db.execute('SELECT invalidated_at FROM person_create_requests WHERE request_id=?',
                         ('1'*32,)).fetchone()
    assert tombstone['invalidated_at'] is not None
    assert v4.person_create_request_status(db,'1'*32)=={'status':'undone'}
    replacement=v4.create_person(db,'Fictional Reuses ID')
    assert replacement==tail['person_id'], (
        'Fixture must exercise actual SQLite rowid reuse to prove tombstone behavior')
    expect_refusal(lambda:v4.create_person(
        db,'Fictional Final Tail',undo=True,request_id='1'*32),
        'profile was undone')
    assert db.execute('SELECT name FROM people WHERE id=?',
                      (replacement,)).fetchone()['name']=='Fictional Reuses ID'
    assert number(db,'person_create_requests')==7

    # Archived profiles are not falsely reported as an active creation.
    archive_key='2'*32
    archived=v4.create_person(db,'Fictional Archived',undo=True,request_id=archive_key)
    with db:
        db.execute('UPDATE people SET merged_into=? WHERE id=?',
                   (replacement,archived['person_id']))
    assert v4.person_create_request_status(db,archive_key)=={
        'status':'archived_or_missing'}
    assert number(db,'person_create_requests')==8
    assert not db.execute('PRAGMA foreign_key_check').fetchall()
    assert db.execute('PRAGMA quick_check').fetchone()[0]=='ok'
    db.close()

print('PASS: durable create replay and read-only status, 7 lost replies, exact-payload mismatch, group/selected/legacy paths, commit-time rollback, 2-connection serialization, Undo tombstone/rowid reuse, archived and expired Undo safety')
