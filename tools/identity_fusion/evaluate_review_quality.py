#!/usr/bin/env python3
"""Synthetic-first, read-only V5 candidate ranking evaluation.

This module makes NO biometric inferences. The caller supplies independently
reviewed truth *only for evaluating suggestions*. Truth is never persisted
or echoed with person IDs, photo paths or observation IDs. The evaluation
has NO API for confirmation, People merges or automatic identity assignment.
"""
from __future__ import annotations

from collections import defaultdict
from collections.abc import Sequence
from typing import Any
import sqlite3
import re

from benchmark_reid_scores import _upper_wilson

from evidence_store import review_candidates, FAMILY_PRIORITY

# Compare progressively richer evidence on the SAME source observations.
# No observed correctness here implies an identity probability.
FAMILY_VARIANTS = {
    "face_only": frozenset({"face"}),
    "face_session": frozenset({"face", "session"}),
    "face_body_session": frozenset({"face", "body", "session"}),
    "all_evidence": frozenset({"face", "body", "session", "clothing", "context"}),
}
CONDITIONS = frozenset({
    "front", "back", "side", "partial", "occluded", "appearance_shift",
    "lookalike", "unknown"
})


def _counts() -> dict[str, int]:
    return dict(
        observations=0, known_truth_observations=0, unknown_truth_observations=0,
        review_suggestions=0, ambiguous_evidence_abstentions=0,
        competing_face_evidence_abstentions=0,
        correct_person_suggestions=0,
        false_person_suggestions=0, wrong_known_person_suggestions=0,
        unknown_person_suggestions=0, abstentions=0,
        missed_known_abstentions=0, correct_unknown_abstentions=0
    )


def _summarize(c: dict[str, int]) -> dict[str, Any]:
    total = c["observations"]
    offered = c["review_suggestions"]
    return {
        **c,
        "coverage_fraction": round(offered / total, 4) if total else None,
        "false_suggestions_per_observation": round(
            c["false_person_suggestions"] / total, 4) if total else None,
        "false_suggestions_per_offer": round(
            c["false_person_suggestions"] / offered, 4) if offered else None,
        "unknown_false_offer_fraction": round(
            c["unknown_person_suggestions"] / c["unknown_truth_observations"], 4)
            if c["unknown_truth_observations"] else None,
        "known_missed_abstention_fraction": round(
            c["missed_known_abstentions"] / c["known_truth_observations"], 4)
            if c["known_truth_observations"] else None,
        # Not called accuracy: Unknown cases and uncertain reference truth
        # require more careful evaluation than a single global number.
    }



# Matched *observation-level* transitions, not causal effects of a modality.
# A condition counts only if it has >=10 reviewed observations. Dependency
# groups must be supplied for ALL trials; never infer capture independence
# from different photo IDs, paths, or visit order.
ABLATION_PAIRS = (
    ("face_only", "face_session"),
    ("face_session", "face_body_session"),
    ("face_only", "face_body_session"),
    ("face_body_session", "all_evidence"),
)


