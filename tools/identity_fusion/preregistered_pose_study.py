#!/usr/bin/env python3
"""Strict SHA-pinned pose study protocol; opaque scores only, no biometrics.

A byte digest is NOT proof that a protocol was committed before holdout was
seen. External timestamping, annotations, model execution, and scientific
validity must all be verified independently.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import sys

from audit_paired_pose_retention import audit_paired_pose_retention
from benchmark_reid_scores import FIELDS as SCORE_FIELDS, TOKEN, _parse
from benchmark_retrieval_recall import _load_pinned
from candidate_list_coverage import FIELDS as ROSTER_FIELDS
from preregistered_comparison import (
    TRUTH_FIELDS, _frozen_truth_index, _json_unique_pairs, _digest,
)
from run_paired_pose_benchmark import PAIR_FIELDS
from verify_split_provenance import FIELDS as PROVENANCE_FIELDS

SCHEMA_VERSION = 4
PAIR_POLICY = "every_eligible_known_subject_first_lexical_independent_pair_v1"
MAX_PAIR_COMPARISONS = 200000
METRIC = "front_rear_open_set_transitions_v1"
CALIBRATION = "calibration_only_5pct_wilson_rear_unknown_subject_event_v1"
CALIBRATION_V3 = (
    "calibration_only_5pct_wilson_rear_unknown_subject_event_and_capture_component_v1"
)
KNOWN_CAPTURE_POLICY_V4 = (
    "minimum_20_declared_event_lineage_session_components_for_known_pose_pairs_v1"
)
MAX_PROTOCOL_BYTES = 8192
MIN_KNOWN_PAIRS = 20
MIN_CONDITION_PAIRS = 10
REAR_UNKNOWN_BUDGET = .05
FIELDS = frozenset({
    "schema_version", "study_uid", "model_uid", "model_checkpoint_sha256",
    "frozen_truth_sha256", "frozen_roster_sha256",
    "frozen_provenance_sha256", "frozen_pairs_sha256", "top_two_margin",
    "min_known_person_pairs", "min_condition_pairs",
    "rear_unknown_wilson95_budget", "primary_endpoint", "calibration_policy",
})
V2_EXTRA_FIELDS = frozenset({"pair_selection_policy"})
V4_EXTRA_FIELDS = frozenset({"known_capture_dependency_policy"})
MANIFESTS = (
    ("truth", TRUTH_FIELDS), ("scores", SCORE_FIELDS),
    ("roster", ROSTER_FIELDS), ("provenance", PROVENANCE_FIELDS),
    ("pairs", PAIR_FIELDS),
)


def validate_pose_protocol(spec):
    if not isinstance(spec, dict) or type(spec.get("schema_version")) is not int:
        raise ValueError("Incomplete or unexpected frozen pose protocol")
    version = spec["schema_version"]
    if version not in (1, 2, 3, 4):
        raise ValueError("Unsupported pose study protocol version")
    registered_fields = (
        FIELDS
        | (V2_EXTRA_FIELDS if version in (2, 3, 4) else frozenset())
        | (V4_EXTRA_FIELDS if version == 4 else frozenset())
    )
    if set(spec) != registered_fields:
        raise ValueError("Unrecognized pose protocol fields")
    if version in (2, 3, 4) and spec["pair_selection_policy"] != PAIR_POLICY:
        raise ValueError("Unsupported deterministic pair-inventory policy")
    if (version == 4
            and spec["known_capture_dependency_policy"] != KNOWN_CAPTURE_POLICY_V4):
        raise ValueError("Unsupported known-pose capture component policy")
    for key in ("study_uid", "model_uid"):
        value = spec[key]
        if (type(value) is not str or not TOKEN.fullmatch(value)
                or value in {"NONE", "UNKNOWN", "UNREVIEWED"}):
            raise ValueError("Opaque protocol identifier required")
    for key in ("model_checkpoint_sha256", "frozen_truth_sha256",
                "frozen_roster_sha256", "frozen_provenance_sha256",
                "frozen_pairs_sha256"):
        if not _digest(spec[key]):
            raise ValueError("Full lowercase SHA256 digest required")
    margin = spec["top_two_margin"]
    if type(margin) not in (int, float) or not math.isfinite(margin) or not 0 <= margin <= 1:
        raise ValueError("Invalid frozen top-two separation margin")
    if (type(spec["min_known_person_pairs"]) is not int
            or spec["min_known_person_pairs"] != MIN_KNOWN_PAIRS
            or type(spec["min_condition_pairs"]) is not int
            or spec["min_condition_pairs"] != MIN_CONDITION_PAIRS
            or type(spec["rear_unknown_wilson95_budget"]) not in (float, int)
            or spec["rear_unknown_wilson95_budget"] != REAR_UNKNOWN_BUDGET):
        raise ValueError("Unregistered sample-size or Unknown safety relaxation")
    frozen_policy = CALIBRATION_V3 if version in (3, 4) else CALIBRATION
    if spec["primary_endpoint"] != METRIC or spec["calibration_policy"] != frozen_policy:
        raise ValueError("Unregistered primary endpoint or threshold policy")
    return spec


def pinned_pose_protocol(path: Path, sha256: str):
    """Verify exact small JSON protocol bytes; refuse aliases and duplicates."""
    if not _digest(sha256) or path.is_symlink() or not path.is_file():
        raise ValueError("Missing or unpinned ordinary research protocol")
    if not 0 < path.stat().st_size <= MAX_PROTOCOL_BYTES:
        raise ValueError("Unbounded research protocol")
    with path.open("rb") as stream:
        raw = stream.read(MAX_PROTOCOL_BYTES + 1)
    if (not 0 < len(raw) <= MAX_PROTOCOL_BYTES
            or hashlib.sha256(raw).hexdigest() != sha256):
        raise ValueError("Protocol does not match SHA256")
    try:
        spec = json.loads(
            raw.decode("utf-8"), object_pairs_hook=_json_unique_pairs,
            parse_constant=lambda value: (_ for _ in ()).throw(
                ValueError("Nonfinite research protocol constant")))
    except (UnicodeError, json.JSONDecodeError):
        raise ValueError("Malformed frozen research protocol") from None
    return validate_pose_protocol(spec)



def audit_exhaustive_pose_pairs(score_splits, provenance_rows, pair_rows):
    """Enumerate an outcome-blind, deterministic eligible known-person inventory.

    Use only reviewed truth/condition and provenance. First lexical matching
    front/rear opaque IDs with distinct session, lineage and event are frozen;
    model scores never enter pair selection. Hard-cap comparison work and
    refuse an oversized/cohort-ambiguous study rather than sampling silently.
    """
    sources = {(x["split"], x["probe_uid"]): x for x in provenance_rows}
    people = defaultdict(lambda: {
        "subjects": set(), "front": [], "rear": [], "rear_tags": {},
    })
    for trial in score_splits["holdout"]:
        probe, session, condition, truth, *_ = trial
        if truth == "UNKNOWN":
            continue
        source = sources.get(("holdout", probe))
        if source is None or source["subject_cluster_uid"] == "UNREVIEWED":
            raise ValueError("Exhaustive known-person pose inventory needs reviewed subject provenance")
        person = people[truth]
        person["subjects"].add(source["subject_cluster_uid"])
        tags = set(condition.split("+"))
        record = (probe, session, source["photo_lineage_uid"],
                  source["event_cluster_uid"])
        if "front" in tags and "back" not in tags:
            person["front"].append(record)
        elif "front" not in tags and tags & {"back", "partial"}:
            person["rear"].append(record)
            person["rear_tags"][probe] = tags

    # These are overlapping person-level strata, not independent trials.
    # Back+occluded counts in both tags; counts are never added together.
    review_tags = ("back", "partial", "occluded", "appearance_shift",
                   "side", "lookalike")
    coverage = {tag: {"eligible_people": 0, "selected_people": 0,
                      "eligible_people_not_selected_for_this_tag": 0}
                for tag in review_tags}
    # Exact reviewed condition combinations must be accounted for separately.
    # Marginal back+occluded counts may come from disjoint photos and people;
    # only a single rear probe carrying BOTH tags supplies intersection
    # evidence. Keys are the fixed-public condition vocabulary, never IDs.
    exact_coverage = defaultdict(lambda: {
        "eligible_people": 0, "selected_people": 0,
        "eligible_people_not_selected_for_this_exact_condition": 0,
    })
    expected = set()
    comparisons = 0
    subject_owners = {}
    for truth, details in people.items():
        if len(details["subjects"]) != 1:
            raise ValueError("One reviewed identity has contradictory subject groups")
        subject = next(iter(details["subjects"]))
        previous_truth = subject_owners.setdefault(subject, truth)
        if previous_truth != truth:
            raise ValueError("One reviewed known subject aliases distinct truth identities")
        selected = None
        eligible_rear_probes = set()
        # Visit every known front × rear provenance combination, rather
        # than just the first lexical success: otherwise a person's later
        # eligible occluded/partial rear views are silently invisible.
        # This remains outcome-blind and capped at 200k comparisons.
        for front in sorted(details["front"]):
            for rear in sorted(details["rear"]):
                comparisons += 1
                if comparisons > MAX_PAIR_COMPARISONS:
                    raise ValueError("Pair inventory exceeds bounded exhaustive comparison budget")
                if (front[1] != rear[1] and front[2] != rear[2]
                        and front[3] != rear[3]):
                    eligible_rear_probes.add(rear[0])
                    if selected is None:
                        selected = (front[0], rear[0])
        eligible_tags = set()
        for probe in eligible_rear_probes:
            eligible_tags.update(details["rear_tags"][probe])
        selected_tags = details["rear_tags"][selected[1]] if selected else set()
        eligible_exact_labels = {
            "+".join(sorted(details["rear_tags"][probe]))
            for probe in eligible_rear_probes
        }
        selected_exact_label = (
            "+".join(sorted(selected_tags)) if selected else None
        )
        for label in eligible_exact_labels:
            exact_coverage[label]["eligible_people"] += 1
            if label == selected_exact_label:
                exact_coverage[label]["selected_people"] += 1
            else:
                exact_coverage[label][
                    "eligible_people_not_selected_for_this_exact_condition"
                ] += 1
        for tag in review_tags:
            if tag in eligible_tags:
                coverage[tag]["eligible_people"] += 1
            if tag in selected_tags:
                coverage[tag]["selected_people"] += 1
            if tag in eligible_tags and tag not in selected_tags:
                coverage[tag]["eligible_people_not_selected_for_this_tag"] += 1
        if selected is not None:
            expected.add(selected)
    observed = set()
    for row in pair_rows:
        if not isinstance(row, dict) or set(row) != set(PAIR_FIELDS):
            raise ValueError("Malformed frozen paired-pose inventory")
        front, rear = row["front_probe_uid"], row["rear_probe_uid"]
        if (type(front) is not str or type(rear) is not str
                or not TOKEN.fullmatch(front) or not TOKEN.fullmatch(rear)):
            raise ValueError("Unsafe opaque pose pair token")
        if (front, rear) in observed:
            raise ValueError("Repeated frozen pose pair")
        observed.add((front, rear))
    if observed != expected:
        raise ValueError("Frozen pair list is not exhaustive/deterministic for reviewed cohort")
    return {
        "known_people_in_holdout": len(people),
        "known_people_with_eligible_distinct_capture_pose_pair": len(expected),
        "known_people_without_eligible_pose_pair": len(people) - len(expected),
        "paired_selection_exhaustive_under_declared_provenance": True,
        "pair_selection_model_score_independent": True,
        "known_truth_to_reviewed_subject_mapping_one_to_one": True,
        "rear_condition_person_coverage_for_deterministic_pair": coverage,
        "rear_exact_condition_person_coverage_for_deterministic_pair": {
            label: exact_coverage[label] for label in sorted(exact_coverage)
        },
        "exact_condition_cooccurrence_verified_on_one_eligible_rear_probe": True,
        "rear_condition_coverage_uses_all_eligible_declared_capture_pairs": True,
        "condition_coverage_counts_are_overlapping_not_independent": True,
        "actual_capture_independence_or_sample_representativeness_proven": False,
    }


def evaluate_under_pose_protocol(spec, truth_rows, score_rows, roster_rows,
                                 provenance_rows, pair_rows):
    """Reject changed independently annotated truth or insufficient pose pairs."""
    validate_pose_protocol(spec)
    expected = _frozen_truth_index(truth_rows)
    actual = {}
    splits = _parse(score_rows)
    for split, trials in splits.items():
        for uid, session, condition, truth, *_ in trials:
            key = (split, uid)
            if key not in expected:
                raise ValueError("Scored trial lacks independently reviewed truth")
            actual[key] = (truth, session, condition)
    if actual != expected:
        raise ValueError("Scored truth, condition or session mismatches frozen truth")
    if len(pair_rows) < spec["min_known_person_pairs"]:
        raise ValueError("Insufficient preregistered front-to-rear known-person pairs")
    selection = None
    if spec["schema_version"] in (2, 3, 4):
        selection = audit_exhaustive_pose_pairs(splits, provenance_rows, pair_rows)
    report = audit_paired_pose_retention(
        score_rows, provenance_rows, roster_rows, pair_rows,
        margin=spec["top_two_margin"],
        min_condition_pairs=spec["min_condition_pairs"],
        require_component_calibration_safety=(spec["schema_version"] in (3, 4)),
    )
    # Source dependencies are frozen independently of model outcomes. An
    # optional v4 precommitted cohort must contain enough distinct capture
    # components as well as distinct known people; a single event attended
    # by twenty people is NOT twenty independently sampled pose episodes.
    if (spec["schema_version"] == 4
            and report["paired_multi_axis_capture_dependency_safety"][
                "connected_event_lineage_session_components"
            ] < spec["min_known_person_pairs"]):
        raise ValueError("Insufficient independent declared known-pose capture components")
    report["protocol_audit"] = {
        "frozen_truth_metadata_matches_model_scores": True,
        "deterministic_eligible_pair_inventory_enforced": selection is not None,
        "primary_endpoint_and_calibration_policy_locked": True,
        "front_rear_pair_manifest_is_predeclared": True,
        "rear_unknown_safety_policy_locked": True,
        "strict_rear_unknown_capture_component_calibration_enforced":
            spec["schema_version"] in (3, 4),
        "strict_known_pose_capture_component_minimum_enforced":
            spec["schema_version"] == 4,
        "raw_manifest_digests_checked_by_cli": False,
        "model_checkpoint_bytes_or_execution_verified": False,
        "physical_pairing_and_photo_lineage_verified": False,
        "external_pre_holdout_protocol_commitment_verified": False,
        "heldout_inspection_count_verified": False,
        "auto_confirm_allowed": False,
    }
    if selection is not None:
        report["pose_selection_audit"] = selection
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--protocol", required=True, type=Path)
    parser.add_argument("--protocol-sha256", required=True)
    for key, _ in MANIFESTS:
        parser.add_argument("--" + key, required=True, type=Path)
        parser.add_argument("--" + key + "-sha256", required=True)
    args = parser.parse_args(argv)
    try:
        spec = pinned_pose_protocol(args.protocol, args.protocol_sha256)
        # Scores are produced by the evaluated model later; unlike separately
        # frozen truth/roster/provenance/pairs their digest is not preregistered.
        for name in ("truth", "roster", "provenance", "pairs"):
            if spec["frozen_" + name + "_sha256"] != getattr(args, name + "_sha256"):
                raise ValueError("Manifest digest disagrees with independent protocol")
        manifests = {
            name: _load_pinned(getattr(args, name),
                               getattr(args, name + "_sha256"), fields)
            for name, fields in MANIFESTS
        }
        result = evaluate_under_pose_protocol(
            spec, manifests["truth"], manifests["scores"], manifests["roster"],
            manifests["provenance"], manifests["pairs"])
        result["protocol_audit"]["raw_manifest_digests_checked_by_cli"] = True
        print(json.dumps(result, sort_keys=True, indent=2))
        return 0
    except (OSError, ValueError, TypeError, KeyError, OverflowError):
        # Paths, truth identities and model scores must never leak through CLI.
        print("Refused unsafe or mismatched frozen pose study", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
