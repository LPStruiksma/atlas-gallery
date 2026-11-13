#!/usr/bin/env python3
"""Read-only synthetic-ready split provenance gate for reidentification scores.

Correlated crops, colorized variants, burst photos and repeated people can
leak from calibration into holdout under different probe/session labels.
Caller-supplied opaque group IDs expose *known* leakage; they do NOT prove
that all physical duplicates or people were successfully grouped.
"""
from __future__ import annotations

from collections import defaultdict
from collections.abc import Sequence
import re

FIELDS = ("probe_uid", "split", "photo_lineage_uid", "event_cluster_uid",
          "subject_cluster_uid")
TOKEN = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
MAX_PROBES = 100_000
UNREVIEWED = "UNREVIEWED"


def verify_split_provenance(provenance_rows: Sequence[dict], score_splits: dict) -> dict:
    """Require exact one-to-one opaque probe coverage and partition isolation.

    score_splits comes from the already-validated benchmark_reid_scores._parse:
      {"calibration": [(probe_uid,...)], "holdout": [(probe_uid,...)]}.
    Photo-lineage and event clusters must be reviewed as meaningful opaque
    equivalence groups. Subject clusters may be UNREVIEWED; those trials
    remain explicitly unverified instead of fabricated 'independent' people.
    """
    if (not isinstance(provenance_rows, (tuple, list))
            or not 1 <= len(provenance_rows) <= MAX_PROBES
            or not isinstance(score_splits, dict)
            or set(score_splits) != {"calibration", "holdout"}):
        raise ValueError("Expected bounded provenance and validated score splits")
    expected = set()
    # Use independently reviewed exact-probe truth, never a top-scoring
    # candidate. Input score_splits is already checked by _parse().
    truth_by_probe = {}
    for split, trials in score_splits.items():
        for trial in trials:
            key = (split, trial[0])
            expected.add(key)
            truth_by_probe[key] = trial[3]
    if len(expected) != sum(len(v) for v in score_splits.values()):
        raise ValueError("Score splits reuse probe identifiers")
    if len(provenance_rows) != len(expected):
        raise ValueError("Provenance manifest does not cover exactly every probe")

    seen = set()
    probe_splits = {}
    lineage_splits = defaultdict(set)
    event_splits = defaultdict(set)
    subject_splits = defaultdict(set)
    lineage_count = defaultdict(int)
    event_count = defaultdict(int)
    reviewed_subject_count = defaultdict(int)
    reviewed_subject_truth = {}
    reviewed_known_truth_subject = {}
    reviewed_subject_conflicts = set()
    unreviewed = 0

    for row in provenance_rows:
        if not isinstance(row, dict) or set(row) != set(FIELDS):
            raise ValueError("Malformed provenance fields or forbidden private path")
        values = [row[field] for field in FIELDS]
        if not all(isinstance(v, str) and TOKEN.fullmatch(v) for v in values):
            raise ValueError("Non-opaque or missing provenance token")
        probe, split, lineage, event, subject = values
        if split not in {"calibration", "holdout"}:
            raise ValueError("Unknown provenance partition")
        key = (split, probe)
        if key not in expected or key in seen:
            raise ValueError("Provenance probe duplicated or missing from score manifest")
        seen.add(key)
        if probe in probe_splits and probe_splits[probe] != split:
            raise ValueError("Exact probe reused across calibration and holdout")
        probe_splits[probe] = split
        if lineage == UNREVIEWED or event == UNREVIEWED:
            raise ValueError("Lineage and event equivalence groups must be supplied")
        lineage_splits[lineage].add(split)
        event_splits[event].add(split)
        lineage_count[lineage] += 1
        event_count[event] += 1
        if subject == UNREVIEWED:
            unreviewed += 1
        else:
            subject_splits[subject].add(split)
            reviewed_subject_count[subject] += 1
            truth = truth_by_probe[key]
            if subject in reviewed_subject_truth:
                previous = reviewed_subject_truth[subject]
                if (previous == "UNKNOWN") != (truth == "UNKNOWN"):
                    reviewed_subject_conflicts.add("known_unknown")
                elif truth != "UNKNOWN" and previous != truth:
                    reviewed_subject_conflicts.add("known_alias")
            else:
                reviewed_subject_truth[subject] = truth
            if truth != "UNKNOWN":
                # A known truth UID denotes the *same* reviewed person:
                # two different reviewed groups for that truth would let
                # captures masquerade as independently sourced subjects.
                previous_subject = reviewed_known_truth_subject.setdefault(
                    (split, truth), subject)
                if previous_subject != subject:
                    reviewed_subject_conflicts.add("known_split_subject_alias")

    if seen != expected:
        raise ValueError("Provenance missing scored probes")
    for collections in (lineage_splits, event_splits, subject_splits):
        if any(len(split_set) != 1 for split_set in collections.values()):
            raise ValueError("Provenance lineage, event or reviewed subject leaks across splits")
    # Existing cross-split isolation gets first refusal so investigations
    # keep the correct primary diagnosis; new within-split truth coherence
    # is then required before the audit can claim success.
    if "known_unknown" in reviewed_subject_conflicts:
        raise ValueError("One reviewed subject cannot be known and Unknown")
    if "known_alias" in reviewed_subject_conflicts:
        raise ValueError("Reviewed subject aliases distinct known truth identifiers")
    if "known_split_subject_alias" in reviewed_subject_conflicts:
        raise ValueError("One known truth aliases distinct reviewed subject clusters")

    return {
        "status": "reported_lineage_event_subject_groups_partition_clean",
        "all_scored_probes_covered": True,
        "scored_probes": len(expected),
        "distinct_photo_lineages": len(lineage_count),
        "lineages_with_multiple_probes": sum(n > 1 for n in lineage_count.values()),
        "distinct_event_clusters": len(event_count),
        "events_with_multiple_probes": sum(n > 1 for n in event_count.values()),
        "distinct_reviewed_subject_clusters": len(reviewed_subject_count),
        "reviewed_subject_truth_coherence_enforced": True,
        "known_truth_to_reviewed_subject_mapping_one_to_one_enforced": True,
        "unreviewed_subject_probes": unreviewed,
        "physical_independence_proven": False,
        "auto_confirm_allowed": False,
        "caveat": "Checks only caller-declared equivalence groups. Contradictory "
                  "truth IDs for one reviewed group fail closed, but separately "
                  "aliased physical people under different truth UIDs, "
                  "unreviewed clusters, misgrouped events and unlabeled "
                  "Unknown identities remain possible. No model or image loaded.",
    }
