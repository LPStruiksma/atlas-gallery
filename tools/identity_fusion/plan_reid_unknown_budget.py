#!/usr/bin/env python3
"""Prospective open-set error-bound sizing on synthetic aggregate counts only.

This is NOT model inference, a sampling-power calculation, an identity
probability, or an approval gate. No person, image, score or database is read.
"""
from __future__ import annotations

import argparse
import json
import math
from statistics import NormalDist

ALPHA_FAMILY = 0.05
ERROR_CEILING = 0.05
MAX_GROUPS = 1_000_000
MAX_CHECKS = 12
MAX_PLANNED_ERRORS = 5


def wilson_upper(errors: int, groups: int, *, checks: int = 1) -> float | None:
    """One-sided Wilson bound; Bonferroni adjusts the family of fixed checks."""
    if type(groups) is not int or type(errors) is not int or type(checks) is not int:
        raise ValueError("Only integer aggregate counts are permitted")
    if not 0 <= groups <= MAX_GROUPS or not 0 <= errors <= groups:
        raise ValueError("Invalid independent-group or error count")
    if not 1 <= checks <= MAX_CHECKS:
        raise ValueError("Predeclared check count outside supported range")
    if groups == 0:
        return None
    z = NormalDist().inv_cdf(1 - ALPHA_FAMILY / checks)
    z2 = z * z
    p = errors / groups
    return min(1.0, (p + z2 / (2 * groups)
                     + z * math.sqrt(p * (1 - p) / groups
                                     + z2 / (4 * groups * groups)))
               / (1 + z2 / groups))


def minimum_groups(*, checks: int, allowed_errors: int) -> int:
    """Smallest number of independent groups giving the frozen <=5% bound.

    The search uses the full-precision value, never a rounded display value.
    This is a sample adequacy illustration, not a guarantee of independence.
    """
    if type(allowed_errors) is not int or not 0 <= allowed_errors <= MAX_PLANNED_ERRORS:
        raise ValueError("Invalid planned error allowance")
    if type(checks) is not int or not 1 <= checks <= MAX_CHECKS:
        raise ValueError("Invalid prespecified family size")
    low = max(1, allowed_errors)
    high = MAX_GROUPS
    if wilson_upper(allowed_errors, high, checks=checks) > ERROR_CEILING:
        raise ValueError("Requested scenario exceeds bounded design capacity")
    while low < high:
        mid = (low + high) // 2
        if wilson_upper(allowed_errors, mid, checks=checks) <= ERROR_CEILING:
            high = mid
        else:
            low = mid + 1
    return low


def plan(*, checks: int = 7, max_errors: int = 1, observations: int = 0,
         components: int = 0, error_components: int = 0) -> dict:
    """Calculate a prospective fixed-family scenario without any row-level data.

    A component is a REVIEWED independent source unit, not a photograph.
    Repeated crops/frames/subjects linked by capture lineage/event/session
    must be collapsed before supplying these aggregate inputs.
    """
    for value in (checks, max_errors, observations, components, error_components):
        if type(value) is not int:
            raise ValueError("Inputs must be exact integer counts")
    if not 1 <= checks <= MAX_CHECKS or not 0 <= max_errors <= MAX_PLANNED_ERRORS:
        raise ValueError("Invalid predeclared family or planned error allowance")
    if not (0 <= error_components <= components <= observations <= MAX_GROUPS):
        raise ValueError("Contradictory aggregate capture-dependency counts")
    target = {str(k): minimum_groups(checks=checks, allowed_errors=k)
              for k in range(max_errors + 1)}
    observed_bound = wilson_upper(error_components, components, checks=checks)
    return {
        "status": "research_planning_only",
        "familywise_one_sided_alpha": ALPHA_FAMILY,
        "predeclared_simultaneous_checks": checks,
        "bonferroni_one_sided_alpha_per_check": round(ALPHA_FAMILY / checks, 9),
        "fixed_any_false_offer_risk_ceiling": ERROR_CEILING,
        "minimum_independent_components_by_allowed_error_count": target,
        "illustrative_observed": {
            "photo_or_probe_observations": observations,
            "declared_independent_capture_components": components,
            "capture_components_with_any_false_offer": error_components,
            "one_sided_adjusted_wilson_upper": (
                round(observed_bound, 6) if observed_bound is not None else None
            ),
            "meets_numeric_bound": (
                observed_bound <= ERROR_CEILING
                if observed_bound is not None else False
            ),
            "observations_are_not_independent_sample_count": True,
        },
        "source_group_independence_physically_verified": False,
        "actual_model_or_person_accuracy_measured": False,
        "pre_holdout_commitment_verified": False,
        "peeking_or_multiple_model_selection_corrected": False,
        "identity_auto_confirmation_allowed": False,
        "production_approved": False,
        "warning": (
            "Purely prospective arithmetic. Register all tests, conditions, "
            "models, thresholds, exclusions and stopping rules before holdout. "
            "Bonferroni covers only the explicitly counted fixed checks, not "
            "adaptive model search or repeated peeking. Declared component "
            "labels cannot prove independent physical captures or exchangeable "
            "risk across years, clothing, cameras and body poses. A Wilson "
            "bound assumes independent representative Bernoulli units and "
            "is not a formal power calculation or Android release gate."
        ),
    }


def _nonnegative_int(raw: str) -> int:
    try:
        if not raw.isascii() or not raw.isdecimal():
            raise ValueError
        result = int(raw)
        if result > MAX_GROUPS:
            raise ValueError
        return result
    except (ValueError, OverflowError):
        raise argparse.ArgumentTypeError("Expected bounded nonnegative integer") from None


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checks", type=_nonnegative_int, default=7,
                        help="Number of PREDECLARED simultaneous checks (default 7)")
    parser.add_argument("--max-errors", type=_nonnegative_int, default=1)
    parser.add_argument("--observations", type=_nonnegative_int, default=0)
    parser.add_argument("--components", type=_nonnegative_int, default=0)
    parser.add_argument("--error-components", type=_nonnegative_int, default=0)
    args = parser.parse_args(argv)
    try:
        print(json.dumps(plan(
            checks=args.checks, max_errors=args.max_errors,
            observations=args.observations, components=args.components,
            error_components=args.error_components,
        ), indent=2, sort_keys=True))
        return 0
    except ValueError:
        parser.error("Refused invalid or contradictory aggregate study counts")


if __name__ == "__main__":
    raise SystemExit(main())
