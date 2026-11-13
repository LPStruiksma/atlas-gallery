"""Optional bounded numeric-index stress study. NO photo decoding or gallery access.

Example: python -m tools.photo_lineage.benchmark_bounded_query --photos 150000
Uses *three* synthetic words per document (far below 384 real ORB words).
All measurements are Linux/Python-specific, never S24+ acceptance evidence.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import resource
import statistics
import tempfile
import time

from .bounded_posting_query import PostingBudgetExceeded, open_pinned_queries
from .external_index import build_snapshot


def synthetic_documents(photos: int):
    for photo_id in range(photos):
        yield photo_id, (7, 1_000 + photo_id, 2_000_000 + photo_id % 50_000)


def run(*, photos: int = 150_000, queries: int = 20) -> dict:
    if type(photos) is not int or not 2 <= photos <= 150_000:
        raise ValueError("Synthetic photos must be 2..150,000")
    if type(queries) is not int or not 1 <= queries <= 100:
        raise ValueError("queries must be 1..100")
    with tempfile.TemporaryDirectory(prefix="atlas-numeric-query-") as folder:
        output = Path(folder) / "numeric.u64"
        start = time.perf_counter()
        built = build_snapshot(synthetic_documents(photos), output,
                               max_postings=photos * 3,
                               chunk_records=8192, fan_in=16)
        build_seconds = time.perf_counter() - start
        began = time.perf_counter()
        observed = []
        with open_pinned_queries(output, built["sha256"]) as index:
            verification_seconds = time.perf_counter() - began
            # A very common descriptor word must fail BEFORE accumulating a
            # 150k-item Counter; do not mistake abstention for empty results.
            try:
                index.query([7], max_posting_visits=min(10_000, photos - 1))
            except PostingBudgetExceeded:
                hot_word_abstained = True
            else:
                hot_word_abstained = False
            sample = sorted({i * (photos - 1) // max(1, queries - 1)
                             for i in range(queries)})
            for photo_id in sample:
                request_started = time.perf_counter()
                result = index.query((1_000 + photo_id,
                                      2_000_000 + photo_id % 50_000))
                observed.append(1000 * (time.perf_counter() - request_started))
                if (not result["complete"] or not result["matches"] or
                        result["matches"][0]["photo_id"] != photo_id or
                        result["matches"][0]["shared_visual_words"] != 2):
                    raise AssertionError("Synthetic numeric-index top-1 parity failed")
        observed.sort()
        return {
            "policy": "synthetic numeric postings only; NO photos, ORB extraction, device or cloud",
            "synthetic_photo_ids": photos,
            "postings_per_photo": 3,
            "approx_real_orb_postings_per_photo": 384,
            "postings": built["postings"],
            "index_mib": round(built["bytes"] / 1048576, 3),
            "build_seconds": round(build_seconds, 3),
            "full_pinned_verification_seconds": round(verification_seconds, 3),
            "hot_word_abstained_explicitly": hot_word_abstained,
            "top1_numeric_query_checks": len(observed),
            "query_p50_ms": round(statistics.median(observed), 3),
            "query_p95_ms": round(observed[min(len(observed) - 1, max(0, (95 * len(observed) + 99) // 100 - 1))], 3),
            "ru_maxrss_kib_linux": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
            "not_measured": "No real ORB visual word distributions, JPEG decode, photo manifest, S24+ CPU/thermal/battery, same-exposure precision/recall",
        }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--photos", type=int, default=150_000)
    parser.add_argument("--queries", type=int, default=20)
    args = parser.parse_args()
    print(json.dumps(run(photos=args.photos, queries=args.queries), indent=2))


if __name__ == "__main__":
    main()
