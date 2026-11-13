"""Read-only 1600px V4 photo-inspection rendition with synthetic source files."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import threading
import types

cv2=types.ModuleType('cv2')
cv2.error=type('FakeOpenCVError',(Exception,),{})
cv2.IMWRITE_JPEG_QUALITY=1
cv2.INTER_AREA=3
encode_calls=[]
decode_calls=[]
class FakeImage:
    ndim=3
    def __init__(self,width,height):self.shape=(height,width,3)
    def __getitem__(self,key):return self
class FakeJpeg:
    def __init__(self,image,quality):
        self.content=f'{image.shape[1]}x{image.shape[0]}:Q{quality}'.encode('ascii')
    def tobytes(self):return self.content
def resize(im,_size,**kwargs):
    factor=float(kwargs['fx'])
    return FakeImage(max(1,round(im.shape[1]*factor)),max(1,round(im.shape[0]*factor)))
def encode(fmt,im,options):
    assert fmt=='.jpg'
    quality=options[1]
    encode_calls.append((im.shape[1],im.shape[0],quality))
    return True,FakeJpeg(im,quality)
cv2.resize=resize
cv2.imencode=encode
sys.modules['cv2']=cv2
sys.modules['numpy']=types.ModuleType('numpy')
source=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_photo_review_rendition',source)
v4=importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)

with tempfile.TemporaryDirectory(prefix='atlas-v4-fake-photo-detail-') as tmp:
    root=Path(tmp)
    v4.STATE=root/'state';v4.DB=v4.STATE/'fictional.sqlite3'
    v4.DB_READY_PATH=None;v4.CACHE=v4.STATE/'cache'
    v4.THUMB_WRITES=0
    v4.THUMB_DECODE_SLOTS=threading.BoundedSemaphore(2)
    original=root/'fictional-inert-photo.jpg'
    original.write_bytes(b'only-fictional-mock-data')
    c=v4.connect()
    with c:
        c.execute("""INSERT INTO images(id,path,size,mtime_ns,width,height,state)
                  VALUES(?,?,?,?,?,?,?)""",
                  (7,str(original),len(original.read_bytes()),100,4000,2000,'done'))
    def decode(path,edge):
        decode_calls.append((str(path),edge))
        return FakeImage(min(4000,edge),min(2000,round(edge/2)))
    v4.load_image=decode
    before=original.read_bytes()

    grid=v4.get_thumbnail(c,'photo',7)
    inspect=v4.get_thumbnail(c,'review',7)
    assert grid==b'750x375:Q83',grid
    assert inspect==b'1600x800:Q88',inspect
    assert decode_calls==[(str(original),900),(str(original),2400)]
    assert len(encode_calls)==2
    assert (v4.CACHE/'photo-7-100.jpg').read_bytes()==grid
    assert (v4.CACHE/'review-7-100.jpg').read_bytes()==inspect
    assert original.read_bytes()==before,'Original photograph modified'
    assert v4.get_thumbnail(c,'review',7)==inspect
    assert v4.get_thumbnail(c,'photo',7)==grid
    assert len(decode_calls)==2,'Hot files must bypass image decoder'
    with c:c.execute('UPDATE images SET mtime_ns=101 WHERE id=7')
    assert v4.get_thumbnail(c,'review',7)==inspect
    assert len(decode_calls)==3,'Source timestamp invalidation failed'
    assert (v4.CACHE/'review-7-101.jpg').exists()
    assert original.read_bytes()==before
    for bad in ('original','full','../../original','blob'):
        try:v4.get_thumbnail(c,bad,7)
        except ValueError as ex:assert 'rendition' in str(ex)
        else:raise AssertionError('Unrecognized rendition was accepted: '+bad)
    assert not list(v4.CACHE.glob('*.tmp'))
    assert v4.THUMB_KEY_LOCKS=={}
    c.close()

print('PASS: fictional photo 750px grid vs 1600px inspect; separate JPEG caches, '
      'bounded intermediate decode cap, source mtime rebuild, hot-cache bypass, '
      'reject unsupported renditions and preserve original file')
