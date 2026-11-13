#!/usr/bin/env python3
"""Fictional candidate inventory safety tests; no media or models."""
import copy
import json
import unittest

from benchmark_reid_scores import benchmark_model_scores
from candidate_list_coverage import audit_candidate_list, FIELDS


def make_roster(rows):
    return [{k: row[k] for k in FIELDS} for row in rows]


class CandidateListCoverageTests(unittest.TestCase):
    def setUp(self):
        from test_benchmark_reid_scores import fixture
        self.rows = fixture()
        self.roster = make_roster(self.rows)

    def test_full_inventory_reports_only_aggregates(self):
        result = benchmark_model_scores(
            self.rows, candidate_roster_rows=self.roster,
            require_candidate_roster=True)
        audit = result["candidate_roster_audit"]
        self.assertEqual(audit["status"], "frozen_roster_matches_scored_candidates")
        self.assertEqual(audit["by_split"]["calibration"]["scored_candidates"], 300)
        self.assertEqual(audit["by_split"]["holdout"]["scored_candidates"], 75)
        self.assertEqual(audit["by_split"]["holdout"]["single_candidate_probes"], 25)
        self.assertEqual(audit["by_split"]["holdout"]["known_target_not_in_roster"], 0)
        self.assertFalse(audit["gallery_exhaustiveness_verified"])
        self.assertFalse(audit["roster_independence_verified"])
        self.assertFalse(audit["auto_confirm_allowed"])
        self.assertEqual(result["similarity_floor"],
                         benchmark_model_scores(self.rows)["similarity_floor"])
        self.assertEqual(result, benchmark_model_scores(
            list(reversed(self.rows)), candidate_roster_rows=list(reversed(self.roster))))
        for forbidden in ("CalProbe", "HoldKnown", "candidate_uid", "/storage/"):
            self.assertNotIn(forbidden, json.dumps(result))

    def test_missing_or_unscored_rival_abstains_from_audit(self):
        ghost = self.roster + [
            {"split": "holdout", "probe_uid": "HoldProbeK_0",
             "candidate_uid": "Unreturned_Rival"}]
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            benchmark_model_scores(self.rows, candidate_roster_rows=ghost)
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            benchmark_model_scores(self.rows, candidate_roster_rows=self.roster[:-1])
        with self.assertRaises(ValueError):
            benchmark_model_scores(self.rows, require_candidate_roster=True)

    def test_no_candidate_known_person_retains_reviewed_known_truth(self):
        rows = [dict(r) for r in self.rows if r["probe_uid"] != "HoldProbeK_0"]
        exemplar = next(r for r in self.rows if r["probe_uid"] == "HoldProbeK_0")
        rows.append(dict(exemplar, candidate_uid="NONE", similarity=""))
        stats = benchmark_model_scores(
            rows, candidate_roster_rows=make_roster(rows)
        )["candidate_roster_audit"]["by_split"]["holdout"]
        self.assertEqual(stats["no_candidate_probes"], 1)
        self.assertEqual(stats["known_target_not_in_roster"], 1)
        self.assertEqual(stats["single_candidate_probes"], 25)

    def test_cli_requires_inventory_and_redacts_local_paths(self):
        import contextlib
        import csv
        import io
        import tempfile
        from pathlib import Path
        from benchmark_reid_scores import main, FIELDS as SCORE_FIELDS
        with tempfile.TemporaryDirectory(prefix="atlas-private-local-candidates-") as d:
            score = Path(d) / "private-scores.csv"
            roster = Path(d) / "private-roster.csv"
            for path, columns, rows in (
                (score, SCORE_FIELDS, self.rows),
                (roster, FIELDS, self.roster),
            ):
                with path.open("w", newline="", encoding="utf-8") as f:
                    writer = csv.DictWriter(f, fieldnames=columns)
                    writer.writeheader()
                    writer.writerows(rows)
            with contextlib.redirect_stdout(io.StringIO()) as output:
                rc = main([str(score), "--candidate-roster-csv", str(roster),
                           "--require-candidate-roster"])
            self.assertEqual(rc, 0)
            self.assertEqual(json.loads(output.getvalue())[
                "candidate_roster_audit"]["by_split"]["holdout"]["probes"], 50)
            roster.write_text("private bad data\n")
            with contextlib.redirect_stderr(io.StringIO()) as errors:
                rc = main([str(score), "--candidate-roster-csv", str(roster),
                           "--require-candidate-roster"])
            self.assertEqual(rc, 2)
            self.assertNotIn(d, errors.getvalue())
            self.assertNotIn("private", errors.getvalue())

    def test_reject_duplicate_private_bad_sentinel_and_missing_probes(self):
        variants = [
            self.roster + [dict(self.roster[0])],
            self.roster + [{"split": "holdout", "probe_uid": "Foreign_Probe",
                            "candidate_uid": "Foreign_Candidate"}],
            self.roster + [{"split": "holdout", "probe_uid": "HoldProbeK_0",
                            "candidate_uid": "NONE"}],
            [dict(r, media_path="/storage/secret.jpg") if i == 0 else dict(r)
             for i, r in enumerate(self.roster)],
            [dict(r, candidate_uid="../secret.jpg") if i == 0 else dict(r)
             for i, r in enumerate(self.roster)],
        ]
        for i, variant in enumerate(variants):
            with self.subTest(case=i):
                with self.assertRaises(ValueError):
                    benchmark_model_scores(self.rows, candidate_roster_rows=variant)
        with self.assertRaisesRegex(ValueError, "boolean"):
            benchmark_model_scores(self.rows, require_candidate_roster=1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
