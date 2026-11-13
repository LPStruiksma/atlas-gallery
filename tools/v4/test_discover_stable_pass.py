"""V4 Discover stable pass: 2,001 fictional groups, no private faces/photos."""
from pathlib import Path
import importlib.util
import sys
import tempfile
import time
import types

sys.modules['cv2']=types.ModuleType('cv2')
src=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_stable_discover',src)
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

for bad in ((None,'1'),('1',None),('','1'),('-1','1'),('+1','1'),
            ('1','0'),('1','01'),('1','x'),('1','9999999999999999999999'),
            ('999999999999999999999','1')):
    try:m.validated_discover_cursor(*bad)
    except ValueError:pass
    else:raise AssertionError('Accepted invalid stable cursor '+repr(bad))
assert m.validated_discover_cursor(None,None) is None
assert m.validated_discover_cursor('0','1')==(0,1)
assert m.validated_discover_cursor('9223372036854775807','9223372036854775807')==(
    9223372036854775807,9223372036854775807)

with tempfile.TemporaryDirectory(prefix='atlas-v4-stable-pass-') as root:
    m.STATE=Path(root)
    m.DB=m.STATE/'fictional.sqlite3'
    m.DB_READY_PATH=None
    c=m.connect()
    with c:
        # The historical group size is immutable. Every group has one
        # still-unreviewed face; dozens of groups tie on historical size.
        c.executemany("INSERT INTO clusters(id,count,representative,created_at) VALUES(?,?,?,?)",
            ((i,(i*37)%89+1,i,'fixture') for i in range(1,2002)))
        c.executemany("INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)",
            ((i,f'/synthetic/fictional-{i}.jpg',10,1,'ready') for i in range(1,2002)))
        c.executemany("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,
                           cluster_id,person_id,ignored)
                           VALUES(?,?,0,0,0,1,1,?,?,NULL,0)""",
            ((i,i,bytes(512),i) for i in range(1,2002)))
    original=sorted(range(1,2002),key=lambda i:(-((i*37)%89+1),i))
    take=lambda p,after=None:m.preview_group_cursor_result(
        m.list_groups(c,p,41,order='stable',pending_only=True,
                      offset_size=40,after_key=after),40)
    begin=time.perf_counter()
    first=take(0)
    seen=[r['id'] for r in first['items']]
    assert seen==original[:40]
    assert first['has_more'] and len(seen)==40
    assert (first['next_count'],first['next_group'])==(
        first['items'][-1]['count'],first['items'][-1]['id'])
    # Other actions deliberately complete 27 groups on page one. The
    # preview must not mistake the last lookahead item for its boundary.
    with c:
        c.executemany("UPDATE faces SET ignored=1 WHERE cluster_id=?",
                      ((i,) for i in seen[:27]))
    changes=c.total_changes
    legacy=m.list_groups(c,1,40,order='stable',pending_only=True)
    assert legacy[0]['id']!=original[40], (
        'Fixture must reproduce a skipped group under legacy offset')
    after=(first['next_count'],first['next_group'])
    for page in range(1,51):
        before_changes=c.total_changes
        current=take(page,after)
        assert c.total_changes==before_changes, 'Discover cursor must be read-only'
        now=[row['id'] for row in current['items']]
        assert now==original[page*40:(page+1)*40], (
            'Stable cursor skipped, repeated or reordered an unseen group',page,now[:2])
        seen.extend(now)
        after=(current['next_count'],current['next_group'])
        if page<50: assert current['has_more']
        else: assert current['has_more'] is False
    assert len(seen)==len(set(seen))==2001
    assert seen==original and c.total_changes==changes
    assert not m.list_groups(c,51,41,order='stable',pending_only=True,
                             offset_size=40,after_key=after)
    # A completed group remains available in All groups, but is correctly
    # omitted from a brand-new Needs review pass.
    new_pass=m.list_groups(c,0,40,order='stable',pending_only=True)
    assert not any(row['id'] in seen[:27] for row in new_pass)
    all_groups=m.list_groups(c,0,40,order='stable',pending_only=False)
    assert [r['id'] for r in all_groups]==original[:40]
    # Never silently allow the immutable keyset on mutable or hashed sorts.
    for mode in ('largest','smallest','shuffle'):
        try:m.list_groups(c,1,41,order=mode,pending_only=True,
                          after_key=(first['next_count'],first['next_group']))
        except ValueError:pass
        else:raise AssertionError('Accepted stable cursor with mutable sort '+mode)
    assert m.preview_paged_result(all_groups,40,False) is all_groups
    assert c.execute('PRAGMA foreign_key_check').fetchall()==[]
    elapsed=time.perf_counter()-begin
    print(f'PASS: 2001 fictional groups, 27 earlier completed, 51 stable cursor '
          f'pages, exact order/uniqueness, legacy offset reproduces skip; '
          f'{elapsed:.2f}s synthetic CI diagnostic only')
    c.close()

text=src.read_text()
assert "groupsstable" in text and "Stable review pass" in text
assert "order!='stable'" in text and "next_group" in text
assert "groups.stableBounds" in text
