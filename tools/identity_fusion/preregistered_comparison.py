#!/usr/bin/env python3
"""Offline V5 A/B evaluation under a separately pinned experiment protocol.

A cryptographic digest establishes byte identity, never the date a protocol
was frozen, the independence of annotations, weight provenance, or quality.
No photos, embeddings, models, private People store, network or phone IO.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys

from benchmark_reid_scores import FIELDS as SCORE_FIELDS, TOKEN, CONDITIONS, _normalize_condition
from benchmark_retrieval_recall import _load_pinned
from candidate_list_coverage import FIELDS as ROSTER_FIELDS
from verify_split_provenance import FIELDS as PROVENANCE_FIELDS
from compare_paired_models import compare_paired_models

TRUTH_FIELDS = ("split", "probe_uid", "truth_uid", "session_uid", "condition")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
PROTOCOL_FIELDS = frozenset({
    "schema_version", "study_uid", "frozen_truth_sha256", "frozen_roster_sha256",
    "frozen_provenance_sha256", "model_a_uid", "model_b_uid",
    "model_a_checkpoint_sha256", "model_b_checkpoint_sha256",
    "model_a_margin", "model_b_margin", "max_false_offer_rate",
    "primary_comparison", "calibration_policy",
})
PROTOCOL_V2_EXTRA_FIELDS = frozenset({"protected_condition_tags"})
METRIC = "paired_known_wrong_and_unknown_false_offers_v1"
CALIBRATION = "calibration_only_wilson_known_unknown_event_subject_v1"
MAX_PROTOCOL_BYTES = 8192


def _json_unique_pairs(pairs):
    result = {}
    for k, v in pairs:
        if k in result:
            raise ValueError("Duplicate preregistration field")
        result[k] = v
    return result


def _digest(value):
    return isinstance(value, str) and SHA256.fullmatch(value) is not None


def validate_protocol(spec):
    """Reject alternative endpoints/metric shopping and undocumented knobs."""
    if not isinstance(spec, dict) or type(spec.get("schema_version")) is not int:
        raise ValueError("Incorrect experimental protocol schema")
    version = spec["schema_version"]
    if version not in (1, 2):
        raise ValueError("Unrecognized preregistration schema version")
    required = PROTOCOL_FIELDS | (PROTOCOL_V2_EXTRA_FIELDS if version == 2
                                  else frozenset())
    if set(spec) != required:
        raise ValueError("Missing or unexpected experimental protocol fields")
    if version == 2:
        tags = spec["protected_condition_tags"]
        if (not isinstance(tags, list)
                or not 1 <= len(tags) <= len(CONDITIONS)
                or any(type(t) is not str or t not in CONDITIONS for t in tags)
                or tags != sorted(set(tags))):
            raise ValueError("V2 protected tags must be unique sorted public labels")
    for field in ("study_uid", "model_a_uid", "model_b_uid"):
        token = spec[field]
        if (not isinstance(token, str) or not TOKEN.fullmatch(token)
                or token in {"UNKNOWN", "NONE", "UNREVIEWED"}):
            raise ValueError("Experiment identifiers must be opaque")
    if spec["model_a_uid"] == spec["model_b_uid"]:
        raise ValueError("Distinct model names required")
    for field in (
        "frozen_truth_sha256", "frozen_roster_sha256",
        "frozen_provenance_sha256", "model_a_checkpoint_sha256",
        "model_b_checkpoint_sha256",
    ):
        if not _digest(spec[field]):
            raise ValueError("Full frozen data/model SHA256 required")
    for field in ("model_a_margin", "model_b_margin"):
        value = spec[field]
        if (type(value) not in (float, int) or not math.isfinite(value)
                or not 0 <= value <= 1):
            raise ValueError("Finite preregistered model margin required")
    budget = spec["max_false_offer_rate"]
    if (type(budget) not in (float, int) or not math.isfinite(budget)
            or not 0 < budget <= .2):
        raise ValueError("Invalid preregistered false-offer budget")
    if spec["primary_comparison"] != METRIC or spec["calibration_policy"] != CALIBRATION:
        raise ValueError("Unsupported or unregistered evaluation policy")
    return spec


def pinned_protocol(path: Path, digest: str):
    """Read one small, regular, non-symlink immutable-pinned JSON manifest."""
    if not _digest(digest) or path.is_symlink() or not path.is_file():
        raise ValueError("Protocol SHA256 or local file invalid")
    if not 0 < path.stat().st_size <= MAX_PROTOCOL_BYTES:
        raise ValueError("Oversized or absent experiment protocol")
    with path.open("rb") as stream:
        raw = stream.read(MAX_PROTOCOL_BYTES + 1)
    if (not 0 < len(raw) <= MAX_PROTOCOL_BYTES
            or hashlib.sha256(raw).hexdigest() != digest):
        raise ValueError("Experiment protocol bytes differ from frozen digest")
    try:
        spec = json.loads(raw.decode("utf-8"),
                          object_pairs_hook=_json_unique_pairs,
                          parse_constant=lambda x: (_ for _ in ()).throw(
                              ValueError("Nonfinite protocol value")))
    except (UnicodeError, json.JSONDecodeError):
        raise ValueError("Malformed experiment protocol JSON") from None
    return validate_protocol(spec)


def _frozen_truth_index(rows):
    if not isinstance(rows, (list, tuple)) or not rows:
        raise ValueError("Independently reviewed truth manifest required")
    indexed = {}
    for row in rows:
        if not isinstance(row, dict) or set(row) != set(TRUTH_FIELDS):
            raise ValueError("Malformed reviewed truth manifest")
        if not all(isinstance(row[key], str) for key in TRUTH_FIELDS):
            raise ValueError("Non-opaque frozen truth field")
        split, probe = row["split"], row["probe_uid"]
        if split not in {"calibration", "holdout"}:
            raise ValueError("Invalid frozen split")
        if any(not TOKEN.fullmatch(row[k])
               for k in ("probe_uid", "truth_uid", "session_uid")):
            raise ValueError("Non-opaque frozen truth token")
        if row["truth_uid"] == "NONE":
            raise ValueError("No-candidate sentinel is not a true identity")
        condition = _normalize_condition(row["condition"])
        key = (split, probe)
        if key in indexed:
            raise ValueError("Duplicate frozen truth probe")
        indexed[key] = (row["truth_uid"], row["session_uid"], condition)
    return indexed


def compare_under_protocol(spec, truth_rows, a_rows, b_rows, roster_rows,
                           provenance_rows):
    """Check the frozen independent truth against both model score files."""
    validate_protocol(spec)
    expected = _frozen_truth_index(truth_rows)
    for data in (a_rows, b_rows):
        observed = {}
        for row in data:
            key = row["split"], row["probe_uid"]
            if key not in expected:
                raise ValueError("Scored probe not in independently frozen truth")
            meta = (row["truth_uid"], row["session_uid"],
                    _normalize_condition(row["condition"]))
            if meta != expected[key]:
                raise ValueError("Scored metadata differs from frozen reviewed truth")
            observed[key] = meta
        if observed != expected:
            raise ValueError("Scored model missing frozen reviewed probes")
    response = compare_paired_models(
        a_rows, b_rows, roster_rows=roster_rows,
        provenance_rows=provenance_rows,
        margin_a=spec["model_a_margin"],
        margin_b=spec["model_b_margin"],
        max_false_offer_rate=spec["max_false_offer_rate"],
        protected_condition_tags=spec.get("protected_condition_tags", []))
    response["protocol_audit"] = {
        "frozen_truth_metadata_matches_both_models": True,
        "metric_selection_matches_pinned_protocol": True,
        "model_specific_margins_match_pinned_protocol": True,
        "calibration_budget_matches_pinned_protocol": True,
        "protected_condition_tags_match_pinned_protocol": (
            response["protected_condition_tags"] ==
            spec.get("protected_condition_tags", [])),
        "protected_condition_tags_explicitly_registered": (
            spec["schema_version"] == 2),
        "raw_manifest_hashes_checked_by_cli": False,
        "checkpoint_execution_verified": False,
        "external_pre_holdout_commitment_verified": False,
        "holdout_inspection_count_verified": False,
        "auto_confirm_allowed": False,
    }
    return response


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("protocol", "truth", "a", "b", "roster", "provenance"):
        parser.add_argument("--" + name, required=True, type=Path)
        parser.add_argument("--" + name + "-sha256", required=True)
    args = parser.parse_args(argv)
    try:
        spec = pinned_protocol(args.protocol, args.protocol_sha256)
        for item in ("truth", "roster", "provenance"):
            if spec["frozen_" + item + "_sha256"] != getattr(args, item + "_sha256"):
                raise ValueError("Frozen input digest contradicts the protocol")
        truth = _load_pinned(args.truth, args.truth_sha256, TRUTH_FIELDS)
        a = _load_pinned(args.a, args.a_sha256, SCORE_FIELDS)
        b = _load_pinned(args.b, args.b_sha256, SCORE_FIELDS)
        roster = _load_pinned(args.roster, args.roster_sha256, ROSTER_FIELDS)
        provenance = _load_pinned(args.provenance, args.provenance_sha256,
                                  PROVENANCE_FIELDS)
        result = compare_under_protocol(spec, truth, a, b, roster, provenance)
        result["protocol_audit"]["raw_manifest_hashes_checked_by_cli"] = True
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, TypeError, KeyError):
        # Keep private paths, raw cohort/subject IDs and scores out of errors.
        print("Refused mismatched or unsafe pinned A/B study", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
