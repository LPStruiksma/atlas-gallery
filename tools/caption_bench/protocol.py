#!/usr/bin/env python3
"""Offline, fail-closed caption benchmark orchestration."""
import argparse, hashlib, json, math, resource, statistics, subprocess, sys, time
from pathlib import Path

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1048576),b''): h.update(chunk)
    return h.hexdigest()

def manifest(path):
    rows=[]; seen=set()
    for n,line in enumerate(path.read_text(encoding='utf-8').splitlines(),1):
        if not line.strip(): continue
        row=json.loads(line); required={'id','image','sha256','rights_id','rights_url'}
        if required-row.keys(): raise ValueError(f'line {n}: missing {sorted(required-row.keys())}')
        if row['id'] in seen: raise ValueError(f"line {n}: duplicate id")
        seen.add(row['id']); image=Path(row['image'])
        if not image.is_absolute() or image.is_symlink() or not image.is_file(): raise ValueError(f'line {n}: image must be absolute regular non-symlink')
        if sha(image)!=row['sha256']: raise ValueError(f'line {n}: SHA-256 mismatch')
        if not str(row['rights_id']).strip() or not str(row['rights_url']).strip(): raise ValueError(f'line {n}: rights metadata empty')
        rows.append(row)
    if not rows: raise ValueError('manifest is empty')
    return rows

def fixtures(out):
    out.mkdir(parents=True,exist_ok=False); rows=[]
    for v in range(2):
        p=(out/f'geometry-{v}.ppm').resolve(); pix=bytearray()
        for y in range(32):
            for x in range(32): pix.extend((30,90+20*v,210) if 6+v<=x<26 and 7<=y<25-v else (245,245,245))
        p.write_bytes(b'P6\n32 32\n255\n'+pix)
        rows.append({'id':f'synthetic-{v}','image':str(p),'sha256':sha(p),'rights_id':'repository-generated-synthetic-fixture','rights_url':'docs/visual-search/CAP_0_FEASIBILITY.md#dataset-licensing'})
    m=out/'manifest.jsonl'; m.write_text(''.join(json.dumps(r,sort_keys=True)+'\n' for r in rows)); print(m)

def bench(a):
    rows=manifest(a.manifest)
    if a.command and a.command[0]=='--': a.command=a.command[1:]
    if not a.command: raise ValueError('adapter command required after --')
    lat=[]; a.output.parent.mkdir(parents=True,exist_ok=True)
    with a.output.open('x',encoding='utf-8') as sink:
        for iteration in range(a.warmups+a.runs):
            for row in rows:
                before=resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss; start=time.perf_counter_ns()
                done=subprocess.run(a.command+['--image',row['image'],'--prompt',a.prompt],capture_output=True,text=True,timeout=a.timeout,check=False)
                elapsed=time.perf_counter_ns()-start; after=resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
                if iteration<a.warmups: continue
                caption=None; error=done.stderr.strip()
                try:
                    payload=json.loads(done.stdout)
                    if not isinstance(payload.get('caption'),str): raise ValueError('caption must be string')
                    caption=payload['caption']
                except (json.JSONDecodeError,AttributeError,ValueError) as e: error=f'invalid adapter output: {e}; {error}'.strip()
                sink.write(json.dumps({'id':row['id'],'iteration':iteration-a.warmups,'elapsed_ns':elapsed,'child_maxrss_delta_kib':max(0,after-before),'returncode':done.returncode,'caption':caption,'error':error},ensure_ascii=False,sort_keys=True)+'\n'); lat.append(elapsed/1e6)
    ordered=sorted(lat); print(json.dumps({'recorded':len(lat),'p50_ms':statistics.median(lat),'p95_ms':ordered[math.ceil(.95*len(ordered))-1],'output':str(a.output)},sort_keys=True))

def cli():
    p=argparse.ArgumentParser(); s=p.add_subparsers(dest='action',required=True)
    f=s.add_parser('make-fixtures'); f.add_argument('--out',type=Path,required=True)
    c=s.add_parser('check-manifest'); c.add_argument('manifest',type=Path)
    b=s.add_parser('bench'); b.add_argument('manifest',type=Path); b.add_argument('--warmups',type=int,default=5); b.add_argument('--runs',type=int,default=30); b.add_argument('--timeout',type=float,default=300); b.add_argument('--prompt',required=True); b.add_argument('--output',type=Path,required=True); b.add_argument('command',nargs=argparse.REMAINDER)
    z=s.add_parser('capacity'); z.add_argument('--images',type=int,default=150000); z.add_argument('--images-per-hour',type=float,required=True); z.add_argument('--retry-multiplier',type=float,default=1); z.add_argument('--caption-bytes',type=int,required=True); z.add_argument('--metadata-bytes',type=int,required=True); z.add_argument('--model-bytes',type=int,required=True)
    return p.parse_args()

def main():
    a=cli()
    try:
        if a.action=='make-fixtures': fixtures(a.out)
        elif a.action=='check-manifest': print(json.dumps({'valid':len(manifest(a.manifest))}))
        elif a.action=='bench':
            if a.warmups<0 or a.runs<=0 or a.timeout<=0: raise ValueError('invalid run counts/timeout')
            bench(a)
        else:
            if a.images<0 or a.images_per_hour<=0 or a.retry_multiplier<0 or min(a.caption_bytes,a.metadata_bytes,a.model_bytes)<0: raise ValueError('invalid capacity input')
            print(json.dumps({'initial_hours':a.images*a.retry_multiplier/a.images_per_hour,'storage_bytes':a.model_bytes+a.images*(a.caption_bytes+a.metadata_bytes),'incremental_hours_per_image':a.retry_multiplier/a.images_per_hour},sort_keys=True))
    except (OSError,ValueError,json.JSONDecodeError,subprocess.SubprocessError) as e: print(f'error: {e}',file=sys.stderr); return 2
    return 0
if __name__=='__main__': raise SystemExit(main())
