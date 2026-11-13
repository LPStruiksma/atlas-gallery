#!/usr/bin/env python3
"""Offline counterfactual correlation-sensitivity audit for V5 review offers.

No model inference, confidence calibration, People assignments, photo access,
networking, file exports or database mutations. Pessimistic stress scenarios
are diagnostic, NOT assertions that all cues really share one source.
"""
from __future__ import annotations

from collections import defaultdict
from collections.abc import Sequence
import sqlite3

from benchmark_reid_scores import _normalize_condition
from evidence_store import FAMILY_PRIORITY

MAX_EVIDENCE_PER_OBSERVATION = 5000
MAX_CANDIDATES = 100
SCENARIOS = (
    "declared_groups", "shared_source_joined",
    "nonface_collapsed", "shared_source_and_nonface_collapsed"
)


def _groups(records: list[tuple[str, str, str, str]], scenario: str) -> list[dict]:
    """Return groups after deterministic hypothetical dependence mergers."""
    raw = {}
    for family, polarity, group, source in records:
        item = raw.setdefault(group, {"supports": False, "contradicts": False,
                                      "families": set(), "sources": set()})
        item[polarity] = True
        item["families"].add(family)
        item["sources"].add(source)

    parent = {key: key for key in raw}

    def find(key):
        while parent[key] != key:
            parent[key] = parent[parent[key]]
            key = parent[key]
        return key

    def union(a, b):
        x, y = find(a), find(b)
        if x != y:
            parent[max(x, y)] = min(x, y)

    if scenario in {"shared_source_joined", "shared_source_and_nonface_collapsed"}:
        first_source = {}
        for group in sorted(raw):
            for source in sorted(raw[group]["sources"]):
                if source in first_source:
                    union(group, first_source[source])
                else:
                    first_source[source] = group

    if scenario in {"nonface_collapsed", "shared_source_and_nonface_collapsed"}:
        first_nonface = None
        for group in sorted(raw):
            if any(f != "face" for f in raw[group]["families"]):
                if first_nonface is None:
                    first_nonface = group
                else:
                    union(group, first_nonface)

    merged = {}
    for group, val in raw.items():
        dest = merged.setdefault(find(group), {"supports": False,
                                               "contradicts": False,
                                               "families": set()})
        dest["supports"] |= val["supports"]
        dest["contradicts"] |= val["contradicts"]
        dest["families"].update(val["families"])
    return list(merged.values())


def _rank_candidates(rows: list[tuple], scenario: str, minimum: int) -> int | None:
    candidates = defaultdict(list)
    for pid, family, polarity, group, source in rows:
        if family not in FAMILY_PRIORITY or polarity not in {"supports", "contradicts"}:
            raise ValueError("Incompatible evidence schema for dependency audit")
        candidates[pid].append((family, polarity, group, source))
    if len(candidates) > MAX_CANDIDATES:
        raise ValueError("Too many candidate identities for exhaustive audit")
    ranked = []
    for pid, records in candidates.items():
        groups = _groups(records, scenario)
        support = sum(g["supports"] for g in groups)
        contrad = sum(g["contradicts"] for g in groups)
        priority = max((FAMILY_PRIORITY[f] for g in groups
                        for f in g["families"]), default=0)
        face_groups = sum(g["supports"] and "face" in g["families"]
                          for g in groups)
        ranked.append((bool(contrad), -support, -priority, pid,
                       support, contrad, face_groups))
    ranked.sort()
    qualified = [item for item in ranked
                 if item[4] >= minimum and not item[5]]
    if not qualified:
        return None
    # Numerical person IDs are deterministic presentation tiebreaks,
    # NOT a source of identity evidence. Unresolved co-leaders abstain.
    if (len(qualified) > 1 and
            qualified[0][1:3] == qualified[1][1:3]):
        return None
    # Pessimistic correlation stresses must not infer a body identity
    # against a separate plausible face-supported candidate.
    if (qualified[0][6] == 0
            and any(item[3] != qualified[0][3] and item[5] == 0
                    and item[6] > 0 for item in ranked)):
        return None
    return qualified[0][3]


def _empty():
    return defaultdict(int)


def _counts(counts):
    n = counts["observations"]
    known = counts["known_observations"]
    unknown = counts["unknown_observations"]
    return {
        "observations": n,
        "known_observations": known,
        "unknown_observations": unknown,
        "review_offers": counts["review_offers"],
        "correct_known_offers": counts["correct_known_offers"],
        "wrong_known_offers": counts["wrong_known_offers"],
        "unknown_false_offers": counts["unknown_false_offers"],
        "missed_known_abstentions": counts["missed_known_abstentions"],
        "correct_unknown_abstentions": counts["correct_unknown_abstentions"],
        "offer_fraction": round(counts["review_offers"] / n, 4) if n else None,
        "wrong_known_fraction": round(counts["wrong_known_offers"] / known, 4) if known else None,
        "unknown_false_offer_fraction": round(counts["unknown_false_offers"] / unknown, 4)
        if unknown else None,
    }


