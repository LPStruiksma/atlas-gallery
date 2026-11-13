#!/usr/bin/env python3
"""Locked-threshold open-set identity risk under complete gallery expansion.

Offline research over precomputed, opaque-ID cosine scores only. Never reads
photos, People databases, model checkpoints or embeddings, and never confirms
an identity. The original calibration chooses a floor ONCE; expanded scores
cannot reselect it.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import csv
import json
from pathlib import Path
import sys

from benchmark_reid_scores import (
    FIELDS, MAX_ROWS, _parse, _summarize, benchmark_model_scores,
)
from verify_split_provenance import (
    FIELDS as PROVENANCE_FIELDS, UNREVIEWED, verify_split_provenance,
)

SPLITS = ("calibration", "holdout")
MAX_GALLERY_CANDIDATES = 1000


def _inventory(rows):
    """Validate a *complete scored roster*, identical for every probe/split.

    A partial scored shortlist is NOT the gallery and cannot establish what
    additional candidates do to the top-1 result.
    """
    if not isinstance(rows, (list, tuple)) or not 1 <= len(rows) <= MAX_ROWS:
        raise ValueError("Bounded complete score roster required")
    probes = {}
    gallery = {s: None for s in SPLITS}
    for row in rows:
        # _parse validates the public scores and token vocabulary; this
        # check protects callers who invoke the inventory helper directly.
        if type(row) is not dict or set(row) != set(FIELDS):
            raise ValueError("Only opaque score schema permitted")
        key = (row["split"], row["probe_uid"])
        entries = probes.setdefault(key, {})
        candidate = row["candidate_uid"]
        if candidate == "NONE" or candidate in entries:
            raise ValueError("Gallery experiment requires scored nonempty unique candidates")
        entries[candidate] = row["similarity"]
    if any(s not in SPLITS for s, _probe in probes):
        raise ValueError("Unexpected partition")
    for (split, _probe), candidates in probes.items():
        names = frozenset(candidates)
        if not 1 <= len(names) <= MAX_GALLERY_CANDIDATES:
            raise ValueError("Unsafe gallery candidate count")
        if gallery[split] is None:
            gallery[split] = names
        elif gallery[split] != names:
            raise ValueError("Incomplete or probe-specific gallery candidate scores")
    if any(v is None for v in gallery.values()):
        raise ValueError("Calibration and holdout must both exist")
    # Closed-set known-truth enrollment must not be quietly conflated with
    # missing positive gallery identities (a retrieval-eligibility failure).
    for row in rows:
        if (row["truth_uid"] != "UNKNOWN"
                and row["truth_uid"] not in gallery[row["split"]]):
            raise ValueError("Known truth absent from complete scored gallery")
    return probes, gallery


def _decision(trial, threshold, margin):
    probe, session, condition, truth, score, candidate, tied, gap = trial
    if (candidate is None or tied or score < threshold
            or (margin > 0 and (gap is None or gap < margin))):
        return "abstain"
    if truth == "UNKNOWN":
        return "unsafe_unknown"
    return "correct_known" if candidate == truth else "unsafe_known"


def _connected_components(provenance_rows, parsed_baseline):
    """Transitive graph of declared photo lineage, event and subject groups.

    Any same-source edge joins a connected capture component. Group
    declarations do not themselves prove all physical repetitions were
    labelled, so independence is ALWAYS unverified.
    """
    verify_split_provenance(provenance_rows, parsed_baseline)
    parent = {trial[0]: trial[0]
              for split in SPLITS for trial in parsed_baseline[split]}
    truth = {trial[0]: trial[3]
             for split in SPLITS for trial in parsed_baseline[split]}
    def root(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    def join(a, b):
        x, y = root(a), root(b)
        if x != y:
            # Stable union direction keeps reports invariant to row order.
            parent[max(x, y)] = min(x, y)
    already = {}
    for row in provenance_rows:
        probe, split = row["probe_uid"], row["split"]
        grouping = [
            ("lineage", row["photo_lineage_uid"]),
            ("event", row["event_cluster_uid"]),
        ]
        if row["subject_cluster_uid"] != UNREVIEWED:
            grouping.append(("subject", row["subject_cluster_uid"]))
        if truth[probe] != "UNKNOWN":
            grouping.append(("known_truth", truth[probe]))
        for family, value in grouping:
            key = (split, family, value)
            if key in already:
                join(probe, already[key])
            else:
                already[key] = probe
    return {probe: root(probe) for probe in parent}


def _paired(top_old, top_new, threshold, margin, components=None):
    old = {t[0]: t for t in top_old}
    new = {t[0]: t for t in top_new}
    if set(old) != set(new):
        raise ValueError("Different probe rosters cannot be paired")
    counters = defaultdict(int)
    sessions = {
        "unknown": set(), "known": set(), "new_unsafe_unknown": set(),
        "new_unsafe_known": set(),
    }
    grouped = {kind: set() for kind in (
        "unknown", "known", "new_unsafe_unknown", "new_unsafe_known")}
    for probe in sorted(old):
        a, b = old[probe], new[probe]
        if a[:4] != b[:4]:
            raise ValueError("Truth, pose, session or probe changed between snapshots")
        kind = "unknown" if a[3] == "UNKNOWN" else "known"
        sessions[kind].add(a[1])
        if components is not None:
            grouped[kind].add(components[probe])
        before, after = _decision(a, threshold, margin), _decision(b, threshold, margin)
        counters["observations"] += 1
        counters[kind + "_observations"] += 1
        counters["old_" + before] += 1
        counters["expanded_" + after] += 1
        # A false offer that already existed must not be described as new.
        if after == "unsafe_unknown" and before != "unsafe_unknown":
            counters["new_unsafe_unknown_offers"] += 1
            sessions["new_unsafe_unknown"].add(a[1])
            if components is not None:
                grouped["new_unsafe_unknown"].add(components[probe])
        if after == "unsafe_known" and before != "unsafe_known":
            counters["new_unsafe_known_offers"] += 1
            sessions["new_unsafe_known"].add(a[1])
            if components is not None:
                grouped["new_unsafe_known"].add(components[probe])
        if before == "unsafe_unknown" and after != "unsafe_unknown":
            counters["resolved_unsafe_unknown_offers"] += 1
        if before == "unsafe_known" and after != "unsafe_known":
            counters["resolved_unsafe_known_offers"] += 1
        if after == "correct_known" and before != "correct_known":
            counters["new_correct_known_offers"] += 1
        if before == "correct_known" and after != "correct_known":
            counters["lost_correct_known_offers"] += 1
    result = {
        "observations": counters["observations"],
        "known_observations": counters["known_observations"],
        "unknown_observations": counters["unknown_observations"],
        "old_correct_known_offers": counters["old_correct_known"],
        "expanded_correct_known_offers": counters["expanded_correct_known"],
        "old_unsafe_unknown_offers": counters["old_unsafe_unknown"],
        "expanded_unsafe_unknown_offers": counters["expanded_unsafe_unknown"],
        "old_unsafe_known_offers": counters["old_unsafe_known"],
        "expanded_unsafe_known_offers": counters["expanded_unsafe_known"],
        "new_unsafe_unknown_offers": counters["new_unsafe_unknown_offers"],
        "new_unsafe_known_offers": counters["new_unsafe_known_offers"],
        "resolved_unsafe_unknown_offers": counters["resolved_unsafe_unknown_offers"],
        "resolved_unsafe_known_offers": counters["resolved_unsafe_known_offers"],
        "new_correct_known_offers": counters["new_correct_known_offers"],
        "lost_correct_known_offers": counters["lost_correct_known_offers"],
        "distinct_unknown_sessions": len(sessions["unknown"]),
        "distinct_known_sessions": len(sessions["known"]),
        "sessions_with_new_unsafe_unknown_offer": len(sessions["new_unsafe_unknown"]),
        "sessions_with_new_unsafe_known_offer": len(sessions["new_unsafe_known"]),
        "sessions_proven_independent": False,
    }
    if components is None:
        result["connected_source_component_diagnostics"] = None
    else:
        result["connected_source_component_diagnostics"] = {
            "known_connected_components": len(grouped["known"]),
            "unknown_connected_components": len(grouped["unknown"]),
            "components_with_new_unsafe_unknown_offer":
                len(grouped["new_unsafe_unknown"]),
            "components_with_new_unsafe_known_offer":
                len(grouped["new_unsafe_known"]),
            "actual_physical_independence_proven": False,
            "unknown_subject_independence_proven": False,
        }
    return result


def audit_gallery_expansion(base_rows, expanded_rows, *,
                            max_false_offer_rate=0.05,
                            min_top_two_margin=0.0,
                            provenance_rows=None, require_provenance=False):
    """Same frozen scored probes, nested exhaustive candidate inventory.

    Not a real model experiment: synthetic scores can exhibit effects but
    cannot establish their empirical frequency or independence.
    """
    # This existing evaluator enforces baseline conservative calibration,
    # splitting, threshold and held-out no-recalibration logic.
    baseline = benchmark_model_scores(
        base_rows, max_false_offer_rate=max_false_offer_rate,
        min_top_two_margin=min_top_two_margin,
        provenance_rows=provenance_rows, require_provenance=require_provenance,
    )
    original = _parse(base_rows)
    enlarged = _parse(expanded_rows)
    components = (_connected_components(provenance_rows, original)
                  if provenance_rows is not None else None)
    original_cells, original_gallery = _inventory(base_rows)
    enlarged_cells, enlarged_gallery = _inventory(expanded_rows)
    if set(original_cells) != set(enlarged_cells):
        raise ValueError("Expansion cannot add, remove or select reviewed probes")
    gallery_sizes = {}
    for split in SPLITS:
        before, after = original_gallery[split], enlarged_gallery[split]
        if not before < after:  # strict proper subset
            raise ValueError("Each split needs additional global candidates")
        gallery_sizes[split] = {
            "baseline": len(before),
            "expanded": len(after),
            "added": len(after - before),
        }
    for key, previous in original_cells.items():
        current = enlarged_cells[key]
        if any(current.get(candidate) != score
               for candidate, score in previous.items()):
            raise ValueError("Preexisting candidate scores changed during gallery expansion")
    floor = baseline["similarity_floor"]
    margin = baseline["minimum_top_two_margin"]
    return {
        "status": "research_gallery_growth_stress_test_only",
        "locked_floor_selected_only_from_baseline_calibration": floor,
        "minimum_top_two_margin": margin,
        "gallery_candidates_by_split": gallery_sizes,
        "calibration": _paired(original["calibration"], enlarged["calibration"],
                               floor, margin, components),
        "holdout": _paired(original["holdout"], enlarged["holdout"],
                           floor, margin, components),
        "holdout_baseline_metrics": _summarize(original["holdout"], floor,
                                               min_top_two_margin=margin)["overall"],
        "holdout_expanded_metrics": _summarize(enlarged["holdout"], floor,
                                               min_top_two_margin=margin)["overall"],
        "all_old_scores_frozen": True,
        "reviewed_provenance_supplied": components is not None,
        "provenance_partition_audit": baseline["provenance_partition_audit"],
        "gallery_exhaustiveness_verified_for_supplied_candidate_lists": True,
        "physical_gallery_exhaustiveness_or_training_split_independence_proven": False,
        "gallery_addition_predeclared_before_holdout_verified": False,
        "changed_model_weights_or_threshold": False,
        "calibrated_identity_probability": None,
        "auto_confirm_allowed": False,
        "production_approved": False,
        "caveat": (
            "A larger candidate gallery can create new high-similarity false "
            "offers to Unknown people without any model or floor change. "
            "Complete supplied score rosters and identical probes are required, "
            "but this does not prove real-gallery exhaustiveness, partition "
            "independence, prospective experiment registration or accuracy. "
            "Optional connected source-component counts use transitive "
            "declared event/photo-lineage/reviewed-subject edges, not proof "
            "of independent physical captures. "
            "Report counts remain descriptive; all offers require human review."
        ),
    }


def _read_csv(path: Path):
    """Bounded strict table of opaque identifiers only, never photographs."""
    if not isinstance(path, Path) or path.is_symlink() or not path.is_file():
        raise ValueError("Expected a regular local opaque score CSV")
    if path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("Score CSV exceeds bounded size")
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, restkey="_overflow", restval=None)
        if reader.fieldnames != list(FIELDS):
            raise ValueError("Wrong scored candidate columns")
        rows = []
        for row in reader:
            if set(row) != set(FIELDS) or any(v is None for v in row.values()):
                raise ValueError("Malformed score CSV row")
            rows.append(row)
            if len(rows) > MAX_ROWS:
                raise ValueError("Too many score rows")
    return rows


def _read_provenance(path: Path):
    if not isinstance(path, Path) or path.is_symlink() or not path.is_file():
        raise ValueError("Expected an opaque source-equivalence CSV")
    if path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("Source-equivalence manifest too large")
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, restkey="_overflow", restval=None)
        if reader.fieldnames != list(PROVENANCE_FIELDS):
            raise ValueError("Wrong source-equivalence columns")
        rows = []
        for row in reader:
            if set(row) != set(PROVENANCE_FIELDS) or any(
                    value is None for value in row.values()):
                raise ValueError("Malformed source-equivalence row")
            rows.append(row)
            if len(rows) > MAX_ROWS:
                raise ValueError("Too many source-equivalence rows")
        return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline_csv", type=Path)
    parser.add_argument("expanded_csv", type=Path)
    parser.add_argument("--min-top-two-margin", type=float, default=0.0)
    parser.add_argument("--provenance-csv", type=Path)
    parser.add_argument("--require-provenance", action="store_true")
    args = parser.parse_args(argv)
    try:
        sidecar = (_read_provenance(args.provenance_csv)
                   if args.provenance_csv is not None else None)
        report = audit_gallery_expansion(
            _read_csv(args.baseline_csv), _read_csv(args.expanded_csv),
            min_top_two_margin=args.min_top_two_margin,
            provenance_rows=sidecar, require_provenance=args.require_provenance)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0
    except (OSError, UnicodeError, ValueError, TypeError, KeyError):
        print("Refused incomplete or unsafe gallery-expansion score study",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
