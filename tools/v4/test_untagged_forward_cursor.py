"""Forward-only Unsorted cursor amid deletions: fictional SQLite, zero navigation writes."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import types

sys.modules['cv2']=types.ModuleType('cv2')
spec=importlib.util.spec_from_file_location(
    'atlas_v4_cursor_test',Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

assert m.validated_face_cursor(None) is None
for invalid in ('', '0', '-1', '+5', '05', '4.0', ' 5', 'NaN', '9999999999999999999999'):
    try:m.validated_face_cursor(invalid)
    except ValueError:pass
    else:raise AssertionError(f'Accepted malformed cursor: {invalid!r}')

with tempfile.TemporaryDirectory(prefix='atlas-v4-forward-inbox-') as directory:
    m.STATE=Path(directory)
    m.DB=m.STATE/'fictional.db'
    m.DB_READY_PATH=None
    db=m.connect()
    with db:
        db.execute("INSERT INTO people(id,name,created_at) VALUES (5,'Synthetic Reviewed','fixture')")
        db.executemany("INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)",
            ((i,f'/fictional/{i}.jpg',100,2,'ready') for i in range(1,2002)))
        db.executemany("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,
             person_id,ignored,review) VALUES (?,?,0,1,1,4,4,?,NULL,0,'unreviewed')""",
            ((i,i,bytes(512)) for i in range(1,2002)))
    predicate='f.person_id IS NULL AND f.ignored=0'
    all_initial=list(range(2001,0,-1))
    limit=60
    first=m.get_faces(db,predicate,(),0,61,offset_size=60)
    first_meta=m.preview_face_cursor_result(first,limit)
    assert [row['id'] for row in first_meta['items']]==all_initial[:60]
    assert first_meta['next_before']==1942, 'The lookahead ID is NOT the cursor'
    assert first_meta['has_more'] is True
    before_changes=db.total_changes
    # Another review assigned the first 35 highest-ID faces after page one.
    with db:db.execute('UPDATE faces SET person_id=5,review="confirmed" WHERE id>=1967')
    before_changes=db.total_changes
    # Old offset skips 35 unseen IDs: cursor must not.
    old=m.get_faces(db,predicate,(),1,60)
    assert old[0]['id']==1906,'Confirm fixture reproduces the offset skip'
    second=m.preview_face_cursor_result(
        m.get_faces(db,predicate,(),1,61,offset_size=60,
                    before_id=first_meta['next_before']),limit)
    assert second['items'][0]['id']==1941
    assert second['next_before']==1882
    assert [x['id'] for x in second['items']]==all_initial[60:120]
    assert db.total_changes==before_changes, 'Cursor browsing wrote identities'
    # Continue forward under interleaved earlier-page mutation; no gaps/duplicates.
    seen=[x['id'] for x in first_meta['items']]+[x['id'] for x in second['items']]
    before=second['next_before']
    for page in range(2,34):
        # Mutate a *previously visited* face: new cursors cannot drift.
        if page%3==0:
            with db:db.execute('UPDATE faces SET ignored=1 WHERE id=?',(2002-page,))
        changes=db.total_changes
        result=m.preview_face_cursor_result(
            m.get_faces(db,predicate,(),page,61,offset_size=60,before_id=before),60)
        assert db.total_changes==changes
        seen.extend(x['id'] for x in result['items'])
        before=result['next_before']
        if not result['has_more']:break
    assert seen==all_initial, 'Earlier-page review must not skip, repeat, or invent faces'
    assert len(seen)==2001
    assert len(set(seen))==2001
    assert before==1
    assert result['has_more'] is False
    # Newly arriving higher IDs are intentionally NOT injected into old cursor.
    with db:
        db.execute("INSERT INTO images(id,path,size,mtime_ns,state) VALUES(3000,'/fictional/new.jpg',1,1,'ready')")
        db.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding)
                   VALUES (3000,3000,0,1,1,4,4,?)""",(bytes(512),))
    assert m.get_faces(db,predicate,(),0,1)[0]['id']==3000
    assert not m.get_faces(db,predicate,(),0,61,before_id=1)
    assert m.preview_paged_result(first,60,False) is first, 'Old array API changed'
    assert db.execute('PRAGMA foreign_key_check').fetchall()==[]
    db.close()
print('PASS: 2001 fictional faces, 35 early-page assignments, interleaved hide and fresh insert; cursor covers every original ID exactly once; legacy API unchanged')