def audit_correlation_sensitivity(
    evidence_db: sqlite3.Connection,
    reviewed_cases: Sequence[dict],
    *, min_independent_groups: int = 2,
) -> dict:
    """Aggregate counterfactual review-offer stability against held-out truth.

    Each truth row: observation_uid, truth_person_id (positive int or None),
    condition (supported one-to-three '+' joined labels). Refuses missing
    ground truth, duplicated trial identifiers, candidate overflow and
    unexpectedly modified databases. No identifier is returned.
    """
    if (type(min_independent_groups) is not int
            or not 2 <= min_independent_groups <= 20):
        raise ValueError("At least two independent positive groups required")
    if not isinstance(reviewed_cases, (tuple, list)) or not reviewed_cases:
        raise ValueError("Explicit independently reviewed cases required")
    validated = []
    seen = set()
    for item in reviewed_cases:
        if not isinstance(item, dict) or set(item) != {
                "observation_uid", "truth_person_id", "condition"}:
            raise ValueError("Explicit reviewed truth required")
        uid, truth = item["observation_uid"], item["truth_person_id"]
        if not isinstance(uid, str) or not uid or uid in seen:
            raise ValueError("Unique observation identifiers required")
        if truth is not None and (type(truth) is not int or truth <= 0):
            raise ValueError("Reviewed known/Unknown truth required")
        condition = _normalize_condition(item["condition"])
        seen.add(uid)
        validated.append((uid, truth, condition))

    start_changes = evidence_db.total_changes
    totals = {s: _empty() for s in SCENARIOS}
    exact = {s: defaultdict(_empty) for s in SCENARIOS}
    marginal = {s: defaultdict(_empty) for s in SCENARIOS}
    stability = {s: _empty() for s in SCENARIOS if s != "declared_groups"}
    for uid, truth, condition in validated:
        # Only the existing dedicated V5 evidence database is expected.
        if not evidence_db.execute(
                "SELECT 1 FROM observations WHERE observation_uid=?", (uid,)
        ).fetchone():
            raise ValueError("Missing reviewed observation in evidence store")
        rows = evidence_db.execute(
            "SELECT candidate_person_id,family,polarity,correlation_group,"
            "source_reference FROM candidate_evidence "
            "WHERE observation_uid=? ORDER BY evidence_uid LIMIT ?",
            (uid, MAX_EVIDENCE_PER_OBSERVATION + 1)
        ).fetchall()
        if len(rows) > MAX_EVIDENCE_PER_OBSERVATION:
            raise ValueError("Evidence exceeds exhaustive audit limit")
        decisions = {s: _rank_candidates(rows, s, min_independent_groups)
                     for s in SCENARIOS}
        baseline = decisions["declared_groups"]
        for name, offer in decisions.items():
            for counts in [totals[name], exact[name][condition],
                           *(marginal[name][tag] for tag in condition.split("+"))]:
                counts["observations"] += 1
                counts["unknown_observations" if truth is None
                       else "known_observations"] += 1
                if offer is None:
                    counts["correct_unknown_abstentions" if truth is None
                           else "missed_known_abstentions"] += 1
                else:
                    counts["review_offers"] += 1
                    if truth is None:
                        counts["unknown_false_offers"] += 1
                    elif offer == truth:
                        counts["correct_known_offers"] += 1
                    else:
                        counts["wrong_known_offers"] += 1
            if name != "declared_groups":
                diff = stability[name]
                if baseline is not None and offer is None:
                    diff["retracted_baseline_offers"] += 1
                elif baseline is None and offer is not None:
                    diff["new_offers_after_stress"] += 1
                elif baseline is not None and offer is not None and baseline != offer:
                    diff["changed_candidate_offers"] += 1
                elif baseline is not None and offer == baseline:
                    diff["unchanged_candidate_offers"] += 1
    if evidence_db.total_changes != start_changes:
        raise RuntimeError("Counterfactual audit modified evidence database")
    report = {}
    for s in SCENARIOS:
        # Minimum 10 records for condition strata; no misleading tiny-cell
        # rates and no attempt to sum overlapping marginal condition tags.
        report[s] = {
            "overall": _counts(totals[s]),
            "by_condition": {k: _counts(v) for k, v in sorted(exact[s].items())
                             if v["observations"] >= 10},
            "by_condition_tag": {k: _counts(v) for k, v in sorted(marginal[s].items())
                                 if v["observations"] >= 10},
            "suppressed_intersections": sum(v["observations"] < 10
                                            for v in exact[s].values()),
            "suppressed_condition_tags": sum(v["observations"] < 10
                                             for v in marginal[s].values()),
        }
        if s != "declared_groups":
            report[s]["versus_declared"] = {
                k: stability[s][k] for k in (
                    "retracted_baseline_offers", "new_offers_after_stress",
                    "changed_candidate_offers", "unchanged_candidate_offers")
            }
    return {
        "status": "hypothetical_correlation_stress_only",
        "unknown_always_allowed": True,
        "auto_confirm_allowed": False,
        "calibrated_identity_probability": None,
        "minimum_independent_groups": min_independent_groups,
        "scenarios": report,
        "caveat": "Collapsing groups is a pessimistic what-if, not evidence "
                  "that sources are actually identical. No review offer is "
                  "a confirmed identity; held-out consented evaluations are required.",
    }
