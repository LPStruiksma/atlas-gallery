#!/usr/bin/env python3
"""Bounded WHOLE-1 pair evaluator; emits aggregate statistics only.

The manifest contains already-computed scores and human labels.  This tool does
not read media, create pairs, infer identity, or modify People state.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
from pathlib import Path

SCHEMA = "atlas-whole-person-l1-eval-v2"
RIGHTS = {"consented", "licensed"}
LABELS = {"match", "nonmatch"}
VISIBILITY = {"clear", "rear", "small", "occluded"}
REQUIRED = {
    "pair_id", "queue_id", "label", "visibility", "face_proposed", "face_score", "score", "captured_a",
    "captured_b", "event_a", "event_b", "camera_a", "camera_b",
    "same_frame", "shared_uniform", "outfit_change", "detection_ok",
}


class ManifestError(ValueError):
    """The private manifest does not satisfy the frozen safety contract."""


def _timestamp(value: object) -> dt.datetime:
    if not isinstance(value, str):
        raise ManifestError("timestamps must be ISO-8601 strings")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ManifestError("invalid timestamp") from exc
    if parsed.tzinfo is None:
        raise ManifestError("timestamps require an explicit UTC offset")
    return parsed


def _wilson(successes: int, total: int) -> list[float] | None:
    if total == 0:
        return None
    z = 1.959963984540054
    p = successes / total
    d = 1 + z * z / total
    centre = (p + z * z / (2 * total)) / d
    margin = z * math.sqrt((p * (1 - p) + z * z / (4 * total)) / total) / d
    return [centre - margin, centre + margin]


def _rate(successes: int, total: int) -> dict[str, object]:
    return {
        "numerator": successes,
        "denominator": total,
        "value": successes / total if total else None,
        "wilson_95": _wilson(successes, total),
    }


def evaluate(document: object, threshold: float, budget: int) -> dict[str, object]:
    """Compare two rankings of exactly the same face-proposed pairs at a fixed budget.

    The threshold and budget are frozen on an independent validation cohort.
    A positive attire score can rerank an existing pair but cannot create one.
    """
    if not isinstance(document, dict) or document.get("schema") != SCHEMA:
        raise ManifestError(f"schema must be {SCHEMA}")
    rights = document.get("rights")
    if not isinstance(rights, str) or rights not in RIGHTS or document.get("human_verified") is not True:
        raise ManifestError("explicit consent/licence and human-verified labels are required")
    pairs = document.get("pairs")
    if not isinstance(pairs, list):
        raise ManifestError("pairs must be a list")
    if isinstance(threshold, bool) or not isinstance(threshold, (int, float)) or not math.isfinite(threshold) or not 0 <= threshold <= 1:
        raise ManifestError("threshold must be finite and in [0,1]")
    if isinstance(budget, bool) or not isinstance(budget, int) or budget < 1:
        raise ManifestError("budget must be a positive integer")

    seen: set[str] = set()
    # Each scoped cohort has a single declared queue of face-proposed pairs.
    groups: dict[tuple[str, str, str], list[dict[str, object]]] = {}
    cohort: list[dict[str, object]] = []
    abstentions: dict[str, int] = {}
    for pair in pairs:
        if not isinstance(pair, dict) or not REQUIRED <= pair.keys():
            raise ManifestError("each pair is missing required fields")
        pair_id = pair["pair_id"]
        if not isinstance(pair_id, str) or not pair_id.strip() or pair_id in seen:
            raise ManifestError("pair_id must be a unique non-empty opaque string")
        seen.add(pair_id)
        for field in ("queue_id", "event_a", "event_b", "camera_a", "camera_b"):
            if not isinstance(pair[field], str) or not pair[field].strip():
                raise ManifestError(f"{field} must be a non-empty opaque string")
        if not isinstance(pair["label"], str) or pair["label"] not in LABELS:
            raise ManifestError("label must be match or nonmatch")
        if not isinstance(pair["visibility"], str) or pair["visibility"] not in VISIBILITY:
            raise ManifestError("visibility must be clear, rear, small or occluded")
        if not isinstance(pair["face_proposed"], bool):
            raise ManifestError("face_proposed must be boolean")
        score = pair["score"]
        if isinstance(score, bool) or not isinstance(score, (int, float)) or not math.isfinite(score) or not 0 <= score <= 1:
            raise ManifestError("score must be finite and in [0,1]")
        face_score = pair["face_score"]
        if pair["face_proposed"]:
            if isinstance(face_score, bool) or not isinstance(face_score, (int, float)) or not math.isfinite(face_score) or not 0 <= face_score <= 1:
                raise ManifestError("proposed pairs require a finite face_score in [0,1]")
        elif face_score is not None:
            raise ManifestError("non-proposed pairs must use a null face_score")
        for field in ("same_frame", "shared_uniform", "outfit_change", "detection_ok"):
            if not isinstance(pair[field], bool):
                raise ManifestError(f"{field} must be boolean")

        # Check every timestamp, including abstained rows; never silently assume a
        # file time. Invalid or offset-free timestamps fail closed.
        a, b = _timestamp(pair["captured_a"]), _timestamp(pair["captured_b"])
        reason = None
        if not pair["detection_ok"]:
            reason = "failed_detection"
        elif pair["event_a"] != pair["event_b"]:
            reason = "different_event"
        elif pair["camera_a"] != pair["camera_b"]:
            reason = "camera_mismatch"
        elif pair["outfit_change"]:
            reason = "known_outfit_change"
        elif pair["shared_uniform"]:
            reason = "shared_uniform"
        elif pair["same_frame"]:
            reason = "cooccurrence_veto"
        elif a.date() != b.date() or abs((a - b).total_seconds()) > 3 * 3600:
            reason = "outside_same_day_three_hour_cohort"
        if reason:
            abstentions[reason] = abstentions.get(reason, 0) + 1
            continue

        cohort.append(pair)
        if not pair["face_proposed"]:
            abstentions["no_face_proposal"] = abstentions.get("no_face_proposal", 0) + 1
            continue
        group = (pair["event_a"], pair["camera_a"], pair["queue_id"])
        groups.setdefault(group, []).append(pair)

    rankable = [pair for group in groups.values() for pair in group]
    baseline_selected: set[str] = set()
    augmented_selected: set[str] = set()
    improved = worsened = 0
    # Fixed, declared 50/50 fusion and stable pair-ID tie-break. No true labels
    # influence either ranking, and both select the same number per queue.
    for group in groups.values():
        baseline = sorted(group, key=lambda p: (-float(p["face_score"]), p["pair_id"]))
        def augmented_score(p: dict[str, object]) -> float:
            face = float(p["face_score"])
            return (face + float(p["score"])) / 2 if float(p["score"]) >= threshold else face
        augmented = sorted(group, key=lambda p: (-augmented_score(p), p["pair_id"]))
        baseline_selected.update(p["pair_id"] for p in baseline[:budget])
        augmented_selected.update(p["pair_id"] for p in augmented[:budget])
        baseline_ranks = {p["pair_id"]: rank for rank, p in enumerate(baseline)}
        augmented_ranks = {p["pair_id"]: rank for rank, p in enumerate(augmented)}
        for p in group:
            if p["label"] == "match":
                improved += augmented_ranks[p["pair_id"]] < baseline_ranks[p["pair_id"]]
                worsened += augmented_ranks[p["pair_id"]] > baseline_ranks[p["pair_id"]]

    cohort_pos = sum(p["label"] == "match" for p in cohort)
    eligible_pos = sum(p["label"] == "match" for p in rankable)
    eligible_neg = sum(p["label"] == "nonmatch" for p in rankable)
    baseline_true = sum(p["label"] == "match" and p["pair_id"] in baseline_selected for p in rankable)
    true_questions = sum(p["label"] == "match" and p["pair_id"] in augmented_selected for p in rankable)
    wrong_questions = sum(p["label"] == "nonmatch" and p["pair_id"] in augmented_selected for p in rankable)
    abstained = sum(abstentions.values())
    if len(pairs) != len(rankable) + abstained:
        raise AssertionError("cohort partitions must reconcile without overlap")
    strata = {}
    for visibility in sorted(VISIBILITY):
        denominator = sum(p["label"] == "match" and p["visibility"] == visibility for p in cohort)
        face_true = sum(p["label"] == "match" and p["visibility"] == visibility and p["pair_id"] in baseline_selected for p in rankable)
        attire_true = sum(p["label"] == "match" and p["visibility"] == visibility and p["pair_id"] in augmented_selected for p in rankable)
        strata[visibility] = {"face_only": _rate(face_true, denominator),
                              "attire_reranked": _rate(attire_true, denominator)}
    return {
        "schema": "atlas-whole-person-l1-report-v2",
        "policy": "question_only_same_face_pairs_no_auto_merge",
        "threshold": threshold, "budget_per_queue": budget, "attire_weight": 0.5,
        "counts": {"input": len(pairs), "cohort_match": cohort_pos,
                   "cohort_nonmatch": len(cohort) - cohort_pos,
                   "eligible_match": eligible_pos, "eligible_nonmatch": eligible_neg,
                   "abstained": abstained, "questions": true_questions + wrong_questions,
                   "face_only_questions": len(baseline_selected), "queues": len(groups)},
        "abstentions": dict(sorted(abstentions.items())),
        "face_proposal_coverage_recall": _rate(eligible_pos, cohort_pos),
        "baseline_face_question_recall": _rate(baseline_true, cohort_pos),
        "augmented_question_candidate_recall": _rate(true_questions, cohort_pos),
        "stratified_budgeted_recall": strata,
        "true_pair_rank_improvement": {"improved": improved, "worsened": worsened,
                                       "eligible_match": eligible_pos},
        "wrong_person_question_rate": _rate(wrong_questions, eligible_neg),
        "question_precision": _rate(true_questions, true_questions + wrong_questions),
        "actual_identity_false_merges": {"value": 0, "reason": "evaluator cannot merge"},
        "empirical_provenance_independently_verified": False,
        "real_world_validation": "NOT MEASURED without separately audited private data",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True,
                        help="authorized private JSON manifest; media paths are forbidden")
    parser.add_argument("--threshold", type=float, required=True,
                        help="threshold frozen on a separate validation split")
    parser.add_argument("--budget", type=int, required=True,
                        help="fixed number of face-proposed questions per queue")
    args = parser.parse_args(argv)
    raw = args.manifest.read_text(encoding="utf-8")
    document = json.loads(raw)
    # Refuse likely media/path-bearing manifests before any report is produced.
    forbidden = {"path", "media", "filename", "embedding", "person_id", "name"}
    if isinstance(document, dict) and any(
            isinstance(p, dict) and forbidden.intersection(p) for p in document.get("pairs", [])):
        raise ManifestError("manifest must not contain media, identity, or embedding fields")
    print(json.dumps(evaluate(document, args.threshold, args.budget), sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
