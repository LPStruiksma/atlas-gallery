"""Privacy-first condition-stratified *synthetic numeric* retrieval audit.

Averaging across easy edits can conceal hard crops/rotations or budget
abstentions. All outcomes are produced by the same bounded pinned-index
query_batch_audit; no files/photos/camera evidence are read by this helper.
"""
from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from .query_batch_audit import audit_query_batch, MAX_BATCH_QUERIES

CONDITIONS = (
    "unmodified", "resized", "gamma", "cropped",
    "rotated_crop", "jpeg_reencode",
)


def audit_conditions(
    index: Any,
    records: Iterable[tuple[str, Iterable[int], int | None]],
    *, max_queries: int = 100,
    max_posting_visits: int = 250_000,
    max_distinct_documents: int = 100_000,
    top_k: int = 20,
) -> dict:
    """Stratify a bounded synthetic batch without losing Unknown denominators.

    Inputs may be arbitrary synthetic edits; condition names are caller-
    assigned labels and do not prove the indexed photo or edit relationship.
    Each category produces its own aggregate counts, conditional metrics and
    deterministic ALL-labelled censoring range. Empty categories remain null,
    never asserted to have zero failures.
    """
    if type(max_queries) is not int or not 1 <= max_queries <= MAX_BATCH_QUERIES:
        raise ValueError("max_queries must be integer 1..1000")
    # Validate budget settings even for an empty or malformed collection.
    for name, value, high in (
        ("max_posting_visits", max_posting_visits, 10_000_000),
        ("max_distinct_documents", max_distinct_documents, 1_000_000),
        ("top_k", top_k, 100),
    ):
        if type(value) is not int or not 1 <= value <= high:
            raise ValueError(f"{name} must be integer 1..{high}")

    buckets: dict[str, list[tuple[Iterable[int], int | None]]] = {
        name: [] for name in CONDITIONS
    }
    received = 0
    for record in records:
        received += 1
        if received > max_queries:
            raise ValueError("Condition batch exceeds declared bound; no partial result")
        if not isinstance(record, (tuple, list)) or len(record) != 3:
            raise ValueError("Expected (condition, visual_words, optional_synthetic_id)")
        condition, words, label = record
        if type(condition) is not str or condition not in buckets:
            raise ValueError("Unrecognized synthetic edit condition")
        buckets[condition].append((words, label))
    if not received:
        raise ValueError("No condition-labelled synthetic queries supplied")

    per_condition: dict[str, dict | None] = {}
    aggregated: dict[str, int] | None = None
    for condition in CONDITIONS:
        subset = buckets[condition]
        if not subset:
            per_condition[condition] = None
            continue
        outcome = audit_query_batch(
            index, subset, max_queries=len(subset),
            max_posting_visits=max_posting_visits,
            max_distinct_documents=max_distinct_documents, top_k=top_k,
        )
        per_condition[condition] = {
            "aggregate": outcome["aggregate"],
            "complete_fraction": outcome["complete_fraction"],
            "synthetic_conditional_top1_fraction": outcome["synthetic_conditional_top1_fraction"],
            "synthetic_conditional_topk_fraction": outcome["synthetic_conditional_topk_fraction"],
            "synthetic_all_labeled_top1_censoring_bounds": outcome["synthetic_all_labeled_top1_censoring_bounds"],
            "synthetic_all_labeled_topk_censoring_bounds": outcome["synthetic_all_labeled_topk_censoring_bounds"],
        }
        if aggregated is None:
            aggregated = dict(outcome["aggregate"])
        else:
            for key, value in outcome["aggregate"].items():
                aggregated[key] += value
    assert aggregated is not None

    total = aggregated["queries"]
    labeled = aggregated["labeled_synthetic_queries"]
    completed_labels = aggregated["labeled_completed_queries"]
    censored = aggregated["labeled_abstained_queries"]

    def bounds(hit_key: str) -> dict:
        hits = aggregated[hit_key]
        return {
            "known_hits": hits,
            "unresolved_abstentions": censored,
            "all_labeled_denominator": labeled,
            "lower_fraction": round(hits / labeled, 4) if labeled else None,
            "upper_fraction": round((hits + censored) / labeled, 4) if labeled else None,
        }

    def conditional(hit_key: str) -> float | None:
        if not completed_labels:
            return None
        return round(aggregated[hit_key] / completed_labels, 4)

    return {
        "policy": (
            "Synthetic numeric condition grouping ONLY. Human-confirmed camera source/exposure "
            "relationships are absent, and strong geometry never certifies photographic equivalence."
        ),
        "condition_order": list(CONDITIONS),
        "conditions_not_measured": [name for name in CONDITIONS if per_condition[name] is None],
        "per_condition": per_condition,
        "total": {
            "aggregate": aggregated,
            "complete_fraction": round(aggregated["completed"] / total, 4),
            "synthetic_conditional_top1_fraction": conditional("synthetic_top1_hits_among_completed"),
            "synthetic_conditional_topk_fraction": conditional("synthetic_topk_hits_among_completed"),
            "synthetic_all_labeled_top1_censoring_bounds": bounds("synthetic_top1_hits_among_completed"),
            "synthetic_all_labeled_topk_censoring_bounds": bounds("synthetic_topk_hits_among_completed"),
        },
        "bounds_are_accounting_only_not_statistical_intervals": True,
        "warnings": (
            "A condition not measured is null, not zero error. Censored queries may be hits "
            "or misses; never infer overall accuracy from completed-only ranks. "
            "Queries from one camera scene/burst are correlated, so request counts do not "
            "constitute independent scene units. No real camera/Android performance claims."
        ),
        "source_paths_or_query_ids_returned": False,
    }
