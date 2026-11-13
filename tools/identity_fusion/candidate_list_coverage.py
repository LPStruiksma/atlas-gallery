"""Synthetic-only equality audit for separately frozen candidate inventories.

Checks declared coverage, not completeness of the real gallery.
"""
from collections import defaultdict
from benchmark_reid_scores import TOKEN

FIELDS = ("split", "probe_uid", "candidate_uid")


def audit_candidate_list(scored, roster):
    if not isinstance(roster, (list, tuple)) or not 1 <= len(roster) <= 100000:
        raise ValueError("Expected bounded frozen roster")
    actual, expected, truths = defaultdict(set), defaultdict(set), {}
    for row in scored:
        key = (row["split"], row["probe_uid"])
        actual[key].add(row["candidate_uid"])
        truths[key] = row["truth_uid"]
    for row in roster:
        if not isinstance(row, dict) or set(row) != set(FIELDS):
            raise ValueError("Candidate roster has invalid fields")
        split, probe, candidate = (row[k] for k in FIELDS)
        if (not all(isinstance(v, str) and TOKEN.fullmatch(v)
                    for v in (split, probe, candidate))
                or split not in ("calibration", "holdout")
                or candidate == "UNKNOWN"):
            raise ValueError("Invalid roster token")
        key = (split, probe)
        if key not in actual or candidate in expected[key]:
            raise ValueError("Unexpected or duplicate roster candidate")
        if candidate == "NONE" and expected[key]:
            raise ValueError("No-candidate sentinel mixed with candidates")
        if candidate != "NONE" and "NONE" in expected[key]:
            raise ValueError("No-candidate sentinel mixed with candidates")
        expected[key].add(candidate)
    if expected.keys() != actual.keys() or any(
            expected[k] != actual[k] for k in actual):
        raise ValueError("Incomplete candidate scores versus frozen roster")
    stats = {}
    for split in ("calibration", "holdout"):
        keys = [k for k in actual if k[0] == split]
        stats[split] = {
            "probes": len(keys),
            "scored_candidates": sum(len(actual[k]) for k in keys),
            "single_candidate_probes": sum(
                len(actual[k]) == 1 and "NONE" not in actual[k] for k in keys),
            "no_candidate_probes": sum("NONE" in actual[k] for k in keys),
            "known_target_not_in_roster": sum(
                truths[k] != "UNKNOWN" and truths[k] not in expected[k]
                for k in keys),
        }
    return {
        "status": "frozen_roster_matches_scored_candidates",
        "by_split": stats,
        "roster_independence_verified": False,
        "gallery_exhaustiveness_verified": False,
        "auto_confirm_allowed": False,
    }
