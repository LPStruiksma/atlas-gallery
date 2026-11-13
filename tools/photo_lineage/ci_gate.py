"""Cheap deterministic synthetic-only CI regression gate; no media or network access.

Run: python -m tools.photo_lineage.ci_gate
Not statistical calibration, and not Android acceptance evidence.
"""
from __future__ import annotations

import json
import sys

from .benchmark import run as pairs
from .benchmark_retrieval import run as retrieval


def check() -> dict:
    pair_results = pairs()
    by_alg = {}
    for algorithm in ('sift', 'orb', 'akaze'):
        rows = [r for r in pair_results if r['algorithm'] == algorithm]
        positives = [r for r in rows if r['expected_related']]
        negatives = [r for r in rows if not r['expected_related']]
        by_alg[algorithm] = {
            'edited_found': sum(r['verdict'] in ('candidate', 'review') for r in positives),
            'edited_total': len(positives),
            'false_strong_candidates': sum(r['verdict'] == 'candidate' for r in negatives),
            'negative_total': len(negatives),
        }
    index = retrieval(80, 8)
    passed = all(v['edited_found'] == v['edited_total'] and v['false_strong_candidates'] == 0
                 for v in by_alg.values()) and index['recall_at_5'] >= 0.95
    return {
        'passed': passed, 'policy': 'synthetic regression only, not real-gallery calibration',
        'pair_comparison': by_alg,
        'retrieval_images': index['index_images'],
        'retrieval_queries': index['rotation_crop_queries'],
        'retrieval_recall_at_5': index['recall_at_5'],
    }


if __name__ == '__main__':
    result = check()
    print(json.dumps(result, indent=2))
    sys.exit(0 if result['passed'] else 1)
