"""Confirmed Person-face paging survives earlier unassignments. Fictional SQLite only."""
from pathlib import Path
import importlib.util
import tempfile
import sys
import types

sys.modules['cv2']=types.ModuleType('cv2')
source=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_person_face_cursor',source)
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

with tempfile.TemporaryDirectory(prefix='atlas-v4-confirmed-paging-') as root:
    m.STATE=Path(root)
    m.DB=m.STATE/'fixture.sqlite3'
    m.DB_READY_PATH=None
    c=m.connect()
    with c:
        c.execute("INSERT INTO people(id,name,created_at) VALUES (9,'Fictional Person','now')")
        c.executemany("INSERT INTO images(id,path,size,mtime_ns,state) VALUES (?,?,?,?,?)",
                      ((i,f'/fictional/image{i}.jpg',40,1,'ready') for i in range(1,181)))
        c.executemany("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,
                       person_id,ignored,review) VALUES (?,?,0,0,0,1,1,?,9,0,'confirmed')""",
                      ((i,i,bytes(512)) for i in range(1,181)))
    where='f.person_id=?'
    visible=m.preview_face_cursor_result(m.get_faces(c,where,(9,),0,61,offset_size=60),60)
    assert [f['id'] for f in visible['items']]==list(range(180,120,-1))
    assert visible['next_before']==121
    assert visible['has_more']
    # Reviewer corrects 35 earlier labels before advancing to unseen faces.
    with c:c.execute("UPDATE faces SET person_id=NULL,review='unreviewed' WHERE id>=146")
    change_count=c.total_changes
    offset=m.get_faces(c,where,(9,),1,60)
    assert offset[0]['id']==85,'Fixture did not reproduce confirmed-page OFFSET skip'
    page2=m.preview_face_cursor_result(m.get_faces(c,where,(9,),1,61,
                                    offset_size=60,before_id=visible['next_before']),60)
    assert page2['items'][0]['id']==120
    assert page2['next_before']==61
    assert [f['id'] for f in page2['items']]==list(range(120,60,-1))
    page3=m.preview_face_cursor_result(m.get_faces(c,where,(9,),2,61,
                                    offset_size=60,before_id=page2['next_before']),60)
    assert [f['id'] for f in page3['items']]==list(range(60,0,-1))
    assert page3['next_before']==1 and page3['has_more'] is False
    assert c.total_changes==change_count,'Read-only face navigation wrote a People label'
    assert m.preview_paged_result(m.get_faces(c,where,(9,),0,60),60,False)==m.get_faces(c,where,(9,),0,60)
    assert c.execute('PRAGMA foreign_key_check').fetchall()==[]
    c.close()

code=source.read_text()
assert "elif path=='/api/person/faces':" in code
assert "before_id=before" in code
assert "get('/api/person/summary?id='+requestedPerson)" in code
print('PASS: 180 fictional confirmed faces, 35 earlier unassignments, pages 2–3 preserve unseen IDs and legacy route; no cursor navigation writes')
