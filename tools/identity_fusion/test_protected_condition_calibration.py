#!/usr/bin/env python3
"""Adversarial fictional subgroup-calibration evaluations. No private images."""
import contextlib
import csv
import io
import json
import tempfile
import unittest
from pathlib import Path

from benchmark_reid_scores import benchmark_model_scores, main, FIELDS


def synthetic_conditions():
    from test_benchmark_reid_scores import fixture
    rows = [dict(row) for row in fixture()]
    for row in rows:
        if row["split"] == "calibration":
            row["condition"] = "back+occluded"
    return rows


def concealed_back_error():
    """One bad back-facing identification masked by 100 easy frontal photos."""
    from test_benchmark_reid_scores import fixture
    scores = [dict(row) for row in fixture()]
    for n in range(60):
        known_probe = "BackCalibrationK_" + str(n)
        truth = "BackKnown_" + str(n)
        scores.append(dict(
            probe_uid=known_probe, split="calibration", truth_uid=truth,
            session_uid="BackSessionK_" + str(n), condition="back+occluded",
            candidate_uid=truth, similarity=".72" if n == 0 else ".91"))
        scores.append(dict(
            probe_uid=known_probe, split="calibration", truth_uid=truth,
            session_uid="BackSessionK_" + str(n), condition="back+occluded",
            candidate_uid="BackImpostor_" + str(n),
            similarity=".97" if n == 0 else ".2"))
        scores.append(dict(
            probe_uid="BackCalibrationU_" + str(n), split="calibration",
            truth_uid="UNKNOWN", session_uid="BackSessionU_" + str(n),
            condition="back+occluded", candidate_uid="BackUnknownImpostor_" + str(n),
            similarity=".1"))
    return scores


class ProtectedConditionCalibrationTests(unittest.TestCase):
    def test_zero_error_back_and_occluded_groups_pass_with_both_truth_classes(self):
        rows = synthetic_conditions()
        report = benchmark_model_scores(
            rows, protected_condition_tags=["occluded", "back"])
        self.assertEqual(report["protected_condition_tags"], ["back", "occluded"])
        self.assertEqual(report["similarity_floor"], .91)
        for tag in ("back", "occluded"):
            metrics = report["protected_tag_calibration_safety"][tag]
            self.assertEqual(metrics["known_observations"], 100)
            self.assertEqual(metrics["unknown_observations"], 100)
            self.assertEqual(metrics["distinct_known_persons"], 100)
            self.assertLessEqual(metrics["unknown_false_offer_session_wilson95_upper"], .05)
            self.assertLessEqual(metrics["wrong_known_person_wilson95_upper"], .05)
        self.assertEqual(report, benchmark_model_scores(
            list(reversed(rows)), protected_condition_tags=("back", "occluded")))
        # Overlapping labels do not produce independent trial claims:
        self.assertEqual(report["calibration"]["overall"]["observations"], 200)
        self.assertEqual(sum(
            v["observations"] for v in report["protected_tag_calibration_safety"].values()
        ), 400)
        self.assertFalse(report["auto_confirm_allowed"])

    def test_global_five_percent_can_hide_a_bad_back_facing_subgroup(self):
        rows = concealed_back_error()
        default = benchmark_model_scores(rows)
        self.assertEqual(default["similarity_floor"], .91)
        self.assertEqual(default["calibration"]["overall"]["wrong_known_offers"], 1)
        self.assertLessEqual(default["calibration"]["overall"][
            "wrong_known_person_wilson95_upper"], .05)
        with self.assertRaisesRegex(ValueError, "nontrivial"):
            benchmark_model_scores(rows, protected_condition_tags=["back"])
        # Crucial: changing the heldout results cannot rescue a subgroup
        # that already failed on calibration.
        changed = [dict(r) for r in rows]
        for r in changed:
            if r["split"] == "holdout":
                r["similarity"] = ".01" if r["candidate_uid"] != "NONE" else ""
        with self.assertRaisesRegex(ValueError, "nontrivial"):
            benchmark_model_scores(changed, protected_condition_tags=["back"])

    def test_tiny_or_one_sided_protected_cohorts_fail_even_at_zero_errors(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        with self.assertRaisesRegex(ValueError, "diversity"):
            benchmark_model_scores(scores, protected_condition_tags=["back"])
        for row in scores:
            if row["split"] == "calibration" and row["truth_uid"] != "UNKNOWN":
                row["condition"] = "back"
        with self.assertRaisesRegex(ValueError, "diversity"):
            benchmark_model_scores(scores, protected_condition_tags=["back"])
        for row in scores:
            if row["split"] == "calibration" and row["truth_uid"] == "UNKNOWN":
                row["condition"] = "back"
        # With 100 independently reviewed known and Unknown, this passes.
        self.assertEqual(benchmark_model_scores(
            scores, protected_condition_tags=["back"])["similarity_floor"], .91)

    def test_reject_invalid_repeat_private_tags_and_boolean(self):
        from test_benchmark_reid_scores import fixture
        rows = fixture()
        for tags in ("back", ["back", "back"], ["private-person-name"], [True],
                     {"back"}, ["back", "occluded"] * 5):
            with self.subTest(tags=str(tags)):
                with self.assertRaises(ValueError):
                    benchmark_model_scores(rows, protected_condition_tags=tags)

    def test_cli_strict_condition_opt_in_and_opaque_results(self):
        rows = synthetic_conditions()
        with tempfile.TemporaryDirectory(prefix="atlas-private-subgroup-") as d:
            path = Path(d) / "opaque-scores.csv"
            with path.open("w", newline="", encoding="utf-8") as f:
                writer = csv.DictWriter(f, fieldnames=FIELDS)
                writer.writeheader()
                writer.writerows(rows)
            with contextlib.redirect_stdout(io.StringIO()) as output:
                result = main([str(path), "--protect-condition-tag", "back",
                               "--protect-condition-tag", "occluded"])
            self.assertEqual(result, 0)
            report = json.loads(output.getvalue())
            self.assertEqual(report["protected_condition_tags"], ["back", "occluded"])
            self.assertNotIn(d, output.getvalue())
            self.assertNotIn("CalProbeK_0", output.getvalue())
            with contextlib.redirect_stderr(io.StringIO()) as error:
                self.assertEqual(main([str(path), "--protect-condition-tag",
                                       "private-person-name"]), 2)
            self.assertNotIn(d, error.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
