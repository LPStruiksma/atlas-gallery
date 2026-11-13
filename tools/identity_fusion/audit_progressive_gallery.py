#!/usr/bin/env python3
"""Every-prefix gallery-enrollment stress test with one locked ReID threshold.

All rows are already-computed anonymous score text, NEVER image/model inputs.
A previously safe final gallery may have been UNSAFE at an intermediate size:
top-score ties and a required runner-up margin can cause a false offer to
disappear again. No repeated calibration, no confirmation, no identity IDs in
aggregate output.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
import re
import sys

from audit_gallery_growth import (
    SPLITS, _connected_components, _decision, _inventory, _paired, _read_csv,
    _read_provenance, audit_gallery_expansion,
)
from benchmark_reid_scores import _parse

ORDER_FIELDS = ("split", "candidate_uid", "enrollment_rank")
MAX_ADDITIONS = 64
RANK = re.compile(r"[1-9][0-9]*")


def _validate_order(rows, before, after):
    """A full predeclared permutation of added candidates within each split.

    No rank is inferred by sorting score, candidate ID or holdout outcomes.
    Rank 1 has exactly one newly introduced candidate *per split*.
    """
    if (type(rows) not in (list, tuple) or not 2 <= len(rows) <=
            2 * MAX_ADDITIONS):
        raise ValueError("Require a bounded two-split enrollment manifest")
    order = {name: {} for name in SPLITS}
    seen = {name: set() for name in SPLITS}
    for row in rows:
        if type(row) is not dict or set(row) != set(ORDER_FIELDS):
            raise ValueError("Unexpected or private enrollment fields")
        split, candidate, raw = (row[field] for field in ORDER_FIELDS)
        if (type(split) is not str or split not in SPLITS
                or type(candidate) is not str or
                not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", candidate)
                or type(raw) is not str or not RANK.fullmatch(raw)
                or len(raw) > 3):
            raise ValueError("Invalid enrollment rank or opaque candidate")
        rank = int(raw)
        if (not 1 <= rank <= MAX_ADDITIONS
                or rank in order[split] or candidate in seen[split]):
            raise ValueError("Repeated or unsupported enrollment event")
        order[split][rank] = candidate
        seen[split].add(candidate)
    counts = [len(order[split]) for split in SPLITS]
    if counts[0] != counts[1] or counts[0] == 0:
        raise ValueError("Require equally complete enrollment prefix lists")
    n = counts[0]
    for split in SPLITS:
        if (set(order[split]) != set(range(1, n + 1))
                or seen[split] != after[split] - before[split]):
            raise ValueError("Incomplete or cherry-picked enrollment permutation")
    return order, n


def _initial_state(old_scores, original):
    """Exact best/runner-up after complete baseline, no float gap inversion."""
    ranked_by_probe = {}
    for row in old_scores:
        key = row["split"], row["probe_uid"]
        ranked_by_probe.setdefault(key, []).append(float(row["similarity"]))
    state = {}
    for split in SPLITS:
        state[split] = {}
        for trial in original[split]:
            ordered = sorted(ranked_by_probe[(split, trial[0])], reverse=True)
            if ordered[0] != trial[4]:
                raise ValueError("Inconsistent baseline score ranking")
            state[split][trial[0]] = [trial, ordered[1] if len(ordered) > 1
                                      else None]
    return state


def _append_candidate(trial, second, candidate, raw_score):
    """Exactly replicate _parse top-1, tie and top-two-gap for one new score."""
    score = float(raw_score)
    if not math.isfinite(score) or not -1 <= score <= 1:
        raise ValueError("Unreviewed gallery expansion score")
    probe, session, condition, truth, highest, winner, tied, gap = trial
    if score > highest:
        new_second, new_highest = highest, score
        new_winner, new_tied = candidate, False
    elif score == highest:
        new_second, new_highest = highest, highest
        new_winner, new_tied = min(winner, candidate), True
    else:
        new_highest, new_winner, new_tied = highest, winner, tied
        new_second = score if second is None or score > second else second
    new_gap = (new_highest - new_second
               if new_second is not None else None)
    return ((probe, session, condition, truth, new_highest,
             new_winner, new_tied, new_gap), new_second)


def audit_enrollment_curve(base_rows, expanded_rows, enrollment_rows, *,
                           min_top_two_margin=0.0, provenance_rows=None,
                           require_provenance=False):
    """Evaluate EVERY contiguous enrollment prefix; no final-only blindspot.

    The original audit validates full roster, frozen scores, reviewed labels,
    split leakage, baseline-only risk calibration and optional provenance.
    """
    validated = audit_gallery_expansion(
        base_rows, expanded_rows, min_top_two_margin=min_top_two_margin,
        provenance_rows=provenance_rows, require_provenance=require_provenance)
    before = _parse(base_rows)
    after = _parse(expanded_rows)
    old_cells, old_pool = _inventory(base_rows)
    new_cells, new_pool = _inventory(expanded_rows)
    order, n = _validate_order(enrollment_rows, old_pool, new_pool)
    if set(old_cells) != set(new_cells):
        raise ValueError("Probe inventory changed unexpectedly")
    components = (_connected_components(provenance_rows, before)
                  if provenance_rows is not None else None)
    floor = validated["locked_floor_selected_only_from_baseline_calibration"]
    margin = validated["minimum_top_two_margin"]
    states = _initial_state(base_rows, before)
    old = {split: {t[0]: t for t in before[split]} for split in SPLITS}
    ever = {split: {"unknown": set(), "known": set()} for split in SPLITS}
    first = {split: {"unknown": {}, "known": {}} for split in SPLITS}
    curves = []
    for step in range(1, n + 1):
        this = {}
        for split in SPLITS:
            added = order[split][step]
            for probe, (previous, second) in states[split].items():
                successor = _append_candidate(
                    previous, second, added, new_cells[(split, probe)][added])
                states[split][probe] = successor
            top = [record[0] for probe, record in sorted(states[split].items())]
            this[split] = _paired(before[split], top, floor, margin, components)
            for trial in top:
                probe = trial[0]
                kind = "unknown" if trial[3] == "UNKNOWN" else "known"
                error = "unsafe_" + kind
                if (_decision(old[split][probe], floor, margin) != error
                        and _decision(trial, floor, margin) == error
                        and probe not in ever[split][kind]):
                    ever[split][kind].add(probe)
                    first[split][kind][step] = first[split][kind].get(step, 0) + 1
        curves.append({
            "enrolled_added_candidates_per_split": step,
            "calibration_gallery_candidates": len(old_pool["calibration"]) + step,
            "holdout_gallery_candidates": len(old_pool["holdout"]) + step,
            "calibration": this["calibration"],
            "holdout": this["holdout"],
        })
    # Full final-prefix parity with independently recomputed full snapshot is
    # a fail-closed, end-to-end guard on the incremental top-two algorithm.
    if (curves[-1]["calibration"] != validated["calibration"]
            or curves[-1]["holdout"] != validated["holdout"]):
        raise ValueError("Enrollment replay diverged from full-matrix result")
    summary = {}
    for split in SPLITS:
        final_errors = {kind: set() for kind in ("unknown", "known")}
        for probe, (trial, _runner) in states[split].items():
            kind = "unknown" if trial[3] == "UNKNOWN" else "known"
            if _decision(trial, floor, margin) == "unsafe_" + kind:
                final_errors[kind].add(probe)
        summary[split] = {
            "new_unsafe_unknown_ever_exposed_observations": len(ever[split]["unknown"]),
            "new_unsafe_known_ever_exposed_observations": len(ever[split]["known"]),
            "transient_unsafe_unknown_observations": len(
                ever[split]["unknown"] - final_errors["unknown"]),
            "transient_unsafe_known_observations": len(
                ever[split]["known"] - final_errors["known"]),
            "first_unsafe_unknown_exposure_by_step": [
                first[split]["unknown"].get(step, 0) for step in range(1, n+1)],
            "first_unsafe_known_exposure_by_step": [
                first[split]["known"].get(step, 0) for step in range(1, n+1)],
            "maximum_simultaneous_new_unsafe_unknown_offers": max(
                stage[split]["new_unsafe_unknown_offers"] for stage in curves),
            "maximum_simultaneous_new_unsafe_known_offers": max(
                stage[split]["new_unsafe_known_offers"] for stage in curves),
        }
    return {
        "status": "synthetic_ready_exact_prefix_gallery_stress_only",
        "source": "precomputed_opaque_scores_no_model_execution",
        "floor_locked_from_original_calibration": floor,
        "minimum_top_two_margin": margin,
        "num_enrollment_steps": n,
        "enrollment_schedule_complete_for_declared_added_candidates": True,
        "actual_prior_preregistration_and_capture_independence_proven": False,
        "correctness_of_external_original_gallery_enrollment_proven": False,
        "model_rights_and_on_device_performance_verified": False,
        "expanded_snapshot_matching_recomputed": True,
        "provenance_partition_report": validated["provenance_partition_audit"],
        "curve": curves,
        "ever_exposed_summary": summary,
        "calibrated_identity_probability": None,
        "auto_confirm_allowed": False,
        "production_approved": False,
        "caveat": ("Every enrolled candidate can create or resolve an unsafe "
                   "review offer due to ties or top-two margins; final-only "
                   "comparisons may conceal transient false offers. Counts "
                   "are descriptive; source independence, real prospective "
                   "enrollment chronology, and true-gallery completeness "
                   "are NOT certified. Every suggested identity needs explicit "
                   "human approval."),
    }


def _read_enrollment(path):
    if not isinstance(path, Path) or path.is_symlink() or not path.is_file():
        raise ValueError("Expected bounded local enrollment manifest")
    if path.stat().st_size > 16 * 1024:
        raise ValueError("Oversized enrollment manifest")
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, restkey="_overflow", restval=None)
        if reader.fieldnames != list(ORDER_FIELDS):
            raise ValueError("Malformed enrollment columns")
        rows = []
        for row in reader:
            if set(row) != set(ORDER_FIELDS) or any(v is None for v in row.values()):
                raise ValueError("Malformed enrollment event")
            rows.append(row)
            if len(rows) > 2 * MAX_ADDITIONS:
                raise ValueError("Too many enrollment events")
        return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline_csv", type=Path)
    parser.add_argument("expanded_csv", type=Path)
    parser.add_argument("enrollment_order_csv", type=Path)
    parser.add_argument("--min-top-two-margin", type=float, default=0.)
    parser.add_argument("--provenance-csv", type=Path)
    parser.add_argument("--require-provenance", action="store_true")
    args = parser.parse_args(argv)
    try:
        provenance = (_read_provenance(args.provenance_csv)
                      if args.provenance_csv is not None else None)
        report = audit_enrollment_curve(
            _read_csv(args.baseline_csv), _read_csv(args.expanded_csv),
            _read_enrollment(args.enrollment_order_csv),
            min_top_two_margin=args.min_top_two_margin,
            provenance_rows=provenance, require_provenance=args.require_provenance)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0
    except (OSError, UnicodeError, ValueError, TypeError, KeyError):
        print("Refused incomplete or unsafe gallery-enrollment evidence",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
