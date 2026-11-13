"""Aggregate-only bounded query-batch audit, never a source-exposure classifier.

Consumes synthetic numeric visual-word requests against an ALREADY verified
PinnedPostingQuery. No photo access, no paths, no uploads, no database writes.
Reports completion and distinct abstention types before conditional ranking.
"""
from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from .bounded_posting_query import PostingBudgetExceeded, PinnedPostingQuery
from .external_index import DOC_MASK

MAX_BATCH_QUERIES = 1000


def audit_query_batch(
    index: PinnedPostingQuery,
    requests: Iterable[tuple[Iterable[int], int | None]],
    *, max_queries: int = 100,
    max_posting_visits: int = 250_000,
    max_distinct_documents: int = 100_000,
    top_k: int = 20,
) -> dict[str, Any]:
    """Count completed/abstained/empty searches without recording query details.

    The expected_id argument is optional synthetic ground-truth document ID
    used only for controlled regression fixtures. Real manually curated
    exposure truth must not be inferred from matches or filenames.
    An abstention is NEVER in the conditional accuracy denominator.
    """
    if type(max_queries) is not int or not 1 <= max_queries <= MAX_BATCH_QUERIES:
        raise ValueError("max_queries must be integer 1..1000")
    if type(max_posting_visits) is not int or not 1 <= max_posting_visits <= 10_000_000:
        raise ValueError("max_posting_visits must be integer 1..10000000")
    if type(max_distinct_documents) is not int or not 1 <= max_distinct_documents <= 1_000_000:
        raise ValueError("max_distinct_documents must be integer 1..1000000")
    if type(top_k) is not int or not 1 <= top_k <= 100:
        raise ValueError("top_k must be integer 1..100")
    totals = {
        "queries": 0,
        "completed": 0,
        "completed_empty_shortlist": 0,
        "completed_with_shortlist": 0,
        "abstained_posting_visits": 0,
        "abstained_distinct_documents": 0,
        "labeled_synthetic_queries": 0,
        "labeled_completed_queries": 0,
        "labeled_abstained_queries": 0,
        "synthetic_top1_hits_among_completed": 0,
        "synthetic_topk_hits_among_completed": 0,
    }
    visits = 0
    for request in requests:
        totals["queries"] += 1
        if totals["queries"] > max_queries:
            raise ValueError("Query batch exceeds declared bound; no partial audit output")
        if not isinstance(request, (tuple, list)) or len(request) != 2:
            raise ValueError("Each request must be (bounded_word_iterable, optional_expected_id)")
        words, expected_id = request
        if expected_id is not None and (type(expected_id) is not int
                                        or not 0 <= expected_id <= DOC_MASK):
            raise ValueError("Synthetic ground-truth ID must be uint32 or None")
        labeled = expected_id is not None
        totals["labeled_synthetic_queries"] += labeled
        try:
            result = index.query(
                words, top_k=top_k,
                max_posting_visits=max_posting_visits,
                max_distinct_documents=max_distinct_documents,
            )
        except PostingBudgetExceeded as exc:
            if exc.reason == "posting_visits":
                totals["abstained_posting_visits"] += 1
            elif exc.reason == "distinct_documents":
                totals["abstained_distinct_documents"] += 1
            else:
                raise ValueError("Unknown budget reason; cannot report audit") from exc
            totals["labeled_abstained_queries"] += labeled
            continue
        totals["completed"] += 1
        visits += result["postings_visited_excluding_self"]
        matches = result["matches"]
        totals["completed_with_shortlist" if matches else "completed_empty_shortlist"] += 1
        if labeled:
            totals["labeled_completed_queries"] += 1
            totals["synthetic_top1_hits_among_completed"] += bool(
                matches and matches[0]["photo_id"] == expected_id
            )
            totals["synthetic_topk_hits_among_completed"] += any(
                match["photo_id"] == expected_id for match in matches
            )
    if not totals["queries"]:
        raise ValueError("Cannot audit an empty query batch")
    completed_labeled = totals["labeled_completed_queries"]
    total_labeled = totals["labeled_synthetic_queries"]
    abstained_labeled = totals["labeled_abstained_queries"]

    def all_labeled_bounds(hit_count: int) -> dict:
        """Deterministic censoring interval, NOT statistical confidence."""
        if not total_labeled:
            return {
                "lower_fraction": None, "upper_fraction": None,
                "known_hits": hit_count, "unresolved_abstentions": abstained_labeled,
                "all_labeled_denominator": 0,
            }
        # Completed misses remain misses. Abstentions may contain zero
        # matches (worst case) or all matches (optimistic possibility).
        return {
            "lower_fraction": round(hit_count / total_labeled, 4),
            "upper_fraction": round((hit_count + abstained_labeled) / total_labeled, 4),
            "known_hits": hit_count,
            "unresolved_abstentions": abstained_labeled,
            "all_labeled_denominator": total_labeled,
        }

    return {
        "policy": (
            "Offline numeric diagnostics only. Incomplete/abstained != negative "
            "and completed match != certified same photographic exposure."
        ),
        "aggregate": totals,
        "complete_fraction": round(totals["completed"] / totals["queries"], 4),
        "mean_postings_visited_per_completed_query": (
            round(visits / totals["completed"], 2) if totals["completed"] else None
        ),
        "synthetic_conditional_top1_fraction": (
            round(totals["synthetic_top1_hits_among_completed"] / completed_labeled, 4)
            if completed_labeled else None
        ),
        "synthetic_conditional_topk_fraction": (
            round(totals["synthetic_topk_hits_among_completed"] / completed_labeled, 4)
            if completed_labeled else None
        ),
        "accuracy_denominator_is_only_completed_labeled_queries": True,
        "synthetic_all_labeled_top1_censoring_bounds": all_labeled_bounds(
            totals["synthetic_top1_hits_among_completed"]
        ),
        "synthetic_all_labeled_topk_censoring_bounds": all_labeled_bounds(
            totals["synthetic_topk_hits_among_completed"]
        ),
        "bounds_are_deterministic_not_confidence_intervals": True,
        "no_camera_photo_or_android_claim": True,
        "warnings": (
            "Conditional scores exclude abstentions and are selection-biased. "
            "Do not extrapolate to all queries, all photographic variants or "
            "source-exposure identity. Correlated synthetic requests do not "
            "constitute independent statistical observations. Report completion "
            "and abstention fractions together with conditional top-k. "
            "All-labelled lower/upper bounds treat every abstention as unknown, "
            "not a miss or a hit. These are deterministic censoring limits, "
            "not statistical confidence intervals or deployment-wide recall."
        ),
    }
