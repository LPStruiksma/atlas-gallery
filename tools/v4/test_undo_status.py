"""Read-only, privacy-minimal status check for synthetic one-use V4 Undo tokens.

Uses actual V4 SQLite code with a disposable fictional database. No images
are read and no real People identities or app data are accessed.
"""
import importlib.util
import pathlib
import sqlite3
import sys
import tempfile
import types

for name in ('cv2','numpy'):
    sys.modules[name]=types.ModuleType(name)
script=pathlib.Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_synthetic_undo_status',script)
v4=importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)


def rejected(fn,phrase):
    try:fn()
    except ValueError as error:
        assert phrase in str(error),repr(error)
    else:raise AssertionError('Expected fail-closed malformed key')


with tempfile.TemporaryDirectory(prefix='atlas-v4-synthetic-undo-status-') as tmp:
    v4.STATE=pathlib.Path(tmp)
    v4.DB=v4.STATE/'fictional.sqlite3'
    v4.DB_READY_PATH=None
    c=v4.connect()

    def read_status(token):
        before=c.total_changes
        result=v4.manual_review_undo_status(c,token)
        assert c.total_changes==before,'Status query mutated SQLite'
        assert set(result)=={'status'},'Status must not leak profiles/faces/snapshot/Undo token'
        return result

    # Missing token is UNKNOWN; it does not prove an earlier attempted Undo
    # failed. An in-flight transaction might commit after this SQL read.
    assert read_status('a'*32)=={'status':'unknown'}
    for invalid in ('', 'G'*32,'a'*31,'a'*33,7,None,{},'a'*32+'\n'):
        rejected(lambda invalid=invalid:read_status(invalid),
                 'Invalid undo status token')

    first=v4.create_person(c,'Fictional Person #1',undo=True)
    token=first['undo_token']
    assert read_status(token)=={'status':'recorded_not_undone'}
    assert v4.undo_manual_review(c,token)==0
    assert read_status(token)=={'status':'undone'}
    # Read-only status NEVER replays the transaction, changes a face, or
    # substitutes an Undo when token was consumed.
    assert read_status(token)=={'status':'undone'}
    rejected(lambda:v4.undo_manual_review(c,token),'already used')

    # Bounded undo history retains only the most recent 12 records. When
    # an old token rotates out, there is no reliable status evidence;
    # the API must report UNKNOWN rather than misleading "not undone".
    second=v4.create_person(c,'Fictional Person #2',undo=True)
    second_token=second['undo_token']
    for i in range(14):
        v4.create_person(c,'Fictional Filler '+str(i),undo=True)
    assert c.execute('SELECT COUNT(*) FROM manual_review_undo').fetchone()[0]<=12
    assert read_status(second_token)=={'status':'unknown'}
    assert read_status(token)=={'status':'unknown'}

    latest=c.execute('SELECT token FROM manual_review_undo ORDER BY rowid DESC LIMIT 1').fetchone()[0]
    assert read_status(latest)=={'status':'recorded_not_undone'}

    # The presence of a not-undone token is NOT proof of a safe retry.
    # A face/profile may change while the record remains unconsumed.
    profile=c.execute("SELECT id FROM people WHERE name='Fictional Filler 13'").fetchone()[0]
    with c:c.execute("UPDATE people SET name='Fictional Modified' WHERE id=?",(profile,))
    assert read_status(latest)=={'status':'recorded_not_undone'}
    rejected(lambda:v4.undo_manual_review(c,latest),
             'changed since creation')
    assert read_status(latest)=={'status':'recorded_not_undone'}

    assert c.execute('PRAGMA quick_check').fetchone()[0]=='ok'
    assert not c.execute('PRAGMA foreign_key_check').fetchall()
    assert "elif path=='/api/review/undo/status':v=manual_review_undo_status(c,body.get('undo_token'))" in script.read_text('utf-8')
    c.close()

print('PASS: real V4 read-only Undo status distinguishes recorded/undone/unknown, refuses malformed tokens, preserves bounded-history ambiguity and never exposes identity material')
