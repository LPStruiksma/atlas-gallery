#!/usr/bin/env python3
"""Synthetic-only V4 People/Discover scale evidence; no real photos or database.

Default: 150,000 distinct fictional indexed photographs, 2,500 candidate
groups, 500 confirmed People. CI uses --smoke to avoid expensive runners.

Outputs measured synthetic medians/P90s, response sizes, Python allocation
peaks, SQLite DB bytes and runtime provenance, not Android FPS or accuracy.
Only a temporary SQLite database is created; its path dies with the context.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import platform
import sqlite3
import statistics
import sys
import tempfile
import time
import tracemalloc
import types

import numpy as np

from hint_scalar_reference import scalar_reference

# The benchmark measures the real offline V4 query code. cv2 is unnecessary
# for these SQLite/NumPy paths and is deliberately not downloaded in CI.
sys.modules['cv2']=types.ModuleType('cv2')
SOURCE=Path(__file__).with_name('monochrome_studio_v4_people_ux_preview.py')
spec=importlib.util.spec_from_file_location('atlas_v4_benchmark_queries',SOURCE)
v4=importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)


def vector(index):
    """Fictional, finite, normalized vectors; NOT learned identity features."""
    x=np.zeros(128,dtype=np.float32)
    x[index%128]=1.0
    return x.tobytes()


def timed_sample(operation,repeat):
    elapsed=[]
    allocations=[]
    result=None
    for _ in range(repeat):
        tracemalloc.reset_peak()
        before=tracemalloc.get_traced_memory()[0]
        start=time.perf_counter()
        result=operation()
        elapsed.append((time.perf_counter()-start)*1000)
        allocations.append(max(0,tracemalloc.get_traced_memory()[1]-before)/1024**2)
    ordered=sorted(elapsed)
    return {
        'median_ms':round(statistics.median(ordered),2),
        'max_ms':round(max(elapsed),2),
        'samples':len(elapsed),
        'python_alloc_peak_mib':round(max(allocations),2),
    },result


def paired_hint_benchmark(c, group_ids, repeats):
    """Same SQLite connection, paired AB/BA order, exact-output hard gate.

    Timings include both SQL reads and the CPU hint scorer, so differ
    materially from microbenchmarks of a prebuilt similarity matrix.
    """
    implementations={
        'scalar_baseline':lambda:scalar_reference(c,group_ids),
        'vectorized':lambda:v4.group_list_existing_people_hints(c,group_ids),
    }
    timings={name:[] for name in implementations}
    peaks={name:[] for name in implementations}
    before=c.total_changes
    for index in range(repeats):
        pair={}
        order=('scalar_baseline','vectorized') if index%2==0 else (
            'vectorized','scalar_baseline')
        for name in order:
            tracemalloc.reset_peak()
            before_heap=tracemalloc.get_traced_memory()[0]
            started=time.perf_counter()
            pair[name]=implementations[name]()
            timings[name].append((time.perf_counter()-started)*1000)
            peaks[name].append(max(0,tracemalloc.get_traced_memory()[1]-
                                   before_heap)/1024**2)
        if pair['scalar_baseline']!=pair['vectorized']:
            raise AssertionError(
                'Same-fixture scalar vs vectorized Discover hint output differs')
    if c.total_changes!=before:
        raise AssertionError('Discover hint A/B benchmark changed People identities')
    output={}
    for name in implementations:
        output[name]={
            'median_ms':round(statistics.median(timings[name]),2),
            'max_ms':round(max(timings[name]),2),
            'python_alloc_peak_mib':round(max(peaks[name]),2),
            'samples':repeats,
        }
    old=statistics.median(timings['scalar_baseline'])
    new=statistics.median(timings['vectorized'])
    output['speedup_ratio_same_run']=round(old/new,2) if new>0 else None
    output['equal_outputs_each_pair']=True
    output['balanced_ab_ba_order']=repeats>=2
    output['visible_groups_requested']=len(group_ids)
    output['interpretation']='Same-process paired synthetic SQLite + hint comparison, '        'not real phone latency or identity accuracy. Memory covers Python allocations only.'
    return output


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--faces',type=int,default=150_000)
    parser.add_argument('--groups',type=int,default=2_500)
    parser.add_argument('--people',type=int,default=500)
    parser.add_argument('--samples',type=int,default=3)
    parser.add_argument('--smoke',action='store_true',
                        help='12k synthetic photos, 200 groups, 80 People, 2 samples')
    args=parser.parse_args()
    if args.smoke:
        args.faces,args.groups,args.people,args.samples=12_000,200,80,2
    if not (args.faces>=args.people*4+args.groups
            and 1<=args.people<=5_000 and 1<=args.groups<=20_000
            and args.faces<=300_000 and 1<=args.samples<=10):
        parser.error('Use people*4+groups <= faces <= 300000, people 1..5000, '
                     'groups 1..20000 and samples 1..10')
    num_faces=args.faces
    profiles=args.people
    groups=args.groups
    vectors=[vector(i) for i in range(128)]
    with tempfile.TemporaryDirectory(prefix='atlas-v4-fictional-scale-') as root:
        v4.STATE=Path(root)
        v4.DB=v4.STATE/'synthetic-only.sqlite3'
        v4.DB_READY_PATH=None
        c=v4.connect()
        t0=time.perf_counter()
        with c:
            c.executemany(
                "INSERT INTO people(id,name,created_at) VALUES(?,?,?)",
                ((i,f'Fictional Person {i}','fixture') for i in range(1,profiles+1)))
            c.executemany(
                "INSERT INTO clusters(id,count,representative,created_at) VALUES(?,?,?,?)",
                ((i,(num_faces-profiles*4)//groups+1,i,'fixture')
                 for i in range(1,groups+1)))
            c.executemany(
                "INSERT INTO images(id,path,size,mtime_ns,state) VALUES(?,?,?,?,?)",
                ((i,f'/synthetic/never-real-{i}.jpg',1024,10,'ready')
                 for i in range(1,num_faces+1)))
            c.executemany("""INSERT INTO faces(
              id,image_id,face_index,x,y,w,h,embedding,quality,cluster_id,
              person_id,ignored,review) VALUES(?,?,0,0,0,40,40,?,?,?,?,0,?)""",
                ((i,i,vectors[i%128],(i%100)/100,
                  None if i<=profiles*4 else (i-profiles*4-1)%groups+1,
                  (i-1)%profiles+1 if i<=profiles*4 else None,
                  'confirmed' if i<=profiles*4 else 'unreviewed')
                 for i in range(1,num_faces+1)))
        setup_ms=round((time.perf_counter()-t0)*1000,1)
        changed=c.total_changes
        def stable_first():
            return v4.preview_group_cursor_result(v4.list_groups(
                c,page=0,limit=41,order='stable',pending_only=True,offset_size=40),40)
        def stable_next():
            first=stable_first_cache
            anchor=(first['next_count'],first['next_group'])
            return v4.preview_group_cursor_result(v4.list_groups(
                c,page=1,limit=41,order='stable',pending_only=True,
                offset_size=40,after_key=anchor),40)
        def dynamic_first():
            return v4.preview_paged_result(v4.list_groups(
                c,page=0,limit=41,order='largest',pending_only=True,
                offset_size=40),40,True)
        def unsorted_first():
            return v4.preview_face_cursor_result(v4.get_faces(
                c,'f.person_id IS NULL AND f.ignored=0',(),0,61,
                offset_size=60),60)
        tracemalloc.start()
        metrics={}
        metrics['discover_stable_first'],stable_first_cache=timed_sample(stable_first,args.samples)
        metrics['discover_stable_next'],stable_next_cache=timed_sample(stable_next,args.samples)
        metrics['discover_largest_remaining'],dynamic_cache=timed_sample(
            dynamic_first,args.samples)
        metrics['unsorted_faces_first'],face_cache=timed_sample(unsorted_first,args.samples)
        metrics['people_metadata'],roster=timed_sample(
            lambda:v4.list_people(c,include_covers=False),args.samples)
        visible_group_ids=[int(row['id']) for row in stable_first_cache['items']]
        hints_ab=paired_hint_benchmark(c,visible_group_ids,args.samples)
        tracemalloc.stop()
        if not (len(stable_first_cache['items'])==40
                and len(stable_next_cache['items'])==40
                and len(dynamic_cache['items'])==40
                and len(face_cache['items'])==60
                and len(roster)==profiles):
            raise AssertionError('Unexpected bounded synthetic result shape')
        if set(x['id'] for x in stable_first_cache['items']).intersection(
               x['id'] for x in stable_next_cache['items']):
            raise AssertionError('Stable forward cursor duplicated a group')
        if c.total_changes!=changed:
            raise AssertionError('Read-only V4 benchmark changed identity data')
        if c.execute('PRAGMA foreign_key_check').fetchall():
            raise AssertionError('Synthetic fixture violates SQLite foreign keys')
        report={
            'benchmark':'atlas-v4-synthetic-review-query-scale-v2',
            'source':'monochrome_studio_v4_people_ux_preview.py (same folder)',
            'environment':{
                'python':platform.python_version(),'sqlite':sqlite3.sqlite_version,
                'numpy':np.__version__,'platform':platform.platform()},
            'fixture':{'photos':num_faces,'faces':num_faces,
                       'candidate_groups':groups,'named_people':profiles,
                       'confirmed_faces':profiles*4,'private_data':False,
                       'model_inference':False},
            'setup_ms':setup_ms,
            'db_file_mib':round(v4.DB.stat().st_size/1024**2,2),
            'operations':metrics,
            'controlled_hint_ab':hints_ab,
            'interpretation':'Synthetic local SQLite query timing and same-process paired hint A/B; no Android phone, '
                'image decode, animation, TalkBack, battery, recognition precision or '
                'on-device 150k-photo latency established.',
        }
        print(json.dumps(report,indent=2,sort_keys=True))
        c.close()


if __name__=='__main__':
    main()
