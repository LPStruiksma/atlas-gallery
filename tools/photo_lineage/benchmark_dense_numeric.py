"""Full 384-word/photo *numeric* posting benchmark, bounded disposable sandbox.

Four visual-word prevalence categories are handcrafted: 4 global words,
32 words per scene cohort, 64 per burst cohort and 284 unique per photo.
This is NOT ORB descriptor extraction, real photographic correlation,
independent exposure validation or Android memory/thermal measurement.
"""
from __future__ import annotations

import argparse
from itertools import chain
import json
from pathlib import Path
import resource
import statistics
from tempfile import TemporaryDirectory
import time

from .bounded_posting_query import PostingBudgetExceeded, open_pinned_queries
from .external_index import build_snapshot
from .query_load_envelope import estimate_footprint

WORDS_PER_PHOTO = 384
GLOBAL_WORDS = 4
SCENE_WORDS = 32
BURST_WORDS = 64
RARE_WORDS = WORDS_PER_PHOTO - GLOBAL_WORDS - SCENE_WORDS - BURST_WORDS


def numeric_words(photo_id: int, *, scene_size: int, burst_size: int):
    """Distinct numeric words, zero image access and no persisted ID maps."""
    scene = photo_id // scene_size
    burst = photo_id // burst_size
    return chain(
        range(GLOBAL_WORDS),
        range(10_000 + 32 * scene, 10_000 + 32 * scene + SCENE_WORDS),
        range(100_000 + 64 * burst, 100_000 + 64 * burst + BURST_WORDS),
        range(1_000_000 + RARE_WORDS * photo_id,
              1_000_000 + RARE_WORDS * (photo_id + 1)),
    )


def run(*, photos: int = 1200, scene_size: int = 200,
        burst_size: int = 20, queries: int = 8) -> dict:
    if type(photos) is not int or not 200 <= photos <= 5000:
        raise ValueError("photos must be integer 200..5000 (full-density sandbox limit)")
    if type(scene_size) is not int or not 20 <= scene_size <= photos:
        raise ValueError("scene_size must be integer 20..photos")
    if type(burst_size) is not int or not 2 <= burst_size <= scene_size:
        raise ValueError("burst_size must be integer 2..scene_size")
    if photos % scene_size or scene_size % burst_size:
        raise ValueError("photos must divide evenly into scenes, and scenes into bursts")
    if type(queries) is not int or not 1 <= queries <= 20:
        raise ValueError("queries must be integer 1..20")
    model = estimate_footprint(
        photos=photos, query_words=WORDS_PER_PHOTO,
        globally_shared_words=GLOBAL_WORDS, scene_words=SCENE_WORDS,
        burst_words=BURST_WORDS, scene_photos=scene_size, burst_photos=burst_size,
    )
    expected = model["posting_visits_required"]
    with TemporaryDirectory(prefix="atlas-full-density-numeric-") as folder:
        path = Path(folder) / "synthetic.u64"
        begun = time.perf_counter()
        receipt = build_snapshot(
            ((i, numeric_words(i, scene_size=scene_size, burst_size=burst_size))
             for i in range(photos)),
            path, max_postings=photos * WORDS_PER_PHOTO,
            chunk_records=8192, fan_in=16,
        )
        build_sec = time.perf_counter() - begun
        if receipt["postings"] != photos * WORDS_PER_PHOTO:
            raise AssertionError("Synthetic full-density postings unexpectedly deduplicated")
        started_verify = time.perf_counter()
        query_ms = []
        samples = sorted({i * (photos - 1) // max(1, queries - 1)
                          for i in range(queries)})
        with open_pinned_queries(path, receipt["sha256"]) as index:
            verify_sec = time.perf_counter() - started_verify
            for doc in samples:
                wordset = list(numeric_words(doc, scene_size=scene_size,
                                             burst_size=burst_size))
                if len(wordset) != WORDS_PER_PHOTO or len(set(wordset)) != WORDS_PER_PHOTO:
                    raise AssertionError("Not exactly 384 distinct terms per synthetic photo")
                cost = index.inspect_query_load(wordset)
                if (cost["postings_preflight"] != expected or
                        cost["largest_single_word_posting_list"] != photos):
                    raise AssertionError("Measured posting windows disagree with exact prevalence model")
                began_query = time.perf_counter()
                ranked = index.query(wordset)
                query_ms.append((time.perf_counter() - began_query) * 1000)
                if (not ranked["complete"] or
                        ranked["postings_preflight"] != expected or
                        not ranked["matches"] or
                        ranked["matches"][0] != {
                            "photo_id": doc,
                            "shared_visual_words": WORDS_PER_PHOTO,
                        }):
                    raise AssertionError("Full-feature numeric top-1 parity failed")
            # Repeat same exact query one visit below measured need to ensure
            # explicit abstention, never partial false negatives.
            probe = list(numeric_words(0, scene_size=scene_size,
                                       burst_size=burst_size))
            try:
                index.query(probe, max_posting_visits=expected - 1)
            except PostingBudgetExceeded as exc:
                below_required_abstained = exc.reason == "posting_visits"
            else:
                below_required_abstained = False
        if not below_required_abstained:
            raise AssertionError("Missing fail-closed numerical posting-budget abstention")
        query_ms.sort()
        return {
            "policy": "Synthetic full-384-word numeric indexing ONLY. No real image bytes or device use.",
            "synthetic_photo_ids": photos,
            "synthetic_visual_words_per_photo": WORDS_PER_PHOTO,
            "explicit_assumed_prevalence": {
                "global_words": GLOBAL_WORDS,
                "scene_words": SCENE_WORDS,
                "burst_words": BURST_WORDS,
                "rare_words": RARE_WORDS,
                "scene_photos": scene_size,
                "burst_photos": burst_size,
            },
            "posting_records": receipt["postings"],
            "index_mib": round(receipt["bytes"] / (1024 * 1024), 4),
            "build_seconds": round(build_sec, 3),
            "verified_open_seconds": round(verify_sec, 3),
            "model_visits_per_384_word_query": expected,
            "actual_exact_pinned_index_visits": expected,
            "one_posting_below_required_abstains": below_required_abstained,
            "numeric_top1_checks": len(query_ms),
            "query_p50_ms_linux": round(statistics.median(query_ms), 3),
            "query_p95_ms_linux": round(query_ms[(95 * len(query_ms) + 99) // 100 - 1], 3),
            "ru_maxrss_kib_linux": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
            "limitations": (
                "Artificial independent numeric IDs, not real ORB words or photos. "
                "Cohort size and frequency are invented. Tiny 200..5000-doc sandbox, "
                "NOT 150k measured at full density. No Android memory, power, heat "
                "or camera-photo similarity/recall evidence. A numeric nearest hit "
                "does not establish same-exposure provenance."
            ),
        }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--photos", type=int, default=1200)
    parser.add_argument("--scene-size", type=int, default=200)
    parser.add_argument("--burst-size", type=int, default=20)
    parser.add_argument("--queries", type=int, default=8)
    args = parser.parse_args()
    print(json.dumps(run(photos=args.photos, scene_size=args.scene_size,
                         burst_size=args.burst_size, queries=args.queries), indent=2))


if __name__ == "__main__":
    main()
