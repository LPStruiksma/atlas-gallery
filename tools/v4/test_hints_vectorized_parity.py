"""V4 Discover hints: independent frozen scalar reference vs vectorized scorer.

Fictional embeddings only; validates exactly the same hints, thresholds,
distinct-photo corroboration, ties and no identity mutations.
"""
import importlib.util
from pathlib import Path
import math
import sys
import tempfile
import types

import numpy as np

sys.modules['cv2']=types.ModuleType('cv2')
source=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_hint_equivalence',source)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


def feature(x=1.,y=0.,axis=None):
    values=np.zeros(128,dtype=np.float32)
    if axis is None:
        values[0],values[1]=x,y
    else:values[axis]=1.
    magnitude=max(float(np.linalg.norm(values)),1e-8)
    return (values/magnitude).tobytes()


from hint_scalar_reference import scalar_reference


with tempfile.TemporaryDirectory(prefix='atlas-v4-hint-parity-') as root:
    m.STATE=Path(root);m.DB=m.STATE/'fiction.sqlite3';m.DB_READY_PATH=None
    c=m.connect()
    counters={}
    next_face=0
    def insert(image,cluster,person,blob,quality=0.6):
        global next_face
        next_face+=1
        index=counters.get(image,0);counters[image]=index+1
        c.execute('INSERT OR IGNORE INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)',
                  (image,f'/fictional/{image}.jpg',10,1,'ready'))
        c.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,
                     cluster_id,person_id,quality,review)
                     VALUES(?,?,?,0,0,20,20,?,?,?,?,?)""",
                  (next_face,image,index,blob,cluster,person,quality,
                   'confirmed' if person is not None else 'unreviewed'))
    with c:
        for pid in range(1,18):
            c.execute('INSERT INTO people(id,name,created_at) VALUES(?,?,?)',
                      (pid,f'Fictional {pid}','fixture'))
        for gid in range(1,9):
            c.execute('INSERT INTO clusters(id,count,representative,created_at) VALUES(?,?,?,?)',
                      (gid,5,gid,'fixture'))
        for pid in range(1,18):
            base=feature(axis=pid%127)
            if pid==1:base=feature(1,0)
            if pid==2:base=feature(0,1)
            if pid==17:
                corrupted=np.zeros(128,dtype=np.float32)
                corrupted[0]=np.nan
                base=corrupted.tobytes()
            for j in range(4):insert(50000+pid*10+j,None,pid,base)
        for j in range(5):
            insert(1000+j,1,None,feature(1,0) if j<3 else feature(-1,0))
            insert(2000,2,None,feature(1,0)) # five faces, one photo
            insert(3000+j,3,None,feature(1,0) if j%2 else feature(0,1))
            insert(50010 if j<4 else 4000,4,None,feature(1,0))
            insert(50020 if j<4 else 5000,5,None,feature(0,1))
            insert(6000+j,6,None,feature(-1,0))
            insert(7000+j,7,None,feature(.5,math.sqrt(.75)) if j<3 else feature(-1,0))
            insert(8000+j,8,None,feature(axis=(j+20)%127))
    before=c.total_changes
    wanted=[1,2,3,4,5,6,7,8]
    expected=scalar_reference(c,wanted)
    actual=m.group_list_existing_people_hints(c,wanted)
    assert actual==expected,('Optimized hints must match scalar reference',actual,expected)
    assert actual.get(1) and actual[1][0]['person_id']==1
    assert actual.get(2)==[],'Single distinct photo cannot support a hint'
    assert all(x['person_id']!=17 for rows in actual.values() for x in rows)
    for ids in ([8,3,3,1],[2,7,4,8],[],[1,1,1,1]):
        assert m.group_list_existing_people_hints(c,ids)==scalar_reference(c,ids)
    assert c.total_changes==before,'Read-only hint lookup wrote to SQLite'
    assert not c.execute('PRAGMA foreign_key_check').fetchall()
    c.close()
print('PASS: optimized Discover hints exactly match frozen scalar reference '
      'across mixed profiles, same-photo/duplicate detections, NaN, thresholds, '
      'ties and empty clusters; no identity writes')
