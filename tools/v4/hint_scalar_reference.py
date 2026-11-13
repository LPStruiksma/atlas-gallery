"""Frozen PRE-vectorization Discover hint scorer for controlled A/B and parity tests.

This is test-only evidence logic. It reads synthetic, supplied SQLite rows,
never labels People, and is NOT used by the live V4 server.
Keep the scalar baseline independent of optimized implementation changes.
"""
import numpy as np

def scalar_reference(c,ids):
    """Frozen pre-optimization algorithm, independently executed against SQL."""
    ids=list(dict.fromkeys(ids))[:80]
    if not ids:return {}
    marks=','.join('?' for _ in ids)
    groups=c.execute(f'''SELECT cluster_id,embedding,image_id FROM (
        SELECT cluster_id,embedding,image_id,
        ROW_NUMBER() OVER(PARTITION BY cluster_id
          ORDER BY COALESCE(quality,0) DESC,id ASC) AS rank_in_group
        FROM faces
        WHERE cluster_id IN ({marks}) AND person_id IS NULL AND ignored=0
          AND LENGTH(embedding)=512
    ) WHERE rank_in_group<=5 ORDER BY cluster_id,rank_in_group''',ids).fetchall()
    profiles=c.execute('''SELECT person_id,name,embedding,image_id FROM (
        SELECT f.person_id,p.name,f.embedding,f.image_id,
        ROW_NUMBER() OVER(PARTITION BY f.person_id
          ORDER BY COALESCE(f.quality,0) DESC,f.id ASC) AS rank_in_profile
        FROM faces f JOIN people p ON p.id=f.person_id
        WHERE p.merged_into IS NULL AND f.person_id IS NOT NULL
          AND f.review='confirmed' AND f.ignored=0 AND LENGTH(f.embedding)=512
    ) WHERE rank_in_profile<=4 ORDER BY person_id''').fetchall()
    gg=[x for x in groups if np.isfinite(np.frombuffer(x['embedding'],dtype=np.float32)).all()]
    pp=[x for x in profiles if np.isfinite(np.frombuffer(x['embedding'],dtype=np.float32)).all()]
    if not gg or not pp:return {}
    gm=np.stack([np.frombuffer(x['embedding'],dtype=np.float32) for x in gg])
    pm=np.stack([np.frombuffer(x['embedding'],dtype=np.float32) for x in pp])
    similarity=gm@pm.T
    group_indices={}
    for i,g in enumerate(gg):group_indices.setdefault(int(g['cluster_id']),[]).append(i)
    people={}
    for i,p in enumerate(pp):
        person=people.setdefault(int(p['person_id']),{'name':p['name'],'indices':[]})
        person['indices'].append(i)
    out={}
    for group_id,face_indices in group_indices.items():
        matches=[]
        for person_id,profile in people.items():
            sub=similarity[np.ix_(face_indices,profile['indices'])].copy()
            for r,face_pos in enumerate(face_indices):
                for k,person_pos in enumerate(profile['indices']):
                    if gg[face_pos]['image_id']==pp[person_pos]['image_id']:
                        sub[r,k]=-1.
            best=np.max(sub,axis=1)
            distinct={int(gg[face_indices[j]]['image_id'])
                      for j in range(len(face_indices)) if best[j]>=.50}
            if len(distinct)<2:continue
            strong=np.sort(best[best>=.50])
            score=round(float(np.mean(strong[-min(3,len(strong)):])),3)
            matches.append(dict(person_id=person_id,name=profile['name'],
                supporting_photos=len(distinct),similarity=score,
                note='Possible already named person; review every face'))
        matches.sort(key=lambda row:(-row['supporting_photos'],-row['similarity'],row['person_id']))
        out[group_id]=matches[:2]
    return out

