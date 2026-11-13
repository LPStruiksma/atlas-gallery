"""Closed-form query-load sensitivity envelope; zero image/index/file access.

This is intentionally NOT a learned/empirical visual-word distribution. It
models 384 distinct query words assigned to coarse prevalence classes. Because
the inverted index stores one posting per distinct (word, photo ID), each
class contributes word_count * matching_photo_count exact posting visits.
The question is "could this workload exceed our budget?", NOT recall.
"""
from __future__ import annotations

import argparse
import json

DEFAULT_CASES = (
    ("diverse_low_overlap", 0, 0, 0, 500, 40),
    ("shared_scene_no_global", 0, 16, 64, 1000, 60),
    ("one_global_background_word", 1, 32, 64, 5000, 50),
    ("many_repeated_patterns", 8, 64, 96, 3000, 100),
    ("all_terms_global_upper_extreme", 384, 0, 0, 1, 1),
)
DEFAULT_WORDS = 384
DEFAULT_PHOTOS = 150_000
DEFAULT_BUDGET = 250_000


def estimate_footprint(
    *, photos: int = DEFAULT_PHOTOS, query_words: int = DEFAULT_WORDS,
    globally_shared_words: int = 0, scene_words: int = 0,
    burst_words: int = 0, scene_photos: int = 1000,
    burst_photos: int = 60, max_posting_visits: int = DEFAULT_BUDGET,
) -> dict:
    """Cost of a *hypothetical* 4-class query, without constructing an index.

    Four assumed classes: one-posting rare words, within-burst words, within-
    scene words, and global words in every photo. Even a healthy passing
    envelope says nothing about feature discriminability or source identity.
    """
    for name, value, lower, upper in (
        ("photos", photos, 1, 1_000_000),
        ("query_words", query_words, 1, 512),
        ("globally_shared_words", globally_shared_words, 0, 512),
        ("scene_words", scene_words, 0, 512),
        ("burst_words", burst_words, 0, 512),
        ("scene_photos", scene_photos, 1, 1_000_000),
        ("burst_photos", burst_photos, 1, 1_000_000),
        ("max_posting_visits", max_posting_visits, 1, 10_000_000),
    ):
        if type(value) is not int or not lower <= value <= upper:
            raise ValueError(f"{name} must be integer {lower}..{upper}")
    if globally_shared_words + scene_words + burst_words > query_words:
        raise ValueError("Prevalence-class word counts exceed query_words")
    if not 1 <= burst_photos <= scene_photos <= photos:
        raise ValueError("Assumed prevalence must satisfy burst <= scene <= photos")
    rare = query_words - globally_shared_words - scene_words - burst_words
    components = (
        ("globally_shared", globally_shared_words, photos),
        ("scene_shared", scene_words, scene_photos),
        ("burst_shared", burst_words, burst_photos),
        ("rare", rare, 1),
    )
    total = sum(count * frequency for _, count, frequency in components)
    return {
        "assumption_status": "illustrative_what_if_not_measured",
        "photo_count": photos,
        "query_word_count": query_words,
        "posting_visit_budget": max_posting_visits,
        "posting_visits_required": total,
        "visit_budget_envelope": (
            "abstain_incomplete" if total > max_posting_visits
            else "within_visits_only_not_retrieval_or_safety_pass"
        ),
        "over_budget_postings": max(0, total - max_posting_visits),
        "unused_posting_visit_budget": max(0, max_posting_visits - total),
        "postings_by_assumed_prevalence": {
            label: {"query_words": count, "assumed_photos_per_word": freq,
                    "postings": count * freq}
            for label, count, freq in components
        },
        "note": (
            "Synthetic exact counts only; one distinct word has one posting per indexed photo. "
            "No ORB/real-photo frequency histogram, feature correlations, approximate retrieval "
            "recall, false-match evidence, distinct-ID budget, CPU/RAM/thermal or S24+ measurements. "
            "Never silently drop global words to fit: that changes rank/recall. "
            "All candidates require separate geometric verification and human review."
        ),
    }


def scenario_sensitivity(*, photos: int = DEFAULT_PHOTOS,
                         query_words: int = DEFAULT_WORDS,
                         max_posting_visits: int = DEFAULT_BUDGET) -> dict:
    """Run documented illustrative archetypes in a fixed reproducible order."""
    cases = []
    for name, global_count, scene_count, burst_count, scene_size, burst_size in DEFAULT_CASES:
        if name == "all_terms_global_upper_extreme":
            global_count, scene_count, burst_count = query_words, 0, 0
        else:
            # Preserve the 384-word archetype exactly by default; scale to
            # other feature budgets with deterministic rounding, no overflow.
            global_count = global_count * query_words // DEFAULT_WORDS
            scene_count = scene_count * query_words // DEFAULT_WORDS
            burst_count = burst_count * query_words // DEFAULT_WORDS
        if photos < scene_size:
            scene_size = photos
        if photos < burst_size:
            burst_size = photos
        if burst_size > scene_size:
            burst_size = scene_size
        output = estimate_footprint(
            photos=photos, query_words=query_words,
            globally_shared_words=global_count, scene_words=scene_count,
            burst_words=burst_count, scene_photos=scene_size,
            burst_photos=burst_size, max_posting_visits=max_posting_visits,
        )
        cases.append({"scenario": name, "analysis": output})
    return {
        "policy": "No files/images/index generation; illustrative sensitivity, NEVER calibrated gallery prevalence or recall",
        "scenarios": cases,
        "photos": photos,
        "query_words": query_words,
        "posting_visit_budget": max_posting_visits,
        "summary": {
            "cases_abstaining_due_to_visit_budget": sum(
                c["analysis"]["visit_budget_envelope"] == "abstain_incomplete"
                for c in cases
            ),
            "scenarios_tested": len(cases),
            "no_real_camera_observations": True,
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--photos", type=int, default=DEFAULT_PHOTOS)
    parser.add_argument("--query-words", type=int, default=DEFAULT_WORDS)
    parser.add_argument("--max-posting-visits", type=int, default=DEFAULT_BUDGET)
    args = parser.parse_args()
    print(json.dumps(scenario_sensitivity(
        photos=args.photos, query_words=args.query_words,
        max_posting_visits=args.max_posting_visits,
    ), indent=2))


if __name__ == "__main__":
    main()
