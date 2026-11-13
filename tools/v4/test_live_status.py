"""V4 status must read the live server or explicitly say 'unknown'."""
from pathlib import Path
from unittest.mock import patch
import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import types

for module in ('cv2','numpy'):
    sys.modules[module] = types.ModuleType(module)
script=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_job_status',script)
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
live={'version':'4.0.0','job':{'phase':'scan','kind':'scan','done':57,'total':120},
      'photos':100,'people':12}

def get_output():
    output=io.StringIO()
    with contextlib.redirect_stdout(output),patch.object(sys,'argv',['v4.py','status','--port','8767']):
        m.main()
    return json.loads(output.getvalue())

with patch.object(m.urllib.request,'urlopen',return_value=io.BytesIO(json.dumps(live).encode())) as url:
    actual=get_output()
    assert actual['job']['phase']=='scan' and actual['job']['done']==57
    assert '127.0.0.1:8767/api/status' in url.call_args.args[0]
with tempfile.TemporaryDirectory(prefix='atlas-v4-live-status-') as temp:
    m.STATE=Path(temp)
    m.DB=m.STATE/'people.sqlite3'
    m.DB_READY_PATH=None
    with patch.object(m.urllib.request,'urlopen',side_effect=OSError('connection refused')):
        fallback=get_output()
    assert fallback['job']['phase']=='unknown',fallback['job']
    assert 'cannot determine whether a job is running' in fallback['job']['message']
    assert fallback['photos']==0
print('PASS: live V4 status reads actual server job; unavailable server never pretends idle')
