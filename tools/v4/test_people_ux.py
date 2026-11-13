"""Synthetic regression tests: no photos or private databases needed."""
from pathlib import Path
import importlib.util, tempfile, sqlite3, sys, types, subprocess, shutil
from unittest.mock import patch

path=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
# Lightweight CI does not require downloading 40 MB face-recognition models.
# The synthetic group-transaction test does not invoke CV or vector math.
for module in ('cv2','numpy'):
    sys.modules[module] = types.ModuleType(module)
spec=importlib.util.spec_from_file_location('atlas_v4_preview',path)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
with tempfile.TemporaryDirectory(prefix='atlas-v4-ux-') as tmp:
    m.STATE=Path(tmp); m.DB=m.STATE/'test.sqlite3'; m.DB_READY_PATH=None
    c=m.connect()
    with c:
        for pid,name in [(1,'Known'),(2,'Other')]:
            c.execute('INSERT INTO people(id,name,created_at) VALUES (?,?,?)',(pid,name,'2026-10-14'))
        for k in range(1,7):
            c.execute('INSERT INTO images(id,path,size,mtime_ns) VALUES (?,?,?,?)',(k,f'/test/{k}.png',100,1))
            c.execute('INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,person_id,cluster_id) VALUES (?,?,?,?,?,?,?,?,?,?)',
                      (k,k,0,1,1,40,40,bytes(512), 2 if k==4 else None, 7 if k<=5 else None))
        c.execute('INSERT INTO clusters(id,count,representative,created_at) VALUES(7,4,1,?)',('now',))
    def state():
        return [tuple(x) for x in c.execute('SELECT id,person_id,cluster_id FROM faces ORDER BY id')]
    old=state()
    try:m.attach_group_to_person(c,1,7,999)
    except ValueError as exc: assert 'changed since review' in str(exc)
    else:raise AssertionError('stale count not rejected')
    assert state()==old,'rejected request changed any identity'
    try:m.attach_group_to_person(c,999,7,4)
    except ValueError as exc:assert 'Unknown existing person' in str(exc)
    else:raise AssertionError('unknown target not rejected')
    assert state()==old
    changed=m.attach_group_to_person(c,1,7,4)
    assert changed==4
    after=state()
    assert all(x[1]==1 for x in after[:3]),after
    assert after[3][1]==2,'existing confirmed identity overwritten'
    assert after[4][1]==1 and after[5][1] is None
    assert c.execute('SELECT count(*) FROM clusters WHERE id=7').fetchone()[0]==0
    event=c.execute("SELECT detail FROM events WHERE action='attach_group'").fetchone()
    assert event is not None
    c.close()
print('PASS: synthetic group attachment, stale-count atomicity, existing confirmed tags preserved, audit, cluster cleanup')

html=path.read_text().split('<script>',1)[1].split('</script>',1)[0]
if shutil.which('node'):
    check=subprocess.run(['node','--check'],input=html,text=True,capture_output=True)
    assert check.returncode==0,check.stderr
    print('PASS: embedded browser JavaScript parses under Node')
else:
    print('SKIP: Node unavailable for browser JavaScript parse')

# Synthetic profile covers: automatic quality ranking, safe manual override,
# fallback after face relabel, and no false cross-person cover.
with tempfile.TemporaryDirectory(prefix='atlas-v4-cover-') as tmp:
    m.STATE=Path(tmp);m.DB=m.STATE/'covers.sqlite3';m.DB_READY_PATH=None
    c=m.connect()
    with c:
        for pid,name in [(1,'Alice'),(2,'Bob')]:
            c.execute('INSERT INTO people(id,name,created_at) VALUES (?,?,?)',(pid,name,'now'))
        for k,side in [(1,25),(2,75),(3,240),(4,155)]:
            c.execute('INSERT INTO images(id,path,size,mtime_ns,state) VALUES (?,?,?,?,?)',
                (k,f'/synthetic/{k}.png',100,1,'done'))
            c.execute('INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,person_id,quality,confidence) VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                (k,k,0,1,1,side,side,bytes(512),2 if k==4 else 1,0.8,0.92))
    items=m.list_people(c)
    assert items[0]['cover']==3,items
    assert items[0]['cover_manual'] is False
    assert items[1]['cover']==4
    try:m.set_person_cover(c,1,4)
    except ValueError as error:assert 'not a confirmed face' in str(error)
    else:raise AssertionError('Cover could reference another person')
    assert m.set_person_cover(c,1,1)
    assert m.list_people(c)[0]['cover']==1
    assert m.list_people(c)[0]['cover_manual']
    # Remove an assignment without editing originals or breaking cover selection:
    with c:c.execute('UPDATE faces SET person_id=NULL WHERE id=1')
    assert m.list_people(c)[0]['cover']==3,'cover should revert if manual face no longer belongs to person'
    assert m.list_people(c)[0]['cover_manual'] is False
    assert c.execute("SELECT COUNT(*) FROM events WHERE action='person_cover'").fetchone()[0]==1
    c.close()
print('PASS: deterministic high-resolution portrait ranking, manual override, stale cover fallback, ownership safety')

html=path.read_text().split('<script>',1)[1].split('</script>',1)[0]
assert 'showPickerConfirmation(p)' in html,'Picker must show explicit confirmation before assignment'
assert 'confirmTitle:\'Confirm face assignment\'' in html
assert 'Confirm profile merge' in html
assert "root.querySelector('#picker-heading').focus({preventScroll:true})" in html
assert "root.querySelector('#picker-review-back').focus({preventScroll:true})" in html
assert "cyclePickerTab(e,root)" in html
assert "root.querySelector('#picker-query').focus()" not in html
assert 'Use as portrait' in html
if shutil.which('node'):
    result=subprocess.run(['node','--check'],input=html,text=True,capture_output=True)
    assert result.returncode==0,result.stderr
print('PASS: confirmation before identity write and keyboard closed until Search pressed')
# Navigation must never carry selected IDs invisibly into the next page.
assert "if(a==='next'){await moveReviewPage('next');return}" in html
assert "if(a==='prev'){await moveReviewPage('prev');return}" in html
assert "if(!next||next.disabled)return;" in html
assert "if(page<=0)return;page--" in html
assert "if(a==='accept'||a==='reject')" in html
assert "async function finishDirectReview(result,submittedIds,message,start,undoAtSubmit)" in html
assert "const newerUndoExists=lastUndoToken!==undoAtSubmit" in html
assert "keepReviewUndo(result);" in html
assert "for(const id of submittedIds)selected.delete(id)" in html
assert "if(!reviewWriteStillVisible(start))" in html
print('PASS: page-local selections and explicitly accepted/rejected suggestions support undo')
