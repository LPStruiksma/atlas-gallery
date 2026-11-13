"""Pure numerical correlated-scene calibration tests (no images or downloads)."""
import unittest

from tools.photo_lineage.scene_risk import summarize_scene_exposure_risk, wilson_upper95


class SceneRiskTests(unittest.TestCase):
    def test_zero_errors_still_wide_and_not_zero(self):
        self.assertAlmostEqual(wilson_upper95(0, 1), 0.79345, delta=0.0001)
        self.assertAlmostEqual(wilson_upper95(0, 20), 0.1611, delta=0.0001)

    def test_monotone_in_errors_and_denominator(self):
        self.assertLess(wilson_upper95(1, 20), wilson_upper95(2, 20))
        self.assertGreater(wilson_upper95(0, 3), wilson_upper95(0, 20))
        self.assertEqual(wilson_upper95(20, 20), 1)

    def test_reject_bool_float_invalid_and_empty(self):
        for errors, count in ((True, 2), (0, True), (0.0, 5), (-1, 5), (4, 3), (0, 0)):
            with self.subTest(errors=errors, count=count), self.assertRaises(ValueError):
                wilson_upper95(errors, count)

    def test_28_correlated_candidates_one_scene_not_28_independent_samples(self):
        report = summarize_scene_exposure_risk([["candidate"] * 28, ["unknown"]])
        self.assertEqual(report["scenes"], 2)
        self.assertEqual(report["scenes_with_any_strong_same_scene_candidate"], 1)
        self.assertEqual(report["correlated_pairs_diagnostic_only"], 29)
        self.assertEqual(report["correlated_strong_candidate_pairs"], 28)
        self.assertAlmostEqual(report["scene_strong_candidate_fraction"], 0.5)
        self.assertGreater(report["scene_strong_candidate_wilson95_upper"], 0.8)

    def test_review_not_strong_but_unknown_not_certified_negative(self):
        report = summarize_scene_exposure_risk(iter((iter(["review"]), iter(["unknown"]), iter(["unknown"]))))
        self.assertEqual(report["scenes_with_any_strong_same_scene_candidate"], 0)
        self.assertEqual(report["scenes_with_any_review_or_candidate"], 1)
        self.assertGreater(report["scene_strong_candidate_wilson95_upper"], 0)
        self.assertIn("NOT source-exposure proof", report["policy"])

    def test_wrong_verdict_and_scene_bounds(self):
        for cases in ([], [[]], [["candidate"] * 29], [["candidate"]] * 21, [["confirmed"]]):
            with self.subTest(cases=len(cases)), self.assertRaises(ValueError):
                summarize_scene_exposure_risk(cases)


if __name__ == "__main__":
    unittest.main()
