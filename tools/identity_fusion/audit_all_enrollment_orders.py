#!/usr/bin/env python3
"""Exact per-probe existential unsafe-offer envelope over ALL enrollment orders.

This evaluates anonymous precomputed model scores only. No photographs,
embeddings, checkpoints, device/database reads, remote calls, or links.
An existential per-probe witness is NOT a simultaneously attainable global
enrollment order, independence proof, or probability estimate.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import json
from pathlib import Path
import sys

from audit_gallery_growth import (
    SPLITS, _connected_components, _decision, _inventory, _read_csv,
    _read_provenance, audit_gallery_expansion,
)
from audit_progressive_gallery import MAX_ADDITIONS, _append_candidate
from benchmark_reid_scores import _parse


def _per_partition(baseline, final, previous_scores, grown_scores,
                   before_names, after_names, floor, margin, components):
    old = {trial[0]: trial for trial in baseline}
    final_by_probe = {trial[0]: trial for trial in final}
    if set(old) != set(final_by_probe):
        raise ValueError("Matched reviewed probes required")
    additions = sorted(after_names - before_names)
    counts = defaultdict(int)
    nominal_sessions = {
        "known": set(), "unknown": set(),
        "possible_new_known": set(), "possible_new_unknown": set(),
    }
    grouped = {key: set() for key in nominal_sessions}
    for probe, trial in old.items():
        kind = "unknown" if trial[3] == "UNKNOWN" else "known"
        previous = _decision(trial, floor, margin)
        end = _decision(final_by_probe[probe], floor, margin)
        counts[kind + "_observations"] += 1
        nominal_sessions[kind].add(trial[1])
        if components is not None:
            grouped[kind].add(components[probe])
        if previous == "unsafe_" + kind:
            counts["already_unsafe_" + kind] += 1
            continue
        counts["baseline_safe_" + kind] += 1
        # Exact single-candidate witness theorem:
        # If ANY enrolled prefix has a newly unsafe review offer, its
        # highest-scoring added impostor is strictly above every baseline
        # score; including that impostor ALONE would have at most the
        # original runner-up, and hence an equal or larger margin.
        # So checking each added identity independently is complete.
        scores = grown_scores[probe]
        prior_best = previous_scores[probe]
        witnessed = 0
        for candidate in additions:
            proposed, _second = _append_candidate(
                trial, prior_best, candidate, scores[candidate])
            if _decision(proposed, floor, margin) == "unsafe_" + kind:
                witnessed += 1
        if end == "unsafe_" + kind:
            counts["new_unsafe_at_endpoint_" + kind] += 1
        if witnessed:
            counts["possibly_new_unsafe_" + kind] += 1
            counts["total_single_candidate_witnesses_" + kind] += witnessed
            counts["max_witnesses_one_probe_" + kind] = max(
                counts["max_witnesses_one_probe_" + kind], witnessed)
            if witnessed > 1:
                counts["multiple_witness_candidates_" + kind] += 1
            nominal_sessions["possible_new_" + kind].add(trial[1])
            if components is not None:
                grouped["possible_new_" + kind].add(components[probe])
            if end != "unsafe_" + kind:
                counts["temporarily_unsafe_possible_despite_safe_endpoint_" + kind] += 1
        elif end == "unsafe_" + kind:
            raise ValueError("Endpoint unsafe but no ordering witness")
    result = {
        "known_observations": counts["known_observations"],
        "unknown_observations": counts["unknown_observations"],
        "added_candidates": len(additions),
        "candidate_orderings_enumerated": False,
        "all_candidate_orders_accounted_for_per_probe": True,
        "simultaneous_global_worst_case_order_proven": False,
    }
    for kind in ("known", "unknown"):
        result[kind] = {
            "already_unsafe_at_baseline": counts["already_unsafe_" + kind],
            "baseline_safe_observations": counts["baseline_safe_" + kind],
            "could_become_newly_unsafe_in_at_least_one_order":
                counts["possibly_new_unsafe_" + kind],
            "newly_unsafe_at_final_snapshot":
                counts["new_unsafe_at_endpoint_" + kind],
            "could_be_unsafe_temporarily_even_if_final_safe":
                counts["temporarily_unsafe_possible_despite_safe_endpoint_" + kind],
            "multiple_single_candidate_witnesses":
                counts["multiple_witness_candidates_" + kind],
            "total_single_candidate_witnesses":
                counts["total_single_candidate_witnesses_" + kind],
            "max_distinct_witnesses_one_probe":
                counts["max_witnesses_one_probe_" + kind],
            "distinct_nominal_sessions": len(nominal_sessions[kind]),
            "sessions_with_possibly_new_unsafe_offer":
                len(nominal_sessions["possible_new_" + kind]),
            "source_component_diagnostics": (
                {
                    "declared_components": len(grouped[kind]),
                    "components_with_possibly_new_unsafe_offer": len(
                        grouped["possible_new_" + kind]),
                    "independent_physical_sources_proven": False,
                }
                if components is not None else None
            ),
        }
    return result


def audit_all_enrollment_orders(base_rows, expanded_rows, *,
                                min_top_two_margin=0.0,
                                provenance_rows=None,
                                require_provenance=False):
    """Check existence of newly unsafe prefix, WITHOUT enumerating n! orders.

    Scores, reviewed identities and their split provenance are unchanged;
    original calibration alone chooses the locked threshold. No post-hoc
    order is selected by inspecting holdout outcomes.
    """
    audited = audit_gallery_expansion(
        base_rows, expanded_rows, min_top_two_margin=min_top_two_margin,
        provenance_rows=provenance_rows, require_provenance=require_provenance)
    base = _parse(base_rows)
    final = _parse(expanded_rows)
    old_scores, old_names = _inventory(base_rows)
    new_scores, new_names = _inventory(expanded_rows)
    sizes = {split: len(new_names[split] - old_names[split])
             for split in SPLITS}
    if any(not 1 <= n <= MAX_ADDITIONS for n in sizes.values()):
        raise ValueError("Order-free analysis requires 1..64 additions per split")
    components = (_connected_components(provenance_rows, base)
                  if provenance_rows is not None else None)
    before_runner = {split: {} for split in SPLITS}
    for split in SPLITS:
        for probe, info in old_scores.items():
            if probe[0] != split:
                continue
            vals = sorted((float(x) for x in info.values()), reverse=True)
            before_runner[split][probe[1]] = vals[1] if len(vals) > 1 else None
    floor = audited["locked_floor_selected_only_from_baseline_calibration"]
    margin = audited["minimum_top_two_margin"]
    result = {
        "status": "synthetic_ready_order_invariant_existential_envelope_only",
        "similarity_floor_locked_to_original_calibration": floor,
        "minimum_top_two_margin": margin,
        "candidate_additions_per_split": sizes,
        "calibration": _per_partition(
            base["calibration"], final["calibration"],
            before_runner["calibration"],
            {key[1]: data for key, data in new_scores.items()
             if key[0] == "calibration"},
            old_names["calibration"], new_names["calibration"],
            floor, margin, components),
        "holdout": _per_partition(
            base["holdout"], final["holdout"],
            before_runner["holdout"],
            {key[1]: data for key, data in new_scores.items()
             if key[0] == "holdout"},
            old_names["holdout"], new_names["holdout"],
            floor, margin, components),
        "baseline_only_calibration_and_full_roster_verified": True,
        "holdout_used_to_choose_threshold_or_order": False,
        "real_gallery_enrollment_chronology_independently_authenticated": False,
        "source_independence_or_model_accuracy_proven": False,
        "auto_confirm_allowed": False,
        "production_approved": False,
        "caveat": (
            "This is an exact per-probe existence bound over all permutations "
            "of supplied added candidates, not a maximum count under one "
            "simultaneously realizable ordering. A source-level false "
            "suggestion is not an identity or a calibrated probability; "
            "every proposal requires human confirmation."
        ),
    }
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline_csv", type=Path)
    parser.add_argument("expanded_csv", type=Path)
    parser.add_argument("--min-top-two-margin", type=float, default=0.0)
    parser.add_argument("--provenance-csv", type=Path)
    parser.add_argument("--require-provenance", action="store_true")
    args = parser.parse_args(argv)
    try:
        provenance = (_read_provenance(args.provenance_csv)
                      if args.provenance_csv is not None else None)
        answer = audit_all_enrollment_orders(
            _read_csv(args.baseline_csv), _read_csv(args.expanded_csv),
            min_top_two_margin=args.min_top_two_margin,
            provenance_rows=provenance,
            require_provenance=args.require_provenance)
        print(json.dumps(answer, indent=2, sort_keys=True))
        return 0
    except (OSError, UnicodeError, ValueError, TypeError, KeyError):
        print("Refused unsupported or unsafe enrollment-order audit",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
