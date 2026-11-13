"""V4 preview synthetic one-row lookahead and legacy-array pagination parity."""
from pathlib import Path
import importlib.util
import sys
import tempfile
import types

sys.modules['cv2']=types.ModuleType('cv2')
spec=importlib.util.spec_from_file_location(
    'atlas_v4_paging_probe',Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

with tempfile.TemporaryDirectory(prefix='atlas-v4-page-boundaries-') as td:
    m.STATE=Path(td)
    m.DB=m.STATE/'synthetic.sqlite3'
    m.DB_READY_PATH=None
    c=m.connect()
    with c:
        c.execute("INSERT INTO people(id,name,created_at) VALUES(11,'Fictional Person','now')")
        for i in range(1,82):
            c.execute("INSERT INTO clusters(id,count,representative,created_at) VALUES(?,?,?,?)",
                      (i,200 if i==1 else 1,1000+i,'now'))
        rows=[]
        images=[]
        # 130 provisional faces in the first group; 80 other Discover clusters.
        for i in range(210):
            face_id=i+1
            cl=1 if i<130 else i-128
            images.append((face_id,f'/synthetic/{face_id}.jpg',64,1,'done'))
            rows.append((face_id,face_id,0,1,1,35,35,b'bad',cl,None,'unreviewed',None))
        # 121 tagged Person faces, each representing a distinct photo.
        for i in range(121):
            fid=1000+i
            images.append((fid,f'/synthetic/{fid}.jpg',64,1,'done'))
            rows.append((fid,fid,0,1,1,35,35,b'bad',None,11,'confirmed',None))
        # 121 separate unassigned, pending machine suggestions.
        for i in range(121):
            fid=2000+i
            images.append((fid,f'/synthetic/{fid}.jpg',64,1,'done'))
            rows.append((fid,fid,0,1,1,35,35,b'bad',None,None,'unreviewed',11))
        c.executemany("INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)",images)
        c.executemany("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,
            cluster_id,person_id,review,suggested_person_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",rows)
        c.execute("UPDATE faces SET suggested_score=0.77 WHERE suggested_person_id=11")

    def assert_three_pages(fetch,limit,count,label):
        legacy=[]
        probe=[]
        for page in range(3):
            old=fetch(page,limit,None)
            wide=fetch(page,limit+1,limit)
            envelope=m.preview_paged_result(wide,limit,True)
            assert len(old)<=limit and isinstance(old,list)
            assert envelope['items']==old,(label,page,'lookahead changed visible rows')
            assert envelope['has_more']==((page+1)*limit<count),(label,page)
            assert m.preview_paged_result(old,limit,False) is old
            legacy.extend(x['id'] for x in old)
            probe.extend(x['id'] for x in envelope['items'])
        assert len(legacy)==count,(label,len(legacy),count)
        assert len(set(probe))==count,(label,'skipped or repeated a row')
        assert legacy==probe,(label,'changed ordering')

    for order in ('largest','smallest','shuffle'):
        for pending in (False,True):
            def fetch(page,take,stride):
                return m.list_groups(c,page,limit=take,order=order,seed=20261017,
                                     pending_only=pending,offset_size=stride)
            assert_three_pages(fetch,40,81,f'Discover {order} pending={pending}')

    assert_three_pages(
        lambda page,take,stride:m.get_faces(c,'f.cluster_id=?',(1,),page,take,stride),
        60,130,'Group')
    assert_three_pages(
        lambda page,take,stride:m.get_faces(c,'f.person_id=?',(11,),page,take,stride),
        60,121,'Confirmed Person faces')
    assert_three_pages(
        lambda page,take,stride:m.get_photos(c,11,page,take,stride),
        60,121,'Person distinct photos')
    assert_three_pages(
        lambda page,take,stride:m.person_suggestions(c,11,page,take,stride),
        60,121,'Pending Person suggestions')
    # Unsorted is 130+80 group candidates plus 121 other suggestions = 331,
    # so iterate however many pages are necessary and assert exact full coverage.
    for expected_page in (0,1,2,3,4,5):
        visible=m.get_faces(c,'f.person_id IS NULL AND f.ignored=0',(),expected_page,60)
        wide=m.get_faces(c,'f.person_id IS NULL AND f.ignored=0',(),expected_page,61,60)
        meta=m.preview_paged_result(wide,60,True)
        assert visible==meta['items']
        assert meta['has_more']==((expected_page+1)*60<331)
    assert c.execute("SELECT COUNT(*) FROM faces WHERE person_id IS NULL AND ignored=0").fetchone()[0]==331
    assert c.execute("PRAGMA foreign_key_check").fetchall()==[]
    c.close()

print('PASS: 81 fictional Discover groups (3 sort modes, both queue filters), '
      '130 group faces, 121 tagged photos/faces and pending suggestions, '
      '331 unsorted faces; exact 40/60-item page boundaries without skipping, '
      'repetition, false Next, API-shape changes or private media.')
