"""Bounded real-SQLite retrieval stress against disposable fabricated index rows.

This is **not** a 150k-photo or on-device benchmark. One temporary query PNG,
one temporary valid candidate PNG, and N-1 numeric fake *nonexistent paths*
exercise the actual SQLite posting-cap/grouping/ranking implementation.
No user images, phone databases, live caches, cloud or original-file writes.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import json
from pathlib import Path
import resource
import statistics
from tempfile import TemporaryDirectory
import time

from PIL import Image

from .benchmark import textured
from .bounded_posting_query import PostingBudgetExceeded
from .matcher import read_grayscale
from .retrieval import (
    FINGERPRINT_VERSION, MAX_SIDE, open_db, shortlist, words_for_image,
)

MAX_FAKE_DOCS = 10_000


def _quantile_p95(samples: list[float]) -> float:
    return sorted(samples)[(95 * len(samples) + 99) // 100 - 1]


def run(*, synthetic_docs: int = 2000, repetitions: int = 3) -> dict:
    if type(synthetic_docs) is not int or not 10 <= synthetic_docs <= MAX_FAKE_DOCS:
        raise ValueError("synthetic_docs must be integer 10..10000")
    if type(repetitions) is not int or not 1 <= repetitions <= 8:
        raise ValueError("repetitions must be integer 1..8")
    with TemporaryDirectory(prefix="atlas-sqlite-budget-numeric-") as folder:
        root = Path(folder)
        query = root / "probe.png"
        valid = root / "0000-valid.png"
        pixels = textured(1601, 256)
        Image.fromarray(pixels).save(query)
        Image.fromarray(pixels).save(valid)
        terms = sorted(words_for_image(read_grayscale(query, MAX_SIDE)))
        if len(terms) < 2:
            raise AssertionError("Synthetic ORB query unexpectedly lacks two terms")
        selected = terms[:2]
        stat = valid.stat()
        database = root / "fabricated.sqlite"
        started = time.perf_counter()
        with closing(open_db(database, write=True)) as conn:
            conn.execute("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            conn.execute("INSERT OR REPLACE INTO metadata (key,value) VALUES ('fingerprint',?)",
                         (FINGERPRINT_VERSION,))
            conn.execute(
                "INSERT INTO photos (id,path,size,mtime_ns,word_count) VALUES (?,?,?,?,?)",
                (1, str(valid), stat.st_size, stat.st_mtime_ns, 2),
            )
            # Fake *missing* document paths are a controlled stress input:
            # they are NEVER materialized or read. One real synthetic hit is
            # lexicographically earliest to avoid stale-page traversal.
            conn.executemany(
                "INSERT INTO photos (id,path,size,mtime_ns,word_count) VALUES (?,?,?,?,?)",
                ((i, str(root / "missing" / f"fake-{i:07d}.png"), 0, 0, 2)
                 for i in range(2, synthetic_docs + 1)),
            )
            conn.executemany(
                "INSERT INTO words (word,photo_id) VALUES (?,?)",
                ((word, doc_id)
                 for word in selected for doc_id in range(1, synthetic_docs + 1)),
            )
            conn.commit()
        build_seconds = time.perf_counter() - started
        expected_visits = synthetic_docs * 2
        failures = {}
        for reason, visits, docs in (
            ("posting_visits", expected_visits - 1, synthetic_docs),
            ("distinct_documents", expected_visits, synthetic_docs - 1),
        ):
            started = time.perf_counter()
            try:
                shortlist(query, database, top_k=1,
                          max_posting_visits=visits,
                          max_distinct_documents=docs)
            except PostingBudgetExceeded as exc:
                if exc.reason != reason:
                    raise AssertionError("Wrong SQLite budget-abstention reason") from exc
            else:
                raise AssertionError("Incomplete SQLite search incorrectly completed")
            failures[reason] = round(1000 * (time.perf_counter() - started), 3)
        complete_ms = []
        for _ in range(repetitions):
            started = time.perf_counter()
            hits = shortlist(query, database, top_k=1,
                             max_posting_visits=expected_visits,
                             max_distinct_documents=synthetic_docs)
            complete_ms.append(1000 * (time.perf_counter() - started))
            if len(hits) != 1 or hits[0].path != str(valid) or hits[0].shared_visual_words != 2:
                raise AssertionError("Completed SQLite shortlist has incorrect synthetic top hit")
        return {
            "policy": (
                "Temporary SQL numeric row stress only; 2 matching words per synthetic ID. "
                "NOT a real 384-word/photo gallery performance or matching-accuracy benchmark."
            ),
            "fabricated_numeric_photo_ids": synthetic_docs,
            "actual_temporary_pngs": 2,
            "materialized_fake_photo_paths": 0,
            "postings_per_fabricated_id": 2,
            "expected_matched_postings": expected_visits,
            "sqlite_mebibytes": round(database.stat().st_size / 1048576, 3),
            "build_seconds_linux": round(build_seconds, 3),
            "explicit_abstention_latency_ms_linux": failures,
            "completed_query_checks": len(complete_ms),
            "completed_query_p50_ms_linux": round(statistics.median(complete_ms), 3),
            "completed_query_p95_ms_linux": round(_quantile_p95(complete_ms), 3),
            "ru_maxrss_kib_linux_process": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
            "complete_candidate_count_verified": 1,
            "all_original_user_media_untouched": True,
            "limitations": (
                "Single artificially repeated descriptor pair, one real tiny synthetic hit, "
                "and N-1 missing fake files. Timings are host-dependent and include "
                "synthetic query extraction. Not 150k full ORB posting density, natural "
                "feature frequencies, independent-shutter precision/recall, Android "
                "p95/thermal/battery, candidate freshness or production usability."
            ),
        }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docs", type=int, default=2000)
    parser.add_argument("--repetitions", type=int, default=3)
    args = parser.parse_args()
    print(json.dumps(run(synthetic_docs=args.docs, repetitions=args.repetitions), indent=2))


if __name__ == "__main__":
    main()