def _matched_ablation(cases, baseline, expanded, *, has_components):
    def summarize(selected):
        counts = dict(
            observations=0, known_observations=0, unknown_observations=0,
            new_correct_known_offers=0, lost_correct_known_offers=0,
            new_false_offers_to_unknown=0, resolved_false_offers_to_unknown=0,
            new_wrong_known_offers=0, resolved_wrong_known_offers=0,
        )
        groups = {"known": set(), "unknown": set(),
                  "new_wrong_known": set(), "new_false_unknown": set()}
        for uid, truth, _, component in selected:
            old, new = baseline[uid], expanded[uid]
            known = truth is not None
            kind = "known" if known else "unknown"
            counts["observations"] += 1
            counts[kind + "_observations"] += 1
            old_correct = known and old == truth
            new_correct = known and new == truth
            old_false = old is not None and old != truth
            new_false = new is not None and new != truth
            if new_correct and not old_correct:
                counts["new_correct_known_offers"] += 1
            if old_correct and not new_correct:
                counts["lost_correct_known_offers"] += 1
            if new_false and not old_false:
                key = ("new_wrong_known_offers" if known
                       else "new_false_offers_to_unknown")
                counts[key] += 1
            if old_false and not new_false:
                key = ("resolved_wrong_known_offers" if known
                       else "resolved_false_offers_to_unknown")
                counts[key] += 1
            if has_components:
                groups[kind].add(component)
                if new_false and not old_false:
                    groups["new_wrong_known" if known
                           else "new_false_unknown"].add(component)
        if has_components:
            known_n = len(groups["known"])
            unknown_n = len(groups["unknown"])
            component_report = {
                "known_components": known_n,
                "unknown_components": unknown_n,
                "components_with_new_wrong_known_offer": len(groups["new_wrong_known"]),
                "components_with_new_false_unknown_offer": len(groups["new_false_unknown"]),
                "unknown_new_false_component_wilson95_upper_diagnostic": (
                    round(_upper_wilson(len(groups["new_false_unknown"]), unknown_n), 4)
                    if unknown_n else None
                ),
                "known_new_wrong_component_wilson95_upper_diagnostic": (
                    round(_upper_wilson(len(groups["new_wrong_known"]), known_n), 4)
                    if known_n else None
                ),
                "declared_components_proven_physically_independent": False,
                "diagnostic_not_calibration_or_release_gate": True,
            }
        else:
            component_report = None  # Never assume distinct rows are independent.
        return {**counts, "capture_component_diagnostics": component_report}

    strata = sorted(set(condition for _, _, condition, _ in cases))
    by_condition = {}
    suppressed = 0
    for condition in strata:
        subset = [c for c in cases if c[2] == condition]
        if len(subset) >= 10:
            by_condition[condition] = summarize(subset)
        else:
            suppressed += 1
    return {
        "overall": summarize(cases),
        "by_condition": by_condition,
        "suppressed_condition_count": suppressed,
        "matched_same_observations": True,
        "causal_effect_of_body_or_session_established": False,
        "component_correlation_metadata_complete": has_components,
        "auto_confirm_allowed": False,
    }


