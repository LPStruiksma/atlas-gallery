#!/usr/bin/env python3
"""Exact joint candidate-gallery risk split by reviewed visibility conditions.

Offline, model-free, precomputed opaque score data only. The global
baseline calibration/threshold and full-score inventory remain frozen.
A broad average must not conceal rear/partial/occluded Unknown failures.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from audit_joint_enrollment_peak import (
    MAX_EXHAUSTIVE_ADDITIONS, MAX_PROBE_SUBSET_STATES, _partition,
    audit_joint_enrollment_peak,
)
from audit_gallery_growth import (
    SPLITS, _connected_components, _inventory, _read_csv, _read_provenance,
)
from benchmark_reid_scores import CONDITIONS, _normalize_condition, _parse

MIN_REVIEWED_PER_PRESENT_TRUTH_CLASS = 10
MAX_EXTRA_PROBE_SUBSET_STATES_PER_SPLIT = 650_000


def _requested(values, *, intersection):
    if type(values) not in (list, tuple) or len(values) > 8:
        raise ValueError("Bounded predeclared visibility requirements expected")
    canonical = set()
    for value in values:
        if type(value) is not str:
            raise ValueError("Visibility requirement must be reviewed text")
        if intersection:
            parsed = _normalize_condition(value)
            if parsed != value or "+" not in value or "unknown" in value:
                raise ValueError("Noncanonical protected visibility intersection")
        elif value not in CONDITIONS or value == "unknown":
            raise ValueError("Unsupported protected visibility tag")
        else:
            parsed = value
        if parsed in canonical:
            raise ValueError("Repeated visibility requirement")
        canonical.add(parsed)
    return canonical


def _partition_visibility(trials, scored_old, scored_expanded,
                          prior_roster, expanded_roster, floor, margin,
                          components, required_tags, required_intersections,
                          global_report):
    truth_types = ("known", "unknown")
    all_exact = sorted(set(t[2] for t in trials))
    all_tags = sorted({tag for t in trials for tag in t[2].split("+")})
    states = 1 << len(expanded_roster - prior_roster)
    if states > (1 << MAX_EXHAUSTIVE_ADDITIONS):
        raise ValueError("Exceeded bound on visibility source subsets")
    output = {}
    for family, names, protected in (
        ("exact_intersections", all_exact, required_intersections),
        ("overlapping_tags", all_tags, required_tags),
    ):
        result = {}
        suppressed = 0
        budget = 0
        for label in names:
            cohort = [t for t in trials if (
                (t[2] == label) if family == "exact_intersections"
                else (label in t[2].split("+"))
            )]
            denominators = {
                cls: sum((t[3] == "UNKNOWN") == (cls == "unknown")
                         for t in cohort) for cls in truth_types
            }
            eligible = (len(cohort) >= MIN_REVIEWED_PER_PRESENT_TRUTH_CLASS
                        and all(n == 0 or n >= MIN_REVIEWED_PER_PRESENT_TRUTH_CLASS
                                for n in denominators.values()))
            if label in protected and (
                not eligible or denominators["unknown"] <
                MIN_REVIEWED_PER_PRESENT_TRUTH_CLASS
            ):
                raise ValueError("Protected Unknown visibility cohort underpowered")
            if not eligible:
                suppressed += 1
                continue
            budget += len(cohort) * states
            if budget > MAX_EXTRA_PROBE_SUBSET_STATES_PER_SPLIT:
                raise ValueError("Condition breakdown exceeds bounded work budget")
            partition = _partition(
                cohort, scored_old, scored_expanded,
                prior_roster, expanded_roster, floor, margin, components)
            if (partition["peak_joint_simultaneous_new_unsafe_observations"]
                    > global_report["peak_joint_simultaneous_new_unsafe_observations"]
                    or partition["peak_simultaneous_new_unknown_observations"]
                    > global_report["peak_simultaneous_new_unknown_observations"]
                    or partition["peak_simultaneous_new_known_observations"]
                    > global_report["peak_simultaneous_new_known_observations"]):
                raise ValueError("Condition result contradicts complete gallery")
            result[label] = {
                "known_reviewed_observations": denominators["known"],
                "unknown_reviewed_observations": denominators["unknown"],
                "peak_joint_new_unsafe_observations":
                    partition["peak_joint_simultaneous_new_unsafe_observations"],
                "peak_new_unsafe_unknown_observations":
                    partition["peak_simultaneous_new_unknown_observations"],
                "peak_new_unsafe_known_observations":
                    partition["peak_simultaneous_new_known_observations"],
                "endpoint_new_unsafe_unknown_observations":
                    partition["newly_unsafe_unknown_at_expanded_endpoint"],
                "endpoint_new_unsafe_known_observations":
                    partition["newly_unsafe_known_at_expanded_endpoint"],
                "unknown_possible_in_some_prefix":
                    partition["newly_unsafe_unknown_possible_in_some_prefix"],
                "known_possible_in_some_prefix":
                    partition["newly_unsafe_known_possible_in_some_prefix"],
                "max_joint_new_unsafe_by_gallery_size": [
                    s["max_joint_new_unsafe_observations"]
                    for s in partition["by_gallery_size"]],
                "max_unknown_new_unsafe_by_gallery_size": [
                    s["max_new_unsafe_unknown_observations"]
                    for s in partition["by_gallery_size"]],
                "max_known_new_unsafe_by_gallery_size": [
                    s["max_new_unsafe_known_observations"]
                    for s in partition["by_gallery_size"]],
                "max_unknown_declared_source_components_by_gallery_size": (
                    [s["max_unknown_connected_components_affected"]
                     for s in partition["by_gallery_size"]]
                    if components is not None else None),
                "physical_capture_independence_proven": False,
            }
        # Requiring absent conditions MUST fail instead of silently making
        # the protected denominator look better.
        if not protected <= set(result):
            raise ValueError("Missing required reviewed Unknown visibility cohort")
        output[family] = {
            "cohorts": result,
            "suppressed_underpowered_cohort_count": suppressed,
            "present_reported_cohort_count": len(result),
            "non_disjoint_with_other_visibility_families": True,
            "subgroup_counts_may_not_be_summed_as_independent_trials": True,
        }
    return output


def audit_joint_visibility(base_rows, expanded_rows, *,
                           min_top_two_margin=0.0,
                           provenance_rows=None, require_provenance=False,
                           require_unknown_tags=(),
                           require_unknown_intersections=()):
    """Require full baseline calibration and reconstruct protected strata.

    Condition-specific results are descriptive; no threshold, gallery or
    model is chosen using the holdout. Absent protected rear/occluded
    Unknown evidence fails closed rather than becoming an apparent pass.
    """
    tags = _requested(require_unknown_tags, intersection=False)
    exact = _requested(require_unknown_intersections, intersection=True)
    full = audit_joint_enrollment_peak(
        base_rows, expanded_rows,
        min_top_two_margin=min_top_two_margin,
        provenance_rows=provenance_rows,
        require_provenance=require_provenance)
    original = _parse(base_rows)
    old_scores, old_roster = _inventory(base_rows)
    new_scores, new_roster = _inventory(expanded_rows)
    components = (_connected_components(provenance_rows, original)
                  if provenance_rows is not None else None)
    floor, margin = (full["floor_locked_to_original_calibration"],
                     full["minimum_top_two_margin"])
    sections = {}
    for split in SPLITS:
        old = {probe: scores for (p, probe), scores in old_scores.items()
               if p == split}
        new = {probe: scores for (p, probe), scores in new_scores.items()
               if p == split}
        sections[split] = _partition_visibility(
            original[split], old, new, old_roster[split], new_roster[split],
            floor, margin, components, tags, exact, full[split])
    return {
        "status": "descriptive_joint_visibility_subset_audit_only",
        "similarity_floor_locked_to_baseline_calibration": floor,
        "minimum_top_two_margin": margin,
        "minimum_reviewed_observations_per_present_truth_class":
            MIN_REVIEWED_PER_PRESENT_TRUTH_CLASS,
        "required_unknown_visibility_tags": sorted(tags),
        "required_unknown_exact_intersections": sorted(exact),
        "calibration": sections["calibration"],
        "holdout": sections["holdout"],
        "full_gallery_joint_extrema_reconciled": True,
        "overlapping_condition_tags_are_not_disjoint_samples": True,
        "sparse_condition_cohorts_suppressed_not_declared_safe": True,
        "independent_people_events_or_consent_proven": False,
        "sufficient_empirical_protection_or_accuracy_proven": False,
        "holdout_selected_model_or_threshold": False,
        "auto_confirm_allowed": False,
        "production_approved": False,
        "caveat": (
            "Visible rear, partial and occluded condition labels are "
            "caller-reviewed source metadata. Suppression is not evidence "
            "of safety, marginal tags overlap, and a maximum for each "
            "condition may require mutually incompatible candidate subsets. "
            "No real body identification or identity probability is validated."
        ),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline_csv", type=Path)
    parser.add_argument("expanded_csv", type=Path)
    parser.add_argument("--min-top-two-margin", type=float, default=0.0)
    parser.add_argument("--provenance-csv", type=Path)
    parser.add_argument("--require-provenance", action="store_true")
    parser.add_argument("--require-unknown-tag", action="append", default=[])
    parser.add_argument("--require-unknown-intersection", action="append",
                        default=[])
    args = parser.parse_args(argv)
    try:
        sidecar = (_read_provenance(args.provenance_csv)
                   if args.provenance_csv is not None else None)
        report = audit_joint_visibility(
            _read_csv(args.baseline_csv), _read_csv(args.expanded_csv),
            min_top_two_margin=args.min_top_two_margin,
            provenance_rows=sidecar,
            require_provenance=args.require_provenance,
            require_unknown_tags=args.require_unknown_tag,
            require_unknown_intersections=args.require_unknown_intersection)
        print(json.dumps(report, sort_keys=True, indent=2))
        return 0
    except (OSError, UnicodeError, ValueError, TypeError, KeyError):
        print("Refused unsafe or unsupported visibility cohort audit",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
