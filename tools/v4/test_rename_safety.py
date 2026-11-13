"""Offline V4 rename safeguards — no real identities or image files."""
from pathlib import Path
import importlib.util
import sys
import tempfile
import types

for name in ('cv2','numpy'):
    sys.modules[name]=types.ModuleType(name)
path=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_rename',path)
v4=importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)
with tempfile.TemporaryDirectory(prefix='atlas-v4-rename-synthetic-') as folder:
    v4.STATE=Path(folder);v4.DB=v4.STATE/'test.sqlite3';v4.DB_READY_PATH=None
    c=v4.connect()
    with c:
        for pid,name in [(1,'Alice'),(2,'Beth'),(3,'Alice')]:
            c.execute('INSERT INTO people(id,name,created_at) VALUES(?,?,?)',(pid,name,'now'))
    def names():return [tuple(r) for r in c.execute('SELECT id,name FROM people ORDER BY id')]
    before=names()
    for attempt in [('ＡＬＩＣＥ',2,False),('  alice  ',2,False),('New',999,False)]:
        name,person,approval=attempt
        try:v4.rename_person(c,person,name,allow_duplicate_name=approval)
        except ValueError:pass
        else:raise AssertionError('Invalid rename succeeded: '+name)
        assert names()==before,'Refused rename mutated People names'
    assert v4.rename_person(c,2,'Beth   '),'No-op same-name rename should succeed'
    assert v4.rename_person(c,2,'  Beth Revised  ')
    assert names()[1]==(2,'Beth Revised')
    assert v4.rename_person(c,2,'ALICE',allow_duplicate_name=True)
    assert names()[1]==(2,'ALICE'),'Explicit homonym must retain separate profile'
    assert names()[0]==(1,'Alice') and names()[2]==(3,'Alice')
    assert c.execute('SELECT COUNT(*) FROM people').fetchone()[0]==3,'Renaming is NOT a merge'
    assert c.execute("SELECT COUNT(*) FROM events WHERE action='rename_person'").fetchone()[0]==3
    c.close()
print('PASS: duplicate-name rename blocked by default, explicit homonym, atomic failure, preserved identities and audit')
