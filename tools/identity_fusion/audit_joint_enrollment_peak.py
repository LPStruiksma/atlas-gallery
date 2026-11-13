#!/usr/bin/env python3
"""Exact worst *simultaneously attainable* false-offer count by gallery size.

Read-only, model-free research on precomputed opaque score CSVs. Every subset
of enrolled additions can be a prefix of a candidate-enrollment order.
Unlike per-probe existential envelopes, counts here are jointly attainable
at a SINGLE gallery state. Exhaustive enumeration is deliberately capped.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from audit_gallery_growth import (
    SPLITS, _connected_components, _decision, _inventory, _read_csv,
    _read_provenance, audit_gallery_expansion,
)
from audit_all_enrollment_orders import audit_all_enrollment_orders
from audit_progressive_gallery import _append_candidate
from benchmark_reid_scores import _parse

MAX_EXHAUSTIVE_ADDITIONS = 10
MAX_PROBE_SUBSET_STATES = 200_000


def _partition(subset_rows, old_cells, expanded_cells,
               old_pool, new_pool, floor, margin, components):
    additions = sorted(new_pool - old_pool)
    count = len(additions)
    if not 1 <= count <= MAX_EXHAUSTIVE_ADDITIONS:
        raise ValueError("Exact joint study requires 1..10 gallery additions")
    states = 1 << count
    if len(subset_rows) * states > MAX_PROBE_SUBSET_STATES:
        raise ValueError("Exact joint gallery subset calculation exceeds budget")
    wrong_unknown = [0] * states
    wrong_known = [0] * states
    unknown_sessions = [set() for _ in range(states)]
    known_sessions = [set() for _ in range(states)]
    unknown_components = ([set() for _ in range(states)]
                          if components is not None else None)
    known_components = ([set() for _ in range(states)]
                        if components is not None else None)
    possible_unknown = set()
    possible_known = set()
    for trial in subset_rows:
        probe = trial[0]
        score_set = old_cells[probe]
        all_scores = expanded_cells[probe]
        runner_scores = sorted((float(x) for x in score_set.values()),
                               reverse=True)
        first = (trial, runner_scores[1] if len(runner_scores) > 1 else None)
        old_kind = ("unknown" if trial[3] == "UNKNOWN" else "known")
        prior = _decision(trial, floor, margin)
        # The loop state contains only ONE probe's exact ranking across all
        # subsets; bounded 2^10 memory, independent of total archive size.
        per_probe = [None] * states
        per_probe[0] = first
        for mask in range(1, states):
            lowbit = mask & -mask
            added_index = lowbit.bit_length() - 1
            ancestor = per_probe[mask ^ lowbit]
            current = _append_candidate(
                ancestor[0], ancestor[1], additions[added_index],
                all_scores[additions[added_index]])
            per_probe[mask] = current
            if (prior == "unsafe_" + old_kind
                    or _decision(current[0], floor, margin)
                       != "unsafe_" + old_kind):
                continue
            if old_kind == "unknown":
                wrong_unknown[mask] += 1
                unknown_sessions[mask].add(trial[1])
                if unknown_components is not None:
                    unknown_components[mask].add(components[probe])
                possible_unknown.add(probe)
            else:
                wrong_known[mask] += 1
                known_sessions[mask].add(trial[1])
                if known_components is not None:
                    known_components[mask].add(components[probe])
                possible_known.add(probe)

    # A size-k worst-case is achieved by a *single* concrete enrolled
    # subset, never by adding separate per-person maxima that need
    # mutually incompatible candidate prefixes.
    by_size = []
    for size in range(count + 1):
        masks = [mask for mask in range(states) if mask.bit_count() == size]
        entry = {
            "added_candidates_enrolled": size,
            "distinct_reachable_gallery_subsets": len(masks),
            "max_new_unsafe_unknown_observations": max(
                wrong_unknown[mask] for mask in masks),
            "max_new_unsafe_known_observations": max(
                wrong_known[mask] for mask in masks),
            "max_joint_new_unsafe_observations": max(
                wrong_unknown[mask] + wrong_known[mask] for mask in masks),
            "max_unknown_sessions_affected": max(
                len(unknown_sessions[mask]) for mask in masks),
            "max_known_sessions_affected": max(
                len(known_sessions[mask]) for mask in masks),
        }
        if components is not None:
            entry["max_unknown_connected_components_affected"] = max(
                len(unknown_components[mask]) for mask in masks)
            entry["max_known_connected_components_affected"] = max(
                len(known_components[mask]) for mask in masks)
            entry["physical_capture_independence_proven"] = False
        by_size.append(entry)
    final_mask = states - 1
    return {
        "added_candidates": count,
        "total_reachable_gallery_subsets_including_baseline": states,
        "total_nonbaseline_subsets_enumerated": states - 1,
        "by_gallery_size": by_size,
        "peak_simultaneous_new_unknown_observations": max(wrong_unknown),
        "peak_simultaneous_new_known_observations": max(wrong_known),
        "peak_joint_simultaneous_new_unsafe_observations": max(
            a + b for a, b in zip(wrong_unknown, wrong_known)),
        "newly_unsafe_unknown_at_expanded_endpoint":
            wrong_unknown[final_mask],
        "newly_unsafe_known_at_expanded_endpoint":
            wrong_known[final_mask],
        "newly_unsafe_unknown_possible_in_some_prefix":
            len(possible_unknown),
        "newly_unsafe_known_possible_in_some_prefix":
            len(possible_known),
        "maxima_across_truth_classes_need_not_share_one_subset": True,
        "gallery_subsets_are_reachable_prefixes_not_actual_history": True,
        "physical_capture_independence_proven": False,
    }


def audit_joint_enrollment_peak(base_rows, expanded_rows, *,
                                min_top_two_margin=0.0,
                                provenance_rows=None, require_provenance=False):
    """Exact, capped joint false-offer peak over every candidate subset.

    Existing audits handle locked calibration and all per-probe witness
    upper bounds. The new study must never be used to select or change the
    actual production model/threshold based on holdout outcomes.
    """
    paired = audit_gallery_expansion(
        base_rows, expanded_rows,
        min_top_two_margin=min_top_two_margin,
        provenance_rows=provenance_rows,
        require_provenance=require_provenance)
    envelope = audit_all_enrollment_orders(
        base_rows, expanded_rows,
        min_top_two_margin=min_top_two_margin,
        provenance_rows=provenance_rows,
        require_provenance=require_provenance)
    old = _parse(base_rows)
    old_cells, old_pool = _inventory(base_rows)
    new_cells, new_pool = _inventory(expanded_rows)
    components = (_connected_components(provenance_rows, old)
                  if provenance_rows is not None else None)
    floor = paired["locked_floor_selected_only_from_baseline_calibration"]
    margin = paired["minimum_top_two_margin"]
    result = {}
    for split in SPLITS:
        old_by_probe = {probe: values
                        for (row_split, probe), values in old_cells.items()
                        if row_split == split}
        new_by_probe = {probe: values
                        for (row_split, probe), values in new_cells.items()
                        if row_split == split}
        section = _partition(
            old[split], old_by_probe, new_by_probe,
            old_pool[split], new_pool[split], floor, margin, components)
        # Independent full-matrix endpoint and per-probe existential
        # envelope must agree exactly. A malformed subset evaluator
        # must not silently publish a benign risk count.
        if (section["newly_unsafe_unknown_at_expanded_endpoint"]
                != paired[split]["new_unsafe_unknown_offers"]
                or section["newly_unsafe_known_at_expanded_endpoint"]
                != paired[split]["new_unsafe_known_offers"]
                or section["newly_unsafe_unknown_possible_in_some_prefix"]
                != envelope[split]["unknown"][
                    "could_become_newly_unsafe_in_at_least_one_order"]
                or section["newly_unsafe_known_possible_in_some_prefix"]
                != envelope[split]["known"][
                    "could_become_newly_unsafe_in_at_least_one_order"]):
            raise ValueError("Independent source-model audit disagrees")
        result[split] = section
    return {
        "status": "exact_joint_gallery_subset_research_only",
        "floor_locked_to_original_calibration": floor,
        "minimum_top_two_margin": margin,
        "calibration": result["calibration"],
        "holdout": result["holdout"],
        "all_permutation_prefix_subsets_evaluated_within_cap": True,
        "independent_endpoint_and_order_free_envelope_reconciled": True,
        "worst_case_each_section_attainable_at_one_subgallery": True,
        "same_subset_simultaneously_maximizes_every_metric": False,
        "candidate_enrollment_history_authenticated": False,
        "holdout_tuned_calibration_or_model": False,
        "genuine_gallery_roster_or_capture_independence_proven": False,
        "calibrated_identity_probability": None,
        "auto_confirm_allowed": False,
        "production_approved": False,
        "caveat": ("Each subset is a reachable prefix in some ordering, not "
                   "evidence of actual gallery chronology. Maximum known, "
                   "Unknown, source-component and combined counts may occur "
                   "at DIFFERENT subsets. Descriptive score-only research, "
                   "not a real identity model validation or production gate."),
    }


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
        result = audit_joint_enrollment_peak(
            _read_csv(args.baseline_csv), _read_csv(args.expanded_csv),
            min_top_two_margin=args.min_top_two_margin,
            provenance_rows=provenance,
            require_provenance=args.require_provenance)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (OSError, UnicodeError, ValueError, TypeError, KeyError):
        print("Refused unsupported exact gallery subset study",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
