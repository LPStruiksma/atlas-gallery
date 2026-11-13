"""V4 Discover tile hints: local synthetic profile match, no identity commits."""
from pathlib import Path
import importlib.util
import math
import subprocess
import shutil
import sqlite3
import sys
import tempfile
import time
import types

import numpy as np

sys.modules['cv2'] = types.ModuleType('cv2')
source = Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec = importlib.util.spec_from_file_location('atlas_v4_hints', source)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def v(x,y=0,index=None):
    arr = np.zeros(128, dtype=np.float32)
    if index is None:
        arr[0] = x
        arr[1] = y
    else:
        arr[index] = 1.0
    arr /= max(float(np.linalg.norm(arr)), 1e-8)
    return arr.tobytes()


with tempfile.TemporaryDirectory(prefix='atlas-discover-hint-test-') as root:
    m.STATE = Path(root)
    m.DB = m.STATE / 'synthetic-v4.db'
    m.DB_READY_PATH = None
    db = m.connect()
    with db:
        for pid in range(1, 501):
            db.execute(
                'INSERT INTO people(id,name,created_at) VALUES(?,?,?)',
                (pid, f'Synthetic Person {pid}', 'today')
            )
        for cluster_id in range(1, 41):
            db.execute(
                'INSERT INTO clusters(id,count,representative,created_at) VALUES(?,?,?,?)',
                (cluster_id, 5, cluster_id, 'today')
            )
        face_id = 0
        image_faces = {}
        def add(cluster_id, person_id, img, embedding):
            global face_id
            face_id += 1
            face_index = image_faces.get(img, 0)
            image_faces[img] = face_index + 1
            db.execute(
                "INSERT OR IGNORE INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)",
                (img,f'/synthetic/{img}.jpg',100,2,'done')
            )
            db.execute("""INSERT INTO faces(
                id,image_id,face_index,x,y,w,h,embedding,quality,
                person_id,cluster_id,ignored,review)
                VALUES(?,?,?,1,1,100,100,?,.95,?,?,0,?)""",
                (face_id,img,face_index,embedding,person_id,cluster_id,
                 'confirmed' if person_id else 'unreviewed')
            )
        # 500 profiles, four manually verified *distinct* exemplar photos.
        for pid in range(1,501):
            ref = v(1) if pid==1 else v(0,1) if pid==2 else v(0, index=2+(pid%126))
            for ex in range(4):
                add(None,pid,50_000+pid*10+ex,ref)
        # Cluster 1: three separate images resembling existing Person #1.
        for i in range(5):
            add(1,None,1_000+i,v(1) if i<3 else v(-1))
        # Cluster 2: deliberately mixed group, TWO people supported.
        for i in range(5):
            add(2,None,2_000+i,v(1) if i<2 else v(0,1))
        # Cluster 3: repeated face detections from one photo != 2 photos.
        for i in range(5):
            add(3,None,3_000,v(1))
        # Cluster 4: one exact SAME-PHOTO confirmed reference is excluded.
        one_person_ref_image = 50_000+1*10
        for i in range(5):
            add(4,None,one_person_ref_image if i<4 else 4_000,
                v(1) if i<4 else v(-1))
        # Rest are low-similarity noise, never silently named.
        for cid in range(5,41):
            for i in range(5):
                add(cid,None,10_000+cid*10+i,v(-1))
    old = [tuple(x) for x in db.execute(
        'SELECT id,image_id,person_id,cluster_id,review FROM faces ORDER BY id'
    )]
    initial_changes=db.total_changes
    start=time.perf_counter()
    groups=m.list_groups(db,0,40)
    duration=(time.perf_counter()-start)*1_000
    print(f'Synthetic V4 Discover page: 40 groups × 500 profiles × '
          f'4 refs in {duration:.0f} ms (CI diagnostic only)')
    assert len(groups)==40
    hints={g['id']:g['possible_existing_people'] for g in groups}
    assert hints[1][0]['person_id']==1,hints[1]
    assert hints[1][0]['supporting_photos']>=3,hints[1]
    assert [p['person_id'] for p in hints[2]]==[2,1] or            [p['person_id'] for p in hints[2]]==[1,2],hints[2]
    assert hints[3]==[],hints[3]
    assert hints[4]==[],hints[4]
    assert all(p['note'].startswith('Possible') for x in hints.values() for p in x)
    assert len(m.list_groups(db,2,40))==0
    assert old == [tuple(x) for x in db.execute(
        'SELECT id,image_id,person_id,cluster_id,review FROM faces ORDER BY id'
    )], 'Hint computation mutated confirmed labels or provisional grouping'
    assert initial_changes==db.total_changes,'Hint API wrote to source V4 SQLite'
    # Regression: baseline group detail candidates remains available, separate
    # from these conservative, capped LIST hints.
    assert m.group_identity_candidates(db,1), 'Existing detail suggestion disappeared'
    db.close()

source_text=source.read_text()
assert 'possible_existing_people' in source_text
assert 'Possibly already named:' in source_text
if shutil.which('node'):
    js=source_text.split('<script>',1)[1].split('</script>',1)[0]
    lint=subprocess.run(['node','--check'],input=js,text=True,capture_output=True)
    assert lint.returncode==0,lint.stderr
print('PASS: batched Discover hints, mixed/unknown safeguards, no DB mutations')
