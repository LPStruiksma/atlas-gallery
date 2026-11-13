"""Disposable SQLite evaluation of capped blur visual words; never use on private media."""
from __future__ import annotations

import argparse
from contextlib import closing
import json
from pathlib import Path
from tempfile import TemporaryDirectory

import cv2
import numpy as np
from PIL import Image

from .benchmark import textured
from .blur_robustness_matrix import SOURCES, STEREO_NAMES, apply_blur
from .capped_blur_word_tradeoff import LIMITS, QUERY_CONDITIONS, select_extra_words
from .middlebury_stereo_smoke import STEREO_SOURCES
from .public_domain_smoke import PINNED_PHOTOS, _bundled_root, _read_pinned_photo
from .public_blur_word_overlap import EXPECTED_FINGERPRINT, _validated_words
from .matcher import compare_arrays, read_grayscale
from .retrieval import FINGERPRINT_VERSION, MAX_SIDE, index_folder, open_db, shortlist

TOP_K = 20
MAX_DISTRACTORS = 32
POSTING_BUDGET = 50_000
DOCUMENT_BUDGET = 1_000


def _rewrite_disposable_index(db: Path, sources: dict[str, Path], cap: int) -> dict:
    """Compare initial SQLite descriptors, then update temporary source rows only."""
    if type(cap) is not int or cap not in LIMITS or len(sources) != 7:
        raise ValueError("Invalid bounded disposable index request")
    added = 0
    with closing(open_db(db, write=True)) as conn:
        try:
            conn.execute("BEGIN IMMEDIATE")
            for name in (*SOURCES, *STEREO_NAMES):
                path = sources[name]
                row = conn.execute(
                    "SELECT id,word_count FROM photos WHERE path=?", (str(path),)
                ).fetchone()
                if row is None:
                    raise ValueError("Incomplete temporary source index")
                photo_id, stored_count = row
                gray = read_grayscale(path, MAX_SIDE)
                base = _validated_words(gray)
                stored = {word for (word,) in conn.execute(
                    "SELECT word FROM words WHERE photo_id=?", (photo_id,))}
                if stored != base or stored_count != len(base):
                    raise ValueError("Temporary SQLite baseline words differ")
                mild = cv2.GaussianBlur(
                    gray, (0, 0), 2.0, borderType=cv2.BORDER_REFLECT_101)
                augmented = select_extra_words(base, _validated_words(mild), cap)
                if not base <= augmented or len(augmented) > len(base) + cap:
                    raise ValueError("Supplementary ORB terms exceed budget")
                conn.execute("DELETE FROM words WHERE photo_id=?", (photo_id,))
                conn.executemany(
                    "INSERT INTO words(word,photo_id) VALUES (?,?)",
                    ((word, photo_id) for word in sorted(augmented)))
                conn.execute("UPDATE photos SET word_count=? WHERE id=?",
                             (len(augmented), photo_id))
                added += len(augmented) - len(base)
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        postings = conn.execute("SELECT COUNT(*) FROM words").fetchone()[0]
    return {"added": added, "total": postings}


def _rank(hits: list, expected: Path) -> int | None:
    if type(hits) is not list or len(hits) > TOP_K:
        raise ValueError("Malformed bounded shortlist")
    paths = [getattr(hit, "path", None) for hit in hits]
    if any(type(path) is not str for path in paths) or len(set(paths)) != len(paths):
        raise ValueError("Malformed or duplicate candidate")
    return next((i + 1 for i, path in enumerate(paths)
                 if path == str(expected)), None)


