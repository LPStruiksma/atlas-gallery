"""Offline regression for read-only V4 policy frontier; synthetic embeddings only."""
from pathlib import Path
import importlib.util
import hashlib
import json
import sys
import tempfile
import types
import math
import numpy as np

mock_cv2 = types.ModuleType('cv2')
sys.modules['cv2'] = mock_cv2
path = Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec = importlib.util.spec_from_file_location('atlas_v4_calibration', path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def vec(x,y):
    a = np.zeros(128,dtype=np.float32)
    a[0]=x
    a[1]=y
    a/=np.linalg.norm(a)
    return a.tobytes()


with tempfile.TemporaryDirectory(prefix='atlas-v4-calibrate-') as tmp:
    m.STATE=Path(tmp)
    m.DB=m.STATE/'synthetic.sqlite3'
    m.CONFIG=m.STATE/'settings.json'
    m.DB_READY_PATH=None
    c=m.connect()
    held_1=min((i for i in range(1,6)),
               key=lambda i:hashlib.sha256(f'v4-eval:1:{i}'.encode()).digest())
    with c:
        for pid in (1,2,3,4):
            c.execute('INSERT INTO people(id,name,created_at) VALUES(?,?,?)',
                      (pid, f'Fictional Person {pid}', 'now'))
        for i in range(1,15):
            c.execute('INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)',
                      (i,f'/synthetic/nonexistent_{i}.png',100,5,'done'))
        fid=0
        def add(pid,image,embedding):
            global fid
            fid+=1
            c.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,confidence,
                embedding,quality,person_id,review)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                (fid,image,0,1,1,90,90,.99,embedding,.95,pid,'confirmed'))
        for i in range(1,6):
            # The held-out face is slightly harder than the three references.
            # Production top score 0.78 abstains, exploratory setting scores it.
            hard=vec(.77,math.sqrt(1-.77**2))
            add(1,i,hard if i==held_1 else vec(1,0))
        for i in range(6,11):
            add(2,i,vec(0,-1))
        # Novel person visually mimics #1. It must be measured as unknown risk.
        add(3,11,vec(1,0))
        add(4,12,vec(0,1))
        add(4,13,vec(0,1))
    initial=[tuple(x) for x in c.execute(
        'SELECT id,image_id,person_id,review FROM faces ORDER BY id')]
    rows_before=c.execute('SELECT COUNT(*) FROM auto_matches').fetchone()[0]
    baseline=m.evaluate_auto_identity(c,limit=30)
    calibrated=m.evaluate_auto_identity(c,limit=30,policy_frontier=True)
    assert 'policy_frontier' not in baseline
    for k,v in baseline.items():
        assert calibrated[k]==v,(k,calibrated.get(k),v)
    settings=calibrated['policy_frontier']
    assert len(settings)==1+len(m.AUTO_EVAL_FRONTIER)
    assert settings[0]['policy']=='current_baseline'
    assert settings[0]['correct']==baseline['correct']
    assert settings[0]['wrong']==baseline['wrong']
    assert settings[0]['known_coverage']==baseline['coverage']
    assert settings[0]['false_unknown_links']==baseline['false_unknown_links']
    assert all(e['held_out_known']==baseline['held_out'] for e in settings)
    assert all(e['held_out_unknown']==baseline['unknown_held_out'] for e in settings)
    # Controlled relaxation actually broadens the synthetic-known coverage.
    assert settings[-1]['known_coverage']>settings[0]['known_coverage'],settings
    # The close unknown identity is dangerous even if positives look perfect:
    # the report must NOT declare a safe policy merely from increased coverage.
    assert settings[0]['false_unknown_links']>=1,settings
    assert all(e['zero_observed_errors'] is False for e in settings)
    assert all(e['unknown_false_link_rate'] is not None for e in settings)
    assert all('policy_frontier' not in e for e in settings)
    assert 'No setting is automatically adopted' in calibrated['frontier_caveat']
    assert not hasattr(m,'AUTO_TOP_SCORE_CHANGED')
    assert (m.AUTO_TOP_SCORE,m.AUTO_SECOND_SUPPORT,m.AUTO_PROFILE_MARGIN)==(.78,.73,.14)

    # Custom exploratory thresholds can never be used to write an identity.
    for bad in [(-1,.75,.14),(.8,float('nan'),.14),(.8,.7),('high',.7,.14)]:
        try:
            m.auto_identity_candidates(c,cap=10,score_policy=bad)
        except ValueError:pass
        else:raise AssertionError(f'Invalid candidate policy accepted: {bad}')
    try:
        m.auto_identity_candidates(c,cap=10,commit=True,score_policy=(.7,.7,.1))
    except ValueError as exc:
        assert 'never assign' in str(exc)
    else:raise AssertionError('Experimental scores enabled People assignment!')
    assert [tuple(x) for x in c.execute(
        'SELECT id,image_id,person_id,review FROM faces ORDER BY id')]==initial
    assert c.execute('SELECT COUNT(*) FROM auto_matches').fetchone()[0]==rows_before
    assert not m.CONFIG.exists(), 'Calibration unexpectedly created a settings file'
    exported=json.dumps(calibrated,sort_keys=True)
    assert 'Fictional Person' not in exported and '/synthetic/' not in exported
    c.close()
print('PASS: identical read-only V4 holdout, exploratory threshold frontier, novel-person risk, never-auto-commit gate')
