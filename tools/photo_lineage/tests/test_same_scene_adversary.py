"""Same-scene hard negatives are synthetic. No photographs, files or cloud."""
import json
import unittest

import numpy as np

from tools.photo_lineage.benchmark import textured
from tools.photo_lineage.same_scene_adversary import (
    run, simulated_frame, contrast_algorithms, contrast_foreground_modes,
    ALGORITHMS, FOREGROUND_MODES, _reason_code, _MATCHER_REASONS,
)


class SameSceneAdversaryTests(unittest.TestCase):
    def test_distinct_framebuffer_time_steps_are_different_but_share_scene(self):
        background = textured(101, size=256)
        a = simulated_frame(background, scene_seed=101, frame_number=0)
        b = simulated_frame(background, scene_seed=101, frame_number=1)
        self.assertEqual(a.shape, b.shape)
        self.assertEqual(a.dtype, np.uint8)
        self.assertEqual(b.dtype, np.uint8)
        self.assertFalse(np.array_equal(a, b))
        # Input background is not modified, simulating two fresh redraws.
        self.assertFalse(np.shares_memory(a, background))
        self.assertFalse(np.shares_memory(b, background))
        self.assertTrue(np.array_equal(
            a, simulated_frame(background, scene_seed=101, frame_number=0)
        ))

    def test_matcher_audit_distinguishes_positive_edits_from_separate_frames(self):
        result = run(scenes=1, frames_per_scene=2, size=256, algorithm="orb")
        self.assertEqual(result["scenes"], 1)
        self.assertEqual(result["synthetic_separately_rendered_frames"], 2)
        self.assertEqual(result["same_scene_distinct_frame_pairs"], 1)
        self.assertTrue(result["all_distinct_frame_pairs_pixel_nonidentical"])
        for edit, counts in result["same_exposure_derived_edits"].items():
            self.assertIn(edit, ("resized", "gamma", "cropped", "rotated_crop", "jpeg_reencode"))
            self.assertEqual(sum(counts.values()), 2)
        negative = result["same_scene_distinct_frame_risk"]
        self.assertEqual(negative["scenes"], 1)
        self.assertEqual(negative["correlated_pairs_diagnostic_only"], 1)
        self.assertEqual(sum((
            negative["correlated_strong_candidate_pairs"],
            negative["correlated_unknown_pairs"],
        )) <= 1, True)
        self.assertTrue(result["unsafe_to_certify_exposure_identity"])
        self.assertFalse(result["raw_images_saved_or_returned"])
        self.assertNotIn("image_a", json.dumps(result))
        self.assertNotIn("image_b", json.dumps(result))
        self.assertIn("NOT actual camera shutter", result["policy"])

    def test_paired_three_algorithm_contrast_is_bounded_and_never_selects_winner(self):
        result = contrast_algorithms(scenes=1, frames_per_scene=2, size=256)
        self.assertEqual(result["fixed_algorithm_order"], list(ALGORITHMS))
        self.assertFalse(result["winner_selected"])
        self.assertTrue(result["unsafe_to_certify_exposure_identity"])
        self.assertFalse(result["raw_image_or_paths_returned"])
        for algorithm in ALGORITHMS:
            group = result["algorithms"][algorithm]
            self.assertEqual(group["derived_total_correlated"], 10)
            self.assertEqual(group["distinct_time_step_pair_count_correlated"], 1)
            self.assertEqual(sum(group["matcher_gate_codes_by_condition"]["distinct_frame_same_scene"].values()), 1)
            self.assertIn(group["distinct_time_step_strong_candidate_offers"], (0, 1))
            self.assertIn(group["scenes_with_any_distinct_step_strong_candidate_offer"], (0, 1))
            self.assertLessEqual(group["derived_reviewable"], group["derived_total_correlated"])
            self.assertEqual(set(group["derived_edit_conditions"]), {
                "resized", "gamma", "cropped", "rotated_crop", "jpeg_reencode"})
            for condition in group["derived_edit_conditions"].values():
                self.assertEqual(condition["total"], 2)
                self.assertEqual(condition["reviewable"] + condition["abstained_unknown"], 2)
        rendered = json.dumps(result)
        self.assertNotIn("image_a", rendered)
        self.assertNotIn("image_b", rendered)
        self.assertIn("NEVER choose", result["policy"])

    def test_algorithm_matrix_limits_fail_before_allocating_three_runs(self):
        for kwargs in ({"scenes": True}, {"scenes": 0}, {"scenes": 3},
                       {"frames_per_scene": True}, {"frames_per_scene": 1},
                       {"frames_per_scene": 4}, {"size": True},
                       {"size": 255}, {"size": 361}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                contrast_algorithms(**kwargs)

    def test_stationary_tripod_like_and_absent_foreground_are_separate_redraws(self):
        background = textured(101, size=256)
        pristine = background.copy()
        first_moving = simulated_frame(background, scene_seed=101, frame_number=0)
        first_stationary = simulated_frame(
            background, scene_seed=101, frame_number=0,
            foreground_mode="stationary")
        self.assertTrue(np.array_equal(first_moving, first_stationary))
        for mode in FOREGROUND_MODES:
            first = simulated_frame(
                background, scene_seed=101, frame_number=0,
                foreground_mode=mode)
            second = simulated_frame(
                background, scene_seed=101, frame_number=1,
                foreground_mode=mode)
            self.assertFalse(np.array_equal(first, second))
            self.assertFalse(np.shares_memory(first, background))
            self.assertTrue(np.array_equal(
                second,
                simulated_frame(background, scene_seed=101,
                                frame_number=1, foreground_mode=mode)))
        self.assertFalse(np.array_equal(
            simulated_frame(background, scene_seed=101, frame_number=1,
                            foreground_mode="stationary"),
            simulated_frame(background, scene_seed=101, frame_number=1,
                            foreground_mode="moving"),
        ))
        self.assertFalse(np.array_equal(
            simulated_frame(background, scene_seed=101, frame_number=0,
                            foreground_mode="absent"),
            first_moving,
        ))
        self.assertTrue(np.array_equal(background, pristine))

    def test_foreground_sweep_explicitly_shows_correlated_scene_motion_risks(self):
        report = contrast_foreground_modes(
            scenes=1, frames_per_scene=2, size=256, algorithm="orb")
        self.assertEqual(report["compared_foreground_modes"],
                         list(FOREGROUND_MODES))
        self.assertEqual(set(report["modes"]), set(FOREGROUND_MODES))
        self.assertFalse(report["winner_selected"])
        self.assertTrue(report["not_independent_camera_frames"])
        self.assertTrue(report["unsafe_to_certify_exposure_identity"])
        self.assertFalse(report["raw_images_or_paths_returned"])
        for mode, data in report["modes"].items():
            self.assertTrue(data["distinct_frames_pixel_nonidentical"])
            self.assertEqual(data["correlated_distinct_frame_pairs"], 1)
            self.assertEqual(data["scenes_as_reused_synthetic_seeds"], 1)
            self.assertIn(data["correlated_strong_candidate_offers"], (0, 1))
            self.assertEqual(len(data["derived_edit_verdicts"]), 5)
            reasons = data["matcher_gate_codes_by_condition"]
            self.assertEqual(sum(reasons["distinct_frame_same_scene"].values()), 1)
            for condition in data["derived_edit_verdicts"].values():
                self.assertEqual(sum(condition.values()), 2)
        self.assertNotIn("image_a", json.dumps(report))
        self.assertNotIn("image_b", json.dumps(report))
        self.assertIn("Never infer identity", report["policy"])

    def test_foreground_modes_fail_closed_for_invalid_args(self):
        background = textured(101, size=256)
        with self.assertRaisesRegex(ValueError, "Foreground mode"):
            simulated_frame(background, scene_seed=101,
                            frame_number=0, foreground_mode="mystery")
        with self.assertRaisesRegex(ValueError, "foreground"):
            run(scenes=1, frames_per_scene=2, size=256,
                algorithm="orb", foreground_mode="mystery")
        with self.assertRaisesRegex(ValueError, "foreground"):
            contrast_algorithms(
                scenes=1, frames_per_scene=2, size=256,
                foreground_mode="mystery")
        for bad in ({"scenes": True}, {"scenes": 0}, {"scenes": 3},
                    {"frames_per_scene": True}, {"frames_per_scene": 1},
                    {"frames_per_scene": 4}, {"size": True}, {"size": 255},
                    {"size": 361}, {"algorithm": "unknown"}):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                contrast_foreground_modes(**bad)

    def test_finite_review_gate_codes_reject_unknown_or_mismatched_reasons(self):
        self.assertEqual(len(_MATCHER_REASONS), 7)
        self.assertEqual(len({code for _, code in _MATCHER_REASONS.values()}), 7)
        for reason, (verdict, expected_code) in _MATCHER_REASONS.items():
            self.assertEqual(_reason_code(verdict, reason), expected_code)
        with self.assertRaisesRegex(ValueError, "Unknown or mismatched"):
            _reason_code("unknown", "Dynamic exception /private/image.jpg")
        with self.assertRaisesRegex(ValueError, "Unknown or mismatched"):
            _reason_code("candidate", "Insufficient distinctive visual features")

    def test_edit_and_distinct_frame_reason_counts_are_aggregate_only(self):
        study = run(scenes=1, frames_per_scene=2, size=256, algorithm="orb")
        bins = study["matcher_gate_codes_by_condition"]
        self.assertEqual(len(bins), 6)
        for condition in ("resized", "gamma", "cropped", "rotated_crop", "jpeg_reencode"):
            self.assertEqual(sum(bins["edit_" + condition].values()), 2)
        self.assertEqual(sum(bins["distinct_frame_same_scene"].values()), 1)
        allowed_codes = {code for _, code in _MATCHER_REASONS.values()}
        self.assertTrue(all(set(row).issubset(allowed_codes) for row in bins.values()))
        self.assertNotIn("unknown or mismatched matcher gate reason", json.dumps(study))
        self.assertNotIn("<array>", json.dumps(study))
        self.assertNotIn("/private/", json.dumps(study))

    def test_numeric_and_algorithm_bounds_fail_early(self):
        for kw in (
            {"scenes": True}, {"scenes": 0}, {"scenes": 5},
            {"frames_per_scene": True}, {"frames_per_scene": 1},
            {"frames_per_scene": 5}, {"size": 255}, {"size": 481},
            {"size": True}, {"algorithm": "learned_unverified"},
        ):
            with self.subTest(kw=kw), self.assertRaises(ValueError):
                run(**kw)
        base = textured(101, size=256)
        with self.assertRaisesRegex(ValueError, "256..480"):
            simulated_frame(textured(101, size=512), scene_seed=101, frame_number=0)
        for args in (
            {"scene_seed": -1, "frame_number": 0},
            {"scene_seed": 101, "frame_number": True},
            {"scene_seed": 101, "frame_number": 4},
        ):
            with self.subTest(args=args), self.assertRaises(ValueError):
                simulated_frame(base, **args)


if __name__ == "__main__":
    unittest.main()
