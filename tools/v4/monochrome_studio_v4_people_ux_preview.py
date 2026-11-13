#!/usr/bin/env python3
"""Monochrome Studio 4 — local, review-led face organizer for Termux.

Photos are READ ONLY. No external web dependencies or cloud face APIs.
Models are separate optional downloads from OpenCV Zoo and verified with SHA256.
No person is named or confirmed by an ML model. Face groups are proposals.
"""
from __future__ import annotations
import argparse
import csv
import io
import fcntl
from contextlib import contextmanager
import hashlib
import heapq
import json
import os
import re
import sqlite3
import sys
import threading
import uuid
import unicodedata
import time
import urllib.request
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, parse_qs

import cv2
import numpy as np

VERSION = "4.0.0"
STATE = Path.home() / '.monochrome_studio_v4'
DB = STATE / 'people.sqlite3'
CONFIG = STATE / 'settings.json'
MODELS = STATE / 'models'
CACHE = STATE / 'cache'
LOCK_FILE = STATE / 'active-job.lock'
CACHE_BUDGET = 512 * 1024 * 1024
# A lazy-loaded 48-card People page can burst into many thumbnail requests.
# Bound simultaneously live OpenCV images (especially 3200px portraits) on
# an unrooted phone; never serialize already-cached thumbnail reads.
THUMB_DECODE_SLOTS = threading.BoundedSemaphore(2)
THUMB_KEY_GUARD = threading.Lock()
THUMB_KEY_LOCKS = {}  # Only keys with requests in flight; auto-evicted.
THUMB_WRITES = 0
SCHEMA_LOCK = threading.Lock()
DB_READY_PATH = None
DETECTOR_NAME = 'face_detection_yunet_2023mar.onnx'
RECOGNIZER_NAME = 'face_recognition_sface_2021dec.onnx'
MODEL_INFO = {
    DETECTOR_NAME: ('face_detection_yunet', '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4', 227000),
    RECOGNIZER_NAME: ('face_recognition_sface', '0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79', 38696353),
}
EXTS = {'.jpg', '.jpeg', '.png', '.webp', '.bmp', '.tif', '.tiff'}
EXTRA_EXTS = {'.heic', '.heif', '.avif'}
try:
    import pillow_heif
    from PIL import Image, ImageOps
    pillow_heif.register_heif_opener()
    EXTS |= EXTRA_EXTS
except ImportError:
    Image = None
JOB_LOCK = threading.Lock()
STOP = threading.Event()
STATUS_LOCK = threading.Lock()
JOB = {'phase':'idle','message':'Ready','done':0,'total':0,'speed':0,'eta':None,'started_at':None}


def now():
    return datetime.now().astimezone().isoformat(timespec='seconds')



@contextmanager
def exclusive_job():
    """OS-level lock: protects against concurrent Termux sessions, not just threads."""
    STATE.mkdir(parents=True, exist_ok=True)
    with LOCK_FILE.open('a+') as handle:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise RuntimeError('Another Studio V4 scan/group job is already running in Termux.') from exc
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def prune_cache(budget=CACHE_BUDGET):
    if not CACHE.is_dir():return (0,0)
    files=[];total=0
    for p in CACHE.glob('*.jpg'):
        try:
            st=p.stat();total+=st.st_size;files.append((st.st_mtime,st.st_size,p))
        except OSError:pass
    deleted=0
    if total>budget:
        for _,size,p in sorted(files):
            try:p.unlink();total-=size;deleted+=1
            except OSError:pass
            if total<=int(budget*.8):break
    return deleted,total


def progress(**kw):
    with STATUS_LOCK:
        JOB.update(kw)


def progress_snapshot():
    with STATUS_LOCK:
        return dict(JOB)


def config():
    if CONFIG.exists():
        return json.loads(CONFIG.read_text('utf-8'))
    roots = []
    old = Path.home() / '.bw_studio_v3' / 'config.json'
    if old.exists():
        try:
            previous = json.loads(old.read_text('utf-8'))
            roots = [previous['source'], previous['destination']]
        except (ValueError, KeyError, OSError):
            pass
    return {'roots':list(dict.fromkeys(roots)), 'recursive':True, 'detect_maxedge':2000,
            'detect_threshold':0.72, 'min_face':14}


def save_config(d):
    STATE.mkdir(parents=True,exist_ok=True)
    t = CONFIG.with_suffix('.tmp')
    t.write_text(json.dumps(d,indent=2),encoding='utf-8')
    os.replace(t,CONFIG)


def add_root(path):
    path = Path(path).expanduser().resolve()
    if not path.is_dir():
        raise ValueError(f'Folder does not exist: {path}')
    c=config()
    if str(path) not in c['roots']:
        c['roots'].append(str(path))
        save_config(c)
    return path


def connect():
    global DB_READY_PATH
    STATE.mkdir(parents=True,exist_ok=True)
    c=sqlite3.connect(str(DB),timeout=30,check_same_thread=False)
    c.row_factory=sqlite3.Row
    c.execute('PRAGMA busy_timeout=30000')
    c.execute('PRAGMA foreign_keys=ON')
    with SCHEMA_LOCK:
        if DB_READY_PATH != str(DB):
            c.execute('PRAGMA journal_mode=WAL')
            c.executescript('''
            CREATE TABLE IF NOT EXISTS images(
                id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE,
                size INTEGER NOT NULL, mtime_ns INTEGER NOT NULL,
                width INTEGER, height INTEGER, state TEXT NOT NULL DEFAULT 'pending',
                error TEXT, scanned_at TEXT, num_faces INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS img_match ON images(size,mtime_ns);
            CREATE TABLE IF NOT EXISTS people(
                id INTEGER PRIMARY KEY, name TEXT NOT NULL, note TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS faces(
                id INTEGER PRIMARY KEY, image_id INTEGER NOT NULL REFERENCES images(id) ON DELETE CASCADE,
                face_index INTEGER NOT NULL, x REAL NOT NULL, y REAL NOT NULL,
                w REAL NOT NULL, h REAL NOT NULL, confidence REAL,
                embedding BLOB NOT NULL, quality REAL,
                person_id INTEGER REFERENCES people(id) ON DELETE SET NULL,
                cluster_id INTEGER,
                suggested_person_id INTEGER REFERENCES people(id) ON DELETE SET NULL,
                suggested_score REAL,
                ignored INTEGER NOT NULL DEFAULT 0,
                review TEXT NOT NULL DEFAULT 'unreviewed',
                UNIQUE(image_id,face_index));
            CREATE INDEX IF NOT EXISTS face_person ON faces(person_id,ignored);
            CREATE INDEX IF NOT EXISTS face_cluster ON faces(cluster_id,ignored);
            CREATE INDEX IF NOT EXISTS face_image ON faces(image_id);
            CREATE TABLE IF NOT EXISTS person_covers(
                person_id INTEGER PRIMARY KEY REFERENCES people(id) ON DELETE CASCADE,
                face_id INTEGER NOT NULL REFERENCES faces(id) ON DELETE CASCADE,
                updated_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS clusters(
                id INTEGER PRIMARY KEY, count INTEGER NOT NULL, representative INTEGER NOT NULL,
                created_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS auto_matches(
                id INTEGER PRIMARY KEY,
                face_id INTEGER NOT NULL REFERENCES faces(id) ON DELETE CASCADE,
                person_id INTEGER REFERENCES people(id) ON DELETE SET NULL,
                score REAL NOT NULL, model_version TEXT NOT NULL,
                batch_id TEXT NOT NULL, created_at TEXT NOT NULL,
                reverted_at TEXT);
            CREATE INDEX IF NOT EXISTS auto_matches_batch ON auto_matches(batch_id,id);
            CREATE TABLE IF NOT EXISTS rejected(
                face_id INTEGER NOT NULL REFERENCES faces(id) ON DELETE CASCADE,
                person_id INTEGER NOT NULL REFERENCES people(id) ON DELETE CASCADE,
                PRIMARY KEY(face_id,person_id));
            CREATE TABLE IF NOT EXISTS manual_review_undo(token TEXT PRIMARY KEY, at TEXT NOT NULL, kind TEXT NOT NULL, person_id INTEGER NOT NULL, snapshot TEXT NOT NULL, undone_at TEXT);
            -- Opaque client operation keys survive lost HTTP replies/restarts.
            -- Deliberately no person_id FK: Undo must not erase a replay tombstone.
            -- No names, face IDs, photos or biometric vectors are stored here.
            CREATE TABLE IF NOT EXISTS person_create_requests(
                request_id TEXT PRIMARY KEY,
                payload_digest TEXT NOT NULL,
                person_id INTEGER NOT NULL,
                undo_token TEXT,
                created_at TEXT NOT NULL,
                invalidated_at TEXT);
            -- No face IDs, names, embeddings or file paths are retained.
            CREATE TABLE IF NOT EXISTS review_write_requests(
                request_id TEXT PRIMARY KEY,
                payload_digest TEXT NOT NULL,
                action TEXT NOT NULL,
                count INTEGER NOT NULL,
                undo_token TEXT,
                created_at TEXT NOT NULL);
            -- Merge archives never delete source People or cascade review history.
            CREATE TABLE IF NOT EXISTS person_merges(
                source_id INTEGER PRIMARY KEY REFERENCES people(id) ON DELETE RESTRICT,
                target_id INTEGER NOT NULL REFERENCES people(id) ON DELETE RESTRICT,
                moved_count INTEGER NOT NULL, imported_cover_face_id INTEGER,
                imported_cover_at TEXT, undo_token TEXT NOT NULL UNIQUE,
                merged_at TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS person_merges_target ON person_merges(target_id);
            CREATE TABLE IF NOT EXISTS person_merge_faces(
                source_id INTEGER NOT NULL REFERENCES person_merges(source_id) ON DELETE CASCADE,
                face_id INTEGER NOT NULL REFERENCES faces(id) ON DELETE CASCADE,
                review TEXT NOT NULL, cluster_id INTEGER, ignored INTEGER NOT NULL,
                suggested_person_id INTEGER, suggested_score REAL,
                PRIMARY KEY(source_id,face_id));
            CREATE TABLE IF NOT EXISTS events(
                id INTEGER PRIMARY KEY, at TEXT NOT NULL, action TEXT NOT NULL,
                detail TEXT NOT NULL);
            ''')
            columns={r['name'] for r in c.execute('PRAGMA table_info(faces)')}
            if 'suggested_person_id' not in columns:
                c.execute('ALTER TABLE faces ADD COLUMN suggested_person_id INTEGER')
            if 'suggested_score' not in columns:
                c.execute('ALTER TABLE faces ADD COLUMN suggested_score REAL')
            people_columns={r['name'] for r in c.execute('PRAGMA table_info(people)')}
            if 'merged_into' not in people_columns:
                c.execute('ALTER TABLE people ADD COLUMN merged_into INTEGER REFERENCES people(id)')
            c.commit()
            DB_READY_PATH = str(DB)
    return c


def log(c,action,detail):
    c.execute('INSERT INTO events(at,action,detail) VALUES(?,?,?)',
              (now(),action,json.dumps(detail,ensure_ascii=False)))


def local_model(name):
    for p in (MODELS/name,Path.home()/name,Path.home()/'models'/name,
              Path.home()/'storage'/'downloads'/name):
        if p.is_file() and p.stat().st_size > 100000:
            return p
    return MODELS/name


def install_models():
    MODELS.mkdir(parents=True,exist_ok=True)
    for name,(section,expected,_size) in MODEL_INFO.items():
        dst = MODELS/name
        if dst.is_file() and digest(dst)==expected:
            print(f'Already verified: {dst}',flush=True)
            continue
        url=f'https://huggingface.co/opencv/opencv_zoo/resolve/main/models/{section}/{name}'
        temp=dst.with_suffix('.download')
        print(f'Downloading {name} from OpenCV Zoo…',flush=True)
        try:
            req=urllib.request.Request(url,headers={'User-Agent':'MonochromeStudio/4.0'})
            with urllib.request.urlopen(req,timeout=50) as response, temp.open('wb') as file:
                size=0
                while True:
                    chunk=response.read(1<<20)
                    if not chunk: break
                    file.write(chunk); size+=len(chunk)
                    print(f'\r  {size/1048576:.1f} MB',end='',flush=True)
            print()
            actual=digest(temp)
            if actual!=expected:
                raise ValueError(f'SHA256 mismatch for {name}: {actual}; expected {expected}')
            temp.replace(dst)
            print('Verified SHA256:',str(dst),flush=True)
        finally:
            temp.unlink(missing_ok=True)


def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1<<20),b''): h.update(chunk)
    return h.hexdigest()


def check_models():
    bad=[]
    for n in MODEL_INFO:
        p=local_model(n)
        if not p.is_file(): bad.append(n)
    if bad:
        raise RuntimeError('Missing face models: '+', '.join(bad)+'\nRun: python ~/monochrome_studio_v4.py install-models')


def load_image(path,maxedge=2000):
    path=Path(path)
    if path.suffix.lower() in EXTRA_EXTS:
        if Image is None: raise ValueError('HEIC/HEIF requires pillow-heif and Pillow')
        with Image.open(path) as pil:
            pil=ImageOps.exif_transpose(pil).convert('RGB')
            pil.thumbnail((maxedge,maxedge))
            a=cv2.cvtColor(np.asarray(pil),cv2.COLOR_RGB2BGR)
    else:
        # Half-resolution decoding conserves RAM on Android; small images stay full size.
        a=cv2.imread(str(path),cv2.IMREAD_REDUCED_COLOR_2)
        if a is None: raise ValueError('OpenCV could not decode image')
        if max(a.shape[:2]) < 700:
            full=cv2.imread(str(path),cv2.IMREAD_COLOR)
            if full is not None: a=full
        h,w=a.shape[:2]
        if max(h,w)>maxedge:
            scale=maxedge/max(h,w)
            a=cv2.resize(a,(max(1,round(w*scale)),max(1,round(h*scale))),
                         interpolation=cv2.INTER_AREA)
    if a is None or a.ndim!=3: raise ValueError('Could not decode 3-channel photo')
    return a


class Vision:
    def __init__(self,settings=None):
        check_models()
        settings=settings or config()
        try:
            self.detector=cv2.FaceDetectorYN.create(str(local_model(DETECTOR_NAME)), '', (320,320),
                         float(settings.get('detect_threshold',.72)),.3,500)
            self.recognizer=cv2.FaceRecognizerSF.create(str(local_model(RECOGNIZER_NAME)),'')
        except AttributeError as e:
            raise RuntimeError('Your cv2 build lacks FaceDetectorYN or FaceRecognizerSF; update OpenCV.') from e
        cv2.setNumThreads(2)
        self.maxedge=int(settings.get('detect_maxedge',2000))
        self.minface=int(settings.get('min_face',14))

    def analyze(self,path):
        im=load_image(path,self.maxedge)
        height,width=im.shape[:2]
        self.detector.setInputSize((width,height))
        _ok, detected=self.detector.detect(im)
        out=[]
        if detected is not None:
            for face in detected:
                x,y,w,h=map(float,face[:4])
                if min(w,h)<self.minface: continue
                try:
                    aligned=self.recognizer.alignCrop(im,face)
                    emb=self.recognizer.feature(aligned).ravel().astype('float32')
                    norm=float(np.linalg.norm(emb))
                    if not np.isfinite(norm) or norm<1e-8: continue
                    emb/=norm
                    quality=float(min(1.,min(w,h)/130.)*max(.0,min(1.,float(face[-1]))))
                    out.append((x,y,w,h,float(face[-1]),emb,quality))
                except cv2.error:
                    continue
        return width,height,out


def image_files(roots,recursive):
    """Yield resolved path, size, mtime. Avoid following directory symlinks."""
    seen=set()
    for root in roots:
        root=Path(root).expanduser().resolve()
        if not root.is_dir():
            print(f'WARNING: Missing root: {root}',file=sys.stderr,flush=True)
            continue
        if recursive:
            for base,dirs,files in os.walk(root,followlinks=False):
                dirs[:]=[d for d in dirs if not (Path(base)/d).is_symlink()]
                for name in files:
                    p=Path(base)/name
                    if p.suffix.lower() not in EXTS or p.is_symlink(): continue
                    s=str(p)
                    if s in seen: continue
                    seen.add(s)
                    try:
                        st=p.stat()
                        if p.is_file(): yield s,int(st.st_size),int(st.st_mtime_ns)
                    except OSError: continue
        else:
            with os.scandir(root) as listing:
                for entry in listing:
                    if Path(entry.name).suffix.lower() not in EXTS: continue
                    s=entry.path
                    if s in seen: continue
                    seen.add(s)
                    try:
                        if not entry.is_file(follow_symlinks=False): continue
                        st=entry.stat(follow_symlinks=False)
                        yield s,int(st.st_size),int(st.st_mtime_ns)
                    except OSError: continue


def inventory(c, roots, recursive, force=False):
    """Only path and metadata; look for moved files before treating them as new."""
    jobs=[]; cached=0; relinked=0
    for path,size,mtime in image_files(roots,recursive):
        r=c.execute('SELECT id,size,mtime_ns,state FROM images WHERE path=?',(path,)).fetchone()
        if r:
            if not force and r['size']==size and r['mtime_ns']==mtime and r['state']=='done':
                cached+=1; continue
            jobs.append((path,size,mtime,int(r['id'])))
        else:
            # Recognize file moves between monitored folders without losing labels.
            old=c.execute('SELECT id,path FROM images WHERE size=? AND mtime_ns=? AND path<>? ORDER BY id LIMIT 6',
                          (size,mtime,path)).fetchall()
            missing=[r for r in old if not Path(r['path']).exists()]
            if len(missing)==1:
                c.execute('UPDATE images SET path=? WHERE id=?',(path,missing[0]['id']))
                relinked+=1; cached+=1
            else:
                jobs.append((path,size,mtime,None))
    c.commit()
    return jobs,cached,relinked


def scan(roots=None,limit=None,force=False):
    with exclusive_job():
        return _scan_impl(roots,limit,force)


AUTO_MODEL_VERSION = 'sface_2021dec_confirmed_only_v1'
# Conservative engineering *starting criteria*, not probabilities/calibrated
# on the user's archive. Validate before considering unattended roll-out.
AUTO_TOP_SCORE = 0.78
AUTO_SECOND_SUPPORT = 0.73
AUTO_PROFILE_MARGIN = 0.14
AUTO_MIN_FACE_QUALITY = 0.55
AUTO_MIN_FACE_SIDE = 38
# Read-only diagnostic policies. None changes live auto-matching thresholds,
# enrollment rules or face-quality gates. A successful holdout is NOT a license
# to switch auto-assign on; no policy is automatically selected or saved.
AUTO_EVAL_FRONTIER = (
    ('more_strict', (.82, .77, .18)),
    ('strict', (.80, .75, .16)),
    ('higher_coverage_1', (.76, .71, .14)),
    ('higher_coverage_2', (.74, .69, .14)),
    ('exploratory_only', (.72, .67, .12)),
)


def auto_identity_candidates(c, face_ids=None, cap=500, commit=False, return_full=False,
                             score_policy=None):
    """Fast, bounded, high-precision existing-person matching.

    Only MANUALLY CONFIRMED labels seed proposals: automated links never train
    later automatic links, so one bad guess cannot snowball across scans.
    Requires >=3 reference PHOTOS/person and agreement from >=2 different
    reference photos. All conflicts, rejected labels and uncertain matches
    abstain rather than force an identity. No new profiles are created.
    """
    # Guardrail: experimental thresholds are legal for read-only scoring only.
    # Nobody can accidentally call commit=True with a frontier candidate.
    if score_policy is not None:
        if commit:
            raise ValueError('Calibration policies must never assign People')
        if (not isinstance(score_policy,(tuple,list)) or len(score_policy)!=3
            or any(not isinstance(value,(int,float)) or not np.isfinite(value)
                   or not 0.0<=value<=1.0 for value in score_policy)):
            raise ValueError('Three finite cosine/margin thresholds in [0,1] required')
        top_threshold,second_threshold,margin_threshold=score_policy
    else:
        top_threshold,second_threshold,margin_threshold=(
            AUTO_TOP_SCORE,AUTO_SECOND_SUPPORT,AUTO_PROFILE_MARGIN)
    cap=max(0,min(int(cap),10000))
    if cap==0:return dict(examined=0,linked=0,candidates=[],reason='Empty batch')
    anchors={}
    refs=c.execute("""SELECT f.id,f.person_id,f.image_id,f.embedding,f.quality,f.confidence
        FROM faces f JOIN images i ON i.id=f.image_id JOIN people p ON p.id=f.person_id
        WHERE p.merged_into IS NULL AND f.person_id IS NOT NULL AND f.review='confirmed'
          AND f.ignored=0 AND LENGTH(f.embedding)=512 AND i.state='done'
        ORDER BY f.person_id,COALESCE(f.quality,0) DESC,f.id""").fetchall()
    for r in refs:
        pid=int(r['person_id'])
        a=anchors.setdefault(pid,[])
        if len(a)<8 and int(r['image_id']) not in {v[0] for v in a}:
            a.append((int(r['image_id']),np.frombuffer(r['embedding'],dtype=np.float32)))
    # A single good selfie is not a sufficiently reliable identity reference.
    good={pid:items for pid,items in anchors.items() if len(items)>=3}
    if not good:return dict(examined=0,linked=0,candidates=[],reason='Need >=3 manually confirmed photos per person')
    profile_ids=[];reference_images=[];vectors=[]
    for pid,items in good.items():
        for image_id,v in items:
            profile_ids.append(pid);reference_images.append(image_id);vectors.append(v)
    exemplar=np.ascontiguousarray(np.stack(vectors))
    profile_ids=np.array(profile_ids,dtype=np.int64)
    reference_images=np.array(reference_images,dtype=np.int64)
    # Index each profile's reference columns ONCE per batch. Previously this
    # scanned every exemplar ID again for every candidate face * every person;
    # that quadratic Python/NumPy setup cost dominates on 500+ named People.
    profile_columns={pid:np.flatnonzero(profile_ids==pid) for pid in good}
    if face_ids is not None:
        face_ids=validated_ids(face_ids)
        if not face_ids:return dict(examined=0,linked=0,candidates=[],reason='No scanned faces')
        # cap is enforced even for a recently scanned batch.
        face_ids=face_ids[:cap]
        markers=','.join('?' for _ in face_ids)
        rows=c.execute(f"""SELECT f.id,f.image_id,f.embedding,f.quality,
            f.confidence,f.w,f.h FROM faces f
            WHERE f.id IN ({markers}) AND f.person_id IS NULL
            AND f.ignored=0 AND f.review='unreviewed'
            AND LENGTH(f.embedding)=512 ORDER BY f.id""",face_ids).fetchall()
    else:
        rows=c.execute("""SELECT f.id,f.image_id,f.embedding,f.quality,
            f.confidence,f.w,f.h FROM faces f
            WHERE f.person_id IS NULL AND f.ignored=0 AND f.review='unreviewed'
            AND LENGTH(f.embedding)=512 ORDER BY f.id LIMIT ?""",(cap,)).fetchall()
    if not rows:return dict(examined=0,linked=0,candidates=[],reason='No eligible faces')
    blocked={(int(r['face_id']),int(r['person_id']))
             for r in c.execute('SELECT face_id,person_id FROM rejected')}
    # Current confirmed/auto assignments are not independent faces of the
    # same person if another crop in that photo resembles them.
    occupied={(int(r['image_id']),int(r['person_id']))
              for r in c.execute('SELECT image_id,person_id FROM faces WHERE person_id IS NOT NULL')}
    candidates=[]
    for base in range(0,len(rows),192):
        block=rows[base:base+192]
        vec=np.ascontiguousarray(np.stack([
            np.frombuffer(r['embedding'],dtype=np.float32) for r in block]))
        similarity=vec@exemplar.T
        for i,r in enumerate(block):
            fid=int(r['id']);image_id=int(r['image_id'])
            if (float(r['quality'] or 0)<AUTO_MIN_FACE_QUALITY
                or min(float(r['w'] or 0),float(r['h'] or 0))<AUTO_MIN_FACE_SIDE
                or float(r['confidence'] or 0)<0.85):
                continue
            profile_scores=[]
            for pid,indices in profile_columns.items():
                if (fid,pid) in blocked or (image_id,pid) in occupied:
                    continue
                mask=reference_images[indices]!=image_id
                scores=similarity[i,indices[mask]]
                if len(scores)<2:continue
                best_two=np.sort(scores)[-2:]
                profile_scores.append((float(best_two[-1]),float(best_two[0]),pid))
            if not profile_scores:continue
            profile_scores.sort(reverse=True)
            first,second,pid=profile_scores[0]
            # Compare against the best competing person's evidence, not
            # against another exemplar of the same person.
            rival=profile_scores[1][0] if len(profile_scores)>1 else -1.0
            if (first>=top_threshold and second>=second_threshold
                and first-rival>=margin_threshold):
                candidates.append(dict(face_id=fid,person_id=pid,
                    image_id=image_id,score=round(first,5),support=round(second,5),
                    margin=round(first-rival,5)))
    # One person generally cannot occupy two separate face detections in the
    # same ordinary photograph. When candidate photos conflict, do NOT pick a
    # winner silently; abstain on all conflicting faces.
    frequency={}
    for x in candidates:
        key=(x['image_id'],x['person_id'])
        frequency[key]=frequency.get(key,0)+1
    candidates=[x for x in candidates if frequency[(x['image_id'],x['person_id'])]==1]
    linked=0
    if commit and candidates:
        batch_id=uuid.uuid4().hex
        with c:
            for x in candidates:
                fid=x['face_id'];pid=x['person_id']
                # Re-check no conflicting same-person face in image and no
                # manual rejection. Guard on label state prevents overwrites.
                fresh=c.execute('''SELECT image_id FROM faces WHERE id=? AND
                    person_id IS NULL AND ignored=0 AND review='unreviewed' ''',(fid,)).fetchone()
                if fresh is None:continue
                if c.execute('SELECT 1 FROM faces WHERE image_id=? AND person_id=? LIMIT 1',
                    (fresh['image_id'],pid)).fetchone():continue
                if c.execute('SELECT 1 FROM rejected WHERE face_id=? AND person_id=?',
                    (fid,pid)).fetchone():continue
                result=c.execute('''UPDATE faces SET person_id=?,review='auto',
                    cluster_id=NULL,suggested_person_id=NULL,suggested_score=NULL
                    WHERE id=? AND person_id IS NULL AND ignored=0 AND review='unreviewed' ''',(pid,fid))
                if result.rowcount==1:
                    c.execute('''INSERT INTO auto_matches(face_id,person_id,score,
                        model_version,batch_id,created_at)
                        VALUES(?,?,?,?,?,?)''',(fid,pid,x['score'],AUTO_MODEL_VERSION,batch_id,now()))
                    linked+=1
            if linked:
                refresh_clusters(c)
                log(c,'auto_match_batch',dict(batch=batch_id,count=linked,
                    model=AUTO_MODEL_VERSION,threshold=AUTO_TOP_SCORE))
    return dict(examined=len(rows),linked=linked,
                eligible=len(candidates),candidates=candidates if return_full else candidates[:30],
                mode='automatic' if commit else 'preview',
                references=len(good))


