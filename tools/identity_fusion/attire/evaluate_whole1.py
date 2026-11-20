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

SCHEMA = "atlas-whole-person-l1-eval-v1"
RIGHTS = {"consented", "licensed"}
LABELS = {"match", "nonmatch"}
REQUIRED = {
    "pair_id", "label", "face_proposed", "score", "captured_a",
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


def evaluate(document: object, threshold: float) -> dict[str, object]:
    if not isinstance(document, dict) or document.get("schema") != SCHEMA:
        raise ManifestError(f"schema must be {SCHEMA}")
    if document.get("rights") not in RIGHTS or document.get("human_verified") is not True:
        raise ManifestError("explicit consent/licence and human-verified labels are required")
    pairs = document.get("pairs")
    if not isinstance(pairs, list):
        raise ManifestError("pairs must be a list")
    if not isinstance(threshold, float) or not math.isfinite(threshold) or not 0 <= threshold <= 1:
        raise ManifestError("threshold must be finite and in [0,1]")

    seen: set[str] = set()
    eligible: list[tuple[str, bool, bool]] = []
    abstentions: dict[str, int] = {}
    for pair in pairs:
        if not isinstance(pair, dict) or not REQUIRED <= pair.keys():
            raise ManifestError("each pair is missing required fields")
        pair_id = pair["pair_id"]
        if not isinstance(pair_id, str) or not pair_id or pair_id in seen:
            raise ManifestError("pair_id must be a unique non-empty opaque string")
        seen.add(pair_id)
        if pair["label"] not in LABELS or not isinstance(pair["face_proposed"], bool):
            raise ManifestError("invalid label or face_proposed flag")
        score = pair["score"]
        if isinstance(score, bool) or not isinstance(score, (int, float)) or not math.isfinite(score) or not 0 <= score <= 1:
            raise ManifestError("score must be finite and in [0,1]")
        for field in ("same_frame", "shared_uniform", "outfit_change", "detection_ok"):
            if not isinstance(pair[field], bool):
                raise ManifestError(f"{field} must be boolean")

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
        else:
            a, b = _timestamp(pair["captured_a"]), _timestamp(pair["captured_b"])
            if a.date() != b.date() or abs((a - b).total_seconds()) > 3 * 3600:
                reason = "outside_same_day_three_hour_cohort"
        if reason:
            abstentions[reason] = abstentions.get(reason, 0) + 1
            continue
        face_proposed = pair["face_proposed"]
        if not face_proposed:
            abstentions["no_face_proposal"] = abstentions.get("no_face_proposal", 0) + 1
        eligible.append((pair["label"], face_proposed, face_proposed and float(score) >= threshold))

    positives = sum(label == "match" for label, _, _ in eligible)
    negatives = sum(label == "nonmatch" for label, _, _ in eligible)
    baseline_true = sum(label == "match" and face for label, face, _ in eligible)
    true_questions = sum(label == "match" and asked for label, _, asked in eligible)
    wrong_questions = sum(label == "nonmatch" and asked for label, _, asked in eligible)
    questions = true_questions + wrong_questions
    return {
        "schema": "atlas-whole-person-l1-report-v1",
        "policy": "question_only_no_pair_creation_no_auto_merge",
        "threshold": threshold,
        "counts": {"input": len(pairs), "eligible_match": positives,
                   "eligible_nonmatch": negatives, "abstained": sum(abstentions.values()),
                   "questions": questions},
        "abstentions": dict(sorted(abstentions.items())),
        "baseline_face_question_recall": _rate(baseline_true, positives),
        "augmented_question_candidate_recall": _rate(true_questions, positives),
        "wrong_person_question_rate": _rate(wrong_questions, negatives),
        "question_precision": _rate(true_questions, questions),
        "actual_identity_false_merges": {"value": 0, "reason": "no auto-merge exists"},
        "real_world_validation": "NOT MEASURED",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True,
                        help="authorized private JSON manifest; media paths are forbidden")
    parser.add_argument("--threshold", type=float, required=True,
                        help="threshold frozen on a separate validation split")
    args = parser.parse_args(argv)
    raw = args.manifest.read_text(encoding="utf-8")
    document = json.loads(raw)
    # Refuse likely media/path-bearing manifests before any report is produced.
    forbidden = {"path", "media", "filename", "embedding", "person_id", "name"}
    if isinstance(document, dict) and any(
            isinstance(p, dict) and forbidden.intersection(p) for p in document.get("pairs", [])):
        raise ManifestError("manifest must not contain media, identity, or embedding fields")
    print(json.dumps(evaluate(document, args.threshold), sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
