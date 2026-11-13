"""Regression: transient image/ONNX read failures must not delete manual People labels.

All image paths, identities and embeddings here are synthetic. Never needs
Termux, original photographs or downloaded face-recognition models.
"""
import importlib.util
import sys
import tempfile
import types
from pathlib import Path
from unittest.mock import patch

import numpy as np

mock_cv2 = types.ModuleType('cv2')
mock_cv2.error = RuntimeError
sys.modules['cv2'] = mock_cv2
source = Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec = importlib.util.spec_from_file_location('atlas_v4_scan_safety', source)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


with tempfile.TemporaryDirectory(prefix='atlas-v4-scan-safety-') as root:
    m.STATE = Path(root)
    m.DB = m.STATE / 'synthetic.sqlite3'
    m.DB_READY_PATH = None
    old_photo = m.STATE / 'old-photo.jpg'
    new_photo = m.STATE / 'new-photo.jpg'
    old_photo.write_bytes(b'old synthetic fixture')
    new_photo.write_bytes(b'new synthetic fixture')
    unit = np.zeros(128, dtype=np.float32)
    unit[0] = 1.0

    c = m.connect()
    with c:
        c.execute('INSERT INTO people(id,name,created_at) VALUES(?,?,?)',
                  (81, 'Existing person', 'now'))
        st = old_photo.stat()
        c.execute('INSERT INTO images(id,path,size,mtime_ns,state,num_faces) VALUES(?,?,?,?,?,?)',
                  (1, str(old_photo), st.st_size, st.st_mtime_ns, 'done', 1))
        c.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,confidence,
                     embedding,quality,person_id,review)
                     VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                  (10, 1, 0, 2, 2, 80, 80, .99, unit.tobytes(), .9, 81, 'confirmed'))
    c.close()

    broken = {'value': True}

    class FakeVision:
        def __init__(self, settings):
            pass

        def analyze(self, photo):
            if broken['value']:
                raise OSError('synthetic transient image decoding failure')
            return (200, 200, [(2, 2, 80, 80, .99, unit.copy(), .9)])

    def fixture_inventory(connection, roots, recursive, force=False):
        photos = (old_photo, new_photo)
        result = []
        for photo in photos:
            stat = photo.stat()
            row = connection.execute('SELECT id FROM images WHERE path=?', (str(photo),)).fetchone()
            result.append((str(photo), stat.st_size, stat.st_mtime_ns,
                           int(row['id']) if row else None))
        return result, 0, 0

    cfg = {'roots': [str(m.STATE)], 'recursive': False, 'auto_assign_enabled': True}
    with patch.object(m, 'Vision', FakeVision), \
         patch.object(m, 'inventory', fixture_inventory), \
         patch.object(m, 'config', lambda: cfg), \
         patch.object(m, 'refresh_clusters', lambda c: None), \
         patch.object(m, 'maybe_export_native_sync', lambda c: None), \
         patch.object(m, 'progress', lambda **kwargs: None), \
         patch.object(m, 'auto_identity_candidates', side_effect=AssertionError('Scanner must not silently assign People')):
        assert m._scan_impl(limit=2) == 2

        c = m.connect()
        existing = c.execute('SELECT id,person_id,review,embedding FROM faces WHERE image_id=1').fetchall()
        assert len(existing) == 1, 'Transient decode failure wiped an existing face'
        assert tuple(existing[0][:3]) == (10, 81, 'confirmed')
        assert existing[0]['embedding'] == unit.tobytes()
        assert c.execute('SELECT state,error,num_faces FROM images WHERE id=1').fetchone()[0] == 'error'
        assert c.execute('SELECT COUNT(*) FROM faces WHERE image_id=2').fetchone()[0] == 0
        c.close()

        # Retry succeeds. The existing person's tag must carry over by
        # embedding, but an identical NEW photograph must not inherit it.
        broken['value'] = False
        assert m._scan_impl(limit=2) == 2
        c = m.connect()
        preserved = c.execute('SELECT person_id,review FROM faces WHERE image_id=1').fetchall()
        assert len(preserved) == 1 and tuple(preserved[0]) == (81, 'confirmed'), preserved
        untouched_new = c.execute('SELECT person_id,review FROM faces WHERE image_id=2').fetchall()
        assert len(untouched_new) == 1 and tuple(untouched_new[0]) == (None, 'unreviewed'), untouched_new
        assert c.execute('SELECT state,error FROM images WHERE id=1').fetchone()['state'] == 'done'
        assert m.api_status(c)['auto_enabled'] is False
        assert c.execute("SELECT COUNT(*) FROM faces WHERE review='auto'").fetchone()[0] == 0
        c.close()

print('PASS: failed/recovered scans preserve manual tags; legacy auto-on configuration cannot auto-confirm new identities')