def evaluate_review_quality(
    evidence_db: sqlite3.Connection,
    truth_cases: Sequence[dict[str, Any]],
    *, min_independent_groups: int = 2,
) -> dict[str, Any]:
    """Count review-only ranked suggestions against independently supplied truth.

    Each case has {observation_uid, truth_person_id (int or None), condition}.
    A proposal can be *offered for manual review* when it has at least two
    independent positive correlation groups and NO contradictory group.
    Abstention is always valid; all output is aggregate and non-identifying.
    The method has no data mutation or external I/O.
    """
    if not isinstance(min_independent_groups, int) or isinstance(
        min_independent_groups, bool
    ) or not 2 <= min_independent_groups <= 20:
        raise ValueError("Evaluation must use a conservative 2..20 group gate")
    if not isinstance(truth_cases, (tuple, list)) or not truth_cases:
        raise ValueError("An explicit nonempty list of independently reviewed cases is required")

    seen: set[str] = set()
    validated = []
    component_metadata = ["capture_component_uid" in row if isinstance(row, dict)
                          else False for row in truth_cases]
    if any(component_metadata) and not all(component_metadata):
        raise ValueError("Incomplete declared capture-dependency group coverage")
    has_components = all(component_metadata)
    for row in truth_cases:
        if not isinstance(row, dict):
            raise ValueError("Evaluation case must be a record")
        # A missing reference truth is NOT the same as an independently
        # reviewed Unknown person. Never misclassify unlabeled cases as a
        # reassuringly correct abstention or hide incomplete pilot data.
        if not {"observation_uid", "truth_person_id", "condition"} <= row.keys():
            raise ValueError("Explicit reviewed truth (including Unknown) required")
        uid = row.get("observation_uid")
        truth = row.get("truth_person_id")
        condition = row.get("condition")
        if not isinstance(uid, str) or not uid or uid in seen:
            raise ValueError("Unique observation UID required per trial")
        if condition not in CONDITIONS:
            raise ValueError("Use a supported public evaluation condition")
        if truth is not None and (not isinstance(truth, int)
                                  or isinstance(truth, bool) or truth <= 0):
            raise ValueError("Truth label must be a confirmed positive ID or Unknown")
        component = row.get("capture_component_uid") if has_components else None
        if has_components and (not isinstance(component, str) or
                               re.fullmatch(r"[A-Za-z0-9_-]{1,64}", component) is None):
            raise ValueError("Invalid opaque reviewed capture component")
        seen.add(uid)
        validated.append((uid, truth, condition, component))

    start_changes = evidence_db.total_changes
    variants: dict[str, dict[str, Any]] = {}
    variant_offers: dict[str, dict[str, int | None]] = {}
    for name, families in FAMILY_VARIANTS.items():
        overall = _counts()
        strata: dict[str, dict[str, int]] = defaultdict(_counts)
        variant_offers[name] = {}
        for uid, truth, condition, _component in validated:
            response = review_candidates(
                evidence_db, uid, limit=100, allowed_families=families)
            # review_candidates has an intentionally bounded review UI list.
            # If more than 100 identities were proposed, we cannot safely
            # treat a missing eligible candidate outside that slice as an
            # Unknown abstention. Refuse misleading benchmark metrics.
            if response["total_candidates"] > len(response["proposals"]):
                raise ValueError("Candidate review list truncated; evaluation not exhaustive")
            eligible = [p for p in response["proposals"]
                        if p["independent_support_groups"] >= min_independent_groups
                        and p["contradicting_groups"] == 0]
            # The store's deterministic numeric person-ID tiebreak is for
            # presentation order ONLY, never evidentiary identification.
            # Two equally supported eligible candidates must cause abstention.
            def rank_without_person_id(p):
                return (p["independent_support_groups"],
                        max(FAMILY_PRIORITY[f] for f in p["evidence_families"]))
            ambiguous = (
                len(eligible) > 1 and
                rank_without_person_id(eligible[0]) ==
                rank_without_person_id(eligible[1])
            )
            # A top-ranked body/session candidate cannot defeat independent,
            # non-contradicted face evidence for another candidate merely
            # because it has one more correlated group. Abstain and surface
            # the unresolved modalities for human inspection.
            competing_face = bool(
                eligible and eligible[0]["positive_face_support_groups"] == 0
                and any(p["candidate_person_id"] != eligible[0]["candidate_person_id"]
                        and p["positive_face_support_groups"] > 0
                        and p["contradicting_groups"] == 0
                        for p in response["proposals"])
            )
            offer = (eligible[0]["candidate_person_id"]
                     if eligible and not ambiguous and not competing_face
                     else None)
            variant_offers[name][uid] = offer
            for dest in (overall, strata[condition]):
                dest["observations"] += 1
                if truth is None:
                    dest["unknown_truth_observations"] += 1
                else:
                    dest["known_truth_observations"] += 1
                if offer is None:
                    dest["abstentions"] += 1
                    if ambiguous:
                        dest["ambiguous_evidence_abstentions"] += 1
                    if competing_face:
                        dest["competing_face_evidence_abstentions"] += 1
                    if truth is None:
                        dest["correct_unknown_abstentions"] += 1
                    else:
                        dest["missed_known_abstentions"] += 1
                else:
                    dest["review_suggestions"] += 1
                    if offer == truth:
                        dest["correct_person_suggestions"] += 1
                    else:
                        dest["false_person_suggestions"] += 1
                        if truth is None:
                            dest["unknown_person_suggestions"] += 1
                        else:
                            dest["wrong_known_person_suggestions"] += 1
        variants[name] = {
            "overall": _summarize(overall),
            "by_condition": {k: _summarize(v) for k, v in sorted(strata.items())},
        }
    if evidence_db.total_changes != start_changes:
        raise RuntimeError("Evaluation unexpectedly modified the evidence store")
    return {
        "status": "research_metrics_only",
        "calibrated_probability": None,
        "auto_confirm_allowed": False,
        "unknown_always_allowed": True,
        "minimum_independent_groups": min_independent_groups,
        "variants": variants,
        "matched_ablation": {
            before + "_to_" + after: _matched_ablation(
                validated, variant_offers[before], variant_offers[after],
                has_components=has_components)
            for before, after in ABLATION_PAIRS
        },
        "caveat": (
            "Tied candidates and competing positive face-vs-body cues "
            "must abstain, never imply a confirmed identity. "
            "Evidence groups may be correlated even across apparent sessions. "
            "Synthetic counts are not real-world accuracy; validation requires "
            "held-out reviewed identities, Unknown people and physical-device tests."
        ),
    }
