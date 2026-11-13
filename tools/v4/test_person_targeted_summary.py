"""Synthetic performance evidence for direct People summary; no live media/DB."""
from pathlib import Path
import importlib.util
import sys
import tempfile
import time
import types

for mod in ('cv2','numpy'):sys.modules[mod]=types.ModuleType(mod)
script=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('v4_targeted_summary_synthetic',script)
v4=importlib.util.module_from_spec(spec);spec.loader.exec_module(v4)
with tempfile.TemporaryDirectory(prefix='atlas-v4-summary-fake-') as root:
    v4.STATE=Path(root);v4.DB=v4.STATE/'fake.sqlite3';v4.DB_READY_PATH=None
    db=v4.connect()
    with db:
        for person in range(1,2002):
            db.execute('INSERT INTO people(id,name,created_at) VALUES(?,?,?)',
                       (person,f'Fictional Person {person:04}','now'))
        for face in range(1,16009):
            pid=1+((face-1)%2001)
            db.execute('INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)',
                       (face,f'/fake/library/{face}.png',100,1,'done'))
            db.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,confidence,embedding,person_id,quality)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                (face,face,0,1,1,60,60,.9,bytes(512),pid,.8))
    t=time.perf_counter()
    whole=v4.list_people(db)
    full_elapsed=(time.perf_counter()-t)*1000
    target=1311
    found=next(p for p in whole if p['id']==target)
    # Metadata-only name search avoids the full face-cover scan completely.
    original_cover=v4.choose_profile_covers
    v4.choose_profile_covers=lambda *_args,**_kwargs: (_ for _ in ()).throw(
        AssertionError('Metadata-only People roster scored face portraits'))
    compact=v4.list_people(db,include_covers=False)
    v4.choose_profile_covers=original_cover
    assert len(compact)==2001 and len(whole)==len(compact)
    assert [(x['id'],x['name'],x['faces'],x['images']) for x in compact]==[
        (x['id'],x['name'],x['faces'],x['images']) for x in whole]
    assert all(x['cover'] is None and not x['cover_manual'] for x in compact)
    visible_ids=[int(x['id']) for x in compact[:48]]
    changes_before=db.total_changes
    displayed=v4.visible_people_covers(db,visible_ids)
    assert len(displayed)==48
    assert all(displayed[str(pid)]['cover']==next(
        p['cover'] for p in whole if p['id']==pid) for pid in visible_ids)
    assert v4.visible_people_covers(db,[])=={}
    assert v4.visible_people_covers(db,[999999])=={}
    assert v4.visible_people_covers(db,[visible_ids[0],visible_ids[0]])=={
        str(visible_ids[0]):displayed[str(visible_ids[0])]}
    try:v4.visible_people_covers(db,list(range(1,50)))
    except ValueError as exc:assert '48' in str(exc)
    else:raise AssertionError('Unbounded >48-portrait request accepted')
    try:v4.visible_people_covers(db,[-2])
    except ValueError as exc:assert 'positive' in str(exc)
    else:raise AssertionError('Invalid People ID allowed')
    assert db.total_changes==changes_before,'Portrait preview unexpectedly mutated People'
    # The targeted endpoint must never call the full-library cover scorer.
    original=v4.choose_profile_covers
    v4.choose_profile_covers=lambda _: (_ for _ in ()).throw(AssertionError('Full roster cover scan occurred'))
    t=time.perf_counter()
    profile=v4.get_person_summary(db,target)
    targeted_ms=(time.perf_counter()-t)*1000
    v4.choose_profile_covers=original
    assert profile['id']==found['id']
    assert profile['name']==found['name']
    assert profile['note']==found['note']
    assert profile['faces']==found['faces']
    assert profile['images']==found['images']
    assert len(whole)==2001
    assert v4.api_status(db)['people']==2001
    # Archived identities must disappear from direct API, just like the roster.
    v4.merge_people_profiles(db,1,target)
    try:v4.get_person_summary(db,target)
    except ValueError as exc:assert 'archived' in str(exc)
    else:raise AssertionError('Archived profile exposed in Person summary')
    assert v4.get_person_summary(db,1)['faces']==profile['faces']+8
    try:v4.get_person_summary(db,999999)
    except ValueError as exc:assert 'Unknown' in str(exc)
    else:raise AssertionError('Unknown Person did not return error')
    db.close()
print(f'PASS: synthetic 2001 People, 16008 faces; targeted summary {targeted_ms:.2f} ms vs global roster+portraits {full_elapsed:.2f} ms (illustrative CI timings; not Android benchmark)')
