"""Discover shuffle regression: stable paging, no group loss, no identity writes."""
import importlib.util
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import types

import numpy as np

sys.modules["cv2"] = types.ModuleType("cv2")
source = Path(__file__).with_name("monochrome_studio_v4_people_ux_preview.py")
spec = importlib.util.spec_from_file_location("atlas_v4_discover_shuffle", source)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

with tempfile.TemporaryDirectory(prefix="atlas-v4-shuffled-discover-") as root:
    m.STATE = Path(root)
    m.DB = m.STATE / "synthetic-people.db"
    m.DB_READY_PATH = None
    db = m.connect()
    with db:
        # Larger than the real 788-group snapshot without real faces/photos.
        db.executemany(
            "INSERT INTO clusters(id,count,representative,created_at) VALUES (?,?,?,?)",
            ((i, (i % 19) + 1, i, "synthetic") for i in range(1, 2_001))
        )
    # Realistic but fictional review transitions. Original cluster sizes
    # remain historical after a face is named or ignored.
    with db:
        db.execute("INSERT INTO people(id,name,created_at) VALUES (77,'Fictional', 'synthetic')")
        db.executemany(
            "INSERT INTO images(id,path,size,mtime_ns,state) VALUES (?,?,?,?,?)",
            ((i, f'/synthetic/photo-{i}.jpg', 123, i, 'ready') for i in range(1, 7)))
        db.executemany(
            """INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,
               cluster_id,person_id,ignored) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            [
                (1,1,0,0,0,1,1,bytes(512),1,None,0),
                (2,2,0,0,0,1,1,bytes(512),1,None,0),
                (3,3,0,0,0,1,1,bytes(512),2,None,0),
                (4,4,0,0,0,1,1,bytes(512),2,77,0),
                (5,5,0,0,0,1,1,bytes(512),3,None,1),
                (6,6,0,0,0,1,1,bytes(512),4,77,0),
            ])
    # The old cluster representative can be already named (or ignored).
    # Always show a face that can STILL be reviewed, best quality first.
    with db:
        db.executemany("UPDATE faces SET quality=? WHERE id=?", [
            (0.15,1), (0.94,2), (0.72,3), (0.99,4)])
        db.execute("UPDATE clusters SET representative=4 WHERE id=2")
    needed = m.list_groups(db, 0, 40, pending_only=True)
    assert m.count_discover_groups(db, pending_only=True) == 2
    assert m.count_discover_groups(db, pending_only=False) == 2000
    assert [(r["id"],r["remaining_count"]) for r in needed] == [(1,2),(2,1)]
    assert [(r["id"],r["representative"]) for r in needed] == [(1,2),(2,3)], (
        "Active Discover tile must show an unassigned face, not a named historical cover")
    with db:
        db.execute("UPDATE faces SET ignored=1 WHERE id=2")
    assert m.list_groups(db, 0, 40, pending_only=True)[0]["representative"] == 1, (
        "Ignored face must immediately stop appearing as an active-group thumbnail")
    with db:
        db.execute("UPDATE faces SET ignored=0 WHERE id=2")
    assert m.list_groups(db, 0, 40, pending_only=True)[0]["representative"] == 2
    assert [r["id"] for r in m.list_groups(db, 0, 40, order="smallest", pending_only=True)] == [2,1]
    assert [r["id"] for r in m.list_groups(db, 0, 1, pending_only=True)] == [1]
    assert [r["id"] for r in m.list_groups(db, 1, 1, pending_only=True)] == [2]
    assert m.list_groups(db, 2, 1, pending_only=True) == []
    for first_seed in (42, 20261014):
        first = [r["id"] for r in m.list_groups(db, 0, 10, order="shuffle",
                    pending_only=True, seed=first_seed)]
        second = [r["id"] for r in m.list_groups(db, 0, 10, order="shuffle",
                     pending_only=True, seed=first_seed)]
        assert first == second and set(first) == {1,2}, "Active queue shuffle changed"
    historical = {r["id"]:r for n in range(25) for r in m.list_groups(db, n, 80, order="smallest", pending_only=False)}
    assert historical[3]["remaining_count"] == historical[4]["remaining_count"] == 0
    # Naming the last eligible face is a test fixture change, not a
    # Discover-navigation mutation. It should disappear from Needs review,
    # but remain accessible in All groups.
    with db:
        db.execute("UPDATE faces SET person_id=77 WHERE id=3")
    assert [r["id"] for r in m.list_groups(db, 0, 40, pending_only=True)] == [1]
    assert m.count_discover_groups(db, pending_only=True) == 1
    assert m.count_discover_groups(db, pending_only=False) == 2000
    assert {r["id"] for n in range(25) for r in m.list_groups(db, n, 80, order="smallest")} == set(range(1,2001))
    assert next(r for n in range(25) for r in m.list_groups(db, n, 80, order="smallest") if r["id"]==2)["remaining_count"] == 0
    assert next(r for n in range(25) for r in m.list_groups(db, n, 80, order="smallest") if r["id"]==2)["representative"] == 4, (
        "Completed groups must retain their historical cover in All groups")
    before_count = db.execute("SELECT COUNT(*) FROM clusters").fetchone()[0]
    before_changes = db.total_changes
    start = time.perf_counter()
    page_size = 40
    pages = [
        m.list_groups(db, p, page_size, order="shuffle", seed=20261014)
        for p in range(50)
    ]
    duration_ms = (time.perf_counter() - start) * 1_000
    ids = [row["id"] for page in pages for row in page]
    assert len(ids) == before_count == 2000
    assert len(set(ids)) == 2000, "Stable pages duplicated or skipped groups"
    assert set(ids) == set(range(1, 2001)), "Shuffling hid a review candidate"
    assert ids == [
        row["id"] for p in range(50)
        for row in m.list_groups(db, p, page_size, order="shuffle", seed=20261014)
    ], "Same-seed shuffle moved groups between pages"
    changed = [row["id"] for row in m.list_groups(db, 0, page_size, order="shuffle", seed=20261015)]
    assert changed != ids[:page_size], "Explicit reshuffle had no visible effect"
    largest = m.list_groups(db, 0, page_size, order="largest")
    assert [r["count"] for r in largest] == sorted((r["count"] for r in largest), reverse=True)
    smallest_pages = [
        m.list_groups(db, p, page_size, order="smallest") for p in range(50)
    ]
    ascending = [r for page in smallest_pages for r in page]
    assert len(ascending) == before_count and len({r["id"] for r in ascending}) == before_count
    assert [r["count"] for r in ascending] == sorted(r["count"] for r in ascending)
    assert [r["id"] for r in ascending] == [
        r["id"] for r in sorted(ascending, key=lambda row: (row["count"], row["id"]))
    ], "Smallest-first ties must remain stable across all pages"
    assert {r["id"] for r in ascending} == set(ids), "Size sorting must not lose groups"
    assert not m.list_groups(db, 50, page_size, order="smallest")
    assert [row["id"] for row in m.list_groups(db, 50, page_size, order="shuffle", seed=20261014)] == []
    try:
        m.list_groups(db, 0, order="nonsense")
    except ValueError:
        pass
    else:
        raise AssertionError("Unexpected Discover sort mode accepted")
    assert db.execute("SELECT COUNT(*) FROM clusters").fetchone()[0] == before_count
    assert db.total_changes == before_changes, "Discover navigation wrote to source SQLite"
    db.close()

print(f"PASS: 2,000 synthetic groups, stable 50-page shuffle, no duplicates or mutations; {duration_ms:.0f} ms CI diagnostic")
source_text = source.read_text()
assert "groupsreshuffle" in source_text and "groupShuffleSeed" in source_text
assert "groupspending" in source_text and "groupsall" in source_text
assert "const summary=await get('/api/groups/total?pending_only='+only)" in source_text
assert "page=t==='groups'?groupResumePage:0" in source_text
assert "const data={order:groupOrder,pending:groupPendingOnly," in source_text
assert "function saveDiscoverCursor()" in source_text
assert "if(!items.length&&page>0){" in source_text
assert "return groups(epoch);" in source_text, (
    "Empty stale Discover pages must recover in a bounded reset")
assert "if(stable)groups.stableBounds=[null];" in source_text, (
    "The stable pass must invalidate an obsolete page boundary on reset")
assert "groupssmallest" in source_text and "Smallest first" in source_text
assert "groupReturn={page,scroll:window.scrollY}" in source_text
if shutil.which("node"):
    script = source_text.split("<script>", 1)[1].split("</script>", 1)[0]
    result = subprocess.run(["node", "--check"], input=script, text=True, capture_output=True)
    assert result.returncode == 0, result.stderr
print("PASS: Discover shuffle UI JavaScript parses and preserves return context")
# Test the pure cursor decoder under Node; arbitrary stored content must never
# set identifiers or bypass the numeric page/seed bounds. The test does not
# load actual browser localStorage or interact with user photographs.
if shutil.which("node"):
    start = source_text.index("function decodeDiscoverCursor(raw){")
    end = source_text.index("function loadDiscoverCursor()", start)
    decoder = source_text[start:end]
    checks = r"""
const assert=require('node:assert/strict');
const good=JSON.stringify({order:'shuffle',pending:false,page:499,seed:4294967295});
assert.deepEqual(decodeDiscoverCursor(good),
  {order:'shuffle',pending:false,page:499,seed:4294967295});
for(const bad of [
  null,'{oops','[]','"hello"',
  JSON.stringify({order:'invalid',pending:true,page:0,seed:5}),
  JSON.stringify({order:'largest',pending:'false',page:0,seed:5}),
  JSON.stringify({order:'largest',pending:true,page:-1,seed:5}),
  JSON.stringify({order:'largest',pending:true,page:500,seed:5}),
  JSON.stringify({order:'largest',pending:true,page:1.5,seed:5}),
  JSON.stringify({order:'largest',pending:true,page:1,seed:4294967296}),
  JSON.stringify({order:'largest',pending:true,page:1,seed:5,person_id:999,
                  privatePath:'/storage/emulated/0/DCIM/name.jpg'})
]) {
  if(bad && bad.includes('person_id')) {
    const safe=decodeDiscoverCursor(bad);
    assert.deepEqual(safe,{order:'largest',pending:true,page:1,seed:5});
    assert.equal('person_id' in safe,false);
  } else assert.equal(decodeDiscoverCursor(bad),null);
}
"""
    result = subprocess.run(
        ["node", "-e", decoder + checks], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
print("PASS: Discover cursor only restores bounded anonymous navigation primitives")

