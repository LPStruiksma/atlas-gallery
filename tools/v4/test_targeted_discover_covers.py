"""Local synthetic comparison of full-roster versus narrowly scoped portrait scores."""
from pathlib import Path
import importlib.util
import sys
import tempfile
import time
import types

# A portrait-cover scorer needs SQLite metadata, not OpenCV or biometric models.
for module in ('cv2','numpy'):
    sys.modules[module]=types.ModuleType(module)
script=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_targeted_covers',script)
v4=importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)

with tempfile.TemporaryDirectory(prefix='atlas-v4-cover-synthetic-') as root:
    v4.STATE=Path(root);v4.DB=v4.STATE/'fictional.sqlite3';v4.DB_READY_PATH=None
    c=v4.connect()
    with c:
        c.executemany('INSERT INTO people(id,name,created_at) VALUES(?,?,?)',
                      [(pid,f'Fictional Person {pid:04}','now') for pid in range(1,2002)])
        c.executemany('INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)',
                      [(i,f'/fictional/{i}.png',20,1,'done') for i in range(1,2002)])
        rows=[]
        for fid in range(1,16009):
            pid=1+(fid-1)%2001
            # Each person's 8 different face detections come from one fictional
            # image; crop geometry and confidence rank the best variant.
            idx=(fid-1)//2001
            size=15+10*idx
            quality=0.3+idx*0.07
            rows.append((fid,pid,idx,1,1,size,size,.4+idx*.06,
                         bytes(512),quality,pid))
        c.executemany("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,confidence,
                     embedding,quality,person_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",rows)
        # A valid portrait override wins even over higher-scoring automated faces.
        valid_cover=next(x[0] for x in rows if x[1]==1301)
        c.execute('INSERT INTO person_covers(person_id,face_id,updated_at) VALUES(?,?,?)',
                  (1301,valid_cover,'now'))
        # Stale manual face no longer belongs to its chosen person -> fallback.
        stale_cover=next(x[0] for x in rows if x[1]==1302)
        c.execute('INSERT INTO person_covers(person_id,face_id,updated_at) VALUES(?,?,?)',
                  (1302,stale_cover,'now'))
        c.execute('UPDATE faces SET ignored=1 WHERE id=?',(stale_cover,))
        # Archived source must not get a suggestion; neither scorer mutates it.
        c.execute('UPDATE people SET merged_into=100 WHERE id=1998')
        c.execute('UPDATE faces SET person_id=100 WHERE person_id=1998')
    before=c.total_changes
    trace=[]
    c.set_trace_callback(trace.append)
    t=time.perf_counter()
    global_covers,global_manual=v4.choose_profile_covers(c)
    full_ms=(time.perf_counter()-t)*1000
    trace.clear()

    candidate_ids=[1301,1302,100]
    t=time.perf_counter()
    small_covers,small_manual=v4.choose_profile_covers(c,candidate_ids)
    scoped_ms=(time.perf_counter()-t)*1000
    assert small_covers=={pid:global_covers[pid] for pid in candidate_ids}
    assert small_manual==global_manual.intersection(candidate_ids)=={1301}
    assert small_covers[1301]==valid_cover
    assert small_covers[1302]!=stale_cover
    assert all(' IN (' in sql for sql in trace if 'FROM faces f JOIN images' in sql)
    assert all(' IN (' in sql for sql in trace if 'FROM person_covers pc' in sql)
    assert len(trace)==2,'Expected exactly two scoped SQL reads'
    assert v4.choose_profile_covers(c,[])==({},set())
    assert v4.choose_profile_covers(c,[1301,1301,1302])==(dict((x,small_covers[x]) for x in (1301,1302)),{1301})
    assert v4.choose_profile_covers(c,[999999])==({},set())
    # A direct roster retains ALL covers (including valid manual overrides).
    roster=v4.list_people(c)
    match=next(p for p in roster if p['id']==1301)
    assert match['cover']==valid_cover and match['cover_manual']
    assert all(p['id']!=1998 for p in roster)
    assert c.total_changes==before,'Read-only portrait lookup must never alter People data'
    assert c.execute('PRAGMA foreign_key_check').fetchall()==[]
    c.close()
print(f'PASS: 2001 fictional People / 16008 faces; 3 scoped portraits exactly match full scorer, '
      f'valid manual override preserved, stale manual fallback, empty/unknown-safe, no writes. '
      f'CI-only whole roster {full_ms:.2f}ms vs 3-person slice {scoped_ms:.2f}ms; '
      'not measured S24+ timing.')
