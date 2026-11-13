"""Local offline regression; synthetic 128D vectors, no private photos or models."""
from pathlib import Path
import importlib.util, tempfile, sqlite3, subprocess, shutil, sys, types
import numpy as np
from unittest.mock import patch

script=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
sys.modules['cv2']=types.ModuleType('cv2')  # Identity candidate matching never invokes face detection.
spec=importlib.util.spec_from_file_location('atlas_v4_group_candidates',script)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

def vector(x,second=0):
    arr=np.zeros(128,dtype=np.float32);arr[0]=x;arr[1]=second
    arr/=max(float(np.linalg.norm(arr)),1e-8)
    return arr.tobytes()

with tempfile.TemporaryDirectory() as folder:
    m.STATE=Path(folder);m.DB=Path(folder)/'faces.db';m.DB_READY_PATH=None
    c=m.connect()
    with c:
        for pid,name in ((1,'Person Alpha'),(2,'Person Beta')):
            c.execute('INSERT INTO people(id,name,created_at) VALUES(?,?,?)',(pid,name,'2026-10-14'))
        for im in range(1,12):
            c.execute('INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)',(im,f'/synthetic/{im}.png',12,123,'done'))
        c.execute('INSERT INTO clusters(id,count,representative,created_at) VALUES(5,4,1,?)',('2026-10-14',))
        entries=[
            (1,1,5,None,vector(1,0)),(2,2,5,None,vector(.98,.12)),(3,3,5,None,vector(.95,.18)),
            (4,4,5,None,vector(0,1)),
            (5,5,None,1,vector(1,0)),(6,6,None,1,vector(.98,.1)),
            (7,7,None,2,vector(0,1)),(8,8,None,2,vector(.1,.95))]
        for fid,im,cluster,person,emb in entries:
            c.execute('''INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,quality,person_id,cluster_id,ignored,review)
                VALUES(?,?,0,1,1,10,10,?,0.9,?,?,0,?)''',
                (fid,im,emb,person,cluster,'confirmed' if person is not None else 'unreviewed'))
    before=list(c.execute('SELECT id,person_id,cluster_id FROM faces ORDER BY id'))
    with patch.object(m,'choose_profile_covers',wraps=m.choose_profile_covers) as cover_reads:
        suggestions=m.group_identity_candidates(c,5)
    assert cover_reads.call_count==1, 'Discover must fetch portraits once per candidate batch'
    assert cover_reads.call_args.args[1]==[p['person_id'] for p in suggestions], (
        'Discover must NOT scan the whole People roster for a few candidates')
    assert [x['name'] for x in suggestions[:2]]==['Person Alpha','Person Beta'], suggestions
    assert suggestions[0]['supporting_faces']==3,suggestions
    assert suggestions[1]['supporting_faces']==1,suggestions
    assert all(0<=x['similarity']<=1 for x in suggestions),suggestions
    assert list(c.execute('SELECT id,person_id,cluster_id FROM faces ORDER BY id'))==before
    assert m.group_identity_candidates(c,999)==[]
    # A full-length float32 BLOB can still hold NaN/Inf. One poisoned
    # high-quality reference must NOT suppress an otherwise valid candidate,
    # and a poisoned group face must not count as independent support.
    corrupted_group=np.zeros(128,dtype=np.float32)
    corrupted_group[0]=np.nan
    corrupted_known=np.zeros(128,dtype=np.float32)
    corrupted_known[0]=np.inf
    with c:
        c.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,quality,
                    person_id,cluster_id,ignored,review) VALUES (10,10,0,1,1,1,1,?,1.0,NULL,5,0,'unreviewed')""",
                  (corrupted_group.tobytes(),))
        c.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,quality,
                    person_id,cluster_id,ignored,review) VALUES (11,11,0,1,1,1,1,?,1.0,1,NULL,0,'confirmed')""",
                  (corrupted_known.tobytes(),))
    before_invalid=c.total_changes
    after=m.group_identity_candidates(c,5)
    assert after==suggestions, ('Nonfinite candidate evidence changed the accepted finite '
                                'score/reference set or sample count',after,suggestions)
    assert c.total_changes==before_invalid,'Inspecting poisoned evidence wrote identities'
    # Same-image evidence alone is disallowed (group observation and profile photo both image_id=1).
    with c:
        c.execute('DELETE FROM faces WHERE person_id IS NOT NULL')
        c.execute('UPDATE faces SET cluster_id=NULL WHERE id IN (2,3,4)')
        c.execute("INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,person_id,review) VALUES (9,1,1,1,1,10,10,?,1,'confirmed')",(vector(1,0),))
    assert m.group_identity_candidates(c,5)==[], 'Same-image face cannot ground identity suggestion'
    c.close()
print('PASS: ranked profile candidates, mixed clusters, nonfinite evidence abstained, read-only, no self-evidence')
text=script.read_text()
assert "'/api/group/candidates'" in text
assert 'data-candidate-person' in text
assert 'initialPersonId:suggestedPersonId' in text
assert 'confirmTitle' in text
if shutil.which('node'):
    js=text.split('<script>',1)[1].split('</script>',1)[0]
    res=subprocess.run(['node','--check'],input=js,text=True,capture_output=True)
    assert res.returncode==0,res.stderr
    print('PASS: embedded JavaScript parses')
