"""Synthetic-only multi-source recall@K, latency and storage benchmark for ORB retrieval."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import statistics
from tempfile import TemporaryDirectory
import time

from PIL import Image

from .benchmark import rotate, textured
from .retrieval import index_folder, shortlist

ANGLES = (-150, -95, -44, 26, 68, 111, 163)


def run(distractors: int = 80, anchors: int = 8):
    if not 1 <= distractors <= 2000 or not 1 <= anchors <= 30:
        raise ValueError('distractors 1..2000, anchors 1..30')
    with TemporaryDirectory() as temp:
        root = Path(temp)
        corpus = root / 'synthetic'
        corpus.mkdir()
        origins = {}
        for seed in range(101, 101 + anchors):
            img = textured(seed, 600)
            origins[seed] = img
            Image.fromarray(img).save(corpus / f'origin_{seed}.png')
        for seed in range(500, 500 + distractors):
            Image.fromarray(textured(seed, 600)).save(corpus / f'distractor_{seed}.png')
        database = root / 'lineage.sqlite'
        started = time.perf_counter()
        report = index_folder(corpus, database, limit=distractors + anchors)
        indexing_ms = 1000 * (time.perf_counter() - started)
        ranks, query_ms = [], []
        for seed, original in origins.items():
            for angle in ANGLES:
                path = root / f'query_{seed}_{angle}.png'
                Image.fromarray(rotate(original, angle)[110:520, 105:530]).save(path)
                start = time.perf_counter()
                hits = shortlist(path, database, top_k=20)
                query_ms.append(1000 * (time.perf_counter() - start))
                wanted = str(corpus / f'origin_{seed}.png')
                ranks.append(next((i+1 for i, hit in enumerate(hits) if hit.path == wanted), None))
        return {'policy': 'synthetic-only; not calibrated real-world recall',
                'index_images': report['total_cached'], 'postings': report['feature_postings'],
                'sqlite_mb': round(database.stat().st_size / 1048576, 3),
                'indexing_seconds': round(indexing_ms / 1000, 3),
                'rotation_crop_queries': len(ranks),
                'recall_at_1': round(sum(r == 1 for r in ranks) / len(ranks), 4),
                'recall_at_5': round(sum(r is not None and r <= 5 for r in ranks) / len(ranks), 4),
                'recall_at_20': round(sum(r is not None for r in ranks) / len(ranks), 4),
                'median_query_ms': round(statistics.median(query_ms), 2),
                'worst_query_ms': round(max(query_ms), 2),
                'misses_at_1': [{'seed': seed, 'angle': angle, 'rank': rank}
                                for (seed, angle), rank in zip(((s, a) for s in origins for a in ANGLES), ranks)
                                if rank != 1]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distractors', type=int, default=80)
    parser.add_argument('--anchors', type=int, default=8)
    args = parser.parse_args()
    print(json.dumps(run(args.distractors, args.anchors), indent=2))


if __name__ == '__main__':
    main()
