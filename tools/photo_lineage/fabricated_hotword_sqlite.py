"""Actual SQLite budget stress: fictional common supplementary visual words.

No real photos or performance/recall calibration. Numeric-only missing
paths never represent a physically captured photo or valid lineage link.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import json
from pathlib import Path
from tempfile import TemporaryDirectory

from PIL import Image

from .benchmark import textured
from .bounded_posting_query import PostingBudgetExceeded
from .matcher import read_grayscale
from .retrieval import FINGERPRINT_VERSION, MAX_SIDE, open_db, shortlist, words_for_image

MAX_FAKE_DOCUMENTS = 5_000
HYPOTHETICAL_GALLERY = 150_000
PRODUCTION_POSTING_BUDGET = 250_000
PRODUCTION_DOCUMENT_BUDGET = 100_000
CONDITIONS = ("baseline", "one_common_extra", "two_common_extras")


def _cold_word(query_words: set[int]) -> int:
    for n in range(385):
        if n not in query_words:
            return n
    raise ValueError("Synthetic query has no spare 26-bit word")


def _build_case(db: Path, source: Path, terms: tuple[int, ...],
                fake_docs: int, common_extras: int) -> tuple[int, int]:
    if (type(fake_docs) is not int or not 10 <= fake_docs <= MAX_FAKE_DOCUMENTS
            or type(common_extras) is not int or common_extras not in (0, 1, 2)
            or not 3 <= len(terms) <= 384 or len(terms) != len(set(terms))
            or any(type(x) is not int or not 0 <= x < 1 << 26 for x in terms)):
        raise ValueError("Invalid bounded fictional posting corpus")
    cold = _cold_word(set(terms))
    hot = terms[:common_extras]
    st = source.stat()
    with closing(open_db(db, write=True)) as conn:
        conn.execute("CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        conn.execute("INSERT INTO metadata(key,value) VALUES ('fingerprint',?)",
                     (FINGERPRINT_VERSION,))
        conn.execute("INSERT INTO photos(id,path,size,mtime_ns,word_count) VALUES (?,?,?,?,?)",
                     (1, str(source), st.st_size, st.st_mtime_ns, len(terms)))
        conn.executemany("INSERT INTO words(word,photo_id) VALUES (?,1)",
                         ((word,) for word in terms))
        # No files are created for these fake IDs. Adding one or two query
        # words per ID stays far below the 256 extra-word research cap.
        conn.executemany(
            "INSERT INTO photos(id,path,size,mtime_ns,word_count) VALUES (?,?,?,?,?)",
            ((i, str(db.parent / "unmaterialized" / f"fake-{i:06d}.png"),
              0, 0, 1 + common_extras) for i in range(2, fake_docs + 2)),
        )
        conn.executemany("INSERT INTO words(word,photo_id) VALUES (?,?)",
                         ((word, i) for i in range(2, fake_docs + 2)
                          for word in (cold, *hot)))
        conn.commit()
        placeholders = ",".join("?" for _ in terms)
        visits = conn.execute(
            f"SELECT COUNT(*) FROM words WHERE word IN ({placeholders})", terms,
        ).fetchone()[0]
        documents = conn.execute(
            f"SELECT COUNT(DISTINCT photo_id) FROM words WHERE word IN ({placeholders})",
            terms,
        ).fetchone()[0]
    if visits != len(terms) + common_extras * fake_docs:
        raise ValueError("Actual posting count disagrees with synthetic construction")
    if documents != (1 if common_extras == 0 else 1 + fake_docs):
        raise ValueError("Actual candidate cardinality disagrees")
    return visits, documents


def _must_refuse(query: Path, db: Path, visits: int,
                 documents: int, reason: str) -> None:
    try:
        shortlist(query, db, top_k=1, max_posting_visits=visits,
                  max_distinct_documents=documents)
    except PostingBudgetExceeded as error:
        if error.reason != reason:
            raise AssertionError("Unexpected budget refusal category") from error
    else:
        raise AssertionError("SQLite silently completed an incomplete query")


def run(*, fake_docs: int = 120) -> dict:
    if type(fake_docs) is not int or not 10 <= fake_docs <= MAX_FAKE_DOCUMENTS:
        raise ValueError("fake_docs must be integer 10..5000")
    conditions = {}
    with TemporaryDirectory(prefix="atlas-fabricated-hotwords-") as folder:
        root = Path(folder)
        source = root / "synthetic-source.png"
        query = root / "synthetic-query.png"
        fictional = textured(1601, 256)
        Image.fromarray(fictional).save(source)
        Image.fromarray(fictional).save(query)
        terms = tuple(sorted(words_for_image(read_grayscale(query, MAX_SIDE))))
        if not 3 <= len(terms) <= 384:
            raise ValueError("Unexpected finite synthetic ORB fingerprint size")
        for extras, name in enumerate(CONDITIONS):
            database = root / (name + ".sqlite")
            visits, docs = _build_case(database, source, terms, fake_docs, extras)
            _must_refuse(query, database, visits - 1, docs, "posting_visits")
            if extras:
                _must_refuse(query, database, visits, docs - 1, "distinct_documents")
            hits = shortlist(query, database, top_k=1,
                             max_posting_visits=visits,
                             max_distinct_documents=docs)
            if (len(hits) != 1 or hits[0].path != str(source)
                    or hits[0].shared_visual_words != len(terms)):
                raise AssertionError("Valid fictional source not rank1 at exact budget")
            conditions[name] = {
                "fabricated_extra_query_hot_words_per_fake_doc": extras,
                "numeric_fake_ids": fake_docs,
                "fake_ids_with_two_word_overlap": fake_docs if extras == 2 else 0,
                "measured_sqlite_postings_for_query": visits,
                "measured_sqlite_distinct_candidate_ids": docs,
                "exact_posting_boundary_refuses_one_below": True,
                "exact_distinct_id_boundary_refuses_one_below": bool(extras),
                "inclusive_budgets_retrieve_only_materialized_fictional_source": True,
                "sqlite_bytes": database.stat().st_size,
                "fake_photo_paths_created": 0,
            }
    projections = {}
    for extras, name in enumerate(CONDITIONS):
        postings = len(terms) + extras * HYPOTHETICAL_GALLERY
        distinct = 1 if extras == 0 else HYPOTHETICAL_GALLERY + 1
        projections[name] = {
            "conditional_postings": postings,
            "conditional_distinct_ids": distinct,
            "posting_budget": "abstain" if postings > PRODUCTION_POSTING_BUDGET else "within",
            "distinct_id_budget": "abstain" if distinct > PRODUCTION_DOCUMENT_BUDGET else "within",
        }
    return {
        "policy": "Numerical SQLite posting adversary ONLY; no physical camera or real word-prevalence evidence",
        "synthetic_query_term_count": len(terms),
        "fabricated_numeric_documents_per_condition": fake_docs,
        "real_fictional_pngs_created": 2,
        "genuine_camera_photo_count": 0,
        "extra_terms_under_research_per_photo_cap": True,
        "conditions": conditions,
        "conditional_150k_arithmetic_not_a_measurement": projections,
        "assumed_150k_gallery_size": HYPOTHETICAL_GALLERY,
        "assumed_posting_budget": PRODUCTION_POSTING_BUDGET,
        "assumed_distinct_id_budget": PRODUCTION_DOCUMENT_BUDGET,
        "true_shutter_source_identity_estimated": False,
        "real_camera_word_frequency_measured": False,
        "actual_temporary_sqlite_preflight_ran": True,
        "incomplete_queries_never_reported_as_empty": True,
        "no_original_user_media_touched": True,
        "fake_paths_materialized": False,
        "all_temporary_data_removed": True,
        "limitations": (
            "Extreme constructed hot-word prevalence, not typical phone ORB terms. "
            "Only one real fictional PNG source; all competing document paths are "
            "intentionally missing, so this cannot quantify false-review rates. "
            "Arithmetic 150k projections never allocate such a gallery. "
            "No real shutter evidence or native Android disk/thermal/performance results."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fake-docs", type=int, default=120)
    args = parser.parse_args()
    print(json.dumps(run(fake_docs=args.fake_docs), indent=2))


if __name__ == "__main__":
    main()
