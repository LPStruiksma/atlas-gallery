#!/usr/bin/env python3
"""Checksum-locked, offline front/rear re-ID research report from opaque CSVs.

Does not load images, model weights, embeddings, native People, or phone data.
This is a read-only diagnostic, NEVER an automatic identity decision.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from audit_paired_pose_retention import audit_paired_pose_retention
from benchmark_reid_scores import FIELDS as SCORE_FIELDS
from benchmark_retrieval_recall import _load_pinned
from candidate_list_coverage import FIELDS as ROSTER_FIELDS
from verify_split_provenance import FIELDS as PROVENANCE_FIELDS

PAIR_FIELDS = ("front_probe_uid", "rear_probe_uid")
MANIFESTS = (
    ("scores", SCORE_FIELDS),
    ("roster", ROSTER_FIELDS),
    ("provenance", PROVENANCE_FIELDS),
    ("pairs", PAIR_FIELDS),
)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name, _ in MANIFESTS:
        parser.add_argument("--" + name, required=True, type=Path)
        parser.add_argument("--" + name + "-sha256", required=True)
    parser.add_argument("--margin", type=float, default=0.0)
    parser.add_argument("--min-condition-pairs", type=int, default=10)
    args = parser.parse_args(argv)
    try:
        manifests = {
            name: _load_pinned(getattr(args, name),
                               getattr(args, name + "_sha256"), fields)
            for name, fields in MANIFESTS
        }
        report = audit_paired_pose_retention(
            manifests["scores"], manifests["provenance"], manifests["roster"],
            manifests["pairs"], margin=args.margin,
            min_condition_pairs=args.min_condition_pairs,
        )
        report["manifest_audit"] = {
            "scores_roster_provenance_pair_sha256_checked": True,
            "strict_opaque_headers_and_bounded_regular_files": True,
            "preregistered_before_holdout_inspection_proven": False,
            "pair_selection_independently_verified": False,
            "auto_confirm_allowed": False,
        }
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, TypeError, KeyError, OverflowError):
        # Do not print user-supplied paths, subject tokens, images or scores.
        print("Refused unsafe or inconsistent pinned pose benchmark",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