def evaluate_auto_identity(c, limit=500, policy_frontier=False):
    """Private, read-only and reproducible positive + unknown-person holdout.

    Confirmed distinct-photo exemplars are the only ground truth available.
    Positive trials hold out photos from profiles with >=4 photos, leaving at
    least three *other* confirmed photos. Unknown-person trials sample profiles
    with just 1-2 manually confirmed photos, which cannot be recognized under
    the current >=3 distinct-photo enrollment rule. These are two different
    risks: wrong known-person links and false links of un-enrolled people.
    The real source connection, photos, and labels are never written here.
    """
    limit=max(1,min(int(limit),2500))
    by_person={}
    same_photo_faces={}
    rows=c.execute("""SELECT f.id,f.person_id,f.image_id FROM faces f
        JOIN images i ON i.id=f.image_id
        WHERE f.person_id IS NOT NULL AND f.review='confirmed'
          AND f.ignored=0 AND LENGTH(f.embedding)=512 AND i.state='done'
        ORDER BY f.person_id,f.image_id,f.id""").fetchall()
    for x in rows:
        pid=int(x['person_id']);image=int(x['image_id']);fid=int(x['id'])
        photos=by_person.setdefault(pid,[])
        if not photos or photos[-1][0]!=image:
            photos.append((image,fid))
        same_photo_faces.setdefault((pid,image),[]).append(fid)

    def balanced(pool, maximum):
        """Limit by round-robin profile, not by lowest person ID or recency."""
        selected={}
        for index in range(max((len(items) for items in pool.values()),default=0)):
            for pid,items in sorted(pool.items()):
                if len(selected)>=maximum:return selected
                if index<len(items):
                    image,fid=items[index]
                    selected[fid]=(pid,image)
        return selected

    enrolled={}
    unknown={}
    for pid,photos in by_person.items():
        # Stable, reproducible shuffled order: no private data exported.
        ordered=sorted(photos,key=lambda x:hashlib.sha256(
            f'v4-eval:{pid}:{x[0]}'.encode()).digest())
        if len(photos)>=4:
            count=min(max(1,len(photos)//5),len(photos)-3)
            enrolled[pid]=ordered[:count]
        elif photos:
            unknown[pid]=ordered

    if not enrolled:
        return dict(held_out=0,correct=0,wrong=0,abstained=0,
                    people_tested=0,unknown_held_out=0,
                    false_unknown_links=0,unknown_abstained=0,
                    reason='Need >=4 confirmed distinct photos for at least one reference person')

    negative_budget=min(sum(len(v) for v in unknown.values()),max(1,limit//4))
    negatives=balanced(unknown,negative_budget)
    expected=balanced(enrolled,limit-len(negatives))
    withheld={**expected,**negatives}
    if not withheld:
        return dict(held_out=0,correct=0,wrong=0,abstained=0,
                    people_tested=0,unknown_held_out=0,
                    false_unknown_links=0,unknown_abstained=0,
                    reason='No suitable distinct-photo trial faces')

    # If the model found two copies of the same manually confirmed person's
    # face in a chosen photo, remove ALL of them as reference exemplars.
    # Otherwise a hidden evaluation face could leak via its photo-sibling.
    masked_ids=sorted({other_fid for _,(pid,image) in withheld.items()
                      for other_fid in same_photo_faces[(pid,image)]})
    with sqlite3.connect(':memory:') as tmp:
        tmp.row_factory=sqlite3.Row
        c.backup(tmp)
        with tmp:
            tmp.executemany("UPDATE faces SET person_id=NULL,review='unreviewed' WHERE id=?",
                            [(fid,) for fid in masked_ids])
        result=auto_identity_candidates(tmp,face_ids=list(withheld),cap=limit,
                                        commit=False,return_full=True)
        frontier=[]
        if policy_frontier:
            # Score identical held-out faces under every policy. Keep a single
            # masked, in-memory SQLite snapshot for every variant so differing
            # evaluation sets can never masquerade as coverage improvements.
            def measure_policy(name,score_policy,candidates):
                guesses={int(x['face_id']):int(x['person_id']) for x in candidates}
                correct=sum(guesses.get(fid)==pid for fid,(pid,_) in expected.items())
                wrong=sum(fid in guesses and guesses[fid]!=pid
                          for fid,(pid,_) in expected.items())
                n=correct+wrong
                false_unknown=sum(fid in guesses for fid in negatives)
                return dict(policy=name,
                    top=score_policy[0],second=score_policy[1],margin=score_policy[2],
                    held_out_known=len(expected),correct=correct,wrong=wrong,
                    abstained=len(expected)-n,known_coverage=round(n/len(expected),4)
                        if expected else None,
                    observed_precision=round(correct/n,4) if n else None,
                    held_out_unknown=len(negatives),
                    false_unknown_links=false_unknown,
                    unknown_false_link_rate=round(false_unknown/len(negatives),4)
                        if negatives else None,
                    # Merely an observed error count on overlapping holdouts,
                    # NOT a probabilistic proof or recommended deployment policy.
                    zero_observed_errors=(wrong==0 and false_unknown==0))
            baseline=(AUTO_TOP_SCORE,AUTO_SECOND_SUPPORT,AUTO_PROFILE_MARGIN)
            frontier.append(measure_policy('current_baseline',baseline,result['candidates']))
            for name,thresholds in AUTO_EVAL_FRONTIER:
                trial=auto_identity_candidates(tmp,face_ids=list(withheld),cap=limit,
                    commit=False,return_full=True,score_policy=thresholds)
                frontier.append(measure_policy(name,thresholds,trial['candidates']))
    predicted={int(x['face_id']):int(x['person_id']) for x in result['candidates']}
    correct=sum(predicted.get(fid)==pid for fid,(pid,_) in expected.items())
    wrong=sum(fid in predicted and predicted[fid]!=pid
              for fid,(pid,_) in expected.items())
    matched=correct+wrong
    false_unknown=sum(fid in predicted for fid in negatives)
    report=dict(
        held_out=len(expected),correct=correct,wrong=wrong,
        abstained=len(expected)-matched,
        people_tested=len({pid for pid,_ in expected.values()}),
        precision=round(correct/matched,4) if matched else None,
        coverage=round(matched/len(expected),4) if expected else None,
        unknown_held_out=len(negatives),false_unknown_links=false_unknown,
        unknown_abstained=len(negatives)-false_unknown,
        unknown_false_link_rate=round(false_unknown/len(negatives),4)
            if negatives else None,
        # Exact one-sided 95% binomial bound on each observed zero error count.
        # Samples aren't independent across photos; these are descriptive only.
        zero_error_upper_bound=round(1-0.05**(1/matched),4)
            if matched and wrong==0 else None,
        zero_unknown_error_upper_bound=round(1-0.05**(1/len(negatives)),4)
            if negatives and false_unknown==0 else None,
        model=AUTO_MODEL_VERSION,
        caveat=('Two descriptive, potentially correlated holdouts from manually '
                'labeled photos. Unknown group contains only 1-2-photo profiles; '
                'missing or duplicate labels can distort both metrics. No claim '
                'of calibrated deployment-wide precision, novel-person safety, '
                'or independence across photo sessions.')
    )
    if policy_frontier:
        report['policy_frontier']=frontier
        report['frontier_caveat']=(
            'Exploratory, correlated, repeated trials on identical local holdout. '
            'No setting is automatically adopted. More coverage with zero '
            'observed errors does not establish safety on new people, sessions '
            'or the broader photo library. Production thresholds unchanged.')
    return report


def auto_identity_backfill(c, limit=25000, commit=True, restart=False):
    """Resume older unassigned-face matching without rechecking the same pages.

    A fingerprint of user-confirmed labels invalidates the cursor whenever a
    new reference person or confirmed face changes. Each committed chunk saves
    cursor after its DB changes. Preview never changes the cursor or labels.
    """
    STOP.clear()
    limit=max(1,min(int(limit),25000))
    revision=hashlib.sha256()
    for r in c.execute("""SELECT id,person_id FROM faces
        WHERE review='confirmed' AND person_id IS NOT NULL AND ignored=0
        ORDER BY id"""):
        revision.update(f"{int(r['id'])}:{int(r['person_id'])};".encode())
    fingerprint=revision.hexdigest()[:24]
    settings=config()
    previous=settings.get('auto_backfill_state') or {}
    cursor=0 if restart or previous.get('revision')!=fingerprint else int(previous.get('cursor',0))
    ids=[int(r['id']) for r in c.execute("""SELECT id FROM faces WHERE
        id>? AND person_id IS NULL AND ignored=0 AND review='unreviewed'
        ORDER BY id ASC LIMIT ?""",(cursor,limit))]
    total=len(ids);linked=0;eligible=0;done=0
    progress(phase='auto',message='Matching previously indexed faces to confirmed People',
             total=total,done=0,eta=None,started_at=now())
    for offset in range(0,total,500):
        if STOP.is_set():break
        block=ids[offset:offset+500]
        result=auto_identity_candidates(c,face_ids=block,cap=500,commit=commit)
        linked+=result['linked'];eligible+=result.get('eligible',0)
        done+=len(block)
        if commit:
            # No photo or identity write: this is a small, atomic checkpoint
            # to keep repeated catch-up jobs from rechecking abstentions.
            state=config()
            state['auto_backfill_state']=dict(revision=fingerprint,cursor=block[-1],updated_at=now())
            save_config(state)
        progress(done=done,total=total,
                 message=f'{linked} linked, {eligible} strong matches; checkpoint {done}/{total}')
    phase='paused' if STOP.is_set() else 'complete'
    progress(phase=phase,eta=None,message=f'Backfill: {linked} linked; {eligible} candidates; {done}/{total} checked')
    if commit and linked:
        try: maybe_export_native_sync(c)
        except (OSError,ValueError) as error:
            print('Native sync export after backfill failed:',error,file=sys.stderr)
    return dict(examined=done,linked=linked,eligible=eligible,remaining_in_batch=total-done,
                last_face_id=ids[done-1] if done else cursor,
                mode='auto' if commit else 'preview')


def undo_last_auto_batch(c):
    """Undo only still-automatic tags from latest batch; preserve any user edits.

    Reverted pairs are rejected so the very next scan cannot repeat a known
    mistake. Manual confirmations remain untouched.
    """
    row=c.execute("""SELECT batch_id FROM auto_matches
        WHERE reverted_at IS NULL ORDER BY id DESC LIMIT 1""").fetchone()
    if not row:return 0
    batch=row['batch_id'];undone=0
    with c:
        entries=c.execute('''SELECT face_id,person_id FROM auto_matches
            WHERE batch_id=? AND reverted_at IS NULL''',(batch,)).fetchall()
        for x in entries:
            fid=int(x['face_id']);pid=int(x['person_id'])
            changed=c.execute('''UPDATE faces SET person_id=NULL,review='unreviewed'
                WHERE id=? AND person_id=? AND review='auto' ''',(fid,pid))
            if changed.rowcount:
                c.execute('INSERT OR IGNORE INTO rejected(face_id,person_id) VALUES(?,?)',(fid,pid))
                undone+=1
        c.execute('UPDATE auto_matches SET reverted_at=? WHERE batch_id=?',(now(),batch))
        if undone:
            refresh_clusters(c)
        log(c,'undo_auto_batch',dict(batch=batch,count=undone))
    if undone:
        try: maybe_export_native_sync(c)
        except (OSError,ValueError) as error:
            print('Native sync export after undo failed:',error,file=sys.stderr)
    return undone


def _scan_impl(roots=None,limit=None,force=False):
    settings=config()
    roots=roots or settings['roots']
    if not roots: raise RuntimeError('No folders configured. Add roots first.')
    vision=Vision(settings)
    STOP.clear()
    c=connect()
    try:
        new_face_ids=[]
        progress(phase='inventory',message='Reading files and checkpoints',done=0,total=0,speed=0,eta=None,started_at=now())
        jobs,cached,relinked=inventory(c,roots,bool(settings.get('recursive',True)),force=force)
        if limit is not None:jobs=jobs[:max(0,int(limit))]
        total=len(jobs)
        progress(phase='scan',message=f'{cached} cached · {relinked} relocated',done=0,total=total,speed=0,eta=None)
        print(f'Images needing analysis: {total} | cached: {cached} | relocated: {relinked}',flush=True)
        start=time.monotonic(); done=0; errors=0; found=0
        for path,size,mtime,id_ in jobs:
            if STOP.is_set(): break
            try:
                before=Path(path).stat()
                if before.st_size!=size or before.st_mtime_ns!=mtime: continue
                w,h,results=vision.analyze(path)
                after=Path(path).stat()
                if after.st_size!=size or after.st_mtime_ns!=mtime: continue
                status='done'; error=None
            except (OSError,ValueError,cv2.error) as exc:
                w=h=None; results=[]; status='error'; error=str(exc)[:500]; errors+=1
            # Errors on already-indexed photos must NEVER discard manually
            # confirmed identities. Retry the image later while retaining the
            # existing embeddings and labels, even if an ONNX/image read fails.
            with c:
                existed = id_ is not None
                if id_ is None:
                    c.execute('INSERT OR IGNORE INTO images(path,size,mtime_ns,state) VALUES(?,?,?,?)',
                              (path,size,mtime,'pending'))
                    row=c.execute('SELECT id FROM images WHERE path=?',(path,)).fetchone()
                    id_=int(row['id'])
                if status=='error':
                    c.execute('UPDATE images SET state=?,error=?,scanned_at=? WHERE id=?',
                              (status,error,now(),id_))
                else:
                    # Each photo gets its own snapshots: never reuse a prior
                    # photo's manual decisions on a newly discovered image.
                    old=(c.execute('SELECT embedding,person_id,ignored,review FROM faces WHERE image_id=?',
                                   (id_,)).fetchall() if existed else [])
                    c.execute('DELETE FROM faces WHERE image_id=?',(id_,))
                    c.execute('UPDATE images SET size=?,mtime_ns=?,width=?,height=?,state=?,error=?,scanned_at=?,num_faces=? WHERE id=?',
                              (size,mtime,w,h,status,error,now(),len(results),id_))
                    for i,(x,y,ww,hh,score,emb,quality) in enumerate(results):
                        matched=None
                        for o in old:
                            # Only preserve MANUAL decisions during photo reprocessing.
                            # Automated guesses are intentionally re-evaluated.
                            if (o['review']!='confirmed' or not o['person_id']) and not o['ignored']:continue
                            prev=np.frombuffer(o['embedding'],dtype=np.float32)
                            if prev.size==emb.size and float(np.dot(prev,emb))>.78:
                                matched=o;break
                        inserted=c.execute('''INSERT INTO faces(image_id,face_index,x,y,w,h,confidence,embedding,quality,person_id,ignored,review)
                            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)''',
                            (id_,i,x,y,ww,hh,score,emb.tobytes(),quality,
                             matched['person_id'] if matched else None, matched['ignored'] if matched else 0,
                             'confirmed' if matched and matched['person_id'] else 'unreviewed'))
                        if matched is None:
                            new_face_ids.append(int(inserted.lastrowid))
                    found+=len(results)
            done+=1
            elapsed=max(.001,time.monotonic()-start)
            rate=done/elapsed
            progress(done=done,total=total,speed=round(rate,2),eta=int((total-done)/rate) if rate else None,
                     message=f'{found} faces · {errors} image errors · {cached} reused')
            if done%10==0 or done==total:
                print(f'\r  {done:,}/{total:,} ({100*done/max(1,total):.1f}%) · {rate:.2f} img/s · {found:,} faces · ETA {(total-done)/max(rate,.0001)/60:.1f}m ',end='',flush=True)
        print()
        # Review-first product contract: indexing NEVER confirms a model guess.
        # Proposals are available separately through the explicit 'propose' action.
        auto_linked=0
        phase='paused' if STOP.is_set() else 'complete'
        with c: refresh_clusters(c)
        try: maybe_export_native_sync(c)
        except (OSError,ValueError) as export_error:
            print('Native People export failed (scan labels saved):', export_error, file=sys.stderr)
        progress(phase=phase,message=f'Finished {done:,} of {total:,} images; {errors} errors; new identity matches await user review',eta=None)
        return done
    finally:
        c.close()


# Profiles are deliberate user decisions; clustering never sets person_id.
def groups(c,threshold=.64,centroid_floor=.54,k=12,checks=96):
    with exclusive_job():
        return _groups_impl(c,threshold,centroid_floor,k,checks)


def _groups_impl(c,threshold=.64,centroid_floor=.54,k=12,checks=96):
    STOP.clear()
    rows=c.execute('SELECT id,image_id,embedding FROM faces WHERE person_id IS NULL AND ignored=0 ORDER BY id').fetchall()
    ids=[]; img_ids=[]; vec=[]
    for row in rows:
        e=np.frombuffer(row['embedding'],dtype=np.float32)
        if e.size!=128:continue
        ids.append(int(row['id']));img_ids.append(int(row['image_id']));vec.append(e)
    n=len(ids)
    progress(phase='group',message=f'Finding neighbors in {n:,} unassigned faces',done=0,total=n,started_at=now(),eta=None)
    if n==0:
        with c:
            c.execute('UPDATE faces SET cluster_id=NULL WHERE person_id IS NULL')
            c.execute('DELETE FROM clusters')
        progress(phase='complete',message='No unassigned faces to cluster')
        return 0
    mat=np.ascontiguousarray(np.stack(vec).astype(np.float32))
    parent=list(range(n)); count=[1]*n; photo_sets=[{v} for v in img_ids]
    centroids=mat.copy()
    def find(x):
        while parent[x]!=x:
            parent[x]=parent[parent[x]];x=parent[x]
        return x
    def join(a,b):
        a=find(a);b=find(b)
        if a==b or photo_sets[a].intersection(photo_sets[b]):return
        ca=centroids[a];cb=centroids[b]
        if float(np.dot(ca,cb))<centroid_floor:return
        if count[a]<count[b]:a,b=b,a
        new=centroids[a]*count[a]+centroids[b]*count[b]
        new/=max(np.linalg.norm(new),1e-9)
        parent[b]=a;centroids[a]=new
        count[a]+=count[b];photo_sets[a].update(photo_sets[b]);photo_sets[b].clear()
    if n>1:
        index=cv2.flann_Index(mat,dict(algorithm=1,trees=4))
        start=time.monotonic()
        for start_i in range(0,n,128):
            if STOP.is_set():break
            end_i=min(n,start_i+128)
            neighbors,distance=index.knnSearch(mat[start_i:end_i],min(k,n),params={'checks':checks})
            pairs=[]
            for i in range(start_i,end_i):
                for j in neighbors[i-start_i]:
                    j=int(j)
                    if j==i or j<0 or j>=n:continue
                    similarity=float(np.dot(mat[i],mat[j]))
                    if similarity>=threshold:
                        pairs.append((similarity,i,j))
            pairs.sort(reverse=True)
            for _score,i,j in pairs: join(i,j)
            rate=end_i/max(time.monotonic()-start,.001)
            progress(done=end_i,total=n,speed=round(rate,1),eta=int((n-end_i)/max(rate,.0001)),
                     message='Finding likely same-person groups; labels remain untouched')
    if STOP.is_set():
        progress(phase='paused',message='Grouping stopped without modifying old results',eta=None)
        return 0
    sets={}
    for i in range(n): sets.setdefault(find(i),[]).append(ids[i])
    non_singletons=sorted((v for v in sets.values() if len(v)>1),key=len,reverse=True)
    # Atomic rebuild of provisional clusters; manual profiles are untouched.
    with c:
        c.execute('UPDATE faces SET cluster_id=NULL WHERE person_id IS NULL')
        c.execute('DELETE FROM clusters')
        for member in non_singletons:
            cur=c.execute('INSERT INTO clusters(count,representative,created_at) VALUES(?,?,?)',
                          (len(member),member[0],now()))
            cluster=int(cur.lastrowid)
            c.executemany('UPDATE faces SET cluster_id=? WHERE id=?',[(cluster,id_) for id_ in member])
    progress(phase='complete',message=f'Found {len(non_singletons):,} provisional groups from {n:,} faces',eta=None)
    return len(non_singletons)


def api_status(c):
    people=int(c.execute('SELECT COUNT(*) FROM people WHERE merged_into IS NULL').fetchone()[0])
    return dict(version=VERSION,job=progress_snapshot(),roots=config().get('roots',[]),
                auto_enabled=False,
                auto_linked=int(c.execute("SELECT COUNT(*) FROM faces WHERE review='auto' AND person_id IS NOT NULL").fetchone()[0]),
                photos=int(c.execute("SELECT COUNT(*) FROM images WHERE state='done'").fetchone()[0]),
                errors=int(c.execute("SELECT COUNT(*) FROM images WHERE state='error'").fetchone()[0]),
                faces=int(c.execute('SELECT COUNT(*) FROM faces').fetchone()[0]),
                tagged=int(c.execute('SELECT COUNT(*) FROM faces WHERE person_id IS NOT NULL').fetchone()[0]),
                untagged=int(c.execute('SELECT COUNT(*) FROM faces WHERE person_id IS NULL AND ignored=0').fetchone()[0]),
                people=people,groups=int(c.execute('SELECT COUNT(*) FROM clusters').fetchone()[0]),
                suggested=int(c.execute('SELECT COUNT(*) FROM faces WHERE suggested_person_id IS NOT NULL AND person_id IS NULL').fetchone()[0]),
                models_ready=all(local_model(n).is_file() for n in MODEL_INFO))


def face_json(row):
    return dict(id=row['id'],image_id=row['image_id'],person_id=row['person_id'],
                cluster_id=row['cluster_id'],confidence=round(row['confidence'] or 0,3),
                path=row['path'] if 'path' in row.keys() else '',
                quality=round(row['quality'] or 0,2),
                ignored=bool(row['ignored']) if 'ignored' in row.keys() else False)


def choose_profile_covers(c,person_ids=None):
    """Rank only CONFIRMED face examples, without loading all original photos.

    A large usable face crop, clear detector response and scan quality outrank
    earliest face ID. A manual pick wins ONLY while still assigned to that
    person. Deterministic ordering ensures the cover doesn't change randomly.
    """
    ids=None if person_ids is None else tuple(dict.fromkeys(int(x) for x in person_ids))
    if ids is not None and not ids:return {},set()
    # The all-People roster deliberately retains its legacy global scoring.
    # Discover only needs the 1-5 named candidates for THIS group.
    clause='' if ids is None else ' AND f.person_id IN ('+','.join('?' for _ in ids)+')'
    chosen={}
    for r in c.execute('''SELECT f.person_id,f.id,f.w,f.h,f.confidence,f.quality
        FROM faces f JOIN images i ON i.id=f.image_id
        WHERE f.person_id IS NOT NULL AND f.ignored=0 AND i.state='done' '''+clause,ids or ()):
        side=max(0.0,min(float(r['w'] or 0),float(r['h'] or 0)))
        confidence=max(0.0,min(1.0,float(r['confidence'] or 0)))
        quality=max(0.0,min(1.0,float(r['quality'] or 0)))
        # Saturation prevents a huge face with poor confidence winning by size alone.
        score=min(side,320.0)*(0.65+0.35*quality)*(0.65+0.35*confidence)
        pid=int(r['person_id']); fid=int(r['id'])
        if (score,-fid)>(chosen.get(pid,(-1.0,-10**18))[0], -chosen.get(pid,(0,10**18))[1]):
            chosen[pid]=(score,fid)
    manual={int(r['person_id']):int(r['face_id']) for r in c.execute('''SELECT pc.person_id,pc.face_id FROM person_covers pc
        JOIN faces f ON f.id=pc.face_id AND f.person_id=pc.person_id AND f.ignored=0
        WHERE 1=1'''+('' if ids is None else ' AND pc.person_id IN ('+','.join('?' for _ in ids)+')'),ids or ())}
    return {pid:manual.get(pid,v[1]) for pid,v in chosen.items()},set(manual)


def set_person_cover(c,person_id,face_id):
    """Explicit reversible aesthetic choice; never changes face assignments."""
    person_id=int(person_id);face_id=int(face_id)
    with c:
        c.execute('BEGIN IMMEDIATE') if not c.in_transaction else None
        if not c.execute('SELECT 1 FROM people WHERE id=? AND merged_into IS NULL',(person_id,)).fetchone():
            raise ValueError('Archived person cannot receive a cover')
        valid=c.execute('''SELECT f.id FROM faces f JOIN images i ON i.id=f.image_id
            WHERE f.id=? AND f.person_id=? AND f.ignored=0 AND i.state='done' ''',(face_id,person_id)).fetchone()
        if not valid:raise ValueError('This is not a confirmed face of the selected person.')
        c.execute('''INSERT INTO person_covers(person_id,face_id,updated_at)
            VALUES(?,?,?) ON CONFLICT(person_id) DO UPDATE SET
            face_id=excluded.face_id,updated_at=excluded.updated_at''',(person_id,face_id,now()))
        log(c,'person_cover',dict(person=person_id,face=face_id))
    return True


def list_people(c,include_covers=True):
    rows=c.execute('''SELECT p.id,p.name,p.note,COUNT(f.id) AS faces,
          COUNT(DISTINCT f.image_id) AS images
          FROM people p LEFT JOIN faces f ON f.person_id=p.id AND f.ignored=0
          WHERE p.merged_into IS NULL GROUP BY p.id ORDER BY images DESC,p.name''').fetchall()
    if not include_covers:
        return [dict(dict(r),cover=None,cover_manual=False) for r in rows]
    covers,manually_chosen=choose_profile_covers(c)
    return [dict(dict(r),cover=covers.get(int(r['id'])),cover_manual=(int(r['id']) in manually_chosen)) for r in rows]


def visible_people_covers(c,person_ids):
    """Bounded, active-profile-only portraits; old full roster API unchanged."""
    ids=list(dict.fromkeys(int(x) for x in person_ids))
    if len(ids)>48 or any(pid<=0 for pid in ids):
        raise ValueError('Request at most 48 positive People IDs')
    if not ids:return {}
    placeholders=','.join('?' for _ in ids)
    live={int(r[0]) for r in c.execute(
        f'SELECT id FROM people WHERE merged_into IS NULL AND id IN ({placeholders})',ids)}
    if not live:return {}
    covers,manual=choose_profile_covers(c,sorted(live))
    return {str(pid):{'cover':covers.get(pid),'cover_manual':pid in manual}
            for pid in ids if pid in live}


def get_person_summary(c,person_id):
    """One People profile; avoid full-library portrait scoring on every tab/page."""
    row=c.execute('''SELECT p.id,p.name,p.note,COUNT(f.id) AS faces,
        COUNT(DISTINCT f.image_id) AS images
        FROM people p LEFT JOIN faces f ON f.person_id=p.id AND f.ignored=0
        WHERE p.id=? AND p.merged_into IS NULL GROUP BY p.id''',
        (int(person_id),)).fetchone()
    if row is None:raise ValueError('Unknown or archived person')
    return dict(row)


def group_list_existing_people_hints(c,cluster_ids):
    """Batch Discover page hints; never confirm or alter source People.

    Two distinct GROUP PHOTO references must resemble a named, manually
    confirmed profile before its name appears on a group tile. All groups
    remain visible: ambiguous / mixed groups are never suppressed.
    This is NOT a calibrated identity probability or replacement for
    deliberate per-group face-level review.
    """
    ids=list(dict.fromkeys(int(x) for x in cluster_ids))[:80]
    if not ids:return {}
    placeholders=','.join('?' for _ in ids)
    group=c.execute(f'''SELECT cluster_id,embedding,image_id FROM (
        SELECT cluster_id,embedding,image_id,
          ROW_NUMBER() OVER(PARTITION BY cluster_id
            ORDER BY COALESCE(quality,0) DESC,id ASC) AS rank_in_group
        FROM faces
        WHERE cluster_id IN ({placeholders}) AND person_id IS NULL AND ignored=0
          AND LENGTH(embedding)=512
    ) WHERE rank_in_group<=5 ORDER BY cluster_id,rank_in_group''',ids).fetchall()
    if not group:return {}
    profiles=c.execute('''SELECT person_id,name,embedding,image_id FROM (
        SELECT f.person_id,p.name,f.embedding,f.image_id,
          ROW_NUMBER() OVER(PARTITION BY f.person_id
            ORDER BY COALESCE(f.quality,0) DESC,f.id ASC) AS rank_in_profile
        FROM faces f JOIN people p ON p.id=f.person_id
        WHERE p.merged_into IS NULL AND f.person_id IS NOT NULL AND f.review='confirmed'
          AND f.ignored=0 AND LENGTH(f.embedding)=512
    ) WHERE rank_in_profile<=4 ORDER BY person_id''').fetchall()
    if not profiles:return {}

    # Corrupt/NaN vectors cannot influence a visible identity hint.
    valid_group=[g for g in group if np.isfinite(
        np.frombuffer(g['embedding'],dtype=np.float32)).all()]
    valid_known=[p for p in profiles if np.isfinite(
        np.frombuffer(p['embedding'],dtype=np.float32)).all()]
    if not valid_group or not valid_known:return {}
    ga=np.stack([np.frombuffer(g['embedding'],dtype=np.float32) for g in valid_group])
    pa=np.stack([np.frombuffer(p['embedding'],dtype=np.float32) for p in valid_known])
    sim=ga@pa.T
    group_indices={}
    for gi,g in enumerate(valid_group):
        group_indices.setdefault(int(g['cluster_id']),[]).append(gi)
    # SQL orders at most four reference faces per Person contiguously.
    # Reduce the complete similarity matrix once by Person rather than
    # allocating up to 80 groups * 500+ People Python submatrices and loops.
    # Keep the exact old threshold, per-distinct-PHOTO support, top-three
    # rounding and stable tie breaker; these are unverified hints ONLY.
    starts=[];person_ids=[];person_names=[]
    last_person=None
    for pi,p in enumerate(valid_known):
        pid=int(p['person_id'])
        if pid!=last_person:
            starts.append(pi);person_ids.append(pid);person_names.append(p['name'])
            last_person=pid
    known_photo=np.fromiter((int(p['image_id']) for p in valid_known),
                            dtype=np.int64,count=len(valid_known))
    result={}
    for cluster_id,gindices in group_indices.items():
        group_photo=np.fromiter((int(valid_group[gi]['image_id']) for gi in gindices),
                                dtype=np.int64,count=len(gindices))
        sub=sim[gindices,:].copy()
        # Same-photo comparisons cannot corroborate a Person hint. This
        # mask also handles multiple detections in the same photo.
        sub[group_photo[:,None]==known_photo[None,:]]=-1.
        best=np.maximum.reduceat(sub,starts,axis=1) # group faces x People
        support=np.zeros(len(person_ids),dtype=np.int16)
        for image_id in np.unique(group_photo):
            support+=np.any(best[group_photo==image_id]>=.50,axis=0)
        matches=[]
        for col in np.flatnonzero(support>=2):
            strong=np.sort(best[best[:,col]>=.50,col])
            strength=round(float(np.mean(strong[-min(3,len(strong)):])),3)
            matches.append(dict(person_id=person_ids[col],name=person_names[col],
                                supporting_photos=int(support[col]),
                                similarity=strength,
                                note='Possible already named person; review every face'))
        matches.sort(key=lambda m:(-m['supporting_photos'],-m['similarity'],m['person_id']))
        result[cluster_id]=matches[:2]
    return result


def count_discover_groups(c,pending_only=False):
    """Count the same visible group set as Discover; read-only and indexed.

    A stored browser page can outlive clustering or manual review. Clamp it
    using the current queue size instead of recursively requesting dozens of
    obsolete pages after a fresh scan.
    """
    if pending_only:
        return c.execute('''SELECT COUNT(DISTINCT f.cluster_id)
            FROM faces f JOIN clusters c ON c.id=f.cluster_id
            WHERE f.person_id IS NULL AND f.ignored=0''').fetchone()[0]
    return c.execute('SELECT COUNT(*) FROM clusters').fetchone()[0]


def list_groups(c,page=0,limit=40,order='largest',seed=0,pending_only=False,offset_size=None,after_key=None):
    """List provisional clusters with CURRENT unassigned, nonignored work.

    Original cluster size is historical: manual naming or Ignore can leave a
    large group fully processed. The active queue never buries useful work
    behind those completed groups, but All groups preserves access to all.
    """
    limit=max(1,min(80,int(limit)));page=max(0,int(page))
    offset_size=limit if offset_size is None else max(1,min(limit,int(offset_size)))
    if order not in ('largest','smallest','shuffle','stable'):
        raise ValueError('Unsupported Discover order')
    if after_key is not None:
        if order!='stable' or len(after_key)!=2 or any(isinstance(v,bool) or not isinstance(v,int) for v in after_key):
            raise ValueError('Invalid stable Discover cursor')
        size,group_id=after_key
        if not 0<=size<=9223372036854775807 or not 1<=group_id<=9223372036854775807:
            raise ValueError('Invalid stable Discover cursor')
    # Only indexed metadata counts; do not fetch private embeddings here.
    base='''SELECT c.id,c.count,
               COALESCE(
                 (SELECT active.id FROM faces active
                  WHERE active.cluster_id=c.id
                    AND active.person_id IS NULL AND active.ignored=0
                  ORDER BY COALESCE(active.quality,0) DESC,active.id ASC
                  LIMIT 1),
                 c.representative
               ) AS representative,
               c.created_at,COUNT(f.id) AS remaining_count
            FROM clusters c LEFT JOIN faces f ON
              f.cluster_id=c.id AND f.person_id IS NULL AND f.ignored=0
            GROUP BY c.id'''
    params=()
    if after_key is not None:
        size,group_id=after_key
        base=base.replace(' GROUP BY c.id',
            ' WHERE (c.count < ? OR (c.count = ? AND c.id > ?)) GROUP BY c.id')
        params=(size,size,group_id)
    if pending_only:
        base+=' HAVING COUNT(f.id)>0'
    if order=='shuffle':
        seed=int(seed)&0xffffffff
        rows=[dict(r) for r in c.execute(base).fetchall()]
        def rank(row):
            key=f"{seed}:{int(row['id'])}".encode('ascii')
            return (hashlib.blake2b(key,digest_size=8).digest(),int(row['id']))
        rows.sort(key=rank)
        rows=rows[page*offset_size:page*offset_size+limit]
    elif order=='stable':
        # Select the bounded historical-size/ID page BEFORE counting its
        # current faces. The old grouped LEFT JOIN aggregated *every* face
        # in the entire gallery for each Next, even though 40 cards render.
        # EXISTS is exactly the previous HAVING COUNT(f.id)>0 eligibility.
        clauses=[];stable_args=[]
        if after_key is not None:
            size,group_id=after_key
            clauses.append('(c.count < ? OR (c.count = ? AND c.id > ?))')
            stable_args.extend((size,size,group_id))
        if pending_only:
            clauses.append('EXISTS (SELECT 1 FROM faces f WHERE f.cluster_id=c.id '
                           'AND f.person_id IS NULL AND f.ignored=0)')
        candidate_where=' WHERE '+' AND '.join(clauses) if clauses else ''
        candidate_sql='''WITH selected AS (
            SELECT c.id,c.count,c.representative,c.created_at
            FROM clusters c '''+candidate_where+'''
            ORDER BY c.count DESC,c.id LIMIT ? OFFSET ?
        )
        SELECT s.id,s.count,
               COALESCE(
                 (SELECT active.id FROM faces active
                  WHERE active.cluster_id=s.id
                    AND active.person_id IS NULL AND active.ignored=0
                  ORDER BY COALESCE(active.quality,0) DESC,active.id ASC
                  LIMIT 1),
                 s.representative
               ) AS representative,
               s.created_at,
               (SELECT COUNT(*) FROM faces f
                WHERE f.cluster_id=s.id
                  AND f.person_id IS NULL AND f.ignored=0) AS remaining_count
        FROM selected s ORDER BY s.count DESC,s.id'''
        rows=[dict(r) for r in c.execute(candidate_sql,(
            *stable_args,limit,0 if after_key is not None else page*offset_size)).fetchall()]
    else:
        # Stable pass deliberately ranks by IMMUTABLE historical group size.
        # Dynamic remaining_count still controls eligibility and display,
        # but cannot move an unseen group across a reviewed cursor boundary.
        sort_count='c.count' if order=='stable' or not pending_only else 'remaining_count'
        direction='ASC' if order=='smallest' else 'DESC'
        rows=[dict(r) for r in c.execute(
            f'{base} ORDER BY {sort_count} {direction},c.id LIMIT ? OFFSET ?',
            (*params,limit,0 if after_key is not None else page*offset_size)).fetchall()]
    hints=group_list_existing_people_hints(c,[
        r['id'] for r in rows if r['remaining_count']>0])
    for row in rows:
        row['possible_existing_people']=hints.get(int(row['id']),[])
    return rows

def group_identity_candidates(c, cluster_id, limit=3):
    """Suggest named profiles for a provisional group; READ ONLY, never assign.

    Compare up to 24 highest-quality unassigned group exemplars against at
    most 12 high-quality confirmed exemplars per person. Result scores are
    cosine resemblance (NOT identity probabilities). Always require review.
    """
    cluster_id=int(cluster_id)
    group=c.execute('''SELECT f.embedding,f.image_id FROM faces f
        WHERE f.cluster_id=? AND f.person_id IS NULL AND f.ignored=0
        AND LENGTH(f.embedding)=512
        ORDER BY COALESCE(f.quality,0) DESC,f.id ASC LIMIT 24''',(cluster_id,)).fetchall()
    if not group:return []
    known=c.execute('''SELECT person_id,person_name,embedding,image_id FROM (
       SELECT f.person_id,p.name AS person_name,f.embedding,f.image_id,
       ROW_NUMBER() OVER (PARTITION BY f.person_id
                          ORDER BY COALESCE(f.quality,0) DESC,f.id ASC) rank_in_profile
       FROM faces f JOIN people p ON p.id=f.person_id
       WHERE p.merged_into IS NULL AND f.person_id IS NOT NULL AND f.review='confirmed' AND f.ignored=0 AND LENGTH(f.embedding)=512
    ) WHERE rank_in_profile<=12 ORDER BY person_id''').fetchall()
    if not known:return []
    # LENGTH(embedding)=512 is not sufficient: an indexed NaN or infinity
    # must not poison np.max across a person's otherwise sound exemplars.
    # Discard only invalid examples, never elevate a suggested identity.
    group=[g for g in group if np.isfinite(
        np.frombuffer(g['embedding'],dtype=np.float32)).all()]
    known=[k for k in known if np.isfinite(
        np.frombuffer(k['embedding'],dtype=np.float32)).all()]
    if not group or not known:return []
    grouparr=np.stack([np.frombuffer(x['embedding'],dtype=np.float32) for x in group])
    knownarr=np.stack([np.frombuffer(x['embedding'],dtype=np.float32) for x in known])
    # A face from the SAME PHOTO is not independent evidence of an identity.
    scores=grouparr@knownarr.T
    for i,g in enumerate(group):
        for j,k in enumerate(known):
            if g['image_id']==k['image_id']:scores[i,j]=-1.0
    profiles={}
    for j,k in enumerate(known):
        pid=int(k['person_id'])
        entry=profiles.setdefault(pid,dict(person_id=pid,name=k['person_name'],indices=[]))
        entry['indices'].append(j)
    result=[]
    for p in profiles.values():
        sim=np.max(scores[:,p.pop('indices')],axis=1)
        valid=sim[sim>=0.35]
        if len(valid)==0:continue
        # Good evidence from multiple faces beats one striking false positive.
        top=np.sort(valid)[-min(3,len(valid)):]
        p.update(similarity=round(float(np.mean(top)),3),
                 supporting_faces=int(np.count_nonzero(sim>=0.45)),
                 sampled_faces=len(group),
                 note='Unverified resemblance; review the whole group')
        result.append(p)
    result.sort(key=lambda r:(r['supporting_faces'],r['similarity']),reverse=True)
    best=result[:max(1,min(5,int(limit)))]
    covers,_=choose_profile_covers(c,[entry['person_id'] for entry in best])
    for entry in best:entry['cover']=covers.get(entry['person_id'])
    return best


def get_faces(c,where,args=(),page=0,limit=60,offset_size=None,before_id=None):
    """Legacy offset pages, or optional cursor by monotonically descending face ID."""
    page=max(0,int(page));limit=max(1,min(120,int(limit)))
    offset_size=limit if offset_size is None else max(1,min(limit,int(offset_size)))
    if before_id is not None:
        if isinstance(before_id,bool) or not isinstance(before_id,int) or not 0<before_id<=9223372036854775807:
            raise ValueError('Invalid face cursor')
        rows=c.execute('''SELECT f.*,i.path FROM faces f JOIN images i ON f.image_id=i.id
            WHERE ('''+where+''') AND f.id<? ORDER BY f.id DESC LIMIT ?''',
            (*args,before_id,limit)).fetchall()
    else:
        rows=c.execute('''SELECT f.*,i.path FROM faces f JOIN images i ON f.image_id=i.id WHERE '''+where+
                       ' ORDER BY f.id DESC LIMIT ? OFFSET ?',(*args,limit,page*offset_size)).fetchall()
    return [face_json(r) for r in rows]

def get_photos(c,person_id,page=0,limit=60,offset_size=None):
    limit=max(1,min(100,int(limit)));page=max(0,int(page))
    offset_size=limit if offset_size is None else max(1,min(limit,int(offset_size)))
    rows=c.execute('''SELECT i.id,i.path,i.width,i.height,COUNT(f.id) AS faces
    FROM images i JOIN faces f ON f.image_id=i.id WHERE f.person_id=? AND f.ignored=0
    GROUP BY i.id ORDER BY i.id DESC LIMIT ? OFFSET ?''',(person_id,limit,page*offset_size)).fetchall()
    return [dict(r) for r in rows]


SIMILAR_BATCH_SIZE=384 # A bounded input window, never the whole library.


def _stream_similar_faces(cursor,references,limit,threshold,exclude_image=None):
    """Read-only cosine ranking, bounded by one SQLite fetch window + a top-k heap.

    The previous full-table fetchall/materialized matrix grew with the number
    of faces (potentially 150k+). Keep at most 384 face blobs and 100 results
    in memory, regardless of the size of the photo archive.
    """
    limit=max(0,min(100,int(limit)))
    if not limit:return []
    top=[]
    while True:
        rows=cursor.fetchmany(SIMILAR_BATCH_SIZE)
        if not rows:break
        usable=[r for r in rows
                if len(r['embedding'])==512
                and (exclude_image is None or r['image_id']!=exclude_image)]
        if not usable:continue
        matrix=np.frombuffer(b''.join(r['embedding'] for r in usable),
                             dtype=np.float32).reshape(-1,128)
        score=matrix@references
        if score.ndim==2:score=np.max(score,axis=1)
        for offset in np.flatnonzero(np.isfinite(score)&(score>=threshold)):
            row=usable[int(offset)]
            ranked=(float(score[offset]),int(row['id']),row['person_id'])
            if len(top)<limit:heapq.heappush(top,ranked)
            elif ranked[:2]>top[0][:2]:heapq.heapreplace(top,ranked)
    return [dict(id=face_id,person_id=person_id,score=round(score,3))
            for score,face_id,person_id in sorted(top,reverse=True)]


def similar(c,face_id,limit=48):
    face=c.execute('SELECT embedding,image_id FROM faces WHERE id=?',(face_id,)).fetchone()
    if not face:raise ValueError('Unknown face')
    pivot=np.frombuffer(face['embedding'],dtype=np.float32)
    if pivot.size!=128 or not np.isfinite(pivot).all():
        raise ValueError('Invalid face feature')
    cursor=c.execute('''SELECT id,image_id,person_id,embedding FROM faces
                       WHERE ignored=0 AND id<>? ORDER BY id''',(face_id,))
    return _stream_similar_faces(cursor,pivot,limit,.32,exclude_image=face['image_id'])


def validated_ids(values):
    if not isinstance(values,list) or len(values)>2000:raise ValueError('Provide a list of up to 2000 face IDs')
    return list(dict.fromkeys(int(x) for x in values if int(x)>0))



def propose(c,threshold=.44,margin=.035):
    """Suggest matches using confirmed examples; never change confirmed identity tags."""
    with exclusive_job():
        return _propose_impl(c,threshold,margin)


def _propose_impl(c,threshold,margin):
    STOP.clear()
    exemplar=[];profile=[]
    for p in c.execute('SELECT id FROM people WHERE merged_into IS NULL'):
        rows=c.execute("SELECT embedding FROM faces WHERE person_id=? AND review='confirmed' AND ignored=0 LIMIT 350",(p['id'],)).fetchall()
        matrix=[np.frombuffer(r['embedding'],dtype=np.float32) for r in rows if len(r['embedding'])==512]
        if not matrix:continue
        matrix=np.stack(matrix)
        selected=[0];best=matrix@matrix[0]
        while len(selected)<min(8,len(matrix)):
            nxt=int(np.argmin(best))
            if nxt in selected:break
            selected.append(nxt);best=np.maximum(best,matrix@matrix[nxt])
        for v in matrix[selected]:
            exemplar.append(v);profile.append(int(p['id']))
    if not exemplar:raise ValueError('Name a person and confirm at least one face first.')
    E=np.stack(exemplar).T.copy()
    profile=np.array(profile,dtype=np.int32)
    ids=np.array(sorted(set(profile)),dtype=np.int32)
    rows=c.execute('SELECT id,embedding FROM faces WHERE person_id IS NULL AND ignored=0 ORDER BY id').fetchall()
    n=len(rows)
    blocked=set((int(r['face_id']),int(r['person_id'])) for r in c.execute('SELECT face_id,person_id FROM rejected'))
    progress(phase='propose',message=f'Matching {n:,} faces against {len(ids)} named people',done=0,total=n,started_at=now(),speed=0,eta=None)
    done=0;count=0;start=time.monotonic()
    with c:c.execute('UPDATE faces SET suggested_person_id=NULL,suggested_score=NULL WHERE person_id IS NULL')
    for base in range(0,n,384):
        if STOP.is_set():break
        block=rows[base:base+384]
        eligible=[r for r in block if len(r['embedding'])==512]
        if eligible:
            X=np.frombuffer(b''.join(r['embedding'] for r in eligible),dtype=np.float32).reshape(-1,128)
            dots=X@E
            best=np.full(len(eligible),-1.,dtype=np.float32)
            second=np.full(len(eligible),-1.,dtype=np.float32)
            matched=np.zeros(len(eligible),dtype=np.int32)
            for pid in ids:
                scores=np.max(dots[:,profile==pid],axis=1)
                for j,r in enumerate(eligible):
                    if (int(r['id']),int(pid)) in blocked:scores[j]=-1.
                better=scores>best
                second=np.where(better,best,np.maximum(second,scores))
                matched=np.where(better,pid,matched)
                best=np.maximum(best,scores)
            updates=[]
            for j,r in enumerate(eligible):
                if float(best[j])>=threshold and (float(best[j])-float(second[j]))>=margin:
                    updates.append((int(matched[j]),float(best[j]),int(r['id'])))
            with c:
                c.executemany('UPDATE faces SET suggested_person_id=?,suggested_score=? WHERE id=? AND person_id IS NULL',updates)
            count+=len(updates)
        done+=len(block)
        rate=done/max(.001,time.monotonic()-start)
        progress(done=done,total=n,speed=round(rate,1),eta=int((n-done)/max(.0001,rate)),
                 message=f'{count:,} candidate appearances to review; confirmed tags untouched')
    progress(phase='paused' if STOP.is_set() else 'complete',eta=None,
             message=f'{count:,} suggestions saved across {done:,} examined faces')
    return count


def person_suggestions(c,person_id,page=0,limit=60,offset_size=None):
    limit=max(1,min(100,int(limit)));page=max(0,int(page))
    offset_size=limit if offset_size is None else max(1,min(limit,int(offset_size)))
    rows=c.execute("SELECT f.*,i.path FROM faces f JOIN images i ON f.image_id=i.id "
       "WHERE f.suggested_person_id=? AND f.person_id IS NULL AND f.ignored=0 "
       "ORDER BY f.suggested_score DESC LIMIT ? OFFSET ?",
       (person_id,limit,page*offset_size)).fetchall()
    return [dict(face_json(r),score=round(float(r['suggested_score']),3)) for r in rows]


def accept_suggestions(c,person_id,ids,undo=False,request_id=None):
    """Confirm exactly the still-pending proposal, never a stale suggestion.

    The suggestion predicate belongs INSIDE assign_faces' BEGIN IMMEDIATE
    transaction. A prior SELECT followed by a separate assignment transaction
    could confirm a proposal that changed meanwhile.
    """
    return assign_faces(c,person_id,ids,undo=undo,
                        required_suggestion_person_id=int(person_id),
                        request_id=request_id)


def reject_suggestions(c,person_id,ids,undo=False,request_id=None):
    """Reject only this person's still-pending suggestions; exact one-use Undo."""
    person_id=int(person_id)
    ids=validated_ids(ids)
    digest=_review_write_prepare(request_id,'reject_suggestions',person_id,ids,undo)
    if not ids:return {'count':0,'undo_token':None} if undo else 0
    with c:
        c.execute('BEGIN IMMEDIATE') if not c.in_transaction else None
        replay=_review_write_replay(c,request_id,digest)
        if replay is not None:return replay
        if not c.execute('SELECT 1 FROM people WHERE id=? AND merged_into IS NULL',
                         (person_id,)).fetchone():
            raise ValueError('Unknown or archived person')
        rows=_review_rows(c,ids)
        if len(rows)!=len(ids):
            raise ValueError('Some suggested faces no longer exist. Reload before rejecting.')
        if any(r['person_id'] is not None or r['ignored']
               or r['suggested_person_id']!=person_id for r in rows):
            raise ValueError('Suggested faces changed or belong to another person; no changes made')
        scope=_review_scope(c,ids) if undo else None
        before=_review_snapshot(c,scope) if undo else None
        related_before=_review_related_rows(c,ids) if undo else None
        for id_ in ids:
            c.execute('INSERT OR IGNORE INTO rejected(face_id,person_id) VALUES(?,?)',
                      (id_,person_id))
            changed=c.execute('''UPDATE faces SET suggested_person_id=NULL,
                  suggested_score=NULL WHERE id=? AND suggested_person_id=?
                  AND person_id IS NULL AND ignored=0''',(id_,person_id))
            if changed.rowcount!=1:
                raise ValueError('Suggestion changed while rejecting; no changes made')
        log(c,'reject_suggestions',dict(person=person_id,faces=ids))
        token=(_record_review_undo(c,scope,before,person_id,'reject_suggestions',len(ids),
                                   related_ids=ids,related_before=related_before) if undo else None)
        _review_write_record(c,request_id,digest,'reject_suggestions',len(ids),token)
    return {'count':len(ids),'undo_token':token} if undo else len(ids)


def refresh_clusters(c):
    """Keep provisional group covers/counts accurate after manual corrections."""
    rows=c.execute("""SELECT cluster_id,COUNT(*) AS n,MIN(id) AS cover
      FROM faces WHERE cluster_id IS NOT NULL AND person_id IS NULL AND ignored=0
      GROUP BY cluster_id""").fetchall()
    active=set()
    for r in rows:
        if r['n']>=2:
            active.add(r['cluster_id'])
            c.execute('UPDATE clusters SET count=?,representative=? WHERE id=?',
                      (r['n'],r['cover'],r['cluster_id']))
    for r in c.execute('SELECT id FROM clusters').fetchall():
        if r['id'] not in active:
            c.execute('UPDATE faces SET cluster_id=NULL WHERE cluster_id=?',(r['id'],))
            c.execute('DELETE FROM clusters WHERE id=?',(r['id'],))


def similar_person(c,person_id,limit=80):
    """Read-only nearest candidates using diverse confirmed reference faces."""
    anchors=c.execute("SELECT embedding FROM faces WHERE person_id=? AND review='confirmed' AND ignored=0 LIMIT 300",
                      (person_id,)).fetchall() # Bounded 300 anchor rows, not the library.
    anchors=[np.frombuffer(r['embedding'],dtype=np.float32) for r in anchors
             if len(r['embedding'])==512 and
             np.isfinite(np.frombuffer(r['embedding'],dtype=np.float32)).all()]
    if not anchors:return []
    # Farthest-first samples cover changes in age, angle and image quality.
    anchors=np.array(anchors,dtype=np.float32)
    selected=[0]
    nearest=anchors@anchors[0]
    while len(selected)<min(12,len(anchors)):
        idx=int(np.argmin(nearest))
        if idx in selected:break
        selected.append(idx)
        nearest=np.maximum(nearest,anchors@anchors[idx])
    exemplars=anchors[selected].T
    cursor=c.execute('''SELECT id,image_id,person_id,embedding FROM faces
                        WHERE person_id IS NULL AND ignored=0 ORDER BY id''')
    return _stream_similar_faces(cursor,exemplars,limit,.35)



# Review undo stores only IDs and prior review flags; never biometric data or paths.
REVIEW_FIELDS='id,image_id,face_index,person_id,review,cluster_id,ignored,suggested_person_id,suggested_score'


def _review_rows(c,ids):
    rows=[]
    for start in range(0,len(ids),400):
        part=ids[start:start+400]
        marks=','.join('?' for _ in part)
        rows += [dict(r) for r in c.execute(f'SELECT {REVIEW_FIELDS} FROM faces WHERE id IN ({marks})',part)]
    return sorted(rows,key=lambda r:r['id'])


def _review_scope(c,ids):
    clusters=sorted({int(r['cluster_id']) for r in _review_rows(c,ids) if r['cluster_id'] is not None})
    scope=set(ids)
    for start in range(0,len(clusters),400):
        part=clusters[start:start+400]
        marks=','.join('?' for _ in part)
        scope.update(int(r['id']) for r in c.execute(f'SELECT id FROM faces WHERE cluster_id IN ({marks})',part))
    if len(scope)>5000:raise ValueError('Too many group members for safe undo; select fewer faces')
    return {'ids':sorted(scope),'clusters':clusters}


def _review_snapshot(c,scope):
    metadata=[];members=[]
    for start in range(0,len(scope['clusters']),400):
        part=scope['clusters'][start:start+400]
        marks=','.join('?' for _ in part)
        metadata += [dict(r) for r in c.execute(f'SELECT id,count,representative,created_at FROM clusters WHERE id IN ({marks})',part)]
        members += [int(r['id']) for r in c.execute(f'SELECT id FROM faces WHERE cluster_id IN ({marks})',part)]
    return {'faces':_review_rows(c,scope['ids']),
            'clusters':sorted(metadata,key=lambda r:r['id']),
            'members':sorted(members)}


def _review_related_rows(c,ids):
    """Exact, ID-only rejection/auto-match evidence affected by unassignment."""
    rejected=[];automatic=[]
    for start in range(0,len(ids),400):
        part=ids[start:start+400]
        markers=','.join('?' for _ in part)
        rejected += [dict(r) for r in c.execute(
            f'SELECT face_id,person_id FROM rejected WHERE face_id IN ({markers})',part)]
        automatic += [dict(r) for r in c.execute(
            f'''SELECT id,face_id,person_id,score,model_version,batch_id,
                created_at,reverted_at FROM auto_matches WHERE face_id IN ({markers})''',part)]
    return {'rejected':sorted(rejected,key=lambda r:(r['face_id'],r['person_id'])),
            'auto_matches':sorted(automatic,key=lambda r:r['id'])}


def _profile_undo_digest(profile):
    """Compare profile metadata without copying private names into undo history."""
    raw=json.dumps(dict(profile),sort_keys=True,ensure_ascii=False).encode('utf-8')
    return hashlib.sha256(raw).hexdigest()


def _record_review_undo(c,scope,before,person_id,kind,assigned_count,created_person=None,created_face_ids=None,related_ids=None,related_before=None):
    token=uuid.uuid4().hex
    payload=json.dumps({'scope':scope,'before':before,'after':_review_snapshot(c,scope),
                        'assigned_count':assigned_count,
                        'created_profile_digest':_profile_undo_digest(created_person) if created_person is not None else None,
                        'created_face_ids':created_face_ids,
                        'related_ids':related_ids,
                        'related_before':related_before,
                        'related_after':_review_related_rows(c,related_ids) if related_ids is not None else None},sort_keys=True)
    c.execute('INSERT INTO manual_review_undo(token,at,kind,person_id,snapshot) VALUES(?,?,?,?,?)',
              (token,now(),kind,int(person_id),payload))
    c.execute('DELETE FROM manual_review_undo WHERE token NOT IN (SELECT token FROM manual_review_undo ORDER BY rowid DESC LIMIT 12)')
    return token


def manual_review_undo_status(c,token):
    """Read-only, token-scoped check after an Undo reply might be lost.

    A live unconsumed record does NOT promise Undo will succeed: face data
    may have changed, or an earlier request might still be in flight.
    Missing records may have rotated out of the bounded 12-token history.
    No names, IDs, photos, snapshots or biometrics leave this endpoint.
    """
    if not isinstance(token,str) or not re.fullmatch(r'[0-9a-f]{32}',token):
        raise ValueError('Invalid undo status token')
    row=c.execute('SELECT undone_at FROM manual_review_undo WHERE token=?',
                  (token,)).fetchone()
    if row is None:return {'status':'unknown'}
    return {'status':'undone' if row['undone_at'] is not None
            else 'recorded_not_undone'}


def undo_manual_review(c,token):
    if not isinstance(token,str) or not re.fullmatch('[0-9a-f]{32}',token):
        raise ValueError('Invalid undo token')
    with c:
        c.execute('BEGIN IMMEDIATE') if not c.in_transaction else None
        row=c.execute('SELECT * FROM manual_review_undo WHERE token=?',(token,)).fetchone()
        if not row or row['undone_at']:
            raise ValueError('Undo unavailable or already used. No labels changed.')
        record=json.loads(row['snapshot'])
        if row['kind']=='merge_profiles':
            return restore_profile_merge(c,int(record['source']),expected_target=int(row['person_id']))
        scope=record['scope']
        if _review_snapshot(c,scope)!=record['after']:
            raise ValueError('Review state changed since assignment; undo refused to protect newer work')
        before=record['before']
        if row['kind'] in ('unassign_faces','reject_suggestions'):
            related_ids=record.get('related_ids')
            if not isinstance(related_ids,list) or _review_related_rows(c,related_ids)!=record.get('related_after'):
                raise ValueError('Rejection or auto-match history changed since unassignment; undo refused')
        if row['kind']=='new_person':
            # A newly made profile can only be removed when it has not gained
            # any other assignments, covers or other identity references.
            pid=int(row['person_id'])
            profile=c.execute('SELECT id,name,note,created_at FROM people WHERE id=?',(pid,)).fetchone()
            if profile is None or _profile_undo_digest(profile)!=record.get('created_profile_digest'):
                raise ValueError('New person changed since creation; undo refused to protect newer work')
            if c.execute('SELECT 1 FROM person_merges WHERE source_id=? OR target_id=? LIMIT 1',(pid,pid)).fetchone():
                raise ValueError('New person has merge history; undo refused to protect newer work')
            current_ids=[int(v['id']) for v in c.execute('SELECT id FROM faces WHERE person_id=? ORDER BY id',(pid,))]
            if current_ids!=sorted(record.get('created_face_ids') or []):
                raise ValueError('New person gained or lost faces; undo refused to protect newer work')
            for table in ('person_covers','rejected','auto_matches'):
                if c.execute(f'SELECT 1 FROM {table} WHERE person_id=? LIMIT 1',(pid,)).fetchone():
                    raise ValueError('New person gained related review data; undo refused to protect newer work')
        if len(before['faces'])!=len(scope['ids']):
            raise ValueError('Undo evidence incomplete; no changes made')
        c.executemany("""UPDATE faces SET person_id=?,review=?,cluster_id=?,ignored=?,
            suggested_person_id=?,suggested_score=? WHERE id=?""",
            [(f['person_id'],f['review'],f['cluster_id'],f['ignored'],f['suggested_person_id'],
              f['suggested_score'],f['id']) for f in before['faces']])
        for cluster in scope['clusters']:
            c.execute('DELETE FROM clusters WHERE id=?',(cluster,))
        c.executemany('INSERT INTO clusters(id,count,representative,created_at) VALUES(?,?,?,?)',
                      [(r['id'],r['count'],r['representative'],r['created_at']) for r in before['clusters']])
        if row['kind'] in ('unassign_faces','reject_suggestions'):
            # All related records still equal the recorded after-state.
            # Recreate only the exact original rejection set, and restore
            # automatic match timestamps without deleting audit events.
            for start in range(0,len(related_ids),400):
                part=related_ids[start:start+400]
                markers=','.join('?' for _ in part)
                c.execute(f'DELETE FROM rejected WHERE face_id IN ({markers})',part)
            c.executemany('INSERT INTO rejected(face_id,person_id) VALUES(?,?)',
                [(r['face_id'],r['person_id']) for r in record['related_before']['rejected']])
            c.executemany('UPDATE auto_matches SET reverted_at=? WHERE id=?',
                [(r['reverted_at'],r['id']) for r in record['related_before']['auto_matches']])
        if row['kind']=='new_person':
            c.execute('UPDATE person_create_requests SET invalidated_at=? WHERE person_id=? AND invalidated_at IS NULL',
                      (now(),row['person_id']))
            c.execute('DELETE FROM people WHERE id=?',(row['person_id'],))
        c.execute('UPDATE manual_review_undo SET undone_at=? WHERE token=?',(now(),token))
        log(c,'manual_review_undo',dict(kind=row['kind'],person=row['person_id'],faces=record['assigned_count']))
    return record['assigned_count']


def ignore_faces(c,ids,undo=False,request_id=None):
    """Hide only currently unassigned, visible faces; allow exact one-use undo."""
    ids=validated_ids(ids)
    digest=_review_write_prepare(request_id,'ignore_faces',0,ids,undo)
    if not ids:return {'count':0,'undo_token':None} if undo else 0
    with c:
        c.execute('BEGIN IMMEDIATE') if not c.in_transaction else None
        replay=_review_write_replay(c,request_id,digest)
        if replay is not None:return replay
        rows=_review_rows(c,ids)
        if len(rows)!=len(ids):
            raise ValueError('Some faces no longer exist. Reload before hiding.')
        if any(r['person_id'] is not None or r['ignored'] for r in rows):
            raise ValueError('Selected faces changed or were already assigned/hidden. No changes made.')
        scope=_review_scope(c,ids) if undo else None
        before=_review_snapshot(c,scope) if undo else None
        c.executemany("""UPDATE faces SET ignored=1,person_id=NULL,
            review='ignored',cluster_id=NULL
            WHERE id=? AND person_id IS NULL AND ignored=0""",[(x,) for x in ids])
        refresh_clusters(c)
        log(c,'ignore',dict(faces=ids))
        token=_record_review_undo(c,scope,before,0,'hide_faces',len(ids)) if undo else None
        _review_write_record(c,request_id,digest,'ignore_faces',len(ids),token)
    return {'count':len(ids),'undo_token':token} if undo else len(ids)


def unassign_faces(c,person_id,ids,undo=False,request_id=None):
    """Remove only this reviewed person's visible tags, with exact history undo."""
    person_id=int(person_id)
    ids=validated_ids(ids)
    digest=_review_write_prepare(request_id,'unassign_faces',person_id,ids,undo)
    if not ids:return {'count':0,'undo_token':None} if undo else 0
    with c:
        c.execute('BEGIN IMMEDIATE') if not c.in_transaction else None
        replay=_review_write_replay(c,request_id,digest)
        if replay is not None:return replay
        if not c.execute('SELECT 1 FROM people WHERE id=? AND merged_into IS NULL',
                         (person_id,)).fetchone():
            raise ValueError('Unknown or archived person')
        faces=_review_rows(c,ids)
        if len(faces)!=len(ids):
            raise ValueError('Some selected faces no longer exist. Reload review.')
        if any(f['person_id']!=person_id or f['ignored'] for f in faces):
            raise ValueError('Selected face tags changed or belong to another person; no changes made')
        scope=_review_scope(c,ids) if undo else None
        before=_review_snapshot(c,scope) if undo else None
        related_before=_review_related_rows(c,ids) if undo else None
        for f in faces:
            if f['review']=='auto':
                c.execute('INSERT OR IGNORE INTO rejected(face_id,person_id) VALUES(?,?)',
                          (f['id'],person_id))
                c.execute('UPDATE auto_matches SET reverted_at=? WHERE face_id=? AND reverted_at IS NULL',
                          (now(),f['id']))
        changed=c.executemany("""UPDATE faces SET person_id=NULL,review='unreviewed'
            WHERE id=? AND person_id=? AND ignored=0""",[(x,person_id) for x in ids])
        # SQLite executemany's rowcount is the total number of updated rows.
        if changed.rowcount!=len(ids):
            raise ValueError('Face assignments changed during unassignment; no changes made')
        refresh_clusters(c)
        log(c,'unassign',dict(person=person_id,faces=ids))
        token=(_record_review_undo(c,scope,before,person_id,'unassign_faces',len(ids),
                                   related_ids=ids,related_before=related_before) if undo else None)
        _review_write_record(c,request_id,digest,'unassign_faces',len(ids),token)
    return {'count':len(ids),'undo_token':token} if undo else len(ids)


def _review_write_prepare(request_id,action,person_id,ids,undo):
    if request_id is None:return None
    if not isinstance(request_id,str) or not re.fullmatch(r'[0-9a-f]{32}',request_id):
        raise ValueError('Invalid face review request ID')
    if not undo:
        raise ValueError('Face review idempotency requires Undo tracking')
    payload={'action':action,'person_id':int(person_id),
             'face_ids':sorted(ids),'undo':bool(undo)}
    encoded=json.dumps(payload,sort_keys=True,separators=(',',':'))
    return hashlib.sha256(('atlas-v4-face-review-key-v1\\n'+encoded).encode()).hexdigest()


def _review_write_replay(c,request_id,digest):
    """Caller holds the SAME BEGIN IMMEDIATE lock as the reviewed mutation."""
    if request_id is None:return None
    row=c.execute('''SELECT payload_digest,count,undo_token FROM review_write_requests
                     WHERE request_id=?''',(request_id,)).fetchone()
    if row is None:return None
    if row['payload_digest']!=digest:
        raise ValueError('Face review request key already used with different details; no changes made')
    token=row['undo_token']
    state=c.execute('SELECT undone_at FROM manual_review_undo WHERE token=?',
                    (token,)).fetchone() if token else None
    if state is None:
        # A bounded Undo record may have rotated out AFTER an Undo commit.
        # Never tell a reviewer that tags are still current based on a
        # surviving operation ledger alone.
        raise ValueError('Face review request recorded but Undo history expired; inspect tags')
    if state['undone_at'] is not None:
        raise ValueError('Face review request already undone; inspect tags before NEW review')
    return {'count':int(row['count']),
            'undo_token':token,'idempotent_replay':True}


def _review_write_record(c,request_id,digest,action,count,token):
    if request_id is not None:
        # This insert is in the SAME transaction as tags, Undo and audit.
        c.execute('''INSERT INTO review_write_requests
            (request_id,payload_digest,action,count,undo_token,created_at)
            VALUES(?,?,?,?,?,?)''',
            (request_id,digest,action,int(count),token,now()))


def review_write_request_status(c,request_id):
    """Read-only token-scoped evidence, never a claim of current face state."""
    if not isinstance(request_id,str) or not re.fullmatch(r'[0-9a-f]{32}',request_id):
        raise ValueError('Invalid face review status request ID')
    row=c.execute('SELECT count,undo_token FROM review_write_requests WHERE request_id=?',
                  (request_id,)).fetchone()
    if row is None:return {'status':'unknown'}
    token=row['undo_token']
    state=c.execute('SELECT undone_at FROM manual_review_undo WHERE token=?',
                    (token,)).fetchone() if token else None
    if state is None:return {'status':'recorded_unknown','count':int(row['count'])}
    if state['undone_at'] is not None:
        return {'status':'undone','count':int(row['count'])}
    return {'status':'recorded_not_undone','count':int(row['count']),
            'undo_token':token}


def assign_faces(c,person_id,ids,undo=False,required_suggestion_person_id=None,request_id=None):
    """Explicit face assignment, optionally constrained to a live suggestion.

    A suggestion must be rechecked in the SAME SQLite write transaction that
    assigns it. Independent manual assignment is unaffected.
    """
    person_id=int(person_id)
    ids=validated_ids(ids)
    action='accept_suggestions' if required_suggestion_person_id is not None else 'assign_faces'
    digest=_review_write_prepare(request_id,action,person_id,ids,undo)
    if not ids:return {'count':0,'undo_token':None} if undo else 0
    with c:
        c.execute('BEGIN IMMEDIATE') if not c.in_transaction else None
        replay=_review_write_replay(c,request_id,digest)
        if replay is not None:return replay
        if not c.execute('SELECT id FROM people WHERE id=? AND merged_into IS NULL',(person_id,)).fetchone():
            raise ValueError('Unknown or archived person')
        markers=','.join('?' for _ in ids)
        rows=c.execute(f'SELECT id,person_id,ignored,suggested_person_id FROM faces WHERE id IN ({markers})',ids).fetchall()
        if len(rows)!=len(ids):
            raise ValueError('Some selected faces no longer exist. Reload the page.')
        if any(r['person_id'] is not None or r['ignored'] for r in rows):
            raise ValueError('Some selected faces are already assigned or hidden. No changes made; review them before reassigning.')
        if required_suggestion_person_id is not None and any(
                r['suggested_person_id']!=required_suggestion_person_id for r in rows):
            raise ValueError('Suggestion changed or belongs to another person. No changes made; reload review.')
        scope=_review_scope(c,ids) if undo else None
        before=_review_snapshot(c,scope) if undo else None
        required=required_suggestion_person_id is not None
        predicate=' AND suggested_person_id=?' if required else ''
        statement=("UPDATE faces SET person_id=?,review='confirmed',cluster_id=NULL,"
                   "ignored=0,suggested_person_id=NULL,suggested_score=NULL"
                   " WHERE id=? AND person_id IS NULL AND ignored=0"+predicate)
        changes=c.executemany(statement,[(person_id,x,required_suggestion_person_id)
                       if required else (person_id,x) for x in ids])
        if changes.rowcount!=len(ids):
            raise ValueError('Selected faces changed during review; no changes made.')
        refresh_clusters(c)
        log(c,'assign',dict(person=person_id,faces=ids))
        token=_record_review_undo(c,scope,before,person_id,'selected_faces',len(ids)) if undo else None
        _review_write_record(c,request_id,digest,action,len(ids),token)
    return {'count':len(ids),'undo_token':token} if undo else len(ids)



def attach_group_to_person(c, person_id, cluster_id, expected_count, undo=False,request_id=None):
    """Explicitly confirm all remaining unlabeled faces of a reviewed cluster.

    The count guards against a concurrent scan/cluster change between preview
    and confirmation. Never alters already confirmed faces or source files.
    """
    person_id=int(person_id); cluster_id=int(cluster_id)
    expected_count=int(expected_count)
    if expected_count < 1 or expected_count > 2000:
        raise ValueError('Invalid confirmed group size')
    if cluster_id<1 or person_id<1:
        raise ValueError('Invalid existing person or group')
    # Field names matter: group=3/count=7 is NOT group=7/count=3.
    digest=None
    if request_id is not None:
        _review_write_prepare(request_id,'attach_group',person_id,[],undo)
        payload={'action':'attach_group','person_id':person_id,
                 'cluster_id':cluster_id,'expected_count':expected_count,
                 'undo':bool(undo)}
        encoded=json.dumps(payload,sort_keys=True,separators=(',',':'))
        digest=hashlib.sha256(('atlas-v4-group-attach-v1\\n'+encoded).encode()).hexdigest()
    with c:
        c.execute('BEGIN IMMEDIATE') if not c.in_transaction else None
        replay=_review_write_replay(c,request_id,digest)
        if replay is not None:return replay
        if not c.execute('SELECT id FROM people WHERE id=? AND merged_into IS NULL',(person_id,)).fetchone():
            raise ValueError('Unknown existing person')
        rows=c.execute("""SELECT id FROM faces WHERE cluster_id=?
            AND person_id IS NULL AND ignored=0 ORDER BY id""",(cluster_id,)).fetchall()
        ids=[int(r['id']) for r in rows]
        if len(ids)!=expected_count:
            raise ValueError('Group changed since review. Reload before assigning.')
        scope=_review_scope(c,ids) if undo else None
        before=_review_snapshot(c,scope) if undo else None
        changes=c.executemany("""UPDATE faces SET person_id=?,review='confirmed',
            cluster_id=NULL,ignored=0,suggested_person_id=NULL,suggested_score=NULL
            WHERE id=? AND person_id IS NULL AND ignored=0""",[(person_id,x) for x in ids])
        if changes.rowcount!=len(ids):
            raise ValueError('Group changed during review; no assignments made.')
        refresh_clusters(c)
        log(c,'attach_group',dict(person=person_id,cluster_id=cluster_id,count=len(ids)))
        token=_record_review_undo(c,scope,before,person_id,'whole_group',len(ids)) if undo else None
        _review_write_record(c,request_id,digest,'attach_group',len(ids),token)
    return {'count':len(ids),'undo_token':token} if undo else len(ids)

def normalized_person_name(value):
    """Unicode-compatible comparison only; preserve the chosen display name."""
    return ' '.join(unicodedata.normalize('NFKC', str(value)).casefold().split())


def person_create_request_digest(name,face_ids,cluster_id,expected_count,allow_duplicate_name,undo):
    """Canonical intent hash, not a Person-name registry or authorization token.

    The exact same opaque request_id is allowed to replay ONLY this operation.
    Sorted face IDs make reordered equivalent selections deterministic.
    """
    payload={
        'name':name,
        'face_ids':sorted(face_ids),
        'cluster_id':int(cluster_id) if cluster_id is not None else None,
        'expected_count':int(expected_count) if expected_count is not None else None,
        'allow_duplicate_name':allow_duplicate_name,
        'undo':bool(undo),
    }
    encoded=json.dumps(payload,sort_keys=True,separators=(',',':'),ensure_ascii=False)
    return hashlib.sha256(('atlas-v4-person-create-idempotency-v1\\n'+encoded).encode('utf-8')).hexdigest()


def person_create_request_replay(c,request_id,digest,undo):
    """Check a durable completed request while caller holds BEGIN IMMEDIATE.

    An undone or archived profile must NEVER be resurrected or reported as
    newly created. An expired Undo token is not shown as actionable.
    """
    row=c.execute('SELECT payload_digest,person_id,undo_token,invalidated_at FROM person_create_requests WHERE request_id=?',
                  (request_id,)).fetchone()
    if row is None:return None
    if row['payload_digest']!=digest:
        raise ValueError('Create request key already used with different details; no changes made')
    # SQLite INTEGER PRIMARY KEY may reuse a deleted Person ID. Record an
    # irreversible replay tombstone when Undo deletes a created Person, so
    # an old key cannot accidentally resolve to a newer unrelated identity.
    if row['invalidated_at'] is not None:
        raise ValueError('Create request already completed but profile was undone; inspect People before a NEW request')
    person_id=int(row['person_id'])
    active=c.execute('SELECT 1 FROM people WHERE id=? AND merged_into IS NULL',
                     (person_id,)).fetchone()
    if active is None:
        raise ValueError('Create request already completed but profile was undone or archived; inspect People before a NEW request')
    if not undo:return person_id
    token=row['undo_token']
    available=c.execute('SELECT 1 FROM manual_review_undo WHERE token=? AND undone_at IS NULL',
                        (token,)).fetchone() if token else None
    return {'person_id':person_id,'undo_token':token if available else None,
            'idempotent_replay':True}


def person_create_request_status(c,request_id):
    """Read-only recovery for one opaque Person-create operation key.

    'unknown' is NOT evidence of rollback: an in-flight transaction may
    commit after this query's SQLite snapshot. No names, faces, original
    photo paths or embeddings are returned. Only the key holder can ask.
    """
    if not isinstance(request_id,str) or not re.fullmatch(r'[0-9a-f]{32}',request_id):
        raise ValueError('Invalid Person create status request ID')
    row=c.execute('''SELECT person_id,undo_token,invalidated_at
                     FROM person_create_requests WHERE request_id=?''',
                  (request_id,)).fetchone()
    if row is None:return {'status':'unknown'}
    if row['invalidated_at'] is not None:return {'status':'undone'}
    person=c.execute('SELECT merged_into FROM people WHERE id=?',
                     (row['person_id'],)).fetchone()
    if person is None or person['merged_into'] is not None:
        return {'status':'archived_or_missing'}
    token=row['undo_token']
    available=c.execute('SELECT 1 FROM manual_review_undo WHERE token=? AND undone_at IS NULL',
                        (token,)).fetchone() if token else None
    return {'status':'committed','person_id':int(row['person_id']),
            'undo_token':token if available else None}


def create_person(c,name,face_ids=(),cluster_id=None,expected_count=None,allow_duplicate_name=False,undo=False,request_id=None):
    name=str(name).strip()
    if not 1<=len(name)<=100:raise ValueError('Person name must be 1–100 characters')
    if type(allow_duplicate_name) is not bool:raise ValueError('Explicit duplicate-name choice required')
    if cluster_id is not None and face_ids:raise ValueError('Choose a group or selected faces, not both')
    ids=validated_ids(list(face_ids))
    if request_id is not None and (not isinstance(request_id,str) or
            not re.fullmatch(r'[0-9a-f]{32}',request_id)):
        raise ValueError('Invalid Create person request ID; expected 32 lowercase hex characters')
    digest=(person_create_request_digest(name,ids,cluster_id,expected_count,
                allow_duplicate_name,undo) if request_id is not None else None)
    with c:
        c.execute('BEGIN IMMEDIATE') if not c.in_transaction else None
        # Check under the SAME lock as the future write, before name and
        # already-reviewed-face validation. Otherwise a successful first
        # request can appear to fail on its own changed data during replay.
        if request_id is not None:
            replay=person_create_request_replay(c,request_id,digest,undo)
            if replay is not None:return replay
        duplicates=[r for r in c.execute('SELECT id,name FROM people WHERE merged_into IS NULL')
                    if normalized_person_name(r['name'])==normalized_person_name(name)]
        if duplicates and not allow_duplicate_name:
            raise ValueError('Name already exists. Review the existing profile or explicitly create a different person with the same name.')
        if cluster_id is not None:
            ids=[int(r[0]) for r in c.execute('''SELECT id FROM faces
                WHERE cluster_id=? AND person_id IS NULL AND ignored=0 ORDER BY id''',
                (int(cluster_id),)).fetchall()]
            if not ids:raise ValueError('Group has no faces awaiting review')
            if expected_count is not None and len(ids)!=int(expected_count):
                raise ValueError('Group changed since review. Reload before naming.')
        if len(ids)>2000:raise ValueError('Select no more than 2000 faces')
        if ids:
            placeholders=','.join('?' for _ in ids)
            rows=c.execute(f'SELECT id,person_id,ignored FROM faces WHERE id IN ({placeholders})',ids).fetchall()
            if len(rows)!=len(ids):raise ValueError('Selected faces changed. Reload before naming.')
            if any(r['person_id'] is not None or r['ignored'] for r in rows):
                raise ValueError('Selected faces already assigned or hidden. No changes made.')
        scope=_review_scope(c,ids) if undo else None
        before=_review_snapshot(c,scope) if undo else None
        person=c.execute('INSERT INTO people(name,created_at) VALUES(?,?)',(name,now())).lastrowid
        c.executemany("""UPDATE faces SET person_id=?,review='confirmed',cluster_id=NULL,
            ignored=0,suggested_person_id=NULL,suggested_score=NULL WHERE id=? AND person_id IS NULL AND ignored=0""",
            [(person,id_) for id_ in ids])
        refresh_clusters(c)
        log(c,'create_person',dict(id=person,name=name,faces=len(ids),explicit_same_name=bool(duplicates)))
        if undo:
            profile=dict(c.execute('SELECT id,name,note,created_at FROM people WHERE id=?',(person,)).fetchone())
            token=_record_review_undo(c,scope,before,person,'new_person',len(ids),
                                      created_person=profile,created_face_ids=sorted(ids))
        else:token=None
        if request_id is not None:
            # Commit the key with the Person, audited face edits and Undo.
            # A mid-write failure rolls back ALL of them together.
            c.execute('''INSERT INTO person_create_requests(
                request_id,payload_digest,person_id,undo_token,created_at)
                VALUES(?,?,?,?,?)''',(request_id,digest,person,token,now()))
    return {'person_id':person,'undo_token':token} if undo else person


def rename_person(c, person_id, name, allow_duplicate_name=False):
    """Rename one known profile; never implicitly merge same-name identities."""
    person_id=int(person_id)
    name=str(name).strip()
    if not 1<=len(name)<=100:raise ValueError('Invalid person name')
    if type(allow_duplicate_name) is not bool:raise ValueError('Expected explicit duplicate-name approval')
    with c:
        c.execute('BEGIN IMMEDIATE') if not c.in_transaction else None
        original=c.execute('SELECT name FROM people WHERE id=? AND merged_into IS NULL',(person_id,)).fetchone()
        if original is None:raise ValueError('Unknown person; no changes made')
        duplicates=[int(row['id']) for row in c.execute('SELECT id,name FROM people WHERE id!=? AND merged_into IS NULL',(person_id,))
                    if normalized_person_name(row['name'])==normalized_person_name(name)]
        if duplicates and not allow_duplicate_name:
            raise ValueError('Name belongs to an existing profile. Review the duplicate before renaming.')
        c.execute('UPDATE people SET name=? WHERE id=?',(name,person_id))
        log(c,'rename_person',dict(person=person_id,previous=original['name'],
                                   name=name,explicit_same_name=bool(duplicates)))
    return True


def merged_people_for_target(c,target):
    """Archived People remain recoverable but absent from normal identity search."""
    return [dict(id=int(r['id']),name=r['name'],faces=int(r['moved_count']),
                 images=0,cover=None,cover_manual=False)
            for r in c.execute('''SELECT p.id,p.name,m.moved_count
                FROM person_merges m JOIN people p ON p.id=m.source_id
                WHERE m.target_id=? AND p.merged_into=?
                ORDER BY m.merged_at DESC,p.id''',(int(target),int(target)))]


def merge_people_profiles(c,target,source):
    """Durable archive-and-transfer; retain all rejected and suggested provenance."""
    target=int(target);source=int(source)
    if target==source:raise ValueError('Cannot merge a person into themselves')
    with c:
        c.execute('BEGIN IMMEDIATE') if not c.in_transaction else None
        if not c.execute('SELECT 1 FROM people WHERE id=? AND merged_into IS NULL',(target,)).fetchone():
            raise ValueError('Unknown or archived target profile')
        if not c.execute('SELECT 1 FROM people WHERE id=? AND merged_into IS NULL',(source,)).fetchone():
            raise ValueError('Unknown or archived source profile')
        if c.execute('SELECT 1 FROM person_merges WHERE target_id=? LIMIT 1',(source,)).fetchone():
            raise ValueError('Restore profiles already merged into this source before merging again')
        source_cover=c.execute('SELECT face_id FROM person_covers WHERE person_id=?',(source,)).fetchone()
        target_cover=c.execute('SELECT face_id FROM person_covers WHERE person_id=?',(target,)).fetchone()
        count=int(c.execute('SELECT COUNT(*) FROM faces WHERE person_id=?',(source,)).fetchone()[0])
        token=uuid.uuid4().hex;stamp=now()
        imported_face=None;imported_at=None
        if source_cover and not target_cover:
            fid=int(source_cover['face_id'])
            if c.execute('SELECT 1 FROM faces WHERE id=? AND person_id=? AND ignored=0',
                         (fid,source)).fetchone():
                imported_face=fid;imported_at=datetime.now().astimezone().isoformat(timespec='microseconds')
        c.execute('''INSERT INTO person_merges(source_id,target_id,moved_count,
             imported_cover_face_id,imported_cover_at,undo_token,merged_at)
             VALUES(?,?,?,?,?,?,?)''',(source,target,count,imported_face,imported_at,token,stamp))
        c.execute('''INSERT INTO person_merge_faces(source_id,face_id,review,cluster_id,
             ignored,suggested_person_id,suggested_score)
             SELECT ?,id,review,cluster_id,ignored,suggested_person_id,suggested_score
             FROM faces WHERE person_id=?''',(source,source))
        transferred=c.execute('UPDATE faces SET person_id=? WHERE person_id=?',(target,source)).rowcount
        if transferred!=count:raise ValueError('Profile changed during merge; nothing committed')
        if imported_face is not None:
            c.execute('INSERT INTO person_covers(person_id,face_id,updated_at) VALUES(?,?,?)',
                      (target,imported_face,imported_at))
        c.execute('UPDATE people SET merged_into=? WHERE id=?',(target,source))
        c.execute('INSERT INTO manual_review_undo(token,at,kind,person_id,snapshot) VALUES(?,?,?,?,?)',
                  (token,stamp,'merge_profiles',target,json.dumps({'source':source})))
        c.execute('DELETE FROM manual_review_undo WHERE token NOT IN (SELECT token FROM manual_review_undo ORDER BY rowid DESC LIMIT 12)')
        log(c,'merge_archive',dict(source=source,target=target,faces=count))
    return {'ok':True,'count':count,'undo_token':token}


def restore_profile_merge(c,source,expected_target=None):
    """Restore an archived source, refusing to overwrite subsequent changes."""
    source=int(source)
    with c:
        c.execute('BEGIN IMMEDIATE') if not c.in_transaction else None
        merge=c.execute('SELECT * FROM person_merges WHERE source_id=?',(source,)).fetchone()
        if merge is None:raise ValueError('Archived merge not found or already restored')
        target=int(merge['target_id'])
        if expected_target is not None and int(expected_target)!=target:
            raise ValueError('Merge target changed; no changes made')
        sr=c.execute('SELECT merged_into FROM people WHERE id=?',(source,)).fetchone()
        tr=c.execute('SELECT merged_into FROM people WHERE id=?',(target,)).fetchone()
        if sr is None or sr['merged_into']!=target or tr is None or tr['merged_into'] is not None:
            raise ValueError('Merge lineage changed; no changes made')
        count=int(c.execute('SELECT COUNT(*) FROM person_merge_faces WHERE source_id=?',(source,)).fetchone()[0])
        if count!=int(merge['moved_count']):
            raise ValueError('Merged face evidence missing; restore refused')
        if c.execute('SELECT 1 FROM faces WHERE person_id=? LIMIT 1',(source,)).fetchone():
            raise ValueError('Archived source acquired new faces; restore refused')
        changed=c.execute('''SELECT 1 FROM person_merge_faces m
             LEFT JOIN faces f ON f.id=m.face_id WHERE m.source_id=? AND
             (f.id IS NULL OR f.person_id IS NOT ? OR f.review IS NOT m.review
              OR f.cluster_id IS NOT m.cluster_id OR f.ignored IS NOT m.ignored
              OR f.suggested_person_id IS NOT m.suggested_person_id
              OR f.suggested_score IS NOT m.suggested_score) LIMIT 1''',(source,target)).fetchone()
        if changed:raise ValueError('Merged faces changed since merge; restore refused')
        cover=c.execute('SELECT face_id,updated_at FROM person_covers WHERE person_id=?',(target,)).fetchone()
        imported=merge['imported_cover_face_id']
        if imported is not None:
            if (cover is None or int(cover['face_id'])!=int(imported)
                or cover['updated_at']!=merge['imported_cover_at']):
                raise ValueError('Target portrait changed after merge; restore refused')
        elif cover and c.execute('''SELECT 1 FROM person_merge_faces
                WHERE source_id=? AND face_id=?''',(source,int(cover['face_id']))).fetchone():
            raise ValueError('Target portrait uses source face; change it before restoring')
        if imported is not None:c.execute('DELETE FROM person_covers WHERE person_id=?',(target,))
        restored=c.execute('''UPDATE faces SET person_id=? WHERE id IN
            (SELECT face_id FROM person_merge_faces WHERE source_id=?)''',(source,source)).rowcount
        if restored!=count:raise ValueError('Transferred face count changed; restore refused')
        c.execute('UPDATE people SET merged_into=NULL WHERE id=?',(source,))
        c.execute('DELETE FROM person_merges WHERE source_id=?',(source,))
        c.execute('UPDATE manual_review_undo SET undone_at=? WHERE token=?',
                  (now(),merge['undo_token']))
        log(c,'merge_restore',dict(source=source,target=target,faces=count))
    return count


@contextmanager
def thumbnail_key_lock(filename):
    """Single-flight one JPEG key without serializing different portraits.

    Only concurrent callers hold an entry. Never retain a lock per historical
    image, which could otherwise grow unbounded with a 150k-photo library.
    Lock order is key -> decode permit (never the reverse).
    """
    with THUMB_KEY_GUARD:
        entry=THUMB_KEY_LOCKS.get(filename)
        if entry is None:
            entry=[threading.Lock(),0]
            THUMB_KEY_LOCKS[filename]=entry
        entry[1]+=1
    try:
        with entry[0]:
            yield
    finally:
        with THUMB_KEY_GUARD:
            entry[1]-=1
            if entry[1]==0:
                THUMB_KEY_LOCKS.pop(filename,None)


def get_thumbnail(c,kind,id_):
    if kind not in ('face','portrait','photo','review'):raise ValueError('Unknown thumbnail rendition')
    CACHE.mkdir(parents=True,exist_ok=True)
    if kind in ('face','portrait'):
        r=c.execute('''SELECT f.*,i.path,i.width,i.height,i.mtime_ns FROM faces f
                      JOIN images i ON i.id=f.image_id WHERE f.id=?''',(id_,)).fetchone()
    else:
        r=c.execute('SELECT * FROM images WHERE id=?',(id_,)).fetchone()
    if not r:raise FileNotFoundError('Image not found')
    p=Path(r['path'])
    if not p.is_file():raise FileNotFoundError('Original image moved or unavailable; rescan roots')
    # Rebuild thumbnails when the source metadata changes.
    filename=f'{kind}-{id_}-{r["mtime_ns"]}.jpg'
    target=CACHE/filename
    if target.exists():return target.read_bytes()
    # First serialize the exact thumbnail key, then reserve one decoder slot.
    # A second browser request for that key returns the generated JPEG.
    with thumbnail_key_lock(filename):
        if target.exists():return target.read_bytes()
        with THUMB_DECODE_SLOTS:
            if target.exists():return target.read_bytes()
            im=load_image(p,3200 if kind=='portrait' else (1200 if kind=='face' else (2400 if kind=='review' else 900)))
            if kind in ('face','portrait'):
                h,w=im.shape[:2]
                iw=float(r['width'] or w);ih=float(r['height'] or h)
                sx=w/iw;sy=h/ih
                x,y,fw,fh=(float(r[k]) for k in ('x','y','w','h'))
                pad=.38 if kind=='portrait' else .23
                x0=max(0,int((x-pad*fw)*sx)); x1=min(w,int((x+(1+pad)*fw)*sx))
                y0=max(0,int((y-pad*fh)*sy)); y1=min(h,int((y+(1+pad)*fh)*sy))
                if x1<=x0 or y1<=y0:raise ValueError('Invalid face crop')
                im=im[y0:y1,x0:x1]
            limit=600 if kind=='portrait' else (400 if kind=='face' else (1600 if kind=='review' else 750))
            if max(im.shape[:2])>limit:
                scale=limit/max(im.shape[:2]);im=cv2.resize(im,None,fx=scale,fy=scale,interpolation=cv2.INTER_AREA)
            ok,jpg=cv2.imencode('.jpg',im,[int(cv2.IMWRITE_JPEG_QUALITY),91 if kind=='portrait' else (88 if kind=='review' else 83)])
            if not ok:raise ValueError('Could not encode thumbnail')
            global THUMB_WRITES
            data=jpg.tobytes()
            temp=target.with_name(target.name+'.'+uuid.uuid4().hex+'.tmp')
            try:
                temp.write_bytes(data);os.replace(temp,target)
            finally:
                temp.unlink(missing_ok=True)
            THUMB_WRITES+=1
            if THUMB_WRITES%200==0:
                prune_cache()
            return data


def export_csv(c,target):
    target=Path(target).expanduser()
    with target.open('w',newline='',encoding='utf-8') as f:
        out=csv.writer(f)
        out.writerow(['person_id','person_name','image_path','face_id','confidence'])
        for r in c.execute('''SELECT p.id,p.name,i.path,f.id,f.confidence
              FROM people p JOIN faces f ON f.person_id=p.id
              JOIN images i ON f.image_id=i.id WHERE f.ignored=0 AND p.merged_into IS NULL ORDER BY p.name,i.path'''):
            out.writerow(tuple(r))
    print('Exported:',target)


def export_native_sync(c, target):
    """Write a CHECKSUM-SEALED CSV to a stable pathname for Android SAF.

    This deliberately overwrites the SAME path rather than os.replace().
    Persisted Android document URIs may identify a file via MediaStore; changing
    inode/path identity on replacement could invalidate a one-time grant. A
    footer validates completeness so Native ignores interrupted writes.
    """
    target=Path(target).expanduser()
    if not target.parent.is_dir():
        raise ValueError('Native sync export folder does not exist')
    out=io.StringIO(newline='')
    writer=csv.writer(out)
    writer.writerow(['person_id','person_name','image_path','face_id','confidence'])
    count=0
    for r in c.execute('''SELECT p.id,p.name,i.path,f.id,f.confidence
          FROM people p JOIN faces f ON f.person_id=p.id
          JOIN images i ON f.image_id=i.id
          WHERE f.ignored=0 AND p.merged_into IS NULL ORDER BY p.name,i.path,f.id'''):
        writer.writerow(tuple(r));count+=1
    body=out.getvalue()
    digest=hashlib.sha256(body.encode('utf-8')).hexdigest()
    # The footer is NOT part of the legacy V4 export. It belongs to a separate
    # opt-in sync file and proves the CSV was fully written before import.
    footer=f'#ATLAS_SYNC_END,{count},{digest}\r\n'
    with target.open('w',encoding='utf-8',newline='') as file:
        file.write(body)
        file.write(footer)
        file.flush()
        os.fsync(file.fileno())
    return count


def maybe_export_native_sync(c):
    path=config().get('native_sync_export_path')
    if not path:return None
    return export_native_sync(c,path)


def backup_database(target):
    """Create a NEW online SQLite snapshot; never overwrite a recovery point.

    O_EXCL is the compatibility-safe no-overwrite primitive on Termux/Android.
    A process kill can still leave an incomplete NEW snapshot, so owner
    acceptance must independently validate it before any app upgrade.
    """
    target=Path(target).expanduser()
    try:
        fd=os.open(str(target),os.O_CREAT|os.O_EXCL|os.O_RDWR,0o600)
    except FileExistsError as e:
        raise ValueError('Backup destination already exists; choose a new filename. Nothing overwritten.') from e
    identity=os.fstat(fd)
    os.close(fd)
    try:
        c=connect()
        try:
            b=sqlite3.connect(str(target))
            try:
                c.backup(b)
                # Online backup copies can retain source WAL journal mode in
                # the database header. Finalize this NEW standalone snapshot
                # to DELETE mode, avoiding future -wal/-shm files on inspect.
                if b.execute('PRAGMA journal_mode=DELETE').fetchone()[0].lower()!='delete':
                    raise RuntimeError('Could not finalize standalone SQLite backup')
                if b.execute('PRAGMA quick_check').fetchone()[0]!='ok':
                    raise RuntimeError('Created backup failed SQLite quick check')
            finally:b.close()
        finally:c.close()
    except BaseException:
        # Only remove the file we exclusively created in this invocation.
        # A previously existing backup can never enter this branch.
        try:
            present=target.stat()
            if (present.st_dev,present.st_ino)==(identity.st_dev,identity.st_ino):
                target.unlink()
        except OSError:pass
        raise
    print('Backup:',target)


def start_job(kind):
    if not JOB_LOCK.acquire(blocking=False):raise RuntimeError('Another scan/grouping job is already running')
    progress(phase='starting', kind=kind, started_via='V4 Overview button',
             message=f'Starting {kind} from Overview', done=0, total=0,
             speed=0, eta=None, started_at=now())
    def run():
        try:
            if kind=='scan':scan()
            elif kind=='propose':
                c=connect()
                try:propose(c)
                finally:c.close()
            elif kind=='group':
                c=connect()
                try:groups(c)
                finally:c.close()
            elif kind=='auto':
                # Legacy UI/API alias: only queue unconfirmed proposals.
                c=connect()
                try:propose(c)
                finally:c.close()
        except Exception as e:
            progress(phase='error',message=str(e),eta=None)
            print('JOB ERROR:',repr(e),file=sys.stderr,flush=True)
        finally:JOB_LOCK.release()
    threading.Thread(target=run,daemon=True,name=f'v4-{kind}').start()


# All assets and scripts live locally. No external CSS/JS, telemetry, or tracking.
HTML = r'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><title>Monochrome Studio · PEOPLE</title>
<style>
:root{color-scheme:dark;--bg:#0c0d10;--panel:#191b20;--raised:#23262e;--line:#30343b;--muted:#9a9fab;--accent:#e8d5b5;--good:#a5d6b1;--text:#f4f1ee}*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,'Segoe UI',sans-serif;color:var(--text);background:var(--bg)}.app{max-width:1400px;margin:auto;display:grid;grid-template-columns:236px minmax(0,1fr);min-height:100vh}aside{padding:29px 18px;border-right:1px solid var(--line);background:#131518}main{min-width:0;padding:28px 30px 90px}.brand{font-size:17px;font-weight:800;letter-spacing:.06em}.sub{color:var(--muted);font-size:12px;letter-spacing:.19em;margin-top:4px}nav{margin-top:32px;display:grid;gap:6px}nav button{width:100%;text-align:left;background:transparent;border:0;border-radius:10px;padding:13px 12px;color:#b5b8be;font-size:15px;cursor:pointer}nav button.active,nav button:hover{background:#34302a;color:var(--accent)}aside small{color:var(--muted);line-height:1.6}.aside-foot{margin-top:40px;font-size:13px}h1{font-size:clamp(26px,4vw,44px);font-weight:650;letter-spacing:-.04em;margin:0 0 5px}h2{font-size:23px;letter-spacing:-.03em;margin:0 0 12px;font-weight:600}h3{margin:0 0 4px;font-size:16px}.muted,.caption{color:var(--muted)}.caption{font-size:13px}.top{display:flex;justify-content:space-between;gap:14px;align-items:flex-start;margin-bottom:23px}.pill{padding:6px 10px;border:1px solid var(--line);border-radius:50px;font-size:12px;color:var(--accent)}.stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:25px 0}.metric{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:21px 17px}.metric .val{font-size:clamp(24px,4vw,36px);font-weight:700;letter-spacing:-.06em}.metric .lab{margin-top:5px;font-size:13px;color:var(--muted)}.card{border:1px solid var(--line);background:var(--panel);border-radius:15px;padding:18px;margin-bottom:15px}.toolbar{display:flex;gap:9px;align-items:center;flex-wrap:wrap;margin:16px 0}.btn{background:var(--accent);color:#17130e;border:0;border-radius:9px;padding:11px 16px;font-size:13px;font-weight:750;cursor:pointer}.btn.secondary{background:#30343c;color:#fff}.btn:disabled{opacity:.35;cursor:default}.btn.danger{background:#562b2b;color:#fff}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(145px,1fr));gap:13px}.tile{background:var(--panel);border:1px solid var(--line);border-radius:13px;overflow:hidden;cursor:pointer;min-width:0}.tile:hover{border-color:#857a68}.tile[data-person],.tile[data-group],.tile[data-photo]{appearance:none;text-align:left;color:inherit;font:inherit;padding:0;width:100%}.face-open{appearance:none;display:block;width:100%;padding:0;border:0;background:transparent;color:inherit;text-align:left;font:inherit;cursor:pointer}.face-open img{width:100%;aspect-ratio:1;object-fit:cover;display:block}.tile:focus-visible,.face-open:focus-visible,.coverpick:focus-visible,.picker-option:focus-visible{outline:3px solid var(--accent);outline-offset:2px}.portrait-placeholder{width:100%;aspect-ratio:1;display:grid;place-items:center;background:#30343b;color:#b8bdc6;font-size:38px}.picker-option .portrait-placeholder{width:54px;height:54px;aspect-ratio:1;font-size:24px;border-radius:8px;flex-shrink:0}.picker-review-card .portrait-placeholder{width:72px;height:72px;aspect-ratio:1;font-size:30px;border-radius:10px;flex-shrink:0}.tile img{width:100%;aspect-ratio:1;object-fit:cover;background:#333;display:block}.tile .title{font-weight:700;padding:11px 10px 3px;white-space:nowrap;text-overflow:ellipsis;overflow:hidden}.tile .meta{padding:0 10px 12px;color:var(--muted);font-size:12px}.photo-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(175px,1fr));gap:12px}.photo img{width:100%;aspect-ratio:4/3;object-fit:cover}.label{font-size:12px;color:var(--muted);display:block;margin:7px 0}input,select{border:1px solid #42454d;color:#eee;background:#24272f;padding:11px;border-radius:9px;font:inherit;max-width:100%;min-width:0}.bar{height:9px;border-radius:8px;background:#34363c;overflow:hidden}.bar>div{height:100%;background:var(--accent);transition:width .3s}.detail{display:grid;grid-template-columns:210px 1fr;gap:25px}.detail>img{width:100%;aspect-ratio:1;object-fit:cover;border-radius:14px}.fullimg{width:100%;max-height:80vh;object-fit:contain}.facepick{position:relative}.facepick .select{position:absolute;right:6px;top:6px;transform:scale(1.3);accent-color:#ecd5ad}.selected{outline:3px solid var(--accent)}a{color:var(--accent)}.note{border-left:3px solid #82725b;padding-left:12px;color:var(--muted);line-height:1.6}.safelabel{color:var(--good)}.error{color:#ffada8}#toast{position:fixed;bottom:22px;left:50%;transform:translateX(-50%);background:#e9d5b5;color:#161616;border-radius:8px;padding:12px 16px;display:none;max-width:90vw;z-index:120}.num{font-variant-numeric:tabular-nums}@media(max-width:750px){.app{grid-template-columns:1fr}aside{padding:12px;border-right:0;border-bottom:1px solid var(--line);position:sticky;top:0;z-index:10}.brand{display:inline-block;font-size:14px}.sub{display:none}nav{display:flex;margin:9px 0 0;overflow:auto;gap:3px}nav button{text-align:center;white-space:nowrap;font-size:12px;padding:9px 11px}aside .aside-foot{display:none}main{padding:23px 14px 75px}.stats{grid-template-columns:repeat(2,1fr);gap:8px}.metric{padding:14px}.grid{grid-template-columns:repeat(3,minmax(0,1fr));gap:7px}.tile .title{font-size:12px;padding:6px}.tile .meta{font-size:11px;padding:0 6px 6px}.detail{grid-template-columns:1fr}.detail>img{max-width:200px}.top{flex-direction:column;gap:8px}} .overlay{position:fixed;inset:0;background:#000b;z-index:100;display:flex;justify-content:center;align-items:flex-end;padding:10px}.picker{background:#1b1d23;border:1px solid #535861;border-radius:18px 18px 12px 12px;width:min(620px,100%);max-height:min(90dvh,calc(100dvh - 16px));display:flex;flex-direction:column;padding:18px;box-shadow:0 18px 70px #000b}.picker-results{min-height:0;overflow:auto;overscroll-behavior:contain;display:grid;gap:7px;padding:6px 0}.picker-option{display:flex;gap:12px;align-items:center;text-align:left;width:100%;background:#292d34;color:#fff;border:1px solid #42464d;border-radius:11px;padding:8px;cursor:pointer}.picker-option img{width:54px;height:54px;object-fit:cover;border-radius:8px;background:#494949}.picker-option b{display:block}.picker small{color:var(--muted)}.picker-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.picker-review-card{display:flex;gap:13px;align-items:center;border:1px solid #535861;background:#292d34;border-radius:12px;padding:12px;margin:12px 0}.picker-review-card img{width:72px;height:72px;object-fit:cover;border-radius:10px}.picker-review-card b{display:block;font-size:18px}.picker-review-card small{color:var(--muted)}.picker-review-warning{line-height:1.5}.picker-review [hidden],.picker-browse[hidden]{display:none}.picker input{width:100%;font-size:17px;margin:10px 0}.coverpick{display:block;width:100%;padding:8px;border:0;background:#3c3630;color:#f4dfbd;font-size:12px;font-weight:600;cursor:pointer}@media(min-width:750px){.overlay{align-items:center}.picker{border-radius:18px}}
</style></head>
<body><div class="app"><aside><div class="brand">◉ MONOCHROME</div><div class="sub">STUDIO 04 / PEOPLE</div><nav id="nav"><button data-tab="home" class="active">Overview</button><button data-tab="people">People</button><button data-tab="groups">Discover groups</button><button data-tab="untagged">Unsorted faces</button><button data-tab="settings">Library & tools</button></nav><div class="aside-foot"><small>Local only · No photo uploads<br>Identity suggestions require your review.<br><a href="http://127.0.0.1:8765" target="_blank" rel="noopener">V3 on 8765 ↗</a> · <a href="http://127.0.0.1:8766" target="_blank" rel="noopener">V3 on 8766 ↗</a></small></div></aside><main id="view"></main></div><div id="toast" role="status" aria-live="polite" aria-atomic="true"></div><div id="person-picker" hidden></div>
<script>
const $=s=>document.querySelector(s);let tab='home',page=0,person=null,group=null,photo=null,personMode='photos',selected=new Set(),status=null;
let groupReturn=null,pickerState=null,lastUndoToken=null;
// Unsorted-only forward boundaries; identifiers remain in memory, not storage.
let unsortedPageBounds=[null];
let peopleCache=null,peopleCacheRevision=0,peopleQuery='',peopleListPage=0,peopleReturn=null;
let similarityReturn=null,similarityTrail=[];
let photoReturn=null; // In-memory photo detour: profile/page/scroll, never persisted.
const PEOPLE_PAGE_SIZE=48; // Remember Discover pagination/scroll and modal action
// Persist only queue navigation primitives: no names, photo paths, face IDs,
// group IDs, session tokens or selected people are ever stored in the browser.
const DISCOVER_CURSOR_KEY='atlas-v4-discover-cursor-v1';
function decodeDiscoverCursor(raw){
  let data;
  try{data=JSON.parse(raw)}catch(_){return null}
  if(!data||typeof data!=='object'||Array.isArray(data))return null;
  if(!['largest','smallest','shuffle','stable'].includes(data.order))return null;
  if(typeof data.pending!=='boolean')return null;
  if(!Number.isSafeInteger(data.page)||data.page<0||data.page>499)return null;
  if(!Number.isSafeInteger(data.seed)||data.seed<0||data.seed>4294967295)return null;
  return {order:data.order,pending:data.pending,page:data.page,seed:data.seed};
}
function loadDiscoverCursor(){
  try{return decodeDiscoverCursor(localStorage.getItem(DISCOVER_CURSOR_KEY))}
  catch(_){return null} // Browsers can deny local storage in private mode.
}
const discoverCursor=loadDiscoverCursor();
// A fresh reviewer starts on historical-size keyset order instead of a
// shrinking OFFSET queue, which can skip unseen groups after each review.
// Respect existing explicit Largest/Smallest/Shuffle preferences unchanged.
let groupOrder=discoverCursor?.order??'stable',
    groupShuffleSeed=discoverCursor?.seed??20261014,
    groupPendingOnly=discoverCursor?.pending??true,
    groupResumePage=discoverCursor?.page??0;
function saveDiscoverCursor(){
  const data={order:groupOrder,pending:groupPendingOnly,
              page:groupResumePage,seed:groupShuffleSeed};
  try{localStorage.setItem(DISCOVER_CURSOR_KEY,JSON.stringify(data))}
  catch(_){} // UI remains usable when persistence is unavailable.
}
const esc=s=>String(s??'').replace(/[&<>"']/g,x=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[x]));
const fmt=n=>Number(n||0).toLocaleString();
const faceThumb=id=>'/thumb/face/'+id,portraitThumb=id=>'/thumb/portrait/'+id,photoThumb=id=>'/thumb/photo/'+id,reviewPhotoThumb=(id,stamp)=>'/thumb/review/'+id+'?v='+encodeURIComponent(String(stamp??0));
// Undo and error feedback must stay visible for its FULL duration, even if
// several actions finish close together. The live region announces it to
// keyboard/TalkBack users without moving their current review focus.
let toastTimer=null;
function toast(m){
  const t=$('#toast');
  if(toastTimer!==null){clearTimeout(toastTimer);toastTimer=null}
  t.textContent=String(m);t.style.display='block';
  toastTimer=setTimeout(()=>{t.style.display='none';toastTimer=null},3500);
}
async function get(u){let r=await fetch(u);let v=await r.json();if(!r.ok)throw Error(v.error||r.statusText);return v}
function invalidatePeopleCache(){
  peopleCache=null;
  peopleCacheRevision++;
}
// Cross-tab notifications carry NO Person/face IDs, names, photos, Undo
// tokens or write payloads. Optional modern browser API; no storage fallback
// that could persist private gallery behavior. Same-origin only.
let peopleChangeChannel=null;
try{
  if(typeof BroadcastChannel==='function'){
    peopleChangeChannel=new BroadcastChannel('atlas-v4-people-refresh-v1');
    peopleChangeChannel.onmessage=event=>{
      if(event?.data?.type!=='people-changed')return;
      const wasCached=peopleCache!==null;
      invalidatePeopleCache(); // Do not rebroadcast; no echo loop.
      if(wasCached&&tab==='people'&&typeof toast==='function')
        toast('People may have changed in another tab · reopen People to refresh');
    };
  }
}catch(_){peopleChangeChannel=null} // Unsupported/blocked API: local tab remains safe.
function notifyPeopleCacheChanged(){
  invalidatePeopleCache();
  try{peopleChangeChannel?.postMessage({type:'people-changed'})}
  catch(_){} // No sensitive payload and no dependency on cross-tab support.
}
async function post(u,data={}){
  // A write may commit even when fetch()/json() later rejects. Invalidate
  // BEFORE sending it and AGAIN on completion/failure, so neither an old
  // roster nor a concurrent metadata GET can masquerade as fresh evidence.
  // Only these two authenticated token-status routes are truly read-only.
  const readOnlyStatus=u==='/api/person/create/status'||
                       u==='/api/review/undo/status'||
                       u==='/api/review/write/status';
  if(!readOnlyStatus)notifyPeopleCacheChanged();
  try{
    const r=await fetch(u,{method:'POST',headers:{
      'Content-Type':'application/json','X-Studio-Token':window.token
    },body:JSON.stringify(data)});
    const v=await r.json();
    if(!r.ok)throw Error(v?.error||r.statusText);
    // Unresolved read-only status is not a write. Preserve cached visible
    // People unless status reveals a PREVIOUS committed mutation, or the
    // status reply is malformed/unexpected.
    if(readOnlyStatus){
      const unchanged=(u==='/api/person/create/status'&&v?.status==='unknown')||
        (u==='/api/review/undo/status'&&
          (v?.status==='unknown'||v?.status==='recorded_not_undone'))||
        (u==='/api/review/write/status'&&v?.status==='unknown');
      if(!unchanged)notifyPeopleCacheChanged();
    }
    return v;
  }finally{
    // Even a rejected 200/JSON parse may follow a committed transaction.
    // A People GET started during the POST must never repopulate old data.
    if(!readOnlyStatus)notifyPeopleCacheChanged();
  }
}
async function action(url,data,msg){
  // Background job/roots writes run independently of the screen. Completion
  // must not redraw whichever unrelated review page opened in the meantime.
  const origin=reviewWriteContext();
  let acknowledged=false;
  try{
    const result=await post(url,data);
    acknowledged=true;
    if(reviewWriteStillVisible(origin))await render();
    if(msg)toast(msg);
    return result;
  }catch(e){
    // A dropped response can follow an accepted job/roots command. If the
    // POST already acknowledged, only the local repaint has failed.
    toast(acknowledged
      ?'Command accepted; could not refresh view: '+e.message
      :'Could not verify command: '+e.message+' · check status before retrying');
  }
}
const btn=(s,action,extra='')=>`<button class="btn ${extra}" data-action="${action}">${esc(s)}</button>`;
// Bulk actions are deliberately limited to the CURRENT visible page. There is
// no server-side "select every face in the entire index" operation.
const selectionToolbar=()=>`<span class="caption" id="selection-count">${fmt(selected.size)} selected · Select page affects visible faces only</span>${btn('Select page','selectpage','secondary')}${btn('Clear selection','clearselection','secondary')}`;
function keepReviewUndo(result){if(result && result.undo_token)lastUndoToken=result.undo_token}
const undoButton=()=>(
  (lastUndoToken?btn('↶ Undo last review action','undomanual','secondary'):'')+
  // The status button is independent of the newest Undo token: an earlier
  // uncertain request can still need inspection after newer review actions.
  (typeof unverifiedUndoTokens!=='undefined'&&unverifiedUndoTokens.length
    ?btn('Verify earlier Undo status','verifyundo','secondary'):'')+
  (typeof pendingReviewWriteRequests!=='undefined'&&pendingReviewWriteRequests.length
    ?btn('Verify '+pendingReviewWriteRequests.length+' earlier reviews','verifyreviews','secondary')+
     btn('Dismiss one review reminder','dismissreview','secondary'):'')
);
function updateSelectionCount(){let label=$('#selection-count');if(label)label.textContent=fmt(selected.size)+' selected · Select page affects visible faces only'}
function selectVisibleFaces(){
  document.querySelectorAll('#view input[data-select]').forEach(input=>{
    const id=Number(input.dataset.select);
    selected.add(id);input.checked=true;
    input.closest('.tile')?.classList.add('selected');
  });
  updateSelectionCount();
}
function clearSelectedFaces(){
  selected.clear();
  document.querySelectorAll('#view input[data-select]').forEach(input=>{
    input.checked=false;
    input.closest('.tile')?.classList.remove('selected');
  });
  updateSelectionCount();
}

// Keep the modal's keyboard sequence within the currently visible step.
// Hidden browse controls and disabled in-flight buttons are not tab stops.
function cyclePickerTab(e,root){
  const dialog=root.querySelector('.picker');
  const controls=Array.from(dialog.querySelectorAll('a[href], button, input, select, textarea, [tabindex]'))
    .filter(el=>!el.disabled&&el.getAttribute('tabindex')!=='-1'&&
      !el.closest('[hidden], [inert], [aria-hidden="true"]'));
  const current=document.activeElement,idx=controls.indexOf(current);
  const target=!controls.length
    ? root.querySelector(root.querySelector('#picker-review')?.hidden?'#picker-heading':'#picker-review-heading')
    : e.shiftKey
      ? (idx<=0?controls[controls.length-1]:null)
      : (idx===-1||idx===controls.length-1?controls[0]:null);
  if(target){e.preventDefault();target.focus({preventScroll:true});}
}

function closePicker(force=false){
  if(pickerState?.submitting&&!force)return;
  // Invalidate a slow earlier roster read even if this screen never navigated.
  choosePerson.generation=(choosePerson.generation||0)+1;
  const opener=pickerState?.opener;
  if(pickerState?.coverTimer)clearTimeout(pickerState.coverTimer);
  const root=$('#person-picker');
  root.hidden=true;root.innerHTML='';pickerState=null;
  if(opener?.isConnected)opener.focus({preventScroll:true});
}
function personOptionMarkup(p){return `<button class="picker-option" data-picker-person="${Number(p.id)}">${p.cover?`<img loading="lazy" decoding="async" width="54" height="54" alt="" src="${portraitThumb(p.cover)}">`:'<span class="portrait-placeholder" aria-hidden="true">◉</span>'}<span><b>${esc(p.name)}</b><small>${fmt(p.images)} photos · ${fmt(p.faces)} faces · profile #${Number(p.id)}</small></span></button>`}
async function hydratePickerCovers(state,view,sequence){
  if(pickerState!==state||!state.scopedCovers)return;
  const missing=view.filter(p=>!p.cover_loaded);
  if(!missing.length)return;
  // The server caps each request to 48 active, nonarchived IDs. Up to
  // 100 visible picker options therefore need at most three small lookups.
  const requests=[];
  for(let i=0;i<missing.length;i+=48){
    requests.push(get('/api/people/covers?ids='+missing.slice(i,i+48).map(p=>p.id).join(',')));
  }
  try{
    const results=await Promise.all(requests);
    if(pickerState!==state)return;
    const joined=Object.assign({},...results);
    for(const p of missing){
      const cover=joined[String(p.id)];
      p.cover=cover?.cover??null;
      p.cover_manual=!!cover?.cover_manual;
      p.cover_loaded=true;
    }
    if(state.coverSequence!==sequence)return; // Never overwrite newer search.
    const root=$('#person-picker'),review=root.querySelector('#picker-review');
    if(review&&!review.hidden)return; // Confirmation must never be interrupted.
    const list=root.querySelector('#picker-results');
    if(!list)return;
    const focused=document.activeElement?.closest?.('[data-picker-person]')?.dataset.pickerPerson;
    const scroll=list.scrollTop;
    list.innerHTML=view.map(personOptionMarkup).join('');
    list.scrollTop=scroll;
    if(focused)list.querySelector('[data-picker-person="'+focused+'"]')
      ?.focus({preventScroll:true});
  }catch(_){
    // Poor connectivity is unlikely on localhost, but keep name search
    // functional without an optional portrait lookup.
  }
}
// Search uses the same canonical spelling rules as duplicate-name detection.
// Start-of-name results outrank incidental substrings, so the first 100
// options remain useful even in a library with thousands of named people.
function pickerNameKey(name){
  return String(name??'').normalize('NFKC').trim().replace(/\s+/g,' ').toLocaleLowerCase();
}
function rankPickerPeople(people,query){
  const q=pickerNameKey(query);
  if(!q)return people;
  return people.filter(p=>pickerNameKey(p.name).includes(q)).sort((a,b)=>{
    const an=pickerNameKey(a.name),bn=pickerNameKey(b.name);
    const priority=n=>n===q?0:n.startsWith(q)?1:2;
    return priority(an)-priority(bn)||an.localeCompare(bn)||Number(a.id)-Number(b.id);
  });
}
function renderPickerResults(){
  const state=pickerState;if(!state)return;
  if(state.coverTimer){clearTimeout(state.coverTimer);state.coverTimer=null}
  const q=pickerNameKey($('#picker-query')?.value||'');
  // A portrait lookup from an older search must never open a confirmation
  // for a name the reviewer is no longer browsing. Invalidate tapped rows
  // before scheduling/replacing the newly visible results.
  if(state.choiceQuery!==q){
    state.choiceQuery=q;
    state.choiceEpoch=(state.choiceEpoch||0)+1;
  }
  const ranked=rankPickerPeople(state.people,q);
  const view=ranked.slice(0,100);
  $('#picker-results').innerHTML=view.map(personOptionMarkup).join('')||'<p class="caption">No existing person matches. You can cancel and create a new person.</p>';
  $('#picker-result-count').textContent=`${fmt(ranked.length)} matches${ranked.length>100?' · first 100 shown':''}`;
  const sequence=state.coverSequence=(state.coverSequence||0)+1;
  if(state.scopedCovers&&view.some(p=>!p.cover_loaded)){
    // Debounce keyboard input: never fan out three portrait requests for
    // every individual keystroke in a multi-thousand-person library.
    state.coverTimer=setTimeout(()=>{state.coverTimer=null;
      void hydratePickerCovers(state,view,sequence)},140);
  }
}
// A row tap only PREVIEWS the destination. Assignment/merge has a separate,
// explicit Confirm button. Cancel/Back makes zero database requests.
function showPickerConfirmation(p){
  const state=pickerState;if(!state||state.submitting)return;
  // A failed network acknowledgement is NOT proof that the review write
  // rolled back. Close and inspect People before attempting another write.
  if(state.uncertain)return toast('Check People before retrying this unverified confirmation');
  const root=$('#person-picker');
  const browse=root.querySelector('#picker-browse'),review=root.querySelector('#picker-review');
  root.querySelector('#picker-query')?.blur();
  browse.hidden=true;review.hidden=false;
  const title=state.options.confirmTitle||'Confirm this person';
  const summary=state.options.confirmText?state.options.confirmText(p):`Choose ${p.name} as the destination?`;
  review.innerHTML=`<h2 id="picker-review-heading" tabindex="-1">${esc(title)}</h2>
    <div class="picker-review-card">${p.cover?`<img loading="lazy" decoding="async" width="72" height="72" alt="" src="${portraitThumb(p.cover)}">`:'<span class="portrait-placeholder" aria-hidden="true">◉</span>'}<span><b>${esc(p.name)}</b><small>${fmt(p.faces)} faces · ${fmt(p.images)} photos · profile #${Number(p.id)}</small></span></div>
    <p class="picker-review-warning" id="picker-review-warning">${esc(summary)}</p>
    <div class="picker-actions"><button type="button" class="btn secondary" id="picker-review-back">← Go back</button>
    <button type="button" class="btn ${state.options.confirmDanger?'danger':''}" id="picker-review-submit">${esc(state.options.confirmLabel||'Confirm')}</button></div>`;
  root.querySelector('#picker-review-back').onclick=()=>{
    if(state.submitting)return;
    review.hidden=true;review.innerHTML='';browse.hidden=false;
    const previous=root.querySelector('#picker-results [data-picker-person="'+Number(p.id)+'"]');
    (previous||root.querySelector('#picker-heading'))?.focus({preventScroll:true});
  };
  root.querySelector('#picker-review-submit').onclick=async()=>{
    if(state.submitting||state.uncertain)return;state.submitting=true;
    const submit=root.querySelector('#picker-review-submit');
    const back=root.querySelector('#picker-review-back');
    submit.disabled=true;back.disabled=true;submit.textContent='Working…';
    try{
      await state.options.onChoose(p);closePicker(true);
    }catch(ex){
      // A timeout, lost response, or later render error can happen AFTER the
      // server committed. Never claim "nothing changed" or enable one-tap
      // resubmission of a possibly successful identity write.
      state.submitting=false;state.uncertain=true;
      const message='Could not verify completion: '+ex.message+' · check People before retrying';
      toast(message);
      const warning=root.querySelector('#picker-review-warning');
      if(warning)warning.textContent=message;
      submit.disabled=true;back.disabled=false;
      submit.textContent='Check People before retrying';
      back.focus({preventScroll:true});
    }
  };
  root.querySelector('#picker-review-back').focus({preventScroll:true});
}
async function choosePerson(options){
  if(pickerState?.submitting)return; // Do not replace an in-flight confirmation.
  const attempt=choosePerson.generation=(choosePerson.generation||0)+1;
  const opener=document.activeElement;
  const originTab=tab,originPerson=person,originGroup=group,originEpoch=renderEpoch;
  const people=options.people||await get('/api/people?covers=0');
  // A newer chooser or dismissal supersedes an older roster request even
  // on the exact same screen; tab/person checks alone cannot detect this.
  if(attempt!==choosePerson.generation||pickerState?.submitting||
     tab!==originTab||renderEpoch!==originEpoch||
     (tab==='person'&&person!==originPerson)||
     (tab==='group'&&group!==originGroup))return;
  pickerState={people,options,submitting:false,opener,scopedCovers:!options.people,coverSequence:0,coverTimer:null};
  const root=$('#person-picker');root.hidden=false;
  root.innerHTML=`<div class="overlay"><section class="picker" role="dialog" aria-modal="true" aria-label="Select a person">
    <div id="picker-browse" class="picker-browse">
      <h2 id="picker-heading" tabindex="-1">${esc(options.title||'Select a person')}</h2><p class="caption">${esc(options.explanation||'Choose a confirmed profile by name and photograph.')}</p>
      <label for="picker-query" class="caption">Search existing people</label><input id="picker-query" autocomplete="off" placeholder="Type a name" value="${esc(options.query||'')}">
      <span id="picker-result-count" class="caption"></span><div class="picker-results" id="picker-results"></div>
      <div class="picker-actions"><button class="btn secondary" id="picker-cancel">Cancel</button>${options.allowNew?'<button class="btn secondary" id="picker-create">Create separate person instead</button>':''}</div>
    </div>
    <div id="picker-review" class="picker-review" hidden></div>
    </section></div>`;
  root.querySelector('#picker-cancel').onclick=()=>closePicker();
  root.querySelector('.overlay').onclick=e=>{if(e.target.classList.contains('overlay'))closePicker()};
  root.querySelector('.picker').onkeydown=e=>{
    if(e.key==='Tab'){cyclePickerTab(e,root);return;}
    if(e.key!=='Escape')return;
    e.preventDefault();e.stopPropagation();
    if(pickerState?.submitting)return; // Never dismiss an in-flight write.
    const review=root.querySelector('#picker-review');
    if(review&&!review.hidden)root.querySelector('#picker-review-back')?.click();
    else closePicker();
  };
  root.querySelector('#picker-query').addEventListener('input',renderPickerResults);
  root.querySelector('#picker-results').onclick=async e=>{
    const button=e.target.closest('[data-picker-person]');if(!button)return;
    const state=pickerState,choice=Number(button.dataset.pickerPerson);
    const p=people.find(x=>x.id===choice);if(!p||!state)return;
    const choiceEpoch=state.choiceEpoch=(state.choiceEpoch||0)+1;
    // The confirmation card should not hide a portrait that is still being
    // looked up. A one-profile GET is always bounded, even for same-name people.
    if(state.scopedCovers&&!p.cover_loaded){
      try{
        const info=await get('/api/people/covers?ids='+choice);
        if(pickerState!==state||state.choiceEpoch!==choiceEpoch)return;
        p.cover=info[String(choice)]?.cover??null;
        p.cover_manual=!!info[String(choice)]?.cover_manual;
        p.cover_loaded=true;
      }catch(_){
        if(pickerState!==state||state.choiceEpoch!==choiceEpoch)return;
        toast('Portrait unavailable · verify profile name and number carefully');
      }
    }
    if(pickerState===state&&state.choiceEpoch===choiceEpoch)
      showPickerConfirmation(p);
  };
  const create=root.querySelector('#picker-create');
  if(create)create.onclick=async()=>{
    // New/duplicate-name creation may fail after its chooser closes.
    // Report the rejection instead of leaving an unhandled promise; do not
    // falsely claim "nothing changed" if the network outcome is ambiguous.
    if(pickerState?.submitting)return;
    const callback=pickerState?.options?.onNew;
    closePicker();
    if(!callback)return;
    try{await callback()}
    catch(error){toast('Could not finish: '+error.message+' · check People before retrying')}
  };
  renderPickerResults();
  if(options.initialPersonId){const match=people.find(p=>p.id===Number(options.initialPersonId));if(match)showPickerConfirmation(match);}
  // A focusable heading announces the current step without opening Android's
  // soft keyboard. Initial direct-to-confirmation opens on the safe Back action.
  if(root.querySelector('#picker-review')?.hidden)
    root.querySelector('#picker-heading').focus({preventScroll:true});
  else root.querySelector('#picker-review-back').focus({preventScroll:true});
}
async function returnToGroups(){
  const prev=groupReturn;groupReturn=null;tab='groups';page=prev?.page??0;selected.clear();
  const expectedPage=page,painting=render(),epoch=renderEpoch;
  await painting;
  requestAnimationFrame(()=>{
    if(tab!=='groups'||page!==expectedPage||renderEpoch!==epoch)return;
    window.scrollTo(0,prev?.scroll??0);
  });
}

const header=(title,sub)=>`<div class="top"><div><h1>${esc(title)}</h1><div class="muted">${esc(sub)}</div></div><div class="pill">ON-DEVICE · PRIVATE</div></div>`;
function faceTile(f,selectable=true){
  const taggedReview=tab==='person'&&personMode==='faces';
  const assigned=f.person_id!==null&&f.person_id!==undefined;
  const canSelect=selectable&&!f.ignored&&(taggedReview?f.person_id===person:!assigned);
  const chosen=canSelect&&selected.has(f.id);
  const label=assigned&&!taggedReview?`<div class="meta">Already tagged to profile #${Number(f.person_id)} · inspect only</div>`:
    f.ignored?`<div class="meta">Hidden face · inspect only</div>`:'';
  return `<div class="tile facepick ${chosen?'selected':''}"><button type="button" class="face-open" data-face="${Number(f.id)}" aria-label="Find lookalikes for face #${Number(f.id)}"><img loading="lazy" decoding="async" width="160" height="160" alt="" src="${faceThumb(f.id)}"><div class="title">Face #${Number(f.id)}</div><div class="meta">${f.score!==undefined?'Similarity '+Number(f.score).toFixed(3):'Tap to find matches'}</div></button>${label}${canSelect?`<input class="select" type="checkbox" data-select="${Number(f.id)}" aria-label="Select face #${Number(f.id)}" ${chosen?'checked':''}>`:''}${taggedReview&&canSelect?`<button class="coverpick" type="button" data-cover-face="${Number(f.id)}" aria-label="Use face #${Number(f.id)} as portrait">Use as portrait</button>`:''}</div>`;
}
function photoTile(p){return `<button type="button" class="tile photo" data-photo="${Number(p.id)}"><img loading="lazy" decoding="async" width="240" height="180" alt="" src="${photoThumb(p.id)}"><div class="meta" style="padding:9px">${esc(p.path.split('/').pop())}</div></button>`}
async function home(){let s=await get('/api/status');status=s;let j=s.job;let pct=j.total?Math.min(100,Math.round(j.done/j.total*100)):0;return header('Your visual archive','One private index. Every face. Your names.')+`<div class="stats">${[['Photos indexed',s.photos],['Faces detected',s.faces],['People named',s.people],['Awaiting review',s.untagged]].map(([lab,val])=>`<div class="metric"><div class="val num">${fmt(val)}</div><div class="lab">${lab}</div></div>`).join('')}</div><div class="card"><h2>Background jobs</h2><p class="muted">${esc(j.phase)} · ${esc(j.message)}</p><p class="caption">Starts only when you tap Scan / Find groups / Suggest matches, or run a Termux command. No automatic scheduled scans. This page shows jobs started by this web server.</p><div class="bar"><div style="width:${pct}%"></div></div><p class="caption num">${fmt(j.done)} / ${fmt(j.total)} · ${pct}% · ${fmt(j.speed)} image/s ${j.eta!==null?'· ETA '+Math.round(j.eta/60)+'m':''}</p><div class="toolbar">${btn('Scan / resume','scan')}${btn('Find face groups','group','secondary')}${btn('Suggest profile matches','propose','secondary')}${btn('Undo last automatic batch','undolastauto','secondary')}${btn('Pause job','stop','secondary')}</div><p class="caption">Review-first mode: model matches remain unconfirmed until you approve selected faces. ${fmt(s.auto_linked)} historical auto-linked faces are shown for transparency; this preview creates no new automatic identity links.</p><p class="note">Scanning and grouping never edit your original photos. Computer-suggested groups stay provisional until you name or confirm them.</p></div><div class="card"><h2>Library health</h2><p>Found <b>${fmt(s.groups)}</b> proposed groups. <b>${fmt(s.tagged)}</b> faces linked to named profiles, and <b>${fmt(s.suggested)}</b> suggestions awaiting review. <b>${fmt(s.errors)}</b> image decoding errors.</p><p class="caption">${s.models_ready?'✓ Both models located':'⚠ Install the model files from Termux before scanning'}</p><p class="caption">Indexed roots: ${s.roots.map(x=>esc(x)).join(' · ')||'None'}</p></div>`}

// Cache only metadata; render at most 48 portrait tiles. Keyboard input filters
// locally without waiting for network and without replacing the search field.
function peopleSearchMatches(people,query){
  const q=comparablePersonName(query);
  if(!q)return people;
  const matches=people.filter(p=>comparablePersonName(p.name).includes(q));
  const priority=p=>{
    const n=comparablePersonName(p.name);
    return n===q?0:n.startsWith(q)?1:2;
  };
  return matches.sort((a,b)=>priority(a)-priority(b)||
    comparablePersonName(a.name).localeCompare(comparablePersonName(b.name))||a.id-b.id);
}
function peopleTile(p){
  return `<button type="button" class="tile" data-person="${Number(p.id)}">${p.cover?`<img loading="lazy" decoding="async" width="160" height="160" alt="" src="${portraitThumb(p.cover)}">`:'<span class="portrait-placeholder" aria-hidden="true">◉</span>'}<div class="title">${esc(p.name)}</div><div class="meta">${fmt(p.images)} photos · ${fmt(p.faces)} faces · #${Number(p.id)}</div></button>`;
}
function peopleSearchPage(people,query,currentPage){
  const matches=peopleSearchMatches(people,query);
  const pages=Math.max(1,Math.ceil(matches.length/PEOPLE_PAGE_SIZE));
  const index=Math.max(0,Math.min(pages-1,currentPage));
  return {matches:matches.slice(index*PEOPLE_PAGE_SIZE,(index+1)*PEOPLE_PAGE_SIZE),
    count:matches.length,page:index,pages};
}
async function fillVisiblePeopleCovers(profiles,epoch){
  // Metadata-only local search never scores the whole gallery's face crops.
  // Fetch at most one visible 48-card page, independent of roster size.
  const roster=peopleCache;
  const pending=profiles.filter(p=>!p.cover_loaded);
  if(!pending.length)return false;
  const ids=pending.map(p=>Number(p.id));
  const matches=await get('/api/people/covers?ids='+ids.join(','));
  if(tab!=='people'||peopleCache!==roster||
      (epoch!==undefined&&epoch!==renderEpoch))return false;
  for(const p of pending){
    const info=matches[String(p.id)];
    p.cover=info?.cover??null;
    p.cover_manual=!!info?.cover_manual;
    p.cover_loaded=true;
  }
  return true;
}
function updatePeopleResults(hydrate=true){
  if(tab!=='people'||!peopleCache)return;
  const result=peopleSearchPage(peopleCache,peopleQuery,peopleListPage);
  peopleListPage=result.page;
  const grid=$('#people-grid'),status=$('#people-search-count');
  if(!grid||!status)return;
  grid.innerHTML=result.matches.map(peopleTile).join('')||
    '<p class="muted">No matching profiles. Try another name or Discover people.</p>';
  status.textContent=`${fmt(result.count)} matching profiles · page ${result.page+1}/${result.pages} · ${fmt(result.matches.length)} visible`;
  const prev=$('[data-action="peopleprev"]'),next=$('[data-action="peoplenext"]');
  if(prev)prev.disabled=result.page===0;
  if(next)next.disabled=result.page>=result.pages-1;
  if(hydrate){
    const query=peopleQuery,atPage=peopleListPage;
    fillVisiblePeopleCovers(result.matches).then(ok=>{
      if(ok&&tab==='people'&&peopleQuery===query&&peopleListPage===atPage){
        const focused=document.activeElement?.closest?.('[data-person]')?.dataset.person;
        updatePeopleResults(false); // Search input is outside replaced grid.
        if(focused)document.querySelector('#people-grid [data-person="'+focused+'"]')
          ?.focus({preventScroll:true}); // Keep keyboard's visible portrait focus.
      }
    }).catch(()=>{}); // Portrait lookup failure must not break name search.
  }
}
async function people(epoch){
  if(!peopleCache){
    // A slow roster GET can start BEFORE an identity write and finish AFTER
    // that write. Never populate the cache from such a stale response. Retry
    // a bounded once under the newer generation, without losing search/page.
    for(let attempt=0;attempt<2&&!peopleCache;attempt++){
      const revision=peopleCacheRevision;
      const roster=await get('/api/people?covers=0');
      if(epoch!==undefined&&(epoch!==renderEpoch||tab!=='people'))return '';
      if(revision===peopleCacheRevision)peopleCache=roster;
    }
    if(!peopleCache)return '<p role="status" class="muted">People changed while loading. Open People again to refresh.</p>';
  }
  const roster=peopleCache;
  const result=peopleSearchPage(roster,peopleQuery,peopleListPage);
  peopleListPage=result.page;
  try{await fillVisiblePeopleCovers(result.matches,epoch)}catch(_){}
  if(epoch!==undefined&&(epoch!==renderEpoch||tab!=='people'))return '';
  // Even after a fresh metadata GET, visible portrait hydration can wait
  // while a Person write commits (or loses its acknowledgement). Never
  // render old People tiles after the cache has been invalidated/replaced.
  if(peopleCache!==roster)
    return '<p role="status" class="muted">People changed while loading. Open People again to refresh.</p>';
  return header('Your people','Search confirmed profiles by name. Only this page of portraits is displayed.')+
    `<div class="toolbar">${btn('+ Create a person','newperson','secondary')}${undoButton()}${pendingPersonCreateToolbar()}</div>`+
    `<label class="label" for="people-query">Search named people</label>`+
    `<input type="search" id="people-query" autocomplete="off" value="${esc(peopleQuery)}" placeholder="Search existing names…" aria-controls="people-grid">`+
    `<p class="caption" id="people-search-count" role="status">${fmt(result.count)} matching profiles · page ${result.page+1}/${result.pages} · ${fmt(result.matches.length)} visible</p>`+
    `<div class="grid" id="people-grid">${result.matches.map(peopleTile).join('')||
      '<p class="muted">No matching profiles. Try another name or Discover people.</p>'}</div>`+
    `<div class="toolbar"><button class="btn secondary" type="button" data-action="peopleprev" aria-controls="people-grid" ${result.page===0?'disabled':''}>← Previous</button><button class="btn secondary" type="button" data-action="peoplenext" aria-controls="people-grid" ${result.page>=result.pages-1?'disabled':''}>Next →</button></div>`+
    (!peopleCache.length?'<p class="muted">Start by naming a reviewed group in Discover people.</p>':'');
}
async function returnToPeople(){
  const saved=peopleReturn;peopleReturn=null;tab='people';selected.clear();
  peopleListPage=saved?.page??peopleListPage;peopleQuery=saved?.query??peopleQuery;
  peopleCache=null;
  const expectedPage=peopleListPage,expectedQuery=peopleQuery,
        painting=render(),epoch=renderEpoch;
  await painting;
  requestAnimationFrame(()=>{
    if(tab!=='people'||renderEpoch!==epoch||peopleListPage!==expectedPage||
       peopleQuery!==expectedQuery)return;
    window.scrollTo(0,saved?.scroll??0);
  });
}

// A render generation also guards mutable browser navigation, not only HTML.
function reviewRequestCurrent(epoch,screen,requestedPage,subject,mode){
  if(epoch!==undefined&&(epoch!==renderEpoch||tab!==screen))return false;
  if(page!==requestedPage)return false;
  if(screen==='person'&&(person!==subject||personMode!==mode))return false;
  if(screen==='group'&&group!==subject)return false;
  return true;
}
async function groups(epoch){
  const only=groupPendingOnly?'1':'0';
  const stable=groupOrder==='stable';
  const requestedPage=page;
  // A stable boundary belongs to this in-memory review pass only. After a
  // browser restart the old anonymous page bookmark cannot reconstruct the
  // historical sort key; begin a NEW pass rather than silently skip groups.
  if(stable&&requestedPage>0&&!groups.stableBounds?.[requestedPage]){
    page=0;
    groupResumePage=0;
    groups.stableBounds=[null];
  }
  // Legacy dynamic-sort pages retain their indexed count clamp. A valid
  // keyset must NOT be clamped by the shrinking number of pending groups.
  if(page>0&&!stable){
    const summary=await get('/api/groups/total?pending_only='+only);
    if(!reviewRequestCurrent(epoch,'groups',requestedPage))return '';
    page=Math.min(requestedPage,Math.max(0,Math.ceil(Number(summary.count)/40)-1));
  }
  const listedPage=page;
  const bound=stable?groups.stableBounds?.[listedPage]:null;
  const cursorArgs=stable?'&cursor=1'+(bound?
    '&after_count='+bound.count+'&after_group='+bound.id:''):'';
  const batch=await get('/api/groups?page='+listedPage+'&order='+groupOrder+'&seed='+groupShuffleSeed+'&pending_only='+only+'&with_next=1'+cursorArgs);
  if(!reviewRequestCurrent(epoch,'groups',listedPage))return '';
  const items=batch.items;
  if(!items.length&&page>0){
    page=0;
    if(stable)groups.stableBounds=[null];
    return groups(epoch);
  } // Reset at end of newly emptied queue; no recursive page-by-page walk.
  if(stable){
    if(!groups.stableBounds)groups.stableBounds=[null];
    if(Number.isSafeInteger(batch.next_count)&&batch.next_count>=0&&
       Number.isSafeInteger(batch.next_group)&&batch.next_group>0)
      groups.stableBounds[listedPage+1]={count:batch.next_count,id:batch.next_group};
  }
  groupResumePage=page;
  saveDiscoverCursor();
  return header('Discover people','Candidate groups are unverified. Existing names below are resemblance hints, NOT identity confirmations.')+
    ((lastUndoToken||(typeof pendingReviewWriteRequests!=='undefined'&&pendingReviewWriteRequests.length))?`<div class="toolbar">${undoButton()}</div>`:'')+
    `<div class="toolbar">${btn('Needs review','groupspending',groupPendingOnly?'':'secondary')}${btn('All groups','groupsall',!groupPendingOnly?'':'secondary')}</div>`+
    `<div class="toolbar">${btn('Largest first','groupslargest',groupOrder==='largest'?'':'secondary')}${btn('Smallest first','groupssmallest',groupOrder==='smallest'?'':'secondary')}${btn('Stable review pass','groupsstable',groupOrder==='stable'?'':'secondary')}${btn('Surprise me','groupsshuffle',groupOrder==='shuffle'?'':'secondary')}${groupOrder==='shuffle'?btn('Shuffle again','groupsreshuffle','secondary'):''}</div>`+
    `<p class="caption">${groupOrder==='shuffle'?'Shuffled Discover queue · stable while browsing':groupOrder==='smallest'?'Smallest groups first · surface less-prominent faces':groupOrder==='stable'?'Stable pass by original group size · completed groups cannot shift unseen work; current remaining counts still shown':'Largest groups first'} · ${groupPendingOnly?'Showing only groups with unassigned, non-hidden faces':'Showing all historical groups, including completed groups'}. Names are unverified hints, never automatic identity assignments.</p>`+
    `<div class="grid">${items.map(g=>`<button type="button" class="tile" data-group="${g.id}"><img loading="lazy" decoding="async" width="160" height="160" alt="" src="${faceThumb(g.representative)}"><div class="title">Candidate group #${g.id}</div><div class="meta">${fmt(g.remaining_count)} still to review · ${fmt(g.count)} originally grouped</div>${g.remaining_count===0?'<div class="caption">No unassigned faces remain · historical group</div>':''}${g.possible_existing_people?.length?`<div class="caption">Possibly already named: ${g.possible_existing_people.map(p=>esc(p.name)).join(' / ')} · unverified</div>`:''}</button>`).join('')}</div>`+(!items.length?'<p class="muted">Nothing awaiting review here. Try All groups or another scan.</p>':'')+pager(items.length,batch.has_more)
}
function pager(count,hasMore){
  return `<div class="toolbar"><button type="button" class="btn secondary" data-action="prev" ${page===0?'disabled':''}>← Previous</button><button type="button" class="btn secondary" data-action="next" ${hasMore?'':'disabled'}>Next →</button></div><p class="caption">Page ${page+1} · showing ${count} items${hasMore?' · more available':''}</p>`;
}
async function groupPage(epoch){
  const requestedGroup=group,requestedPage=page;
  const [batch,candidates]=await Promise.all([
    get('/api/group?id='+requestedGroup+'&page='+requestedPage+'&with_next=1'),
    get('/api/group/candidates?id='+requestedGroup)
  ]);
  if(!reviewRequestCurrent(epoch,'group',requestedPage,requestedGroup))return '';
  if(!batch.items.length&&page>0){page=0;return groupPage(epoch)}
  const faces=batch.items;return header('Possible same person','Review these faces before naming the whole group.')+`<div class="toolbar">${btn('← Back to groups','backgroups','secondary')}${btn('Name as NEW person','namegroup')}${btn('Attach group to EXISTING person','attachgroup','secondary')}${btn('Add selected to person','assign','secondary')}${selectionToolbar()}${undoButton()}${btn('Find matches for a face','hint','secondary')}</div><p class="caption">Use Select page for only the displayed faces (other pages are not auto-selected). Check each face before tagging. Tap any face outside its checkbox to find similar matches.</p><div class="card"><h2>Could this group be someone you already named?</h2><p class="caption">Resemblance is an unverified similarity score, not an identity probability. Groups can include different people. Check all faces before attaching a whole group.</p>${candidates.length?`<div class="grid">${candidates.map(p=>`<div class="tile" style="padding:8px">${p.cover?`<img loading="lazy" decoding="async" width="160" height="160" alt="Portrait of ${esc(p.name)}" src="${portraitThumb(p.cover)}">`:''}<div class="title">${esc(p.name)}</div><div class="meta">Similarity ${Number(p.similarity).toFixed(2)} · ${p.supporting_faces}/${p.sampled_faces} sampled faces above comparison threshold</div><button class="btn secondary" type="button" data-candidate-person="${p.person_id}">Review & attach</button></div>`).join('')}</div>`:'<p class="muted">No existing profiles met the exploratory similarity cutoff. You can still search existing People manually.</p>'}</div><div class="grid">${faces.map(f=>faceTile(f)).join('')}</div>`+pager(faces.length,batch.has_more)}
async function personPage(epoch){
  const requestedPerson=person,requestedPage=page,requestedMode=personMode;
  // Request only what the current mode displays, alongside a single-profile
  // summary. Do not compute every named portrait or query photos when reviewing
  // face tags/suggestions. Parallel I/O trims the critical path on localhost.
  const source=requestedMode==='photos'?'/api/person':requestedMode==='faces'?'/api/person/faces':'/api/person/suggested';
  // The confirmed-face review is ordered by immutable f.id, unlike photos
  // and similarity-ranked suggestions. A removed earlier tag must not shift
  // an unseen face past OFFSET(page * 60).
  const saved=personPage.faceCursor;
  const faceCursor=requestedMode==='faces'?
    (requestedPage===0||saved?.person!==requestedPerson?
      {person:requestedPerson,bounds:[null]}:saved):null;
  const bound=faceCursor?.bounds[requestedPage];
  const cursorQuery=requestedMode==='faces'?'&cursor=1'+
    (requestedPage>0&&Number.isSafeInteger(bound)&&bound>0?'&before_id='+bound:''):'';
  const [p,batch]=await Promise.all([
    get('/api/person/summary?id='+requestedPerson),
    get(source+'?id='+requestedPerson+'&page='+requestedPage+'&with_next=1'+cursorQuery)
  ]);
  if(!reviewRequestCurrent(epoch,'person',requestedPage,requestedPerson,requestedMode))return '';
  if(!batch.items.length&&page>0){page=0;personPage.faceCursor=null;return personPage(epoch)}
  // Only the winning screen render can publish the next boundary.
  if(faceCursor){
    personPage.faceCursor=faceCursor;
    if(Number.isSafeInteger(batch.next_before)&&batch.next_before>0)
      faceCursor.bounds[requestedPage+1]=batch.next_before;
  }
  const items=batch.items;
  const photos=personMode==='photos'?items:[];
  const facelist=personMode==='photos'?[]:items;
  return header(p?.name||'Person','Every indexed photo with a face you assigned to this profile.')+`<div class="toolbar">${btn('← People','backpeople','secondary')}${btn('Rename','rename','secondary')}${btn('Find more photos','searchperson')}${btn('Merge profiles','merge','secondary')}${btn('Restore merged','restoremerge','secondary')}${btn('Show photos','photomode','secondary')}${btn('Review tags','facemode','secondary')}${btn('Review suggestions','suggestmode','secondary')}${personMode==='faces'?btn('Remove selected tags','unassign','secondary'):''}${personMode==='suggested'?btn('Accept selected','accept','secondary')+btn('Reject selected','reject','secondary'):''}${personMode!=='photos'?selectionToolbar():''}${undoButton()}</div><p class="caption">${fmt(p?.faces||0)} associated face occurrences in ${fmt(p?.images||0)} photos.</p>${personMode==='photos'?`<div class="photo-grid">${photos.map(photoTile).join('')}</div>`:`<p class="note">${personMode==='faces'?'Select incorrect faces to unassign, or tap Use as portrait on a sharp face to change this person’s cover.':'These are suggestions, not confirmed identities. Select only faces that genuinely belong to this person.'} The original photo stays untouched.</p><div class="grid">${facelist.map(f=>faceTile(f)).join('')}</div>`}`+pager(personMode==='photos'?photos.length:facelist.length,batch.has_more)}
async function untagged(epoch){
  const requestedPage=page;
  const bound=unsortedPageBounds[requestedPage];
  // The very first page of a new visit always starts at the newest face.
  // Fallback for an old deep link without an anchor is the legacy offset.
  const query='/api/untagged?page='+requestedPage+'&with_next=1&cursor=1'+
    (requestedPage>0&&Number.isSafeInteger(bound)&&bound>0?'&before_id='+bound:'');
  const batch=await get(query);
  if(!reviewRequestCurrent(epoch,'untagged',requestedPage))return '';
  if(!batch.items.length&&page>0){page=0;unsortedPageBounds=[null];return untagged(epoch)}
  if(batch.next_before!=null&&Number.isSafeInteger(batch.next_before)&&batch.next_before>0)
    unsortedPageBounds[requestedPage+1]=batch.next_before;
  const faces=batch.items;return header('Unsorted faces','The inbox for faces that have no confirmed person profile.')+`<div class="toolbar">${btn('Assign selected to person','assign')}${btn('Ignore selected','ignore','secondary')}${selectionToolbar()}${undoButton()}</div><div class="grid">${faces.map(f=>faceTile(f)).join('')}</div>`+pager(faces.length,batch.has_more)}
async function settings(){let s=await get('/api/status');return header('Your library','Indexes and models live in private Termux storage.')+`<div class="card"><h2>Scanned folders</h2>${s.roots.map(x=>`<p class="caption">${esc(x)}</p>`).join('')||'<p>No roots configured yet.</p>'}<div class="toolbar">${btn('Add folder','addroot')}${btn('Scan all folders','scan','secondary')}</div><p class="note">Use an absolute folder path from Samsung My Files. Subfolders are included. The index automatically tracks unchanged photos.</p></div><div class="card"><h2>Export & privacy</h2><p>All photos stay in their original folders. Only photo paths, face geometry, embeddings, and your chosen names are stored locally.</p>${btn('Export tags CSV','export','secondary')}<p class="caption">Keep regular backups of ~/.monochrome_studio_v4/people.sqlite3. Unlike cloud galleries, only you have a copy unless you back it up.</p></div><div class="card"><h2>Monochrome sorting</h2><p class="caption">Continue using Monochrome Studio V3 for intelligent black-and-white sorting, move previews and undo.</p><a href="http://127.0.0.1:8765" target="_blank" rel="noopener">V3 8765 ↗</a> · <a href="http://127.0.0.1:8766" target="_blank" rel="noopener">V3 8766 ↗</a></div>`}
function openPersonPhoto(id){
  // Capture before go('photo') resets page to 0. In-memory only: no paths,
  // identifiers, or state are written to localStorage/sessionStorage.
  photoReturn=tab==='person'&&personMode==='photos'?
    {person,mode:personMode,page,scroll:window.scrollY,photo:Number(id)}:null;
  photo=Number(id);
  go('photo');
}
async function returnFromPhoto(){
  const previous=photoReturn;
  photoReturn=null;
  if(!previous){go('person');return}
  person=previous.person;personMode=previous.mode;
  tab='person';page=previous.page;selected.clear();
  const painting=render(),currentEpoch=renderEpoch;
  await painting;
  requestAnimationFrame(()=>{
    if(tab!=='person'||page!==previous.page||person!==previous.person||
       personMode!==previous.mode||renderEpoch!==currentEpoch)return;
    window.scrollTo(0,previous.scroll);
    // Keyboard review resumes at the photo that opened this detour.
    document.querySelector('[data-photo="'+previous.photo+'"]')?.focus({preventScroll:true});
  });
}
async function photoPage(){let v=await get('/api/photo?id='+photo);return header('Photograph','Indexed photo and detected faces')+`<div class="toolbar">${btn('← Back to person','backperson','secondary')}</div><div class="card"><img class="fullimg" decoding="async" alt="Photograph under review" src="${reviewPhotoThumb(photo,v.mtime_ns)}"><p class="caption">${esc(v.path)}</p><p>${v.faces.length} detected faces</p></div><div class="grid">${v.faces.map(f=>faceTile(f,false)).join('')}</div>`}
async function matchesPage(){let fid=window.currentFace;let v=window.profileSearch?await get('/api/person/suggestions?id='+person):await get('/api/similar?face='+fid);return header(window.profileSearch?'Find more appearances':'Find lookalikes',window.profileSearch?'Multiple reference photos from the named person are compared against unassigned faces.':'Closest faces to #'+fid+'; similarities are approximate, not proof of identity.')+`<div class="toolbar">${btn('← Back','backsimilar','secondary')}${btn('Assign selected to person','assign')}${btn('Ignore selected','ignore','secondary')}${selectionToolbar()}${undoButton()}</div><div class="detail"><img src="${faceThumb(fid)}"><div class="card"><h2>Similarity search</h2><p class="muted">Scores are cosine similarities, NOT probabilities. Higher scores generally indicate closer matches. Different ages, poses, lighting and historical photographs can cause missed matches or false matches. Confirm visually.</p><p class="caption">Select likely matches, then assign them to a profile. Nothing is tagged automatically.</p></div></div><h2>Nearest neighbors</h2><div class="grid">${v.map(f=>faceTile(f)).join('')}</div>`}
// Superseded asynchronous page/error responses must never overwrite the current tab.
let renderEpoch=0;
async function render(){
  const epoch=++renderEpoch;
  const requestedTab=tab;
  try{
    const html=requestedTab==='home'?await home():
      requestedTab==='people'?await people(epoch):
      requestedTab==='groups'?await groups(epoch):
      requestedTab==='group'?await groupPage(epoch):
      requestedTab==='person'?await personPage(epoch):
      requestedTab==='untagged'?await untagged(epoch):
      requestedTab==='settings'?await settings():
      requestedTab==='photo'?await photoPage():
      requestedTab==='similar'?await matchesPage():await home();
    if(epoch!==renderEpoch || requestedTab!==tab)return;
    $('#view').innerHTML=html;
    document.querySelectorAll('nav button').forEach(
      x=>x.classList.toggle('active',x.dataset.tab===requestedTab));
  }catch(error){
    if(epoch!==renderEpoch || requestedTab!==tab)return;
    $('#view').innerHTML=`<h2>Something needs attention</h2><p class="error">${esc(error.message)}</p>`;
  }
}
function go(t){if(t==='groups' && tab==='group' && groupReturn){returnToGroups();return}if(t==='people'&&tab!=='people')peopleCache=null;if(t==='untagged'&&tab!=='untagged')unsortedPageBounds=[null];tab=t;page=t==='groups'?groupResumePage:0;selected.clear();render();window.scrollTo(0,0)}
// A similarity detour is not navigation away from the review task.
// Remember anonymous-in-memory page and pixel position only; never persist
// private photo, face or person IDs in browser storage.
function rememberSimilarityContext(){
  if(tab==='similar'){
    // A thumbnail in Find lookalikes opens another inspection layer. Keep
    // original group/profile return state while supporting Back one level.
    similarityTrail.push({face:window.currentFace,profileSearch:!!window.profileSearch,
                          scroll:window.scrollY});
    if(similarityTrail.length>12)similarityTrail.shift();
    return;
  }
  similarityTrail.length=0;
  similarityReturn={tab,page,scroll:window.scrollY};
}
async function returnFromSimilarity(){
  const nested=similarityTrail.pop();
  if(nested){
    window.currentFace=nested.face;window.profileSearch=nested.profileSearch;
    const painting=render(),epoch=renderEpoch;
    await painting;
    requestAnimationFrame(()=>{
      if(tab!=='similar'||renderEpoch!==epoch||window.currentFace!==nested.face||
         window.profileSearch!==nested.profileSearch)return;
      window.scrollTo(0,nested.scroll);
    });
    return;
  }
  window.profileSearch=false;
  const previous=similarityReturn;similarityReturn=null;
  if(!previous){go(window.lastTab||'untagged');return}
  tab=previous.tab;page=previous.page;selected.clear();
  const painting=render(),epoch=renderEpoch;
  await painting;
  requestAnimationFrame(()=>{
    if(tab!==previous.tab||page!==previous.page||renderEpoch!==epoch)return;
    window.scrollTo(0,previous.scroll);
  });
}
function promptPersonName(title){let name=prompt(title);return name?.trim()||null}
async function assign(){
  const origin=reviewWriteContext(),ids=[...selected];
  if(!ids.length){toast('Select faces first');return}
  // Both explicit existing-Person chooser paths share a single durable receipt.
  // Missing Web Crypto preserves the old inspect-before-retry behavior; never
  // generate a predictable key. No identity write is automatically repeated.
  async function submitExistingFaceAssignment(targetPerson){
    if(typeof confirmAfterUnverifiedReview==='function'&&!confirmAfterUnverifiedReview())
      throw Error('Earlier review requires inspection before assigning again');
    const recovery=typeof makeReviewWriteKey==='function'&&
      typeof trackPendingReviewWrite==='function'&&
      typeof clearPendingReviewWrite==='function';
    let key=null;
    if(recovery){
      key=makeReviewWriteKey();
      if(key)trackPendingReviewWrite(key,ids.length);
    }
    const body={person_id:targetPerson,face_ids:ids};
    if(key)body.request_id=key;
    try{
      const receipt=await post('/api/person/assign',body);
      if(!Number.isSafeInteger(receipt?.count)||receipt.count!==ids.length||
         typeof receipt.undo_token!=='string'||!/^[0-9a-f]{32}$/.test(receipt.undo_token))
        throw Error('Incomplete face attachment acknowledgement · inspect People before retrying');
      if(key)clearPendingReviewWrite(key);
      return receipt;
    }catch(error){
      // A failed HTTP acknowledgement may follow a committed assignment.
      // Preserve the exact opaque key and require inspection before another
      // deliberate action, including on browsers with no Web Crypto.
      if(typeof unverifiedDirectReviewKind!=='undefined')
        unverifiedDirectReviewKind='face assignment';
      throw error;
    }
  }
  // Freeze both the source review screen and the exact visible selection.
  // A delayed chooser must not attach faces after switching to another
  // Person/group, nor clear newer selections on completion.
  async function finishAssignment(){
    if(!reviewWriteStillVisible(origin))return toast('Previous-view face assignment completed');
    for(const id of ids)selected.delete(id);
    await repaintReviewKeepingPosition();
  }
  await choosePerson({title:'Add selected faces to a person',
    explanation:`${ids.length} selected face(s). Only these faces are assigned; photos stay put.`,allowNew:true,
    confirmTitle:'Confirm face assignment',confirmLabel:'Assign faces',
    confirmText:p=>`Add exactly ${ids.length} selected faces to ${p.name}? You can go back if you tapped the wrong portrait.`,
    onChoose:async p=>{
      if(!reviewWriteStillVisible(origin))throw Error('Face assignment cancelled: view changed');
      const undoAtSubmit=lastUndoToken;
      const receipt=await submitExistingFaceAssignment(p.id);
      if(lastUndoToken===undoAtSubmit)keepReviewUndo(receipt);
      else toast('Face assignment saved · newer Undo preserved');
      if(reviewWriteStillVisible(origin))toast('Assigned selected faces to '+p.name);
      await finishAssignment();
    },
    onNew:async()=>{
      if(!reviewWriteStillVisible(origin))throw Error('Face assignment cancelled: view changed');
      const name=promptPersonName('Name the NEW person');if(!name)return;
      if(!reviewWriteStillVisible(origin))throw Error('Face assignment cancelled: view changed');
      await createReviewedPerson(name,{name,face_ids:ids},
        async p=>{
          if(!reviewWriteStillVisible(origin))throw Error('Face assignment cancelled: view changed');
          const undoAtSubmit=lastUndoToken;
      const receipt=await submitExistingFaceAssignment(p.id);
      if(lastUndoToken===undoAtSubmit)keepReviewUndo(receipt);
      else toast('Face assignment saved · newer Undo preserved');
        },
        finishAssignment);
    }})
}

function comparablePersonName(name){
  return String(name??'').normalize('NFKC').trim().replace(/\s+/g,' ').toLocaleLowerCase();
}
function newSecurePersonCreateRequestId(){
  // Each deliberate create needs its own cryptographically random operation
  // key; the server uses that key to make identical POST replay atomic.
  // Never fall back to Math.random or clock-based IDs for identity writes.
  const source=typeof crypto==='undefined'?null:crypto;
  if(!source||typeof source.getRandomValues!=='function')
    throw Error('Secure browser randomness unavailable; Create person requires a modern local browser');
  const bytes=source.getRandomValues(new Uint8Array(16));
  return Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
}
// Browser-session recovery stores ONLY opaque random operation keys. Never
// persist private names, face IDs or payloads to web storage. A lookup saying
// "unknown" is NOT evidence of rollback; the original POST might still finish.
const PENDING_PERSON_CREATE_STORAGE='atlas-v4-pending-person-create-ids-v1';
function loadPendingPersonCreateIds(){
  try{
    if(typeof sessionStorage==='undefined')return [];
    const raw=sessionStorage.getItem(PENDING_PERSON_CREATE_STORAGE);
    if(raw===null)return [];
    const ids=JSON.parse(raw);
    if(!Array.isArray(ids)||ids.length>8||
       ids.some(x=>typeof x!=='string'||!/^[0-9a-f]{32}$/.test(x))||
       new Set(ids).size!==ids.length)return [];
    return ids;
  }catch(_){return []}
}
let pendingPersonCreateIds=loadPendingPersonCreateIds();
function savePendingPersonCreateIds(){
  try{
    if(typeof sessionStorage!=='undefined')
      sessionStorage.setItem(PENDING_PERSON_CREATE_STORAGE,
                             JSON.stringify(pendingPersonCreateIds));
  }catch(_){} // Private-mode denial keeps only volatile in-memory evidence.
}
function trackPendingPersonCreateId(id){
  if(!/^[0-9a-f]{32}$/.test(id))throw Error('Invalid Person-create recovery key');
  if(pendingPersonCreateIds.includes(id))return;
  if(pendingPersonCreateIds.length>=8)
    throw Error('Eight Person creations await verification · inspect People before creating more');
  pendingPersonCreateIds.push(id);
  // Save BEFORE sending the create POST so a subsequent page reload can
  // safely ask the server if this particular operation committed.
  savePendingPersonCreateIds();
}
function clearPendingPersonCreateId(id){
  pendingPersonCreateIds=pendingPersonCreateIds.filter(x=>x!==id);
  savePendingPersonCreateIds();
}
function pendingPersonCreateToolbar(){
  if(!pendingPersonCreateIds.length)return '';
  const count=pendingPersonCreateIds.length;
  return btn('Verify '+count+' earlier creation'+(count===1?'':'s'),'verifycreates','secondary')+
    btn('Dismiss one browser reminder','dismisscreate','secondary')+
    '<span class="caption" role="status">Unverified Person creation · check before retrying</span>';
}
let unverifiedPersonCreate=null;
function confirmRetryAfterUnverifiedCreate(){
  if(unverifiedPersonCreate===null&&!pendingPersonCreateIds.length)return true;
  const subject=unverifiedPersonCreate===null
    ?'a previous Person'
    :'"'+unverifiedPersonCreate+'"';
  const message='A Create person request for '+subject+
    ' may have SAVED even though its reply failed or the browser reloaded. '+
    'Inspect the People roster or use Verify earlier creations first. '+
    'Cancel to inspect. Choose OK only AFTER checking whether it exists. '+
    'Continuing may create a separate duplicate identity.';
  if(!confirm(message)){
    toast('Create paused · inspect People before retrying');
    return false;
  }
  // Pending keys remain until a server status verification. Merely clicking
  // OK must not erase durable evidence from sessionStorage.
  unverifiedPersonCreate=null;
  return true;
}
async function verifyPendingPersonCreates(){
  // The status POST is strictly read-only, but 50 impatient taps must not
  // launch 50 competing queries or repaint over the reviewer's next screen.
  if(verifyPendingPersonCreates.running)return;
  verifyPendingPersonCreates.running=true;
  try{
  if(!pendingPersonCreateIds.length)return toast('No earlier creation requests to verify');
  const origin=reviewWriteContext();
  let committed=0,undone=0,archived=0,unknown=0;
  for(const id of [...pendingPersonCreateIds]){
    try{
      const result=await post('/api/person/create/status',{request_id:id});
      // A malformed HTTP-200 status cannot retire the ONLY opaque key
      // capable of recovering this exact Person-create operation. Valid
      // committed receipts have a positive Person ID and either the actual
      // 32-hex one-use Undo token or null after bounded Undo expiry.
      const safeCommitted=result?.status==='committed'&&
        Number.isSafeInteger(result.person_id)&&result.person_id>0&&
        (result.undo_token===null||
          (typeof result.undo_token==='string'&&/^[0-9a-f]{32}$/.test(result.undo_token)));
      if(safeCommitted){
        committed++;
        // An expired Undo is not an action. Do not replace a newer review
        // token or trust a malformed string from the recovery status route.
        if(!lastUndoToken&&reviewWriteStillVisible(origin)&&
           typeof result.undo_token==='string')
          keepReviewUndo(result);
        clearPendingPersonCreateId(id);
        unverifiedPersonCreate=null;
      }else if(result.status==='undone'){
        undone++;clearPendingPersonCreateId(id);
        unverifiedPersonCreate=null;
      }else if(result.status==='archived_or_missing'){
        archived++;clearPendingPersonCreateId(id);
        unverifiedPersonCreate=null;
      }else unknown++; // Never clear pending on unknown, invalid or slow work.
    }catch(_){unknown++}
  }
  // Preserve the People search, scroll and focused review control. A
  // status result arriving after navigation must not redraw that newer tab.
  if(reviewWriteStillVisible(origin))await repaintReviewKeepingPosition();
  toast(committed+' confirmed saved · '+undone+' undone · '+archived+
        ' archived/missing · '+unknown+' still unverified'+
        (unknown?' · inspect People before retrying':''));
  }finally{verifyPendingPersonCreates.running=false}
}
async function dismissOldestPendingPersonCreate(){
  // Stale UNKNOWN requests must not permanently lock the UI after eight
  // attempts, but dropping uncertainty evidence must be a DELIBERATE manual
  // choice. No HTTP request or SQLite write occurs in this recovery action.
  if(dismissOldestPendingPersonCreate.running)return;
  dismissOldestPendingPersonCreate.running=true;
  try{
    const oldest=pendingPersonCreateIds[0];
    if(!oldest)return toast('No Person creation reminder to dismiss');
    const approved=confirm('Dismiss ONLY this browser reminder for one unverified Person creation?\\n\\n'+
      'This does NOT cancel a server request, undo a saved Person, or prove the earlier creation failed. '+
      'Its original transaction MAY STILL COMMIT. Inspect People and check the status first. '+
      'Dismiss only if you accept that the original request may later appear.\\n\\n'+
      'Cancel preserves all evidence.');
    if(!approved)return toast('Unverified creation reminder retained');
    const origin=reviewWriteContext();
    clearPendingPersonCreateId(oldest);
    if(!pendingPersonCreateIds.length)unverifiedPersonCreate=null;
    if(reviewWriteStillVisible(origin))await repaintReviewKeepingPosition();
    toast('One browser-only reminder dismissed · People database unchanged');
  }finally{dismissOldestPendingPersonCreate.running=false}
}
// Name-only checks use the metadata roster; scoring 2k+ People portraits just
// to detect a homonym or display a merge count is unnecessary.
// Creating a profile with a name already in use is a deliberately separate
// choice. The picker identifies each same-name destination by portrait and ID;
// neither selecting a match nor typing a name silently merges two identities.
async function createReviewedPerson(name,payload,onExisting,onComplete){
  const origin=reviewWriteContext();
  const assigningFaces=Boolean(payload.cluster_id||(Array.isArray(payload.face_ids)&&payload.face_ids.length));
  const people=await get('/api/people?covers=0');
  if(!reviewWriteStillVisible(origin))return toast('Create person cancelled · view changed');
  const duplicates=people.filter(p=>comparablePersonName(p.name)===comparablePersonName(name));
  async function createSeparate(allowDuplicate){
    if(!reviewWriteStillVisible(origin))throw Error('Create person cancelled: view changed');
    if(!confirmRetryAfterUnverifiedCreate())return;
    // A new key belongs to this ONE explicit creation intent, including
    // any lost response. Never reuse it for different names or face sets.
    const request_id=newSecurePersonCreateRequestId();
    trackPendingPersonCreateId(request_id);
    // A newer reviewed action may complete while this create request waits.
    // Never let an older Person-create reply replace that newer Undo token.
    const undoAtSubmit=lastUndoToken;
    let created;
    try{
      created=await post('/api/person/create',Object.assign({},payload,{
        allow_duplicate_name:allowDuplicate,request_id
      }));
      // HTTP 200 is not enough if a corrupted/truncated handler responds
      // without the committed Person ID or its one-use Undo evidence.
      // An acknowledged expired *replay* may legitimately have null Undo,
      // but a first creation must provide the actual token.
      const id=created?.person_id;
      const token=created?.undo_token;
      const validUndo=typeof token==='string'&&/^[0-9a-f]{32}$/.test(token);
      const expiredReplay=created?.idempotent_replay===true&&token===null;
      if(!Number.isSafeInteger(id)||id<=0||(!validUndo&&!expiredReplay))
        throw Error('Incomplete person creation acknowledgement');
    }catch(error){
      // Even a rejected fetch/JSON parse can occur after SQLite commits.
      // Never show "nothing changed", fabricate Undo or silently resubmit.
      unverifiedPersonCreate=name;
      const message='Could not verify person creation: '+error.message+
        ' · inspect People before retrying';
      toast(message);
      throw Error(message);
    }
    // The server acknowledged this exact key, even when a later UI redraw
    // fails. Clear only that key; never erase concurrent pending operations.
    clearPendingPersonCreateId(request_id);
    const newerUndoExists=lastUndoToken!==undoAtSubmit;
    if(!newerUndoExists)keepReviewUndo(created);
    if(!reviewWriteStillVisible(origin))return toast(newerUndoExists
      ?'Previous-view person created · newer Undo preserved'
      :'Previous-view person creation completed');
    toast((created?.idempotent_replay
      ?'Earlier Person creation verified · inspect '+name
      :'Created separate person '+name)+
      (newerUndoExists?' · newer Undo preserved':''));
    await onComplete();
  }
  if(!duplicates.length){await createSeparate(false);return}
  await choosePerson({
    title:'This name already exists',
    query:name,
    explanation:'Choose the correct existing portrait, or explicitly create a DIFFERENT person who shares this name. No automatic merge or tag changes.',
    allowNew:true,
    confirmTitle:assigningFaces?'Attach to existing profile':'Open existing profile',
    confirmLabel:assigningFaces?'Use this existing person':'Open this person',
    confirmText:p=>(assigningFaces?'Attach reviewed faces to ':'Open the existing profile for ')+p.name+
      ' (profile #'+p.id+')? Review the portrait and face count before confirming.',
    onChoose:async p=>{
      if(!reviewWriteStillVisible(origin))throw Error('Attach cancelled: view changed');
      await onExisting(p);
      if(!reviewWriteStillVisible(origin))return toast('Previous-view attachment completed');
      toast((assigningFaces?'Attached to existing ':'Opening existing ')+p.name);await onComplete()
    },
    onNew:async()=>{
      if(!reviewWriteStillVisible(origin))throw Error('Create person cancelled: view changed');
      if(confirm('Create a DIFFERENT person also named "'+name+'"? This will NOT merge the existing profiles.')){
        await createSeparate(true);
      }
    }
  });
}

// The People-roster New Person entrypoint is not an identity assignment.
// Choosing an existing same-name profile must open it, not mutate the global
// Person ID *inside* the callback while the duplicate-name guard is running.
async function createPersonFromRoster(){
  // Rapid taps while the metadata lookup or create POST is pending must not
  // open competing name prompts. A visible confirmation picker also owns the
  // current intent until dismissed; both paths stay read-only until approved.
  if(createPersonFromRoster.launching||
     (typeof pickerState!=='undefined'&&pickerState))return;
  createPersonFromRoster.launching=true;
  try{
    // Check before even asking for a name, since the prior request may
    // already have created that Person in the same roster.
    if(!confirmRetryAfterUnverifiedCreate())return;
    const name=promptPersonName('New person name');
    if(!name)return;
    let existingProfile=null;
    await createReviewedPerson(name,{name},
      async p=>{existingProfile=Number(p.id)},
      async()=>{
        if(existingProfile!==null){
          if(tab==='people')peopleReturn={query:peopleQuery,page:peopleListPage,scroll:window.scrollY};
          person=existingProfile;
          go('person');
        }else go('people');
      });
  }finally{
    // Cancelled prompts, stale roster replies and failed writes all release.
    // An in-flight confirmed write must finish before another create starts.
    createPersonFromRoster.launching=false;
  }
}

// Creating a SEPARATE person is cheap and recoverable: one name prompt, one save.
// Never make the user pass through an existing-person picker or a second confirm.
// Attaching to an EXISTING identity has its own explicit action and review gate.
async function nameGroup(){
  // Double taps while count/roster loading must not launch duplicate prompts.
  // A visible chooser also owns the current group review intent until closed.
  if(nameGroup.launching||attachWholeGroup.launching||
     (typeof pickerState!=='undefined'&&pickerState))return;
  nameGroup.launching=true;
  try{
  const origin=reviewWriteContext(),clusterId=group;
  const countInfo=await get('/api/group/count?id='+clusterId);
  if(!reviewWriteStillVisible(origin))return toast('Name group cancelled · view changed');
  const count=Number(countInfo.count);
  if(count<1)return toast('This group has already been assigned.');
  const name=promptPersonName('Name NEW person for this reviewed group ('+count+' faces). To reuse an existing person, choose Attach group to EXISTING person instead.');
  if(!name)return;
  await createReviewedPerson(name,{name,cluster_id:clusterId,expected_count:count},
    async p=>{
      if(!reviewWriteStillVisible(origin))throw Error('Attach cancelled: group changed');
      const undoAtSubmit=lastUndoToken;
      const receipt=await submitReviewedWholeGroup(p.id,clusterId,count);
      if(lastUndoToken===undoAtSubmit)keepReviewUndo(receipt);
      else toast('Group attachment saved · newer Undo preserved');
    },async()=>{if(reviewWriteStillVisible(origin))await returnToGroups()});
  }finally{nameGroup.launching=false}
}
// Both explicit group→existing-Person flows use the same durable receipt.
// A lost reply is ambiguous; no repeated writes are sent automatically.
async function submitReviewedWholeGroup(personId,clusterId,count){
  if(typeof confirmAfterUnverifiedReview==='function'&&
     !confirmAfterUnverifiedReview())
    throw Error('Earlier review requires inspection before attaching group again');
  const recovery=typeof makeReviewWriteKey==='function'&&
    typeof trackPendingReviewWrite==='function'&&
    typeof clearPendingReviewWrite==='function';
  let key=null;
  if(recovery){
    key=makeReviewWriteKey();
    if(key)trackPendingReviewWrite(key,count);
  }
  const body={person_id:personId,cluster_id:clusterId,expected_count:count};
  if(key)body.request_id=key;
  try{
    const receipt=await post('/api/group/attach',body);
    if(!Number.isSafeInteger(receipt?.count)||receipt.count!==count||
       typeof receipt.undo_token!=='string'||!/^[0-9a-f]{32}$/.test(receipt.undo_token))
      throw Error('Incomplete group attachment acknowledgement · inspect People before retrying');
    if(key)clearPendingReviewWrite(key);
    return receipt;
  }catch(error){
    if(typeof unverifiedDirectReviewKind!=='undefined')
      unverifiedDirectReviewKind='group attachment';
    throw error;
  }
}
async function attachWholeGroup(suggestedPersonId=null){
  if(nameGroup.launching||attachWholeGroup.launching||
     (typeof pickerState!=='undefined'&&pickerState))return;
  attachWholeGroup.launching=true;
  try{
  const origin=reviewWriteContext(),clusterId=group;
  const countInfo=await get('/api/group/count?id='+clusterId);
  if(!reviewWriteStillVisible(origin))return toast('Attach group cancelled · view changed');
  const count=Number(countInfo.count);
  if(count<1)return toast('This group has already been assigned.');
  await choosePerson({title:'Attach group to an existing person',initialPersonId:suggestedPersonId,
    explanation:`${count} unassigned faces from group #${clusterId}. Verify they all depict the same person. Mixed groups must use Add selected to person.`,
    confirmTitle:'Confirm existing-person assignment',confirmLabel:'Attach faces',
    confirmText:p=>`Add ${count} unassigned faces from group #${clusterId} to ${p.name}? Check the group before confirming.`,
    onChoose:async p=>{
      if(!reviewWriteStillVisible(origin))throw Error('Attach cancelled: group changed');
      const undoAtSubmit=lastUndoToken;
      const receipt=await submitReviewedWholeGroup(p.id,clusterId,count);
      if(lastUndoToken===undoAtSubmit)keepReviewUndo(receipt);
      else toast('Group attachment saved · newer Undo preserved');
      if(!reviewWriteStillVisible(origin))return toast('Previous-view group attachment completed');
      toast(`Linked ${count} faces to ${p.name}`);await returnToGroups();
    }});
  }finally{attachWholeGroup.launching=false}
}
async function renameReviewedPerson(){
  // Keep the first deliberate rename in charge until its metadata check,
  // explicit homonym decision and server acknowledgement finish. A second
  // tap must not race an earlier name write or replace its confirmation.
  if(renameReviewedPerson.launching||
     (typeof pickerState!=='undefined'&&pickerState))return;
  renameReviewedPerson.launching=true;
  try{
    const origin=reviewWriteContext(),targetPerson=person;
    const name=promptPersonName('New person name');
    if(!name)return;
    const roster=await get('/api/people?covers=0');
    // A slow roster response cannot rename a different Person after navigation.
    if(!reviewWriteStillVisible(origin))return toast('Rename cancelled · view changed');
    const dup=roster.filter(p=>p.id!==targetPerson&&comparablePersonName(p.name)===comparablePersonName(name));
    if(dup.length){
      const labels=dup.slice(0,5).map(p=>p.name+' (#'+p.id+', '+p.faces+' faces)').join('\n');
      if(!confirm('Another person already has this name:\n'+labels+
        '\n\nKeep these as SEPARATE identities with the same name? Cancel to use Merge profiles instead.'))return;
    }
    if(!reviewWriteStillVisible(origin))return toast('Rename cancelled · view changed');
    try{
      const receipt=await post('/api/person/rename',{person_id:targetPerson,name,allow_duplicate_name:dup.length>0});
      // HTTP 200 with null/{} or "ok:false" is not a verified SQLite
      // commit. It may be incomplete AFTER a successful rename, so treat
      // it as unknown and tell the reviewer to inspect People first.
      if(receipt?.ok!==true)throw Error('Incomplete person rename acknowledgement');
    }catch(error){
      // The reply can fail after SQLite commits. Never claim the old name
      // survived or encourage a blind retry of an uncertain profile update.
      toast('Could not verify rename: '+error.message+' · check People before retrying');
      return;
    }
    if(!reviewWriteStillVisible(origin))return toast('Previous-view rename completed');
    try{
      await render();
      toast('Person name updated');
    }catch(error){
      // Once a valid server ACK was received, a later DOM/render failure
      // must not be misreported as a failed SQLite name change.
      toast('Person name saved; could not refresh view: '+error.message);
    }
  }finally{
    renameReviewedPerson.launching=false;
  }
}
async function mergePeople(){
  const origin=reviewWriteContext(),targetPerson=person;
  const current=(await get('/api/people?covers=0')).find(p=>p.id===targetPerson);
  if(!reviewWriteStillVisible(origin))return toast('Merge cancelled · view changed');
  if(!current)return toast('Merge cancelled · person no longer available');
  await choosePerson({title:'Merge duplicate People profiles',
    explanation:`Choose the source profile to merge INTO ${current.name}. The source profile and its review history will be archived, not deleted.`,
    confirmTitle:'Confirm profile merge',confirmLabel:'Merge profiles',confirmDanger:true,
    confirmText:source=>`Merge ${source.name} (${source.faces} faces) INTO ${current.name} (${current.faces} faces)? The ${source.name} profile will be archived and can be restored later. No originals or review history are deleted.`,
    onChoose:async source=>{
      if(!reviewWriteStillVisible(origin))throw Error('Merge cancelled: view changed');
      if(source.id===targetPerson)throw Error('Choose a different profile');
      const undoAtSubmit=lastUndoToken;
      const receipt=await post('/api/person/merge',{target:targetPerson,source:source.id});
      // A resolved HTTP 200 is NOT confirmation that source lineage was
      // archived. Require the exact reviewed source count, successful server
      // marker and a genuine one-use Undo receipt BEFORE changing the UI.
      // A malformed reply could follow a committed archive: keep all review
      // state and require inspection instead of blindly repeating Merge.
      if(receipt?.ok!==true||!Number.isSafeInteger(receipt.count)||
         receipt.count!==source.faces||
         typeof receipt.undo_token!=='string'||
         !/^[0-9a-f]{32}$/.test(receipt.undo_token))
        throw Error('Incomplete profile merge acknowledgement · inspect People and archived profiles before retrying');
      if(lastUndoToken===undoAtSubmit)keepReviewUndo(receipt);
      else toast('Profile merge saved · newer Undo preserved');
      if(!reviewWriteStillVisible(origin))return toast('Previous-view merge completed');
      toast('Profiles merged');await render();
    }})
}

async function restoreMergedPerson(){
  const origin=reviewWriteContext(),targetPerson=person;
  const merged=await get('/api/people/merged?target='+targetPerson);
  if(!reviewWriteStillVisible(origin))return toast('Restore cancelled · view changed');
  if(!merged.length){toast('No archived profiles to restore for this person');return}
  await choosePerson({
    people:merged,title:'Restore an archived person',
    explanation:'Restore a source profile and its original face assignments. Changed face reviews are protected; no history or photographs were deleted.',
    confirmTitle:'Restore separate person',confirmLabel:'Restore person',
    confirmText:source=>'Restore '+source.name+' (#'+source.id+') with '+source.faces+' originally assigned faces?',
    onChoose:async source=>{
      if(!reviewWriteStillVisible(origin))throw Error('Restore cancelled: view changed');
      const undoAtSubmit=lastUndoToken;
      const result=await post('/api/person/restore',{target:targetPerson,source:source.id});
      // A committed restoration must return the exact archived-face count
      // selected for this confirmation. Never retire the old merge Undo or
      // claim success after an incomplete HTTP-success response. Inspect
      // archived People before deliberately submitting a separate request.
      if(!Number.isSafeInteger(result?.count)||result.count!==source.faces)
        throw Error('Incomplete profile restore acknowledgement · inspect People and archived profiles before retrying');
      // Restore consumes the archived-merge Undo. It must NOT also clear
      // an independent newer review Undo installed while this POST waited.
      if(lastUndoToken===undoAtSubmit)lastUndoToken=null;
      else toast('Profile restored · newer Undo preserved');
      if(!reviewWriteStillVisible(origin))return toast('Previous-view restore completed');
      toast('Restored '+source.name+' · '+result.count+' faces');
      await render();
    }
  });
}
async function moveReviewPage(direction){
  // Rapid taps on the *old* Next/Previous button can arrive before the next
  // page paints. Only one turn per active render generation is intentional:
  // otherwise page 2 can be skipped without ever appearing for review.
  // A tab/person change advances renderEpoch, so it does NOT inherit the lock.
  const inFlight=moveReviewPage.pending;
  if(inFlight&&inFlight.epoch===renderEpoch&&inFlight.screen===tab&&
     (tab!=='person'||inFlight.subject===person)&&
     (tab!=='group'||inFlight.subject===group))return;
  // A replaced pager button loses focus during render. Move the keyboard
  // cursor to the first new tile, but never steal focus from newer navigation.
  if(direction==='next'){
    const next=document.querySelector('[data-action="next"]');
    if(!next||next.disabled)return;
    page++;
  }else{
    if(page<=0)return;page--;
  }
  const origin=tab,subject=tab==='person'?person:tab==='group'?group:null,
        mode=personMode;
  selected.clear();
  const painting=render(),epoch=renderEpoch;
  const turn={epoch,screen:origin,subject};
  moveReviewPage.pending=turn;
  try{await painting}
  finally{
    // A later navigation can own its own turn. The old request may finish
    // afterward, but must not unlock or focus that newer page.
    if(moveReviewPage.pending===turn)moveReviewPage.pending=null;
  }
  const sameView=()=>tab===origin&&renderEpoch===epoch&&
    (origin!=='person'||(person===subject&&personMode===mode))&&
    (origin!=='group'||group===subject);
  if(!sameView())return;
  window.scrollTo(0,0);
  const finalPage=page;
  requestAnimationFrame(()=>{
    if(!sameView()||page!==finalPage)return;
    document.querySelector('#view .tile[data-group], #view .tile[data-photo], #view .face-open')
      ?.focus({preventScroll:true});
  });
}
// Direct review writes, including Undo, must never double-submit when a
// finger taps twice while the first network request is still pending.
// Inspection, navigation and non-review actions remain independently usable.
let reviewWritePending=false;
// Volatile, token-only recovery evidence. Never store Undo tokens, Person/face
// identifiers or review payloads in web storage. A lost ACK is not rollback.
let unverifiedUndoTokens=[];
function keepUnverifiedUndoToken(token){
  if(typeof token!=='string'||!/^[0-9a-f]{32}$/.test(token))return;
  if(!unverifiedUndoTokens.includes(token))unverifiedUndoTokens.push(token);
}
function clearUnverifiedUndoToken(token){
  unverifiedUndoTokens=unverifiedUndoTokens.filter(t=>t!==token);
}
let declinedUnverifiedUndoPrompt=null;
function confirmUnverifiedUndoRetry(token){
  if(!unverifiedUndoTokens.includes(token))return true;
  // A canceled native confirmation should not be reopened dozens of times
  // by key repeat/touch tap bursts on the same screen. Rechecking status
  // or navigating to a new render generation permits a later decision.
  const attempt=token+'|'+tab+'|'+page+'|'+person+'|'+group+'|'+renderEpoch;
  if(declinedUnverifiedUndoPrompt===attempt){
    toast('Undo paused · Verify earlier Undo status before retrying');
    return false;
  }
  if(!confirm('A previous Undo request MAY HAVE COMMITTED despite its lost reply. '+
     'Inspect face tags or use Verify earlier Undo status first. '+
     'The previous request is NOT automatically retried. '+
     'Choose OK only after inspecting the current review state.')){
    declinedUnverifiedUndoPrompt=attempt;
    toast('Undo paused · inspect restored face tags before retrying');
    return false;
  }
  declinedUnverifiedUndoPrompt=null;
  // Keep the token in the recovery list until confirmed undone, even when
  // the user deliberately submits a subsequent request.
  return true;
}
async function verifyUnverifiedUndo(){
  if(verifyUnverifiedUndo.running)return;
  verifyUnverifiedUndo.running=true;
  try{
    if(!unverifiedUndoTokens.length)
      return toast('No earlier Undo outcome to verify');
    const origin=reviewWriteContext();
    let undone=0,pending=0,unknown=0;
    for(const token of [...unverifiedUndoTokens]){
      try{
        const result=await post('/api/review/undo/status',{undo_token:token});
        if(result?.status==='undone'){
          clearUnverifiedUndoToken(token);
          if(lastUndoToken===token)lastUndoToken=null;
          undone++;
        }else if(result?.status==='recorded_not_undone'){
          // A request may still be in flight, and current review state
          // could forbid Undo. Never call this "undoable" or "not saved".
          pending++;
        }else unknown++;
      }catch(_){unknown++}
    }
    if(reviewWriteStillVisible(origin))await repaintReviewKeepingPosition();
    toast(undone+' Undo confirmed · '+pending+
      ' recorded but not yet undone · '+unknown+
      ' unknown (possibly expired history)'+
      (pending+unknown?' · inspect current face tags':''));
  }finally{verifyUnverifiedUndo.running=false}
}
const DIRECT_REVIEW_WRITES=new Set(['accept','reject','unassign','ignore',
                                    'undomanual','undolastauto']);
function reviewWriteContext(){
  return {tab,page,person,group,mode:personMode,epoch:renderEpoch};
}
function reviewWriteStillVisible(start){
  return tab===start.tab&&page===start.page&&person===start.person&&
    group===start.group&&personMode===start.mode&&renderEpoch===start.epoch;
}
// A Person's aesthetic cover choice is reversible, but multiple rapid taps
// for that Person must not race and let an older POST win unexpectedly.
// Guard per Person so another profile remains independently usable.
const pendingCoverPeople=new Set();
async function setReviewedCover(faceId){
  const origin=reviewWriteContext(),targetPerson=person;
  if(tab!=='person'||personMode!=='faces'||!Number.isSafeInteger(targetPerson)||
     targetPerson<=0||!Number.isSafeInteger(faceId)||faceId<=0){
    return toast('Choose a confirmed face from this Person’s Review tags');
  }
  if(pendingCoverPeople.has(targetPerson))return;
  pendingCoverPeople.add(targetPerson);
  let acknowledged=false;
  try{
    const result=await post('/api/person/cover',{person_id:targetPerson,face_id:faceId});
    // The server returns exactly {ok:true} AFTER persisting the chosen
    // confirmed face. Truthy strings/numbers aren't commit evidence.
    if(result?.ok!==true)throw Error('Incomplete portrait acknowledgement');
    acknowledged=true;
    if(!reviewWriteStillVisible(origin)){
      toast('Portrait updated in previous view');
      return;
    }
    await repaintReviewKeepingPosition();
    toast('Profile portrait updated');
  }catch(error){
    // A lost POST response can follow a committed cover update. A rendering
    // failure AFTER an acknowledged write must not say the cover is unchanged.
    toast(acknowledged
      ?'Portrait saved; could not refresh view: '+error.message
      :'Could not verify portrait update: '+error.message+' · check Person before retrying');
  }finally{pendingCoverPeople.delete(targetPerson)}
}
async function searchCurrentPersonFace(){
  const origin=reviewWriteContext(),targetPerson=person;
  if(tab!=='person'||!Number.isSafeInteger(targetPerson)||targetPerson<=0)return;
  const faces=await get('/api/person/faces?id='+targetPerson);
  // A late search cannot open similarity for an earlier Person on a newly
  // displayed profile, even if the new profile happens to share a face.
  if(!reviewWriteStillVisible(origin))return;
  if(!faces.length){toast('Assign at least one face to this person first');return}
  rememberSimilarityContext();
  window.currentFace=faces[0].id;window.lastTab='person';window.profileSearch=true;
  go('similar');
}
// Direct review and assignment refreshes must not make a person reviewing the
// middle of a long page lose their scroll or keyboard location. Keep only
// transient viewport/focus hints, never a photo, face or People identifier in
// localStorage; a newer screen always wins over the queued focus restoration.
async function repaintReviewKeepingPosition(){
  const origin=reviewWriteContext();
  const oldY=typeof window==='undefined'?null:window.scrollY;
  const focused=typeof document==='undefined'?null:document.activeElement?.dataset;
  const action=focused?.action;
  const cover=Number(focused?.coverFace);
  const selection=Number(focused?.select);
  const focusQuery=typeof action==='string'&&/^[a-z][a-z0-9_-]{0,35}$/.test(action)?
    '#view [data-action="'+action+'"]':
    Number.isSafeInteger(cover)&&cover>0?'#view [data-cover-face="'+cover+'"]':
    Number.isSafeInteger(selection)&&selection>0?'#view [data-select="'+selection+'"]':null;
  await render();
  if(typeof requestAnimationFrame!=='function'||!Number.isFinite(oldY)||oldY<0)return;
  const painted={...origin,epoch:renderEpoch};
  requestAnimationFrame(()=>{
    if(!reviewWriteStillVisible(painted))return;
    window.scrollTo(0,oldY);
    if(focusQuery&&typeof document!=='undefined'){
      const destination=document.querySelector(focusQuery)||
        document.querySelector('#view [data-action="selectpage"]');
      destination?.focus({preventScroll:true});
    }
  });
}
async function finishDirectReview(result,submittedIds,message,start,undoAtSubmit){
  // A different reviewed operation (e.g. explicitly creating another
  // Person in a newer tab) may have completed while this POST was pending.
  // Never replace its newer one-use Undo with this older late reply.
  const newerUndoExists=lastUndoToken!==undoAtSubmit;
  if(!newerUndoExists)keepReviewUndo(result);
  if(!reviewWriteStillVisible(start)){
    toast(newerUndoExists
      ?'Review saved in previous view · newer Undo preserved'
      :'Review completed in previous view · return to inspect Undo');
    return;
  }
  // Only retire faces actually submitted, not a new selection the user
  // made while the server was processing.
  for(const id of submittedIds)selected.delete(id);
  toast(newerUndoExists?message+' · newer Undo preserved':message);
  await repaintReviewKeepingPosition();
}
// Browser-session-only hint that one reviewed face mutation may have
// COMMITTED despite a missing reply. Do not store face IDs, Person IDs or
// action payloads in browser storage or silently retry the HTTP request.
let unverifiedDirectReviewKind=null;
function confirmAfterUnverifiedReview(){
  if(unverifiedDirectReviewKind===null&&!pendingReviewWriteRequests.length)return true;
  const message='An earlier '+(unverifiedDirectReviewKind||'face review')+
    ' operation MAY HAVE SAVED even though its server reply failed. '+
    'Inspect the affected face tags or People before making another change. '+
    'Cancel to inspect. Choose OK only AFTER reviewing the current tags; '+
    'the previous request is NOT automatically retried.';
  if(!confirm(message)){
    toast('Review change paused · inspect face tags before retrying');
    return false;
  }
  unverifiedDirectReviewKind=null;
  return true;
}

/* Browser-only recovery for the four direct reviewed face writes.
   A key is kept before the POST to survive a dropped acknowledgement/reload.
   Only opaque 128-bit keys plus NON-identifying submitted counts are stored.
   A status 'unknown' NEVER proves rollback; no automatic mutation retry. */
const REVIEW_WRITE_RECOVERY_STORE='atlas-v4-reviewed-write-recovery-v1';
function loadPendingReviewWriteRequests(){
  try{
    if(typeof sessionStorage==='undefined')return [];
    const raw=sessionStorage.getItem(REVIEW_WRITE_RECOVERY_STORE);
    if(raw===null)return [];
    const items=JSON.parse(raw);
    if(!Array.isArray(items)||items.length>8||
      items.some(v=>!v||typeof v!=='object'||Array.isArray(v)||
        Object.keys(v).sort().join(',')!=='count,id'||
        typeof v.id!=='string'||!/^[0-9a-f]{32}$/.test(v.id)||
        !Number.isSafeInteger(v.count)||v.count<1||v.count>2000)||
      new Set(items.map(v=>v.id)).size!==items.length)return [];
    return items;
  }catch(_){return []}
}
let pendingReviewWriteRequests=loadPendingReviewWriteRequests();
function savePendingReviewWriteRequests(){
  try{
    if(typeof sessionStorage!=='undefined')
      sessionStorage.setItem(REVIEW_WRITE_RECOVERY_STORE,
                             JSON.stringify(pendingReviewWriteRequests));
  }catch(_){} // Private-mode denial retains volatile evidence in this tab.
}
function makeReviewWriteKey(){
  // A legacy browser without Web Crypto retains the old inspect-before-retry
  // workflow rather than issuing a weak/predictable request identifier.
  if(typeof crypto==='undefined'||typeof crypto.getRandomValues!=='function')return null;
  const bytes=crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
}
function trackPendingReviewWrite(id,count){
  if(typeof id!=='string'||!/^[0-9a-f]{32}$/.test(id)||
     !Number.isSafeInteger(count)||count<1||count>2000)
    throw Error('Invalid direct-review recovery receipt');
  if(pendingReviewWriteRequests.length>=8)
    throw Error('Eight review outcomes await inspection; verify or dismiss a reminder first');
  if(pendingReviewWriteRequests.some(item=>item.id===id))
    throw Error('Duplicate review request key; no changes made');
  pendingReviewWriteRequests.push({id,count});
  savePendingReviewWriteRequests();
}
function clearPendingReviewWrite(id){
  pendingReviewWriteRequests=pendingReviewWriteRequests.filter(item=>item.id!==id);
  savePendingReviewWriteRequests();
}
async function verifyPendingReviewWrites(){
  if(verifyPendingReviewWrites.running)return;
  verifyPendingReviewWrites.running=true;
  try{
    if(!pendingReviewWriteRequests.length)return toast('No earlier review outcomes to verify');
    const origin=reviewWriteContext(),undoBefore=lastUndoToken;
    const single=pendingReviewWriteRequests.length===1;
    let recorded=0,undone=0,expired=0,unknown=0;
    for(const item of [...pendingReviewWriteRequests]){
      try{
        const status=await post('/api/review/write/status',{request_id:item.id});
        if(!pendingReviewWriteRequests.some(x=>x.id===item.id))continue;
        const countValid=Number.isSafeInteger(status?.count)&&status.count===item.count;
        if(status?.status==='recorded_not_undone'&&countValid&&
           typeof status.undo_token==='string'&&/^[0-9a-f]{32}$/.test(status.undo_token)){
          clearPendingReviewWrite(item.id);recorded++;
          // Recover a genuine one-use Undo only if it is the SOLE pending
          // operation, still on the originating view and no newer Undo won.
          if(single&&undoBefore===null&&reviewWriteStillVisible(origin)&&
             lastUndoToken===null)
            keepReviewUndo(status);
        }else if(status?.status==='undone'&&countValid){
          clearPendingReviewWrite(item.id);undone++;
        }else if(status?.status==='recorded_unknown'&&countValid){
          clearPendingReviewWrite(item.id);expired++;
        }else unknown++; // malformed, missing, or unknown ≠ safe rollback
      }catch(_){unknown++}
    }
    if(recorded+undone+expired&&reviewWriteStillVisible(origin)){
      try{await repaintReviewKeepingPosition()}
      catch(_){toast('Review status verified; refresh the page to inspect current tags')}
    }
    toast(recorded+' committed (inspect tags) · '+undone+' undone · '+
          expired+' recorded with expired Undo · '+unknown+' still unknown; no automatic retry');
  }finally{verifyPendingReviewWrites.running=false}
}
async function dismissPendingReviewReminder(){
  if(dismissPendingReviewReminder.running)return;
  dismissPendingReviewReminder.running=true;
  try{
    if(!pendingReviewWriteRequests.length)return toast('No review reminders to dismiss');
    if(!confirm('This only dismisses ONE browser reminder; it does NOT cancel the server request. '+
      'A missing acknowledgement MAY STILL COMMIT. Inspect the affected People/face tags first. '+
      'Choose OK only to remove this reminder after inspection.'))return;
    clearPendingReviewWrite(pendingReviewWriteRequests[0].id);
    toast('One browser reminder dismissed · People database unchanged');
    if(tab==='people'||tab==='person'||tab==='groups'||tab==='group'||tab==='untagged')
      await repaintReviewKeepingPosition();
  }finally{dismissPendingReviewReminder.running=false}
}

async function finishDirectReviewedPost(url,payload,submittedIds,message,origin){
  // A failed HTTP acknowledgement does NOT prove SQLite rolled back the
  // reviewed face tags. Never auto-resubmit or fake an Undo token.
  if(!confirmAfterUnverifiedReview())return;
  const undoAtSubmit=lastUndoToken;
  let requestId=null;
  try{
    requestId=makeReviewWriteKey();
    if(requestId){
      trackPendingReviewWrite(requestId,submittedIds.length);
      payload={...payload,request_id:requestId};
    }
  }catch(error){toast('Review paused before sending: '+error.message);return}
  let result;
  try{
    result=await post(url,payload);
    // A fulfilled HTTP/JSON request is not yet evidence that a complete
    // reviewed transaction was acknowledged. Every one of these four
    // audited writes returns exactly the submitted face count AND a real
    // one-use Undo token. Missing/mismatched evidence stays unverified,
    // including a server that may have committed before its reply was lost.
    if(!Number.isSafeInteger(result?.count)||
       result.count!==submittedIds.length||
       typeof result.undo_token!=='string'||
       !/^[0-9a-f]{32}$/.test(result.undo_token))
      throw Error('Incomplete review acknowledgement');
    if(requestId)clearPendingReviewWrite(requestId);
  }catch(error){
    unverifiedDirectReviewKind=message;
    toast('Could not verify review change: '+error.message+
      ' · inspect People or face tags before retrying');
    return;
  }
  // Once a real reply arrives, the write is known to have completed even
  // if a subsequent stale-view/scroll repaint fails locally.
  try{
    await finishDirectReview(result,submittedIds,message,origin,undoAtSubmit);
  }catch(error){
    toast('Review saved; could not refresh view: '+error.message+
      ' · Undo remains available if still valid');
  }
}
async function eventAction(a){
  const guarded=DIRECT_REVIEW_WRITES.has(a);
  if(guarded&&reviewWritePending)return;
  if(guarded)reviewWritePending=true;
  try{if(a==='peopleprev'||a==='peoplenext'){peopleListPage+=a==='peoplenext'?1:-1;updatePeopleResults();window.scrollTo(0,0);return}if(a==='selectpage'){selectVisibleFaces();return}if(a==='verifyundo'){await verifyUnverifiedUndo();return}if(a==='verifyreviews'){await verifyPendingReviewWrites();return}if(a==='dismissreview'){await dismissPendingReviewReminder();return}if(a==='undomanual'){if(!lastUndoToken)return toast('Nothing to undo');if(!confirmAfterUnverifiedReview())return;const origin=reviewWriteContext(),token=lastUndoToken;if(!confirmUnverifiedUndoRetry(token))return;let undone;try{undone=await post('/api/review/undo',{undo_token:token});/* A fulfilled HTTP response with missing or invalid count is NOT proof that SQLite committed Undo. */if(!Number.isSafeInteger(undone?.count)||undone.count<0)throw Error('Incomplete Undo acknowledgement')}catch(error){/* A POST reply can vanish AFTER the server consumes its one-use token. Never tell the reviewer that Undo failed or encourage blind replay. */keepUnverifiedUndoToken(token);toast('Could not verify Undo: '+error.message+' · inspect People or face tags before retrying');return}clearUnverifiedUndoToken(token);if(lastUndoToken===token)lastUndoToken=null;if(!reviewWriteStillVisible(origin)){toast('Previous-view Undo completed');return}/* Undo restores earlier server state: it does NOT consume the reviewer's current selection. */toast('Review action undone · '+undone.count+' faces restored');try{await repaintReviewKeepingPosition()}catch(error){toast('Undo confirmed; could not refresh view: '+error.message+' · inspect restored face tags')}return}if(a==='clearselection'){clearSelectedFaces();return}if(a==='scan'||a==='group'||a==='propose'||a==='auto'){await action('/api/job/start',{kind:a},a+' started');return}if(a==='stop'){await action('/api/job/stop',{},'Pause requested');return}if(a==='undolastauto'){if(confirm('Undo latest automatic identity batch? Manual labels and photo originals are untouched.')){const origin=reviewWriteContext();let x=await post('/api/auto/undo');toast(x.count+' automatic links reverted');if(reviewWriteStillVisible(origin))await render()}return}if(a==='groupspending'){groupPendingOnly=true;groups.stableBounds=[null];page=0;selected.clear();await render();return}if(a==='groupsall'){groupPendingOnly=false;groups.stableBounds=[null];page=0;selected.clear();await render();return}if(a==='groupslargest'){groupOrder='largest';groups.stableBounds=[null];page=0;selected.clear();await render();return}if(a==='groupssmallest'){groupOrder='smallest';groups.stableBounds=[null];page=0;selected.clear();await render();return}if(a==='groupsstable'){groupOrder='stable';groups.stableBounds=[null];page=0;selected.clear();await render();return}if(a==='groupsshuffle'){groupOrder='shuffle';groups.stableBounds=[null];page=0;selected.clear();await render();return}if(a==='groupsreshuffle'){groupOrder='shuffle';groupShuffleSeed=(groupShuffleSeed+1)>>>0;groups.stableBounds=[null];page=0;selected.clear();await render();return}if(a==='next'){await moveReviewPage('next');return}if(a==='prev'){await moveReviewPage('prev');return}if(a==='backgroups'){await returnToGroups();return}if(a==='backpeople'){await returnToPeople();return}if(a==='backperson'){await returnFromPhoto();return}if(['photomode','facemode','suggestmode'].includes(a)){personMode=a==='photomode'?'photos':a==='facemode'?'faces':'suggested';page=0;selected.clear();render();return}if(a==='accept'||a==='reject'){if(!selected.size)return toast('Select suggested faces first');const origin=reviewWriteContext(),ids=[...selected];await finishDirectReviewedPost('/api/person/'+a,{person_id:person,face_ids:ids},ids,a==='accept'?'Suggestions confirmed':'Suggestions rejected',origin);return}if(a==='unassign'){if(!selected.size)return toast('Select faces first');if(confirm('Remove the selected face tags from this person?')){const origin=reviewWriteContext(),ids=[...selected];await finishDirectReviewedPost('/api/face/unassign',{person_id:person,face_ids:ids},ids,'Tags removed · Undo available',origin)}return}if(a==='backsimilar'){await returnFromSimilarity();return}if(a==='assign'){await assign();return}if(a==='ignore'){if(!selected.size)return toast('Select faces first');if(confirm('Hide selected faces from your unsorted inbox?')){const origin=reviewWriteContext(),ids=[...selected];await finishDirectReviewedPost('/api/face/ignore',{face_ids:ids},ids,'Faces hidden · Undo is available',origin)}return}if(a==='namegroup'){await nameGroup();return}if(a==='attachgroup'){await attachWholeGroup();return}if(a==='newperson'){await createPersonFromRoster();return}if(a==='verifycreates'){await verifyPendingPersonCreates();return}if(a==='dismisscreate'){await dismissOldestPendingPersonCreate();return}if(a==='rename'){await renameReviewedPerson();return}if(a==='merge'){await mergePeople();return}if(a==='restoremerge'){await restoreMergedPerson();return}if(a==='addroot'){let root=prompt('Full path of another photo folder (subfolders included):');if(root)await action('/api/roots/add',{path:root},'Folder added');return}if(a==='searchperson'){await searchCurrentPersonFace();return}if(a==='export'){let v=await post('/api/export');toast('CSV exported to '+v.path);return}if(a==='hint'){toast('Tap an individual face to see close matches');return}}catch(e){toast('Error: '+e.message)}finally{if(guarded)reviewWritePending=false}}
document.addEventListener('input',e=>{if(e.target.id==='people-query'){peopleQuery=e.target.value;peopleListPage=0;updatePeopleResults()}});
document.addEventListener('click',async e=>{let nav=e.target.closest('[data-tab]');if(nav){go(nav.dataset.tab);return}let act=e.target.closest('[data-action]');if(act){eventAction(act.dataset.action);return}let candidate=e.target.closest('[data-candidate-person]');if(candidate){try{await attachWholeGroup(Number(candidate.dataset.candidatePerson))}catch(error){toast('Could not verify group attachment: '+error.message+' · inspect People before retrying')}return}let cover=e.target.closest('[data-cover-face]');if(cover){await setReviewedCover(Number(cover.dataset.coverFace));return}let inp=e.target.closest('[data-select]');if(inp){let id=Number(inp.dataset.select);if(inp.checked)selected.add(id);else selected.delete(id);inp.closest('.tile').classList.toggle('selected',inp.checked);updateSelectionCount();return}let p=e.target.closest('[data-person]');if(p){if(tab==='people')peopleReturn={query:peopleQuery,page:peopleListPage,scroll:window.scrollY};person=Number(p.dataset.person);go('person');return}let g=e.target.closest('[data-group]');if(g){groupReturn={page,scroll:window.scrollY};group=Number(g.dataset.group);go('group');return}let ph=e.target.closest('[data-photo]');if(ph){openPersonPhoto(ph.dataset.photo);return}let f=e.target.closest('[data-face]');if(f){rememberSimilarityContext();window.profileSearch=false;window.currentFace=Number(f.dataset.face);window.lastTab=tab;go('similar');return}});
async function init(){let s=await get('/api/bootstrap');window.token=s.token;render();if(pendingPersonCreateIds.length)toast('Earlier Person creation awaits verification · open People');if(pendingReviewWriteRequests.length)toast('Earlier face review awaits verification · open People');setInterval(async()=>{if(tab==='home')render()},4000)}init();
</script></body></html>'''


class Server(ThreadingHTTPServer):
    daemon_threads=True
    allow_reuse_address=True
    def __init__(self,address,handler):
        super().__init__(address,handler)
        self.token=os.urandom(24).hex()


def preview_paged_result(rows,limit,requested):
    """Opt-in one-row lookahead; leave old array-based API consumers intact."""
    return {'items':rows[:limit], 'has_more':len(rows)>limit} if requested else rows


def preview_group_cursor_result(rows,limit):
    """An ordered historical-size/ID boundary, not a moving review count."""
    visible=rows[:limit]
    last=visible[-1] if visible else None
    return {'items':visible,'has_more':len(rows)>limit,
            'next_count':int(last['count']) if last else None,
            'next_group':int(last['id']) if last else None}


def validated_discover_cursor(raw_count,raw_group):
    """Strict positive group ID, nonnegative size, bounded signed SQLite ints."""
    if raw_count is None and raw_group is None:return None
    if raw_count is None or raw_group is None:
        raise ValueError('Incomplete stable Discover cursor')
    for value in (raw_count,raw_group):
        if not re.fullmatch(r'(?:0|[1-9][0-9]*)',value):
            raise ValueError('Invalid stable Discover cursor')
    size,group_id=int(raw_count),int(raw_group)
    if size>9223372036854775807 or not 1<=group_id<=9223372036854775807:
        raise ValueError('Invalid stable Discover cursor')
    return size,group_id


def preview_face_cursor_result(rows,limit):
    """Advance from last visible row, NEVER lookahead face."""
    visible=rows[:limit]
    return {'items':visible,'has_more':len(rows)>limit,
            'next_before':int(visible[-1]['id']) if visible else None}


def validated_face_cursor(raw):
    if raw is None:return None
    if not re.fullmatch(r'[1-9][0-9]*',raw):
        raise ValueError('Invalid face cursor')
    n=int(raw)
    if n>9223372036854775807:
        raise ValueError('Invalid face cursor')
    return n


class Handler(BaseHTTPRequestHandler):
    def log_message(self,fmt,*args):
        if not self.path.startswith('/api/status'):
            super().log_message(fmt,*args)

    def answer(self,obj,code=200):
        data=json.dumps(obj,ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Content-Length',str(len(data)))
        self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        self.end_headers();self.wfile.write(data)

    def send_html(self):
        data=HTML.encode('utf-8');self.send_response(200)
        self.send_header('Content-Type','text/html; charset=utf-8')
        self.send_header('Content-Length',str(len(data)))
        self.send_header('X-Content-Type-Options','nosniff')
        self.end_headers();self.wfile.write(data)

    def do_GET(self):
        path=urlsplit(self.path).path
        qs=parse_qs(urlsplit(self.path).query)
        val=lambda n,default=0:int(qs.get(n,[default])[0])
        with_next=val('with_next')==1
        if path=='/':return self.send_html()
        if path.startswith('/thumb/'):
            try:
                m=re.fullmatch(r'/thumb/(face|photo|portrait|review)/(\d+)',path)
                if not m:raise ValueError('Invalid thumbnail path')
                c=connect()
                try:data=get_thumbnail(c,m.group(1),int(m.group(2)))
                finally:c.close()
                self.send_response(200)
                self.send_header('Content-Type','image/jpeg')
                self.send_header('Cache-Control','private, max-age=86400')
                self.send_header('Content-Length',str(len(data)))
                self.send_header('X-Content-Type-Options','nosniff')
                self.end_headers();self.wfile.write(data)
            except (FileNotFoundError,ValueError,OSError,cv2.error) as e:self.answer({'error':str(e)},404)
            return
        c=connect()
        try:
            if path=='/api/bootstrap':v=dict(token=self.server.token)
            elif path=='/api/status':v=api_status(c)
            elif path=='/api/people':v=list_people(c,include_covers=qs.get('covers',['1'])[0]!='0')
            elif path=='/api/people/covers':
                raw=qs.get('ids',[''])[0]
                v=visible_people_covers(c,[int(x) for x in raw.split(',') if x])
            elif path=='/api/person/summary':v=get_person_summary(c,val('id'))
            elif path=='/api/people/merged':v=merged_people_for_target(c,val('target'))
            elif path=='/api/groups/total':v={'count':count_discover_groups(c,pending_only=bool(val('pending_only')))}
            elif path=='/api/groups':
                order=qs.get('order',['largest'])[0]
                cursor_requested=val('cursor')==1
                after=validated_discover_cursor(
                    qs.get('after_count',[None])[0],qs.get('after_group',[None])[0])
                if (cursor_requested or after is not None) and order!='stable':
                    raise ValueError('Stable Discover cursor requires stable order')
                if after is not None and (not cursor_requested or val('page')<1):
                    raise ValueError('Stable Discover cursor requires later cursor page')
                rows=list_groups(c,val('page'),limit=41 if with_next else 40,
                    offset_size=40 if with_next else None,order=order,seed=val('seed'),
                    pending_only=bool(val('pending_only')),after_key=after)
                v=(preview_group_cursor_result(rows,40) if cursor_requested and with_next else
                   preview_paged_result(rows,40,with_next))
            elif path=='/api/group':v=preview_paged_result(get_faces(c,'f.cluster_id=?',(val('id'),),val('page'),61 if with_next else 60,offset_size=60 if with_next else None),60,with_next)
            elif path=='/api/group/count':v={'count':c.execute('SELECT COUNT(*) FROM faces WHERE cluster_id=? AND person_id IS NULL AND ignored=0',(val('id'),)).fetchone()[0]}
            elif path=='/api/group/candidates':v=group_identity_candidates(c,val('id'))
            elif path=='/api/untagged':
                cursor_requested=val('cursor')==1
                before=validated_face_cursor(qs.get('before_id',[None])[0])
                if before is not None and not cursor_requested:
                    raise ValueError('Face cursor requires cursor=1')
                if before is not None and val('page')<1:
                    raise ValueError('Face cursor requires a later review page')
                rows=get_faces(c,'f.person_id IS NULL AND f.ignored=0',(),
                    val('page'),61 if with_next else 60,offset_size=60 if with_next else None,
                    before_id=before)
                v=(preview_face_cursor_result(rows,60) if cursor_requested and with_next else
                   preview_paged_result(rows,60,with_next))
            elif path=='/api/person':v=preview_paged_result(get_photos(c,val('id'),val('page'),61 if with_next else 60,offset_size=60 if with_next else None),60,with_next)
            elif path=='/api/person/suggested':v=preview_paged_result(person_suggestions(c,val('id'),val('page'),61 if with_next else 60,offset_size=60 if with_next else None),60,with_next)
            elif path=='/api/person/faces':
                cursor_requested=val('cursor')==1
                before=validated_face_cursor(qs.get('before_id',[None])[0])
                if before is not None and not cursor_requested:
                    raise ValueError('Face cursor requires cursor=1')
                if before is not None and val('page')<1:
                    raise ValueError('Face cursor requires a later review page')
                rows=get_faces(c,'f.person_id=?',(val('id'),),val('page'),
                    61 if with_next else 60,offset_size=60 if with_next else None,
                    before_id=before)
                v=(preview_face_cursor_result(rows,60) if cursor_requested and with_next else
                   preview_paged_result(rows,60,with_next))
            elif path=='/api/person/suggestions':v=similar_person(c,val('id'))
            elif path=='/api/similar':v=similar(c,val('face'))
            elif path=='/api/photo':
                r=c.execute('SELECT * FROM images WHERE id=?',(val('id'),)).fetchone()
                if not r:raise ValueError('Unknown image')
                v=dict(r)
                v['faces']=get_faces(c,'f.image_id=?',(val('id'),),0,100)
            else:return self.answer({'error':'Unknown API route'},404)
            self.answer(v)
        except (ValueError,sqlite3.Error) as e:self.answer({'error':str(e)},400)
        finally:c.close()

    def do_POST(self):
        origin=self.headers.get('Origin')
        if origin:
            parsed=urlsplit(origin)
            if parsed.hostname not in ('127.0.0.1','localhost'):
                return self.answer({'error':'Origin rejected'},403)
        if self.headers.get('X-Studio-Token')!=self.server.token:
            return self.answer({'error':'Missing local session token'},403)
        try:
            size=int(self.headers.get('Content-Length','0'))
            if size>32000:raise ValueError('Request too large')
            body=json.loads(self.rfile.read(size) if size else b'{}')
            if not isinstance(body,dict):raise ValueError('Expected JSON object')
            path=urlsplit(self.path).path
            if path=='/api/job/start':
                kind=body.get('kind')
                if kind not in ('scan','group','propose','auto'):raise ValueError('Unknown job kind')
                start_job(kind);return self.answer({'ok':True})
            if path=='/api/job/stop':
                STOP.set();return self.answer({'ok':True})
            if path=='/api/auto/undo':
                if JOB_LOCK.locked():raise RuntimeError('Finish active V4 indexing before undoing a batch.')
                c=connect()
                try:
                    count=undo_last_auto_batch(c)
                    return self.answer({'ok':True,'count':count})
                finally:c.close()
            if path=='/api/auto/settings':
                enabled=body.get('enabled')
                if type(enabled) is not bool:raise ValueError('Expected a boolean enabled setting')
                raise ValueError('Unreviewed auto-confirmation is disabled in this preview. Use Suggest profile matches and approve individual faces.')
            if path=='/api/roots/add':
                path_=str(add_root(body.get('path','')))
                return self.answer({'ok':True,'path':path_})
            c=connect()
            try:
                if path=='/api/person/create':v=create_person(c,body.get('name',''),body.get('face_ids',[]),body.get('cluster_id'),body.get('expected_count'),body.get('allow_duplicate_name',False),undo=True,request_id=body.get('request_id'))
                elif path=='/api/person/create/status':v=person_create_request_status(c,body.get('request_id'))
                elif path=='/api/review/write/status':v=review_write_request_status(c,body.get('request_id'))
                elif path=='/api/person/assign':v=assign_faces(c,int(body['person_id']),body['face_ids'],undo=True,request_id=body.get('request_id'))
                elif path=='/api/group/attach':v=attach_group_to_person(c,body['person_id'],body['cluster_id'],body['expected_count'],undo=True,request_id=body.get('request_id'))
                elif path=='/api/review/undo':v={'count':undo_manual_review(c,body.get('undo_token'))}
                elif path=='/api/review/undo/status':v=manual_review_undo_status(c,body.get('undo_token'))
                elif path=='/api/person/cover':v={'ok':set_person_cover(c,body['person_id'],body['face_id'])}
                elif path=='/api/person/accept':v=accept_suggestions(c,int(body['person_id']),body['face_ids'],undo=True,request_id=body.get('request_id'))
                elif path=='/api/person/reject':v=reject_suggestions(c,int(body['person_id']),body['face_ids'],undo=True,request_id=body.get('request_id'))
                elif path=='/api/face/ignore':v=ignore_faces(c,body.get('face_ids',[]),undo=True,request_id=body.get('request_id'))
                elif path=='/api/face/unassign':v=unassign_faces(c,body['person_id'],body.get('face_ids',[]),undo=True,request_id=body.get('request_id'))
                elif path=='/api/person/rename':v={'ok':rename_person(c,body['person_id'],body.get('name',''),body.get('allow_duplicate_name',False))}
                elif path=='/api/person/merge':v=merge_people_profiles(c,body['target'],body['source'])
                elif path=='/api/person/restore':v={'count':restore_profile_merge(c,body['source'],body.get('target'))}
                elif path=='/api/export':
                    dst=Path.home()/'storage'/'downloads'/'Monochrome_Studio_people.csv'
                    if not dst.parent.is_dir():dst=STATE/'people_export.csv'
                    export_csv(c,dst);v={'path':str(dst)}
                else:return self.answer({'error':'Unknown POST route'},404)
                if path in ('/api/review/undo','/api/person/create','/api/person/assign',
                            '/api/group/attach','/api/person/accept',
                            '/api/face/ignore','/api/face/unassign',
                            '/api/person/rename','/api/person/merge','/api/person/restore'):
                    try: maybe_export_native_sync(c)
                    except (OSError,ValueError) as export_error:
                        v['native_sync_warning']=str(export_error)
                self.answer(v)
            finally:c.close()
        except (ValueError,KeyError,sqlite3.Error,OSError,RuntimeError) as e:
            self.answer({'error':str(e)},400)


def serve(port):
    connect().close()
    server=Server(('127.0.0.1',port),Handler)
    print(f'Monochrome Studio V4 is running at http://127.0.0.1:{port}',flush=True)
    print('Photo originals are read-only. Ctrl+C closes this dashboard.',flush=True)
    try:server.serve_forever(poll_interval=.25)
    except KeyboardInterrupt:STOP.set()
    finally:server.server_close()


def main():
    p=argparse.ArgumentParser(description='Monochrome Studio V4: local face finder and people albums')
    s=p.add_subparsers(dest='command',required=True)
    s.add_parser('install-models')
    roots=s.add_parser('roots');roots.add_argument('action',choices=['add','list','remove']);roots.add_argument('path',nargs='?')
    sub=s.add_parser('scan');sub.add_argument('--root',action='append',help='Extra one-off scan root, can repeat');sub.add_argument('--limit',type=int,help='Analyze at most N new or changed images');sub.add_argument('--force',action='store_true',help='Recompute results even when photos are unchanged')
    sub=s.add_parser('group');sub.add_argument('--threshold',type=float,default=.64)
    sub=s.add_parser('propose');sub.add_argument('--threshold',type=float,default=.44)
    sub=s.add_parser('auto-match',help='Read-only historical auto-match scoring; never assigns identities')
    sub.add_argument('--limit',type=int,default=25000)
    sub.add_argument('--preview',action='store_true',help='Required: only count matches, never assign or advance cursor')
    sub.add_argument('--restart',action='store_true',help='Rescan all old unassigned faces from the start')
    s.add_parser('auto-undo',help='Undo the latest automatically linked batch')
    sub=s.add_parser('native-sync',help='Enable/disable a checksum-sealed People CSV for native ATLAS')
    sub.add_argument('--output',help='Stable path for native sync CSV; enable and export once')
    sub.add_argument('--disable',action='store_true')
    sub=s.add_parser('auto-eval',help='Read-only holdout evaluation of your own confirmed faces')
    sub.add_argument('--limit',type=int,default=500)
    sub=s.add_parser('auto-calibrate',help='Compare fixed recognition policies on SAME private read-only holdout')
    sub.add_argument('--limit',type=int,default=500)
    sub=s.add_parser('serve');sub.add_argument('--port',type=int,default=8767)
    sub=s.add_parser('status');sub.add_argument('--port',type=int,default=8767,
        help='Read live job progress from the V4 web server (default 8767)')
    sub=s.add_parser('export');sub.add_argument('--output',default=str(Path.home()/'studio_people_export.csv'))
    sub=s.add_parser('backup');sub.add_argument('--output',default=str(Path.home()/'studio_people_backup.sqlite3'))
    sub=s.add_parser('doctor')
    sub=s.add_parser('cache-prune')
    args=p.parse_args()
    if args.command=='install-models':install_models()
    elif args.command=='roots':
        if args.action=='list':
            for x in config()['roots']:print(x)
        else:
            if not args.path:p.error('Provide folder path')
            if args.action=='add':print('Added',add_root(args.path))
            else:
                c=config();c['roots']=[x for x in c['roots'] if x!=str(Path(args.path).expanduser().resolve())]
                save_config(c);print('Updated configured roots')
    elif args.command=='scan':
        try:scan(config()['roots']+list(args.root or []),limit=args.limit,force=args.force)
        except KeyboardInterrupt: STOP.set();print('\nInterrupted. Completed images remain checkpointed.')
    elif args.command=='group':
        if not .35<=args.threshold<=.9:p.error('Threshold must be between .35 and .90')
        c=connect()
        try:print('Provisional groups:',groups(c,threshold=args.threshold))
        except KeyboardInterrupt:STOP.set();print('\nInterrupted; earlier groups left intact.')
        finally:c.close()
    elif args.command=='propose':
        c=connect()
        try:print('Candidate face tags:',propose(c,threshold=args.threshold))
        except KeyboardInterrupt:STOP.set();print('\nInterrupted; saved recommendations remain available.')
        finally:c.close()
    elif args.command=='auto-match':
        if not args.preview:
            p.error('Auto-confirmation disabled in review-first preview. Use propose for unconfirmed suggestions or auto-match --preview for read-only scoring.')
        with exclusive_job():
            c=connect()
            try:print(json.dumps(auto_identity_backfill(c,limit=args.limit,commit=False,restart=args.restart),indent=2))
            finally:c.close()
    elif args.command=='native-sync':
        if bool(args.output)==bool(args.disable):
            p.error('Specify exactly one of --output or --disable')
        settings=config()
        if args.disable:
            settings.pop('native_sync_export_path',None)
            save_config(settings)
            print('Disabled native People auto-export; existing CSV untouched.')
        else:
            output=Path(args.output).expanduser().resolve()
            if not output.parent.is_dir():p.error('Native sync output folder missing')
            if output.suffix.lower()!='.csv':p.error('Native sync output must be a CSV')
            settings['native_sync_export_path']=str(output);save_config(settings)
            c=connect()
            try:print('Exported People links:',export_native_sync(c,output), '→',output)
            finally:c.close()
    elif args.command in ('auto-eval','auto-calibrate'):
        c=connect()
        try:print(json.dumps(evaluate_auto_identity(c,args.limit,
            policy_frontier=args.command=='auto-calibrate'),indent=2))
        finally:c.close()
    elif args.command=='auto-undo':
        c=connect()
        try:print('Auto-linked faces reverted:',undo_last_auto_batch(c))
        finally:c.close()
    elif args.command=='serve':serve(args.port)
    elif args.command=='status':
        # JOB is process-local. This CLI process cannot inspect work running in
        # a different 'serve' process by looking at its own in-memory JOB.
        # Prefer the already-running local server's read-only status endpoint.
        try:
            with urllib.request.urlopen(
                f'http://127.0.0.1:{args.port}/api/status', timeout=2
            ) as response:
                live=json.load(response)
            if (not isinstance(live,dict) or
                not str(live.get('version','')).startswith('4.') or
                not isinstance(live.get('job'),dict)):
                raise ValueError('The selected port does not host Studio V4')
            print(json.dumps(live,indent=2))
        except (OSError,ValueError) as exc:
            c=connect()
            try:
                snapshot=api_status(c)
            finally:c.close()
            snapshot['job']={
                'phase':'unknown', 'message':(
                    f'V4 server on port {args.port} unreachable ({exc}). '
                    'This command cannot determine whether a job is running '
                    'in another Termux process. Open V4 Overview or inspect running processes.'
                ),
                'done':0, 'total':0, 'speed':0, 'eta':None,
                'started_at':None
            }
            print(json.dumps(snapshot,indent=2))
    elif args.command=='export':
        c=connect()
        try:export_csv(c,args.output)
        finally:c.close()
    elif args.command=='backup':backup_database(args.output)
    elif args.command=='cache-prune':
        removed,size=prune_cache();print(f'Deleted {removed} old cached thumbnails; {size/1048576:.1f} MB remain')
    elif args.command=='doctor':
        print('OpenCV:',cv2.__version__,'NumPy:',np.__version__)
        print('FaceDetectorYN:',hasattr(cv2,'FaceDetectorYN'))
        print('FaceRecognizerSF:',hasattr(cv2,'FaceRecognizerSF'))
        for name,(section,sha,_size) in MODEL_INFO.items():
            path=local_model(name)
            print(name,':',path,'(not installed)' if not path.is_file() else
                  ('(verified)' if digest(path)==sha else '(present; checksum differs)'))
        print('Configured folders:',*config()['roots'],sep='\n- ')


if __name__=='__main__':
    try:main()
    except (RuntimeError,ValueError,FileNotFoundError) as e:
        print('ERROR:',e,file=sys.stderr)
        sys.exit(2)
