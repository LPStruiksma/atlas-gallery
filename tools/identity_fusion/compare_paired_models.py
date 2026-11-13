#!/usr/bin/env python3
"""Paired, offline A/B comparison of two independently calibrated re-ID models.

Only opaque precomputed scores and separately frozen cohort/roster/provenance
CSVs are accepted. Does not load photos, weights, embeddings, People data or
perform inference. Synthetic results are research-only, NEVER auto-labels.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import json
from pathlib import Path
import sys

from benchmark_reid_scores import (
    FIELDS, _parse, benchmark_model_scores,
)
from benchmark_retrieval_recall import _load_pinned
from candidate_list_coverage import FIELDS as ROSTER_FIELDS
from verify_split_provenance import FIELDS as PROVENANCE_FIELDS


def _index_scores(rows):
    grouped = defaultdict(set)
    for row in rows:
        key = row["split"], row["probe_uid"]
        grouped[key].add(row["candidate_uid"])
    return grouped


def _offer(trial, floor, margin):
    probe, session, condition, truth, score, candidate, tied, gap = trial
    if (candidate is None or tied or score < floor
            or (margin > 0 and (gap is None or gap < margin))):
        return None
    return candidate


def _transition_labels(truth, offer):
    if truth == "UNKNOWN":
        return "false_offer" if offer is not None else "abstain"
    return "abstain" if offer is None else ("correct" if offer == truth else "wrong")


def _group_deltas(a_errors, b_errors):
    return {
        "affected_in_a": len(a_errors),
        "affected_in_b": len(b_errors),
        "resolved_from_a": len(a_errors - b_errors),
        "new_in_b": len(b_errors - a_errors),
        "affected_in_both": len(a_errors & b_errors),
    }


def compare_paired_models(scores_a, scores_b, *, roster_rows, provenance_rows,
                          margin_a=0.0, margin_b=0.0,
                          max_false_offer_rate=0.05,
                          protected_condition_tags=()):
    """Require identical source cohort/candidates and reviewed Unknown groups.

    Candidate matches are compared only AFTER each model independently
    calibrates its own fixed threshold on calibration. Holdout never changes
    a model's floor, margin, candidate gallery, or allowed identity roster.
    """
    a = _parse(scores_a)
    b = _parse(scores_b)
    if _index_scores(scores_a) != _index_scores(scores_b):
        raise ValueError("Models must score exactly the same frozen gallery candidates")
    for split in ("calibration", "holdout"):
        left = {trial[0]: trial[1:4] for trial in a[split]}
        right = {trial[0]: trial[1:4] for trial in b[split]}
        if left != right:
            raise ValueError("Model cohorts differ in truth, session or condition")

    common = dict(provenance_rows=provenance_rows, require_provenance=True,
                  candidate_roster_rows=roster_rows, require_candidate_roster=True,
                  require_reviewed_unknown_subjects=True,
                  max_false_offer_rate=max_false_offer_rate,
                  protected_condition_tags=protected_condition_tags)
    # No holdout leakage: these independently select floors using calibration.
    first = benchmark_model_scores(
        scores_a, min_top_two_margin=margin_a, **common)
    second = benchmark_model_scores(
        scores_b, min_top_two_margin=margin_b, **common)
    reviewed_subject = {
        (row["split"], row["probe_uid"]): row["subject_cluster_uid"]
        for row in provenance_rows
    }
    counts = {
        "known": {f"a_{x}_to_b_{y}": 0
                  for x in ("correct", "wrong", "abstain")
                  for y in ("correct", "wrong", "abstain")},
        "unknown": {f"a_{x}_to_b_{y}": 0
                    for x in ("false_offer", "abstain")
                    for y in ("false_offer", "abstain")},
    }
    known_a, known_b = set(), set()
    unknown_a, unknown_b = set(), set()
    a_held = {t[0]: t for t in a["holdout"]}
    b_held = {t[0]: t for t in b["holdout"]}
    for uid in sorted(a_held):
        trial_a, trial_b = a_held[uid], b_held[uid]
        truth = trial_a[3]
        offer_a = _offer(trial_a, first["similarity_floor"], margin_a)
        offer_b = _offer(trial_b, second["similarity_floor"], margin_b)
        label_a = _transition_labels(truth, offer_a)
        label_b = _transition_labels(truth, offer_b)
        category = "unknown" if truth == "UNKNOWN" else "known"
        counts[category][f"a_{label_a}_to_b_{label_b}"] += 1
        if truth == "UNKNOWN":
            group = reviewed_subject[("holdout", uid)]
            if offer_a is not None:
                unknown_a.add(group)
            if offer_b is not None:
                unknown_b.add(group)
        else:
            if offer_a is not None and offer_a != truth:
                known_a.add(truth)
            if offer_b is not None and offer_b != truth:
                known_b.add(truth)
    return {
        "status": "paired_heldout_comparison_research_only",
        "paired_probe_count": len(a_held),
        "protected_condition_tags": first["protected_condition_tags"],
        "model_a": {
            "calibration_floor": first["similarity_floor"],
            "margin": margin_a,
            "heldout": first["holdout"]["overall"],
        },
        "model_b": {
            "calibration_floor": second["similarity_floor"],
            "margin": margin_b,
            "heldout": second["holdout"]["overall"],
        },
        "paired_transitions": counts,
        "distinct_known_wrong_offer_subject_groups": _group_deltas(known_a, known_b),
        "distinct_anonymous_unknown_false_offer_groups": _group_deltas(unknown_a, unknown_b),
        "paired_cohort_and_frozen_candidates_identical": True,
        "source_gallery_exhaustiveness_proven": False,
        "model_comparison_pre_registered_proven": False,
        "auto_confirm_allowed": False,
        "caveat": "Paired synthetic holdout counts are NOT calibrated identity "
                  "probabilities or deployment-grade significance. A shared "
                  "gallery, reviewed subject clusters and identical cohorts "
                  "still require independently verified provenance; do not "
                  "choose a model using repeatedly viewed heldout labels.",
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("a", "b", "roster", "provenance"):
        parser.add_argument("--" + key, type=Path, required=True)
        parser.add_argument("--" + key + "-sha256", required=True)
    parser.add_argument("--margin-a", type=float, default=0.0)
    parser.add_argument("--margin-b", type=float, default=0.0)
    parser.add_argument("--protect-condition-tag", action="append", default=[],
                        help="Optional subgroup gate; preregister externally for real comparisons")
    args = parser.parse_args(argv)
    try:
        scores_a = _load_pinned(args.a, args.a_sha256, FIELDS)
        scores_b = _load_pinned(args.b, args.b_sha256, FIELDS)
        roster = _load_pinned(args.roster, args.roster_sha256, ROSTER_FIELDS)
        provenance = _load_pinned(args.provenance, args.provenance_sha256,
                                  PROVENANCE_FIELDS)
        answer = compare_paired_models(
            scores_a, scores_b, roster_rows=roster,
            provenance_rows=provenance,
            margin_a=args.margin_a, margin_b=args.margin_b,
            protected_condition_tags=args.protect_condition_tag)
        print(json.dumps(answer, sort_keys=True, indent=2))
        return 0
    except (OSError, ValueError, TypeError):
        print("Refused unsafe or incompatible local A/B research manifests",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
