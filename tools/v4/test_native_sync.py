"""Synthetic privacy-preserving V4 Native-sync export regression."""
from pathlib import Path
import importlib.util
import tempfile
import hashlib
import sys
import types

for name in ('cv2','numpy'):
    sys.modules[name] = types.ModuleType(name)
path=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_native_sync',path)
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
with tempfile.TemporaryDirectory(prefix='atlas-v4-sync-') as home:
    m.STATE=Path(home)
    m.CONFIG=m.STATE/'config.json'
    m.DB=m.STATE/'people.sqlite3'
    m.DB_READY_PATH=None
    c=m.connect()
    with c:
        c.execute("INSERT INTO people(id,name,created_at) VALUES (1,'Person One','today')")
        c.execute("INSERT INTO images(id,path,size,mtime_ns,state) VALUES (1,'/storage/emulated/0/DCIM/a.jpg',100,100,'done')")
        c.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,person_id,review)
            VALUES(1,1,0,1,1,80,80,?,1,'confirmed')""",(bytes(512),))
    output=Path(home)/'atlas_v4_live.csv'
    assert m.export_native_sync(c,output)==1
    data=output.read_bytes()
    marker=b'#ATLAS_SYNC_END,'
    assert data.count(marker)==1
    payload,footer=data.split(marker)
    n,digest=footer.decode().strip().split(',')
    assert n=='1'
    assert hashlib.sha256(payload).hexdigest()==digest
    assert payload.startswith(b'person_id,person_name,image_path,face_id,confidence')
    with c:
        c.execute("UPDATE people SET name='Changed Person' WHERE id=1")
    assert m.export_native_sync(c,output)==1
    assert b'Changed Person' in output.read_bytes()
    c.close()
print('PASS: private live V4 export has stable path, complete marker, validated row count and checksum')
