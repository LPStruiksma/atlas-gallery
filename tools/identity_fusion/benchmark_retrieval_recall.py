#!/usr/bin/env python3
"""Held-out retrieval recall for synthetic or independently reviewed opaque IDs.

Operates ONLY on frozen CSV ID manifests; never reads images, embeddings,
models, V4 databases or network. A retrieved candidate is NOT an identity
suggestion, a correct Unknown rejection, or a calibrated probability.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import sys

from candidate_list_coverage import FIELDS as ROSTER_FIELDS
from benchmark_reid_scores import TOKEN

TRUTH_FIELDS = ("split", "probe_uid", "truth_uid", "session_uid")
RETRIEVED_FIELDS = ("split", "probe_uid", "candidate_uid", "rank")
SPLITS = ("calibration", "holdout")
MAX_ROWS = 100_000
MAX_BYTES = 16 * 1024 * 1024
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def _opaque(value):
    return isinstance(value, str) and TOKEN.fullmatch(value) is not None


def _index_trials(rows):
    if not isinstance(rows, (list, tuple)) or not 1 <= len(rows) <= MAX_ROWS:
        raise ValueError("Bounded reviewed truth manifest required")
    out = {}
    seen_probes = set()
    people = {s: set() for s in SPLITS}
    sessions = {s: set() for s in SPLITS}
    cohorts = {s: {"known": 0, "unknown": 0} for s in SPLITS}
    for row in rows:
        if not isinstance(row, dict) or set(row) != set(TRUTH_FIELDS):
            raise ValueError("Unexpected truth columns")
        if not all(_opaque(row[k]) for k in TRUTH_FIELDS):
            raise ValueError("Malformed opaque truth token")
        split, probe, truth, session = (row[k] for k in TRUTH_FIELDS)
        if split not in SPLITS or truth == "NONE" or probe in seen_probes:
            raise ValueError("Duplicate probe or invalid split/truth")
        key = (split, probe)
        seen_probes.add(probe)
        out[key] = (truth, session)
        sessions[split].add(session)
        kind = "unknown" if truth == "UNKNOWN" else "known"
        cohorts[split][kind] += 1
        if truth != "UNKNOWN":
            people[split].add(truth)
    if (people["calibration"] & people["holdout"]
            or sessions["calibration"] & sessions["holdout"]):
        raise ValueError("Truth identity/session leaks between splits")
    if any(min(cohorts[split].values()) < 10 for split in SPLITS):
        raise ValueError("Need >=10 independently reviewed known and Unknown probes per split")
    return out


def _eligible_groups(rows, expected_keys):
    if not isinstance(rows, (list, tuple)) or not 1 <= len(rows) <= MAX_ROWS:
        raise ValueError("Bounded frozen roster required")
    inventory = defaultdict(set)
    by_split = {s: None for s in SPLITS}
    all_ids = {s: set() for s in SPLITS}
    for row in rows:
        if not isinstance(row, dict) or set(row) != set(ROSTER_FIELDS):
            raise ValueError("Frozen roster contains unsafe columns")
        split, probe, candidate = (row[k] for k in ROSTER_FIELDS)
        if not all(_opaque(v) for v in (split, probe, candidate)):
            raise ValueError("Non-opaque roster entry")
        key = (split, probe)
        if key not in expected_keys or candidate == "UNKNOWN" or candidate in inventory[key]:
            raise ValueError("Roster has alien/duplicate candidate")
        if candidate == "NONE" and inventory[key]:
            raise ValueError("No-candidate sentinel mixed with gallery identities")
        if candidate != "NONE" and "NONE" in inventory[key]:
            raise ValueError("No-candidate sentinel mixed with gallery identities")
        inventory[key].add(candidate)
        if candidate != "NONE":
            all_ids[split].add(candidate)
    if inventory.keys() != expected_keys:
        raise ValueError("Frozen roster does not cover all reviewed probes")
    if all_ids["calibration"] & all_ids["holdout"]:
        raise ValueError("Eligible gallery identities leak across splits")
    for (split, probe), candidates in inventory.items():
        if by_split[split] is None:
            by_split[split] = frozenset(candidates)
        elif frozenset(candidates) != by_split[split]:
            raise ValueError("Per-probe eligibility filters are unsupported in frozen roster")
    return inventory


def benchmark_retrieval_recall(truth_rows, roster_rows, retrieved_rows, *, top_k: int = 5):
    """Evaluate source-independent top-k retrieval, no score calibration.

    All probe IDs must be present in all three lists. Retrieval can return
    NONE when candidate gallery is nonempty, indicating an empty search
    result (abstention), NOT proof that no candidate exists.
    """
    if type(top_k) is not int or not 1 <= top_k <= 100:
        raise ValueError("Top-k must be an integer from 1 to 100")
    trials = _index_trials(truth_rows)
    eligible = _eligible_groups(roster_rows, trials.keys())
    if (not isinstance(retrieved_rows, (list, tuple))
            or not 1 <= len(retrieved_rows) <= MAX_ROWS):
        raise ValueError("Bounded retrieved-candidate manifest required")

    returned = defaultdict(dict)
    for row in retrieved_rows:
        if not isinstance(row, dict) or set(row) != set(RETRIEVED_FIELDS):
            raise ValueError("Unexpected retrieved candidate fields")
        split, probe, candidate, rank = (row[k] for k in RETRIEVED_FIELDS)
        if (not all(_opaque(v) for v in (split, probe, candidate, rank))
                or split not in SPLITS):
            raise ValueError("Malformed opaque retrieval row")
        key = (split, probe)
        if key not in trials:
            raise ValueError("Retrieved candidate for unreviewed probe")
        if candidate == "UNKNOWN" or not re.fullmatch(r"0|[1-9][0-9]*", rank):
            raise ValueError("Invalid retrieved candidate/rank")
        position = int(rank)
        if candidate == "NONE":
            if position != 0 or returned[key]:
                raise ValueError("Malformed empty-retrieval sentinel")
        elif (not 1 <= position <= top_k or candidate not in eligible[key]
              or "NONE" in returned[key].values()):
            raise ValueError("Unfrozen or over-budget retrieved candidate")
        if position in returned[key] or candidate in returned[key].values():
            raise ValueError("Duplicate retrieved rank/candidate")
        returned[key][position] = candidate
    if returned.keys() != trials.keys():
        raise ValueError("Retrieved list must represent every reviewed probe")

    metrics = {}
    for split in SPLITS:
        known_total = 0
        known_eligible = 0
        known_missing_gallery = 0
        known_retrieved = 0
        known_top1 = 0
        unknown_total = 0
        unknown_with_candidate = 0
        empty_results = 0
        at_cap = 0
        persons = defaultdict(list)
        for key, (truth, session) in trials.items():
            if key[0] != split:
                continue
            values = returned[key]
            if 0 in values:
                if values != {0: "NONE"}:
                    raise ValueError("Mixed empty retrieval with candidate ranks")
                ranked = []
            else:
                if sorted(values) != list(range(1, len(values) + 1)):
                    raise ValueError("Noncontiguous top-k ranking")
                ranked = [values[i] for i in sorted(values)]
            empty_results += not ranked
            at_cap += len(ranked) == top_k
            if truth == "UNKNOWN":
                unknown_total += 1
                unknown_with_candidate += bool(ranked)
            else:
                known_total += 1
                if truth in eligible[key]:
                    known_eligible += 1
                    found = truth in ranked
                    known_retrieved += found
                    known_top1 += bool(ranked and ranked[0] == truth)
                    persons[truth].append(found)
                else:
                    known_missing_gallery += 1

        metrics[split] = {
            "known_probes": known_total,
            "known_probes_target_eligible": known_eligible,
            "known_probes_target_absent_from_gallery": known_missing_gallery,
            "known_eligible_target_returned_at_k": known_retrieved,
            "known_eligible_target_returned_at_1": known_top1,
            "known_eligible_recall_at_k": round(known_retrieved / known_eligible, 4)
                if known_eligible else None,
            "known_eligible_recall_at_1": round(known_top1 / known_eligible, 4)
                if known_eligible else None,
            "known_persons_with_eligible_targets": len(persons),
            "known_persons_retrieved_on_every_probe": sum(all(v) for v in persons.values()),
            "unknown_probes": unknown_total,
            "unknown_probes_with_any_candidate_retrieved": unknown_with_candidate,
            "empty_retrieval_probes": empty_results,
            "candidate_lists_at_k_cap": at_cap,
        }
    return {
        "status": "offline_retrieval_quality_only",
        "top_k": top_k,
        "by_split": metrics,
        "unknown_retrieval_is_not_a_false_identity_offer": True,
        "retrieval_ranks_are_not_identity_probabilities": True,
        "gallery_exhaustiveness_proven": False,
        "auto_confirm_allowed": False,
        "caveat": "Eligible-gallery roster and labels require independent review. "
                  "Ranked retrieval is NOT identity assignment, and no retrieval "
                  "of an Unknown person is NOT proof of a true reject.",
    }


def _load_pinned(path: Path, digest: str, columns):
    if not isinstance(digest, str) or not SHA256.fullmatch(digest):
        raise ValueError("Missing complete SHA256 pin")
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_BYTES:
        raise ValueError("Missing, symlinked or oversized local CSV")
    with path.open("rb") as stream:
        data = stream.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES or hashlib.sha256(data).hexdigest() != digest:
        raise ValueError("Unpinned input content")
    try:
        reader = csv.DictReader(io.StringIO(data.decode("utf-8-sig"), newline=""),
                                strict=True)
        if reader.fieldnames != list(columns):
            raise ValueError("Unexpected input CSV header")
        rows = list(reader)
        if len(rows) > MAX_ROWS:
            raise ValueError("Input row limit exceeded")
        return rows
    except (UnicodeError, csv.Error):
        raise ValueError("Malformed pinned CSV") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for item in ("truth", "roster", "retrieved"):
        parser.add_argument("--" + item, required=True, type=Path)
        parser.add_argument("--" + item + "-sha256", required=True)
    parser.add_argument("--top-k", type=int, default=5)
    args = parser.parse_args(argv)
    try:
        truth = _load_pinned(args.truth, args.truth_sha256, TRUTH_FIELDS)
        roster = _load_pinned(args.roster, args.roster_sha256, ROSTER_FIELDS)
        retrieved = _load_pinned(args.retrieved, args.retrieved_sha256, RETRIEVED_FIELDS)
        print(json.dumps(benchmark_retrieval_recall(
            truth, roster, retrieved, top_k=args.top_k), indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, TypeError):
        print("Retrieval benchmark refused unsafe or incomplete local manifest",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