def run(*, cap: int = 256, distractors: int = 16,
        posting_budget: int = POSTING_BUDGET,
        document_budget: int = DOCUMENT_BUDGET,
        verify_algorithm: str | None = None) -> dict:
    if (type(cap) is not int or cap not in LIMITS
            or type(distractors) is not int or not 0 <= distractors <= MAX_DISTRACTORS
            or type(posting_budget) is not int or not 1 <= posting_budget <= POSTING_BUDGET
            or type(document_budget) is not int or not 1 <= document_budget <= DOCUMENT_BUDGET
            or (verify_algorithm is not None and
                (type(verify_algorithm) is not str or
                 verify_algorithm not in ("orb", "sift", "akaze")))):
        raise ValueError("Only bounded disposable research parameters allowed")
    if (FINGERPRINT_VERSION != EXPECTED_FINGERPRINT
            or set(SOURCES) != set(PINNED_PHOTOS)
            or set(STEREO_NAMES) != set(STEREO_SOURCES)
            or QUERY_CONDITIONS != (("gaussian", 8.0), ("motion", 31))):
        raise ValueError("Pinned public-source protocol changed")
    root = _bundled_root()
    photos = {}
    for name in SOURCES:
        filename, sha, _license = PINNED_PHOTOS[name]
        photos[name] = _read_pinned_photo(root / filename, sha)
    for name in STEREO_NAMES:
        filename, sha = STEREO_SOURCES[name]
        photos[name] = _read_pinned_photo(root / filename, sha)
    if (photos["left_view"].shape != photos["right_view"].shape
            or np.array_equal(photos["left_view"], photos["right_view"])):
        raise ValueError("Distinct stereo camera viewpoints required")
    by_condition = {}
    with TemporaryDirectory(prefix="atlas-capped-sqlite-study-") as folder:
        base_dir = Path(folder)
        corpus, query_dir = base_dir / "corpus", base_dir / "queries"
        corpus.mkdir()
        query_dir.mkdir()
        sources = {}
        for name, gray in photos.items():
            path = corpus / f"public__{name}.png"
            Image.fromarray(gray).save(path)
            sources[name] = path
        for i in range(distractors):
            Image.fromarray(textured(2000 + i, 256)).save(
                corpus / f"fictional__{i:03d}.png")
        database = base_dir / "ephemeral.sqlite"
        built = index_folder(corpus, database, limit=len(photos) + distractors)
        if built["errors"] or built["total_cached"] != len(photos) + distractors:
            raise ValueError("Disposable SQLite index incomplete")
        rewritten = _rewrite_disposable_index(database, sources, cap)
        if rewritten["total"] != built["feature_postings"] + rewritten["added"]:
            raise ValueError("Disposable index posting accounting mismatch")
        reference_gray = (
            {name: read_grayscale(path, MAX_SIDE) for name, path in sources.items()}
            if verify_algorithm is not None else {}
        )
        for kind, strength in QUERY_CONDITIONS:
            ranks = {}
            eligibility = {}
            nonself_offers = 0
            stereo_rank = None
            geom_all_reviewable = 0
            retrieved_geom_reviewable = 0
            retrieved_geom_unknown = 0
            geom_reviewable_not_retrieved = 0
            stereo_geom_verdict = None
            for name, gray in photos.items():
                edited = apply_blur(gray, kind, strength)
                query_path = query_dir / f"query__{name}__{kind}_{strength:g}.png"
                Image.fromarray(edited).save(query_path)
                query_gray = read_grayscale(query_path, MAX_SIDE)
                qwords = _validated_words(query_gray)
                with closing(open_db(database)) as conn:
                    row = conn.execute("SELECT id FROM photos WHERE path=?",
                                       (str(sources[name]),)).fetchone()
                    if row is None:
                        raise ValueError("Source absent from temporary SQLite")
                    indexed = {word for (word,) in conn.execute(
                        "SELECT word FROM words WHERE photo_id=?", (row[0],))}
                eligible = len(qwords & indexed) >= 2
                eligibility[name] = eligible
                # PostingBudgetExceeded propagates, never reported as no match.
                hits = shortlist(query_path, database, top_k=TOP_K,
                                 max_posting_visits=posting_budget,
                                 max_distinct_documents=document_budget)
                rank = _rank(hits, sources[name])
                if rank is not None and not eligible:
                    raise ValueError("Shortlist contradicted two-word requirement")
                ranks[name] = rank
                if verify_algorithm is not None:
                    # Same-source edit reviewability is a *separate stage*:
                    # a retrieved source can still fail geometry, and a good
                    # geometry pair can be absent from the SQLite shortlist.
                    verdict = compare_arrays(
                        reference_gray[name], query_gray,
                        algorithm=verify_algorithm).verdict
                    if verdict not in ("candidate", "review", "unknown"):
                        raise ValueError("Unsupported geometric verifier verdict")
                    reviewable = verdict in ("candidate", "review")
                    geom_all_reviewable += int(reviewable)
                    retrieved_geom_reviewable += int(reviewable and rank is not None)
                    retrieved_geom_unknown += int(not reviewable and rank is not None)
                    geom_reviewable_not_retrieved += int(reviewable and rank is None)
                public_paths = {str(path) for other, path in sources.items()
                                if other != name}
                nonself_offers += sum(hit.path in public_paths for hit in hits)
                if name == "right_view":
                    stereo_rank = _rank(hits, sources["left_view"])
                    if verify_algorithm is not None:
                        stereo_geom_verdict = compare_arrays(
                            reference_gray["left_view"], query_gray,
                            algorithm=verify_algorithm).verdict
                        if stereo_geom_verdict not in ("candidate", "review", "unknown"):
                            raise ValueError("Unsupported stereo verifier verdict")
            label = f"{kind}_{strength:g}"
            by_condition[label] = {
                "known_source_eligible": sum(eligibility.values()),
                "known_source_rank1": sum(rank == 1 for rank in ranks.values()),
                "known_source_top5": sum(rank is not None and rank <= 5
                                         for rank in ranks.values()),
                "known_source_top20": sum(rank is not None for rank in ranks.values()),
                "eligible_but_absent_top20": sum(
                    eligibility[name] and ranks[name] is None for name in ranks),
                "nonself_public_candidate_offers": nonself_offers,
                "different_viewpoint_stereo_left_rank_on_right_query": stereo_rank,
                "optional_geometry_review": (
                    None if verify_algorithm is None else {
                        "algorithm": verify_algorithm,
                        "source_edits_reviewable_even_without_retrieval":
                            geom_all_reviewable,
                        "retrieved_source_and_geometry_reviewable":
                            retrieved_geom_reviewable,
                        "retrieved_source_but_geometry_unknown":
                            retrieved_geom_unknown,
                        "geometry_reviewable_but_source_not_retrieved":
                            geom_reviewable_not_retrieved,
                        "different_viewpoint_stereo_geometry_verdict":
                            stereo_geom_verdict,
                        "different_viewpoint_stereo_retrieved_and_geometry_reviewable":
                            (stereo_rank is not None and
                             stereo_geom_verdict in ("candidate", "review")),
                    }
                ),
            }
        sqlite_bytes = database.stat().st_size
    return {
        "policy": "Temporary actual SQLite review-offer study, not source identity",
        "source_photo_count": len(photos),
        "source_scene_groups_at_most": len(SOURCES) + 1,
        "synthetic_distractors": distractors,
        "extra_words_per_photo_cap": cap,
        "baseline_postings": built["feature_postings"],
        "additional_postings": rewritten["added"],
        "experimental_total_postings": rewritten["total"],
        "temporary_sqlite_bytes": sqlite_bytes,
        "conditions": by_condition,
        "actual_sqlite_shortlist_executed": True,
        "geometry_review_stage_enabled": verify_algorithm is not None,
        "same_shutter_equality_never_inferred_from_geometry": True,
        "original_source_bytes_sha256_checked": True,
        "production_cache_or_fingerprint_modified": False,
        "temporary_research_cache_destroyed": True,
        "separate_shutter_same_camera_negatives_tested": 0,
        "population_recall_or_false_review_estimated": False,
        "source_exposure_equivalence_verified": False,
        "media_originals_modified_or_uploaded": False,
        "limitations": (
            "Only seven curated public image sources from at most six scene groups "
            "and deterministic synthetic distractors. The original public samples "
            "are reused for edit queries, not scene-independent heldout data. "
            "The temporary augmented database retains the original read-API "
            "compatibility marker solely for this disposable experiment: "
            "it must NEVER be used as a production fingerprint cache. "
            "No separate same-camera shutter labels, 150k gallery distribution, "
            "or Android S24+ disk/RSS/thermal measurements."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cap", type=int, choices=LIMITS, default=256)
    parser.add_argument("--distractors", type=int, default=16)
    parser.add_argument("--posting-budget", type=int, default=POSTING_BUDGET)
    parser.add_argument("--document-budget", type=int, default=DOCUMENT_BUDGET)
    parser.add_argument("--verify-algorithm", choices=("orb", "sift", "akaze"))
    args = parser.parse_args()
    print(json.dumps(run(cap=args.cap, distractors=args.distractors,
                         posting_budget=args.posting_budget,
                         document_budget=args.document_budget,
                         verify_algorithm=args.verify_algorithm), indent=2))


if __name__ == "__main__":
    main()
