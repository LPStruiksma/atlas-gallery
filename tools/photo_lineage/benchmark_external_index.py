"""Repeatable synthetic-only external-merge posting benchmark (no photographs).

Separates full streaming build from the output size; measures process RSS
only as a diagnostic, not Android peak heap, CPU or energy consumption.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import resource
import tempfile
import time

from .external_index import build_snapshot


def synthetic_documents(images: int, words_per_image: int):
    """Deterministic numeric postings with occasional repeated descriptor words."""
    for image_id in range(images):
        yield image_id, (((image_id * 7919 + j * 1009) ^ (j * 524287)) & ((1 << 26) - 1)
                         for j in range(words_per_image))


def run(images: int = 1200, words_per_image: int = 384,
        chunk_records: int = 8192, fan_in: int = 16) -> dict:
    if not 1 <= images <= 20000 or not 1 <= words_per_image <= 512:
        raise ValueError('Synthetic inputs limited to 1..20000 images and 1..512 words each')
    with tempfile.TemporaryDirectory(prefix='atlas-external-index-bench-') as folder:
        snapshot = Path(folder) / 'synthetic-snapshot.u64'
        began = time.perf_counter()
        report = build_snapshot(synthetic_documents(images, words_per_image), snapshot,
                                chunk_records=chunk_records, fan_in=fan_in,
                                max_postings=images * words_per_image)
        elapsed = time.perf_counter() - began
        if not report['output_exists'] or snapshot.stat().st_size != report['bytes']:
            raise AssertionError('Synthetic benchmark snapshot publication failed')
        return {
            'policy': 'synthetic numeric postings only; excludes image extraction and Android IO',
            'images': images, 'words_per_image': words_per_image,
            'merge_chunk_records': chunk_records, 'fan_in': fan_in,
            'postings': report['postings'], 'bytes': report['bytes'],
            'index_mib': round(report['bytes'] / 1048576, 4),
            'initial_runs': report['initial_sorted_runs'],
            'merge_passes': report['bounded_merge_passes'],
            'elapsed_seconds': round(elapsed, 3),
            'sha256': report['sha256'],
            'ru_maxrss_kib_linux': resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
            'measurement_limits': 'RSS includes Python interpreter and dependencies; no Android inference',
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--images', type=int, default=1200)
    parser.add_argument('--words-per-image', type=int, default=384)
    parser.add_argument('--chunk-records', type=int, default=8192)
    parser.add_argument('--fan-in', type=int, default=16)
    args = parser.parse_args()
    print(json.dumps(run(args.images, args.words_per_image, args.chunk_records, args.fan_in), indent=2))


if __name__ == '__main__':
    main()
