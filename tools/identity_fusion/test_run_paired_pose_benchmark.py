#!/usr/bin/env python3
"""Synthetic, no-image checksum CLI validation for rear/partial pose research."""
import contextlib
import csv
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from benchmark_reid_scores import FIELDS as SCORE_FIELDS
from candidate_list_coverage import FIELDS as ROSTER_FIELDS
from verify_split_provenance import FIELDS as PROVENANCE_FIELDS
from run_paired_pose_benchmark import main, PAIR_FIELDS
from test_audit_paired_pose_retention import scenario


FIELDS = {
    "scores": SCORE_FIELDS,
    "roster": ROSTER_FIELDS,
    "provenance": PROVENANCE_FIELDS,
    "pairs": PAIR_FIELDS,
}


def frozen(tmp):
    scores, provenance, roster, pairs = scenario()
    rows = dict(scores=scores, provenance=provenance, roster=roster, pairs=pairs)
    flags, paths = [], {}
    for name, fields in FIELDS.items():
        path = Path(tmp) / (name + ".csv")
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows[name])
        paths[name] = path
        flags += ["--" + name, str(path), "--" + name + "-sha256",
                  hashlib.sha256(path.read_bytes()).hexdigest()]
    return flags, paths


def call(args):
    stdout, stderr = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        code = main(args)
    return code, stdout.getvalue(), stderr.getvalue()


class PinnedRearPoseCLITests(unittest.TestCase):
    def test_frozen_manifests_give_only_aggregates_not_photo_identity_data(self):
        with tempfile.TemporaryDirectory(prefix="confidential-pose-cohort-") as root:
            flags, paths = frozen(root)
            code, raw, errors = call(flags)
            self.assertEqual(code, 0, errors)
            self.assertEqual(errors, "")
            data = json.loads(raw)
            self.assertEqual(data["overall"]["reviewed_known_person_pairs"], 25)
            self.assertEqual(data["overall"]["front_correct_rear_wrong"], 5)
            self.assertEqual(data["rear_unknown_controls"]["holdout"]["overall"]
                             ["groups_with_false_offers"], 4)
            self.assertTrue(data["manifest_audit"]
                            ["scores_roster_provenance_pair_sha256_checked"])
            self.assertFalse(data["manifest_audit"]
                             ["preregistered_before_holdout_inspection_proven"])
            self.assertFalse(data["auto_confirm_allowed"])
            for raw_id in ("HoldProbeK", "HoldKnown", "Lineage_", "Anonymous_", root):
                self.assertNotIn(raw_id, raw)
            self.assertEqual(set(paths), set(FIELDS))

    def test_mutated_pair_manifest_after_pin_fails_without_private_path(self):
        with tempfile.TemporaryDirectory(prefix="private-identities-") as root:
            flags, paths = frozen(root)
            with paths["pairs"].open("a", encoding="utf-8") as output:
                output.write("\n")
            code, output, error = call(flags)
            self.assertEqual(code, 2)
            self.assertEqual(output, "")
            self.assertNotIn(root, error)
            self.assertNotIn("HoldKnown", error)
            self.assertIn("Refused", error)

    def test_bad_headers_fail_even_when_rehashed_and_alias_unknown_cohorts_fail(self):
        with tempfile.TemporaryDirectory() as root:
            flags, paths = frozen(root)
            pair_path = paths["pairs"]
            raw = pair_path.read_text(encoding="utf-8").replace(
                "front_probe_uid,rear_probe_uid",
                "front_probe_uid,rear_probe_uid,private_filename", 1)
            pair_path.write_text(raw, encoding="utf-8")
            p = flags.index("--pairs-sha256") + 1
            flags[p] = hashlib.sha256(pair_path.read_bytes()).hexdigest()
            self.assertEqual(call(flags)[0], 2)
            # Valid bytes + hash still cannot substitute a one-sided cohort
            # of front-facing strangers for the required rear Unknowns.
            flags, paths = frozen(root)
            score = paths["scores"]
            text = score.read_text(encoding="utf-8").replace(
                ",back+lookalike,", ",front+lookalike,")
            score.write_text(text, encoding="utf-8")
            p = flags.index("--scores-sha256") + 1
            flags[p] = hashlib.sha256(score.read_bytes()).hexdigest()
            self.assertEqual(call(flags)[0], 2)

    def test_symlink_and_nonfinite_margin_refused(self):
        with tempfile.TemporaryDirectory() as root:
            flags, paths = frozen(root)
            target = paths["scores"]
            alias = Path(root) / "scores-alias.csv"
            alias.symlink_to(target)
            pos = flags.index("--scores") + 1
            flags[pos] = str(alias)
            self.assertEqual(call(flags)[0], 2)
            flags[pos] = str(target)
            self.assertEqual(call(flags + ["--margin", "nan"])[0], 2)


if __name__ == "__main__":
    unittest.main()
