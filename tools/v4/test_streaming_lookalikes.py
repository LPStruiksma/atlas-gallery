"""Large synthetic V4 similarity test: bounded cursors, exact top-k, read-only."""
from pathlib import Path
import importlib.util
import math
import sqlite3
import sys
import tempfile
import time
import types

sys.modules['cv2']=types.ModuleType('cv2')
import numpy as np

script=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_streaming_similarity',script)
v4=importlib.util.module_from_spec(spec);spec.loader.exec_module(v4)

class NoFullScanCursor:
    def __init__(self,cursor,record):
        self.cursor=cursor
        self.record=record
    def fetchmany(self,size):
        assert size<=384,'Working set exceeded declared bounded window'
        self.record.append(size)
        return self.cursor.fetchmany(size)
    def fetchall(self):
        raise AssertionError('Bulk fetchall on candidate embeddings would exhaust phone RAM')
    def __iter__(self):
        raise AssertionError('Unbounded iterator would hide streaming contract')

class GuardDB:
    def __init__(self,db):self.db=db;self.batches=[]
    def execute(self,sql,args=()):
        rows=self.db.execute(sql,args)
        if ('SELECT id,image_id,person_id,embedding FROM faces' in sql and
            ('WHERE ignored=0 AND id<>?' in sql or
             'WHERE person_id IS NULL AND ignored=0' in sql)):
            return NoFullScanCursor(rows,self.batches)
        return rows

def normalized(vec):
    vec=np.asarray(vec,dtype=np.float32)
    return (vec/np.linalg.norm(vec)).astype(np.float32)

with tempfile.TemporaryDirectory(prefix='atlas-v4-fake-streaming-') as folder:
    v4.STATE=Path(folder);v4.DB=v4.STATE/'fake.sqlite3';v4.DB_READY_PATH=None
    db=v4.connect()
    rng=np.random.default_rng(20261017)
    pivot=normalized(rng.standard_normal(128))
    anchors=[pivot,normalized(pivot+.18*rng.standard_normal(128)),
             normalized(pivot+.2*rng.standard_normal(128))]
    with db:
        db.execute("INSERT INTO people(id,name,created_at) VALUES(100,'Fiction Only','now')")
        for img in (1,2,3,4):
            db.execute("INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)",
                       (img,f'/fictional/{img}.jpg',100,1,'done'))
        for fid,img,embedding in ((1,1,anchors[0]),(2,3,anchors[1]),(3,4,anchors[2])):
            db.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,
                         person_id,review) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                       (fid,img,0,1,1,40,40,embedding.tobytes(),100,'confirmed'))

    # 20,000 fictional vectors (~10MB source embeddings) across unassigned faces.
    # Some resemble our fictional confirmed Person; some have bad/corrupt data.
    candidates=[]
    total=20000
    start=time.perf_counter()
    for i in range(total):
        face_id=i+4
        img=1 if i%101==0 else 2
        ignored=int(i%97==0)
        close=i%43==0
        embedding=normalized(pivot+.12*rng.standard_normal(128)) if close else normalized(rng.standard_normal(128))
        if i%59==0:embedding=embedding.copy();embedding[0]=np.nan
        blob=b'bad' if i%61==0 else embedding.tobytes()
        candidates.append((face_id,img,i+1,1,1,40,40,blob,ignored))
    with db:
        db.executemany("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,ignored)
                        VALUES(?,?,?,?,?,?,?,?,?)""",candidates)
    build_time=time.perf_counter()-start
    assert db.execute('SELECT COUNT(*) FROM faces').fetchone()[0]==total+3

    def all_expected(reference,threshold,limit,profile=False):
        expected=[]
        for face_id,image_id,_idx,_x,_y,_w,_h,blob,ignored in candidates:
            if ignored or len(blob)!=512 or (not profile and image_id==1):continue
            vec=np.frombuffer(blob,dtype=np.float32)
            if not np.isfinite(vec).all():continue
            similarity=vec@reference
            if np.ndim(similarity)>0:similarity=np.max(similarity)
            score=float(similarity)
            if score>=threshold:expected.append((score,face_id))
        if not profile:
            # Other confirmed reference faces remain valid similar() results
            # (they are not candidate unknowns), and use distinct photographs.
            for fid,vec in ((2,anchors[1]),(3,anchors[2])):
                score=float(vec@reference)
                if score>=threshold:expected.append((score,fid))
        return sorted(expected,reverse=True)[:limit]

    changes_before=db.total_changes
    guard=GuardDB(db)
    t=time.perf_counter()
    top=v4.similar(guard,1,limit=48)
    direct_ms=(time.perf_counter()-t)*1000
    expected=all_expected(pivot,.32,48)
    assert [x['id'] for x in top]==[id_ for _,id_ in expected], 'Top-k closest IDs do not match full synthetic oracle'
    assert [x['score'] for x in top]==[round(score,3) for score,_ in expected]
    assert len(top)<=48
    assert top and all(p['id']!=1 for p in top), 'Query anchor must not rank itself'
    assert guard.batches and max(guard.batches)==v4.SIMILAR_BATCH_SIZE
    assert len(guard.batches)>20,'Large list should not be materialized at once'
    assert v4.similar(guard,1,limit=0)==[]
    try:v4.similar(guard,999999)
    except ValueError as exc:assert 'Unknown' in str(exc)
    else:raise AssertionError('Unknown anchor accepted')

    profile_guard=GuardDB(db)
    t=time.perf_counter()
    person_top=v4.similar_person(profile_guard,100,limit=80)
    profile_ms=(time.perf_counter()-t)*1000
    refs=np.stack(anchors).astype(np.float32)
    selected=[0]
    nearest=refs@refs[0]
    while len(selected)<min(12,len(refs)):
        idx=int(np.argmin(nearest))
        if idx in selected:break
        selected.append(idx)
        nearest=np.maximum(nearest,refs@refs[idx])
    ref=refs[selected].T
    expected_profile=all_expected(ref,.35,80,profile=True)
    assert [x['id'] for x in person_top]==[id_ for _,id_ in expected_profile], 'Per-Person top-k differs from complete oracle'
    assert [x['score'] for x in person_top]==[round(score,3) for score,_ in expected_profile]
    assert all(x['person_id'] is None for x in person_top),'Suggestions are never confirmed'
    assert len(person_top)<=80
    assert profile_guard.batches and max(profile_guard.batches)==v4.SIMILAR_BATCH_SIZE
    assert db.total_changes==changes_before,'Similarity UI must make no SQLite writes'
    assert db.execute('PRAGMA foreign_key_check').fetchall()==[]
    db.close()

print(f'PASS: 20,000 synthetic candidate faces; exact top-48/top-80 matches oracle, no bulk fetchall, '
      f'<=384 row windows, read-only, invalid embeddings abstain. '
      f'Fixture creation {build_time:.2f}s; streaming queries {direct_ms:.1f}/{profile_ms:.1f}ms '
      '(CI-only timings, not measured S24+ performance).')
