"""Research-only packed sorted uint64 index versus SQLite posting-table size.

Builds from strictly synthetic images. The packed stream has no filename or
photo data, but mapping IDs to paths would still require a separate private
manifest. Snapshot is immutable: it is not a drop-in replacement for the
incremental SQLite index until external sorting/sharding is validated.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import statistics
from tempfile import TemporaryDirectory
import time

import numpy as np

from .benchmark import rotate, textured
from .retrieval import words_for_image

# 26-bit visual word in bits 32..57, 32-bit photo ID in bits 0..31.
DOC_MASK = (1 << 32) - 1


def packed_postings(images: list[set[int]]) -> np.ndarray:
    records = []
    if len(images) > DOC_MASK:
        raise ValueError('More than 2**32-1 image IDs unsupported')
    for photo_id, words in enumerate(images):
        for word in words:
            if not 0 <= word < (1 << 26):
                raise ValueError('Visual word out of supported range')
            records.append((word << 32) | photo_id)
    return np.array(sorted(records), dtype='<u8')


def packed_query(packed: np.ndarray, words: set[int], top_k: int = 20) -> list[tuple[int, int]]:
    if not 1 <= top_k <= 100:
        raise ValueError('top_k must be 1..100')
    scores = Counter()
    for word in words:
        low = np.searchsorted(packed, np.uint64(word << 32), side='left')
        high = np.searchsorted(packed, np.uint64((word << 32) | DOC_MASK), side='right')
        for value in packed[low:high]:
            scores[int(value & np.uint64(DOC_MASK))] += 1
    return sorted(((doc, hits) for doc, hits in scores.items() if hits >= 2),
                  key=lambda item: (-item[1], item[0]))[:top_k]


def run(distractors: int = 250, anchors: int = 8) -> dict:
    if not 1 <= distractors <= 1500 or not 1 <= anchors <= 20:
        raise ValueError('Synthetic range: distractors 1..1500, anchors 1..20')
    source_words = []
    requests = []
    for seed in range(101, 101 + anchors):
        image = textured(seed, 600)
        doc = len(source_words)
        source_words.append(words_for_image(image))
        for angle in (-150, -95, -44, 26, 68, 111, 163):
            requests.append((doc, words_for_image(rotate(image, angle)[110:520, 105:530])))
    for seed in range(500, 500 + distractors):
        source_words.append(words_for_image(textured(seed, 600)))
    started = time.perf_counter()
    packed = packed_postings(source_words)
    build_seconds = time.perf_counter() - started
    # Round-trip through an actual temporary on-disk, read-only mmap.
    with TemporaryDirectory() as dirname:
        path = Path(dirname) / 'synthetic-postings.u64'
        packed.tofile(path)
        mapped = np.memmap(path, dtype='<u8', mode='r')
        times, ranks = [], []
        for doc, words in requests:
            start = time.perf_counter()
            ordered = packed_query(mapped, words, top_k=20)
            times.append(1000 * (time.perf_counter() - start))
            ranks.append(next((i + 1 for i, (candidate, _) in enumerate(ordered)
                               if candidate == doc), None))
        output = {
            'policy': 'synthetic immutable snapshot; do not deploy as incremental index',
            'photos': len(source_words), 'queries': len(requests),
            'postings': len(packed), 'packed_mib': round(path.stat().st_size / 1048576, 3),
            'build_seconds': round(build_seconds, 3),
            'recall_at_1': round(sum(r == 1 for r in ranks) / len(ranks), 4),
            'recall_at_5': round(sum(r is not None and r <= 5 for r in ranks) / len(ranks), 4),
            'recall_at_20': round(sum(r is not None for r in ranks) / len(ranks), 4),
            'median_query_ms': round(statistics.median(times), 2),
            'p95_query_ms': round(float(np.percentile(times, 95)), 2),
            'timing_scope': 'posting lookup only; no image decode/ORB extraction/metadata checking',
        }
        del mapped  # Release mmap before temporary-directory cleanup on Windows.
        return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distractors', type=int, default=250)
    parser.add_argument('--anchors', type=int, default=8)
    args = parser.parse_args()
    print(json.dumps(run(args.distractors, args.anchors), indent=2))


if __name__ == '__main__':
    main()
