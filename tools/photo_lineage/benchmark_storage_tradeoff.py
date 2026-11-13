"""Offline synthetic cost/recall sweep of ORB visual-word projections, no photos.

No file access beyond this module; does not change production descriptor format.
This is a *relative cost and recall* experiment on procedural pictures,
not a photographic validation and not an estimated Android RAM/latency test.
"""
from __future__ import annotations

import argparse
import json
import time

import cv2
import numpy as np

from .benchmark import rotate, textured
from .retrieval import BANDS

DEFAULT_COUNTS = (32, 48, 64, 96)
DEFAULT_BAND_COUNTS = (2, 3, 4)
ANGLES = (-150, -95, -44, 26, 68, 111, 163)


def selected_descriptors(gray: np.ndarray, maximum: int = 96) -> np.ndarray:
    orb = cv2.ORB_create(nfeatures=900, fastThreshold=10)
    points, descriptors = orb.detectAndCompute(gray, None)
    if descriptors is None:
        return np.empty((0, 32), dtype=np.uint8)
    ordered = np.argsort([-p.response for p in points])
    return descriptors[ordered[:maximum]]


def projected_words(descriptors: np.ndarray, top_points: int, band_count: int) -> set[int]:
    words = set()
    for band, (a, b, c) in enumerate(BANDS[:band_count]):
        for row in descriptors[:top_points]:
            words.add((band << 24) | (int(row[a]) << 16) | (int(row[b]) << 8) | int(row[c]))
    return words


def run(distractors: int = 250, anchors: int = 8) -> dict:
    if not 1 <= distractors <= 1500 or not 1 <= anchors <= 20:
        raise ValueError('synthetic only; use 1..1500 distractors and 1..20 anchors')
    corpus = {}
    queries = []
    started = time.perf_counter()
    for seed in range(101, 101 + anchors):
        image = textured(seed, 600)
        corpus[f'origin_{seed}'] = selected_descriptors(image)
        for angle in ANGLES:
            queries.append((f'origin_{seed}', angle,
                            selected_descriptors(rotate(image, angle)[110:520, 105:530])))
    for seed in range(500, 500 + distractors):
        corpus[f'distractor_{seed}'] = selected_descriptors(textured(seed, 600))
    extraction_s = round(time.perf_counter()-started, 2)
    combinations = []
    for count in DEFAULT_COUNTS:
        for band_count in DEFAULT_BAND_COUNTS:
            inverted = {}
            posting_count = 0
            for key, descriptors in corpus.items():
                tokens = projected_words(descriptors, count, band_count)
                posting_count += len(tokens)
                for token in tokens:
                    inverted.setdefault(token, set()).add(key)
            ranks = []
            for original, angle, descriptors in queries:
                common = {}
                for token in projected_words(descriptors, count, band_count):
                    for key in inverted.get(token, ()):
                        common[key] = common.get(key, 0) + 1
                # Explicit stable ties; without a meaningful score a zero-hit
                # true source is not counted among top candidates.
                ordered = sorted(((-hits, key) for key, hits in common.items() if hits >= 2))
                ranks.append(next((i + 1 for i, (_, key) in enumerate(ordered)
                                   if key == original), None))
            combinations.append({
                'keypoints':count, 'bands':band_count,
                'postings': posting_count,
                'relative_postings_vs_96x4':None,
                'recall_at_1':sum(r == 1 for r in ranks)/len(ranks),
                'recall_at_5':sum(r is not None and r<=5 for r in ranks)/len(ranks),
                'recall_at_20':sum(r is not None and r<=20 for r in ranks)/len(ranks),
                'worst_positive_rank': max((r for r in ranks if r is not None), default=None),
                'not_retrieved':sum(r is None for r in ranks),
            })
    baseline = next(row['postings'] for row in combinations
                    if row['keypoints']==96 and row['bands']==4)
    for row in combinations:
        row['relative_postings_vs_96x4'] = round(row['postings'] / baseline, 3)
        for key in ('recall_at_1','recall_at_5','recall_at_20'):
            row[key] = round(row[key], 4)
    return {'policy':'synthetic-only research, NOT approved production footprint',
            'photos':len(corpus), 'queries':len(queries),
            'feature_extraction_seconds':extraction_s, 'configurations':combinations}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distractors',type=int,default=250)
    parser.add_argument('--anchors',type=int,default=8)
    args=parser.parse_args()
    print(json.dumps(run(distractors=args.distractors,anchors=args.anchors),indent=2))


if __name__=='__main__':
    main()
