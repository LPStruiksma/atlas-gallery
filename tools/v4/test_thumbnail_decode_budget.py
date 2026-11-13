"""Synthetic concurrent thumbnail pressure test. Never reads actual images/People."""
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import importlib.util
import sys
import tempfile
import threading
import time
import types

class FakeImage:
    shape=(120,120,3)
    ndim=3
    def __getitem__(self,key):return self
class FakeJpeg:
    def tobytes(self):return b'\xff\xd8synthetic-jpeg\xff\xd9'

cv2=types.ModuleType('cv2')
cv2.IMWRITE_JPEG_QUALITY=1
cv2.INTER_AREA=3
cv2.imencode=lambda *_args: (True,FakeJpeg())
cv2.resize=lambda image,*_args,**_kwargs:image
sys.modules['cv2']=cv2
sys.modules['numpy']=types.ModuleType('numpy')
script=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_bounded_cold_thumbs',script)
v4=importlib.util.module_from_spec(spec);spec.loader.exec_module(v4)

with tempfile.TemporaryDirectory(prefix='atlas-v4-fake-thumbnails-') as folder:
    root=Path(folder)
    v4.STATE=root/'state';v4.DB=v4.STATE/'fictional.sqlite3'
    v4.CACHE=v4.STATE/'cache';v4.DB_READY_PATH=None
    v4.THUMB_WRITES=0
    db=v4.connect()
    with db:
        for n in range(1,85):
            name=root/f'fictional_photo_{n}.jpg'
            name.write_bytes(b'not-a-real-image')
            db.execute("""INSERT INTO images(id,path,size,mtime_ns,width,height,state)
                VALUES(?,?,?,?,?,?,?)""",(n,str(name),16,1,120,120,'done'))
            db.execute("""INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,confidence)
                VALUES(?,?,?,?,?,?,?,?,?)""",(n,n,0,25,25,45,45,bytes(512),.99))
    db.close()

    guard=threading.Lock()
    active=0
    max_active=0
    calls=Counter()
    failures=set()
    def fake_decode(path,_maxedge):
        nonlocal_keys=path.name
        global active,max_active
        with guard:
            active+=1;max_active=max(max_active,active)
            calls[nonlocal_keys]+=1
        try:
            time.sleep(.015) # releases GIL so a 24-request burst overlaps
            if nonlocal_keys in failures:
                raise ValueError('Synthetic image temporarily unreadable')
            return FakeImage()
        finally:
            with guard:active-=1
    v4.load_image=fake_decode

    def thumb(n,kind='portrait'):
        c=v4.connect()
        try:return v4.get_thumbnail(c,kind,n)
        finally:c.close()

    def burst(ids,kind='portrait'):
        started=time.perf_counter()
        with ThreadPoolExecutor(max_workers=24) as executor:
            result=list(executor.map(lambda face:thumb(face,kind),ids))
        elapsed=time.perf_counter()-started
        assert all(out==FakeJpeg().tobytes() for out in result)
        return elapsed

    v4.THUMB_DECODE_SLOTS=threading.BoundedSemaphore(24)
    baseline_time=burst(range(1,25))
    baseline_peak=max_active
    assert baseline_peak>2,'Synthetic cold-load fixture failed to produce overlapping decodes'
    with guard: max_active=0

    # All newly requested cold images remain limited to two simultaneous OpenCV
    # image allocations, despite 24 concurrent browser/HTTP workers.
    v4.THUMB_DECODE_SLOTS=threading.BoundedSemaphore(2)
    bounded_time=burst(range(25,49))
    with guard:bounded_peak=max_active
    assert 1<=bounded_peak<=2, f'Expected <=2 live image decodes, got {bounded_peak}'
    assert len(calls)==48
    assert v4.THUMB_WRITES==48

    # Repeated GETs for the same un-cached file must share the FIRST result,
    # not waste 12 separate OpenCV decodes / cache file writes.
    repeated=burst([49]*12)
    assert calls['fictional_photo_49.jpg']==1
    assert v4.THUMB_WRITES==49
    assert not list(v4.CACHE.glob('*.tmp')),'Temporary output files leaked'

    # Cached hits must work while ALL decoder slots are held by other jobs.
    assert v4.THUMB_DECODE_SLOTS.acquire(timeout=1)
    assert v4.THUMB_DECODE_SLOTS.acquire(timeout=1)
    try:
        with ThreadPoolExecutor(max_workers=1) as executor:
            warm=executor.submit(thumb,49)
            assert warm.result(timeout=2)==FakeJpeg().tobytes(), \
                'Warm thumbnail blocked behind image-decoding semaphore'
    finally:
        v4.THUMB_DECODE_SLOTS.release()
        v4.THUMB_DECODE_SLOTS.release()
    assert calls['fictional_photo_49.jpg']==1

    # Source metadata change must trigger a new cache key/rebuild.
    c=v4.connect()
    with c:c.execute('UPDATE images SET mtime_ns=2 WHERE id=49')
    c.close()
    thumb(49)
    assert calls['fictional_photo_49.jpg']==2

    # Exceptions release permits; a subsequent corrected file succeeds, with
    # no corrupt cache entry or pending temporary image artifact.
    failures.add('fictional_photo_50.jpg')
    try:thumb(50)
    except ValueError as error:assert 'unreadable' in str(error)
    else:raise AssertionError('Synthetic image decoding error not propagated')
    assert not list(v4.CACHE.glob('*50*.jpg'))
    failures.clear()
    assert thumb(50)==FakeJpeg().tobytes()
    assert calls['fictional_photo_50.jpg']==2
    assert v4.THUMB_DECODE_SLOTS.acquire(blocking=False)
    assert v4.THUMB_DECODE_SLOTS.acquire(blocking=False)
    assert not v4.THUMB_DECODE_SLOTS.acquire(blocking=False)
    v4.THUMB_DECODE_SLOTS.release()
    v4.THUMB_DECODE_SLOTS.release()
    assert not list(v4.CACHE.glob('*.tmp'))
    assert not v4.THUMB_KEY_LOCKS, 'Per-thumbnail synchronization entries leaked'

print(f'PASS: 24 concurrent synthetic uncached requests: unbounded peak {baseline_peak}, bounded peak {bounded_peak} (limit 2). '
      f'Fixture elapsed {baseline_time:.3f}s vs {bounded_time:.3f}s: protective memory/throughput trade-off, NOT S24+ timings. '
      'Duplicate misses coalesced, hot hits bypass gate, cache invalidation and exception cleanup verified.')
