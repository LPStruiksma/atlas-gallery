"""V4 stable Discover fast-path: immutable-keyset query matches frozen aggregate SQL.

Fully fictional 2,001-group SQLite DB; no People data or real photos.
The reference uses the original pre-optimization grouped LEFT JOIN.
"""
from pathlib import Path
import importlib.util
import statistics
import sys
import tempfile
import time
import types

sys.modules['cv2']=types.ModuleType('cv2')
source=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_stable_sql_parity',source)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


def frozen_aggregate(c,page,limit=41,pending_only=True,after_key=None):
    """Stable branch as implemented before this fast-path PR; independently SQL."""
    limit=max(1,min(80,int(limit)));page=max(0,int(page))
    query='''SELECT c.id,c.count,
               COALESCE(
                 (SELECT active.id FROM faces active
                  WHERE active.cluster_id=c.id
                    AND active.person_id IS NULL AND active.ignored=0
                  ORDER BY COALESCE(active.quality,0) DESC,active.id ASC
                  LIMIT 1),c.representative) AS representative,
               c.created_at,COUNT(f.id) AS remaining_count
            FROM clusters c LEFT JOIN faces f ON
              f.cluster_id=c.id AND f.person_id IS NULL AND f.ignored=0'''
    params=[]
    if after_key is not None:
        size,gid=after_key
        query+=' WHERE (c.count < ? OR (c.count = ? AND c.id > ?))'
        params.extend((size,size,gid))
    query+=' GROUP BY c.id'
    if pending_only:query+=' HAVING COUNT(f.id)>0'
    query+=' ORDER BY c.count DESC,c.id LIMIT ? OFFSET ?'
    params.extend((limit,0 if after_key is not None else page*40))
    rows=[dict(x) for x in c.execute(query,params).fetchall()]
    hints=m.group_list_existing_people_hints(c,[
        row['id'] for row in rows if row['remaining_count']>0])
    for row in rows:row['possible_existing_people']=hints.get(row['id'],[])
    return rows


with tempfile.TemporaryDirectory(prefix='atlas-v4-stable-query-') as root:
    m.STATE=Path(root);m.DB=m.STATE/'fictional.sqlite3';m.DB_READY_PATH=None
    c=m.connect()
    with c:
        c.execute("INSERT INTO people(id,name,created_at) VALUES(7,'Fictional','now')")
        c.executemany("INSERT INTO clusters(id,count,representative,created_at) VALUES(?,?,?,?)",
            ((i,(i*37)%89+1,i*10,'fixture') for i in range(1,2002)))
        c.executemany("INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)",
            ((i,f'/fictional/item{i}.jpg',10,1,'ready') for i in range(1,6005)))
        c.executemany("""INSERT INTO faces(
            id,image_id,face_index,x,y,w,h,embedding,quality,
            cluster_id,person_id,ignored,review)
            VALUES(?,?,0,0,0,1,1,?,?,?,?,?,?)""",
            ((gid*3+j-2,gid*3+j-2,bytes(512),None if j==2 else j/5,
              gid,None if gid%29!=0 or j!=3 else 7,
              1 if gid%13==0 and j==1 else 0,
              'unreviewed')
             for gid in range(1,2002) for j in (1,2,3)))
    samples={'old':[],'new':[]}
    def compare(page,pending_only,after_key=None):
        changed=c.total_changes
        # Counterbalanced and tested on exactly the same live connection.
        methods={
            'old':lambda:frozen_aggregate(c,page,pending_only=pending_only,
                                           after_key=after_key),
            'new':lambda:m.list_groups(c,page,41,order='stable',
                            pending_only=pending_only,offset_size=40,
                            after_key=after_key)}
        values={}
        for key in ('old','new') if page%2==0 else ('new','old'):
            start=time.perf_counter();values[key]=methods[key]()
            samples[key].append((time.perf_counter()-start)*1000)
        assert values['old']==values['new'], (
            'Stable optimized query returned different cards/counts/representatives',
            page,pending_only,after_key,
            values['old'][:2],values['new'][:2])
        assert c.total_changes==changed, 'Read-only pagination wrote identity data'
        return values['new']
    first=compare(0,True)
    assert len(first)==41
    anchor=(first[39]['count'],first[39]['id'])
    compare(1,True,anchor)
    compare(6,False)
    compare(9,True)
    # Mixed external review: complete some groups, tag others to known
    # People, leave an all-ignored group, retain total historical counts.
    with c:
        c.executemany("UPDATE faces SET ignored=1 WHERE cluster_id=?",
                      ((gid,) for gid in range(1,85)))
        c.executemany("UPDATE faces SET person_id=7 WHERE cluster_id=?",
                      ((gid,) for gid in range(85,155)))
    first=compare(0,True)
    anchor=(first[39]['count'],first[39]['id'])
    compare(1,True,anchor)
    compare(0,False)
    compare(4,True)
    compare(7,False,anchor)
    # No arbitrary changes to existing Largest/Smallest/Shuffle sort paths.
    for other in ('largest','smallest','shuffle'):
        rows=m.list_groups(c,0,41,order=other,pending_only=True)
        assert len(rows)==41
    assert not c.execute('PRAGMA foreign_key_check').fetchall()
    print('PASS: stable two-stage SQL equals frozen full aggregate across '
          '2001 groups, tied historical counts, ignored/assigned faces, '
          'pending/all filters, offset and keyset, representative selection')
    print('DIAGNOSTIC synthetic same-host medians (not thresholds or Android):',
          {name:round(statistics.median(values),2) for name,values in samples.items()})
    c.close()
