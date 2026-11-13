"""Entirely fictional V4 whole-group request replay, atomicity and fail closed.

Exercises production SQLite code on temporary records only. No phone, actual
People database, faces, photos, signing material or private paths are read.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import importlib.util
import sqlite3
import sys
import tempfile
import threading
import types

for name in ('cv2','numpy'):
    sys.modules[name]=types.ModuleType(name)
source=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_group_ledger_synthetic',source)
v4=importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)


def refused(call,fragment):
    try:call()
    except ValueError as error:
        assert fragment in str(error),(fragment,str(error))
    else:raise AssertionError('Unsafe whole-group review accepted: '+fragment)


def snapshot(c):
    return (
        [tuple(r) for r in c.execute(
            'SELECT id,person_id,cluster_id,review,ignored FROM faces ORDER BY id')],
        {table:c.execute('SELECT COUNT(*) FROM '+table).fetchone()[0]
         for table in ('events','manual_review_undo','review_write_requests','clusters')}
    )


with tempfile.TemporaryDirectory(prefix='atlas-group-review-fictional-') as root:
    v4.STATE=Path(root)
    v4.DB=v4.STATE/'fictional.sqlite3'
    v4.DB_READY_PATH=None
    c=v4.connect()
    p=v4.create_person(c,'Fictional Reviewed Identity')
    other=v4.create_person(c,'Another Fictional Identity')
    with c:
        for fid in range(1,9):
            group=7 if fid<=3 else 8 if fid<=6 else 9
            c.execute('INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)',
                      (fid,'/fictional-only/photo-%02d.jpg'%fid,30,1,'ready'))
            c.execute('''INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,cluster_id)
                         VALUES(?,?,0,0,0,1,1,?,?)''',
                      (fid,fid,bytes(512),group))
        for group,size,representative in ((7,3,1),(8,3,4),(9,2,7)):
            c.execute('INSERT INTO clusters(id,count,representative,created_at) VALUES(?,?,?,?)',
                      (group,size,representative,'fictional'))

    # A new reviewed intent receives one atomic result and a real Undo.
    key='a'*32
    first=v4.attach_group_to_person(c,p,7,3,undo=True,request_id=key)
    assert first['count']==3 and len(first['undo_token'])==32
    assert c.execute('SELECT COUNT(*) FROM faces WHERE person_id=?',(p,)).fetchone()[0]==3
    assert v4.review_write_request_status(c,key)=={
        'status':'recorded_not_undone','count':3,'undo_token':first['undo_token']}
    committed=snapshot(c)
    # A post-commit acknowledgement can disappear: exactly the same client
    # intent may be replayed without restoring a now-empty provisional group.
    for _ in range(7):
        assert v4.attach_group_to_person(c,p,7,3,undo=True,request_id=key)=={
            **first,'idempotent_replay':True}
    assert snapshot(c)==committed,'Replay mutated tags, events, Undo or group index'

    # Exact field-role hashing: swapped group and count cannot alias; no
    # reuse with a different person, group, size, action or Undo policy.
    for who,group,count,undo in [
        (p,8,3,True),(other,7,3,True),(p,7,2,True),
        (p,3,7,True),(p,7,3,False)]:
        refused(lambda who=who,group=group,count=count,undo=undo:
                v4.attach_group_to_person(c,who,group,count,undo=undo,request_id=key),
                'different details' if undo else 'requires Undo')
    refused(lambda:v4.assign_faces(c,p,[1],undo=True,request_id=key),
            'different details')
    for bad in ('','A'*32,'a'*31,'a'*33,{},3):
        refused(lambda bad=bad:v4.attach_group_to_person(
            c,p,7,3,undo=True,request_id=bad),'Invalid face review request ID')
    assert snapshot(c)==committed

    # Two independent serialized SQLite writers, one durable request key.
    begin=threading.Barrier(2)
    def race():
        connection=v4.connect()
        try:
            begin.wait(timeout=10)
            return v4.attach_group_to_person(
                connection,p,8,3,undo=True,request_id='b'*32)
        finally:connection.close()
    prior=snapshot(c)
    with ThreadPoolExecutor(max_workers=2) as pool:
        future1=pool.submit(race);future2=pool.submit(race)
        answers=[future1.result(timeout=15),future2.result(timeout=15)]
    assert sum(bool(x.get('idempotent_replay')) for x in answers)==1
    assert answers[0]['undo_token']==answers[1]['undo_token']
    after=snapshot(c)
    assert after[1]['events']==prior[1]['events']+1
    assert after[1]['manual_review_undo']==prior[1]['manual_review_undo']+1
    assert after[1]['review_write_requests']==prior[1]['review_write_requests']+1
    assert c.execute('SELECT COUNT(*) FROM faces WHERE person_id=?',(p,)).fetchone()[0]==6

    # Force INSERT failure after face tags, Undo, audit and cluster updates
    # were staged. Every component rolls back under one SQLite transaction.
    before=snapshot(c)
    with c:
        c.execute("""CREATE TRIGGER fictional_ledger_abort BEFORE INSERT
                     ON review_write_requests
                     BEGIN SELECT RAISE(ABORT,'fictional ledger abort'); END""")
    try:v4.attach_group_to_person(c,p,9,2,undo=True,request_id='c'*32)
    except sqlite3.IntegrityError as error:assert 'fictional ledger abort' in str(error)
    else:raise AssertionError('Ledger failure committed partial group mutation')
    assert snapshot(c)==before
    with c:c.execute('DROP TRIGGER fictional_ledger_abort')
    assert v4.attach_group_to_person(c,p,9,2,undo=True,request_id='c'*32)['count']==2

    # Ledger never stores plaintext labels, face IDs or paths, and matching
    # status is only read-only for the possessor of an opaque request key.
    ledger=dict(c.execute('SELECT * FROM review_write_requests WHERE request_id=?',
                          (key,)).fetchone())
    assert set(ledger)=={
        'request_id','payload_digest','action','count','undo_token','created_at'}
    assert ledger['action']=='attach_group' and len(ledger['payload_digest'])==64
    assert not any(s in repr(ledger) for s in
                   ('Fictional','/fictional-only','cluster_id','face_ids','embedding'))
    total=c.total_changes
    for _ in range(4):
        assert v4.review_write_request_status(c,key)['status']=='recorded_not_undone'
    assert c.total_changes==total

    # A consumed one-use Undo or rotated Undo history cannot be replayed as
    # if face tags were still saved. Current state must be inspected.
    assert v4.undo_manual_review(c,first['undo_token'])==3
    assert v4.review_write_request_status(c,key)=={'status':'undone','count':3}
    refused(lambda:v4.attach_group_to_person(c,p,7,3,undo=True,request_id=key),
            'already undone')
    for n in range(14):
        v4.create_person(c,'Fictional history '+str(n),undo=True)
    assert v4.review_write_request_status(c,'b'*32)=={
        'status':'recorded_unknown','count':3}
    refused(lambda:v4.attach_group_to_person(c,p,8,3,undo=True,request_id='b'*32),
            'Undo history expired')

    # Older V4 clients can omit keys without changing their group attach
    # semantics. Old no-key calls are not inserted into this ledger.
    with c:
        for fid in (9,10):
            c.execute('INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)',
                      (fid,'/fictional-only/photo-%d.jpg'%fid,30,1,'ready'))
            c.execute('''INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,cluster_id)
                         VALUES(?,?,0,0,0,1,1,?,10)''',(fid,fid,bytes(512)))
        c.execute('INSERT INTO clusters(id,count,representative,created_at) VALUES(10,2,9,?)',
                  ('fictional',))
    previous=c.execute('SELECT COUNT(*) FROM review_write_requests').fetchone()[0]
    legacy=v4.attach_group_to_person(c,other,10,2,undo=True)
    assert legacy['count']==2 and len(legacy['undo_token'])==32
    assert c.execute('SELECT COUNT(*) FROM review_write_requests').fetchone()[0]==previous
    assert c.execute('PRAGMA foreign_key_check').fetchall()==[]
    assert c.execute('PRAGMA quick_check').fetchone()[0]=='ok'
    c.close()
print('PASS: whole-group attach atomic key replay x7, field-role collisions, 2 independent writers, ledger-rollback, private-data minimization, Undo consumed/expiry and legacy callers; synthetic SQLite only')
