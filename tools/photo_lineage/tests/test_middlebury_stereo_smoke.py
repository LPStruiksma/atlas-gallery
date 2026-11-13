"""Synthetic stand-ins for same-scene stereo harness; CI uses no camera media."""
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import cv2
import numpy as np

from tools.photo_lineage.benchmark import textured
from tools.photo_lineage import middlebury_stereo_smoke as stereo
from tools.photo_lineage.matcher import Evidence


class StereoSmokeTests(unittest.TestCase):
    def test_exact_upstream_stereo_byte_pins_and_citation_exist(self):
        self.assertEqual(set(stereo.STEREO_SOURCES), {"left_view", "right_view"})
        self.assertEqual({entry[0] for entry in stereo.STEREO_SOURCES.values()},
                         {"motorcycle_left.png", "motorcycle_right.png"})
        self.assertEqual(len({entry[1] for entry in stereo.STEREO_SOURCES.values()}), 2)
        self.assertTrue(all(len(entry[1]) == 64 for entry in stereo.STEREO_SOURCES.values()))
        self.assertIn("Scharstein", stereo.CITATION)
        self.assertIn("middlebury.edu", stereo.USAGE_TERMS_URL)

    def test_distinct_simulated_viewpoints_and_edits_never_certify_shutter(self):
        base = textured(21, size=300)
        other = np.roll(base, 8, axis=1).copy()
        cv2.circle(other, (110, 130), 24, 220, -1)
        original = {"motorcycle_left.png": base,
                    "motorcycle_right.png": other}
        before = {name: pixels.copy() for name, pixels in original.items()}
        def fake_loader(path, digest):
            self.assertIn(path.name, original)
            self.assertEqual(
                digest, stereo.STEREO_SOURCES[
                    "left_view" if path.name.endswith("left.png") else "right_view"][1])
            return original[path.name].copy()
        with patch.object(stereo, "_bundled_root", return_value=Path("/fake-package")), \
             patch.object(stereo, "_read_pinned_photo", side_effect=fake_loader):
            result = stereo.run(algorithm="orb")
        self.assertEqual(result["camera_view_count"], 2)
        self.assertEqual(result["distinct_viewpoint_same_scene_pair_count"], 1)
        self.assertEqual(result["source_derived_edit_pairs"], 10)
        evidence = result["same_scene_pair_matcher_evidence_numeric_only"]
        self.assertEqual(set(evidence), set(stereo._NUMERIC_EVIDENCE_FIELDS))
        self.assertIsInstance(evidence["inliers"], int)
        self.assertLessEqual(evidence["inliers"], evidence["mutual_matches"])
        self.assertNotIn("image_a", evidence)
        self.assertNotIn("image_b", evidence)
        self.assertEqual(result["real_photo_same_scene_sample_size"], 1)
        self.assertIn(result["same_scene_independent_viewpoint_verdict"],
                      ("candidate", "review", "unknown"))
        self.assertEqual(
            result["same_scene_strong_candidate_needing_review"],
            result["same_scene_independent_viewpoint_verdict"] == "candidate")
        for verdicts in result["source_derived_edit_verdicts"].values():
            self.assertEqual(sum(verdicts.values()), 2)
        self.assertTrue(result["separate_camera_viewpoints_not_shutter_time_proof"])
        self.assertTrue(result["unsafe_to_certify_source_exposure_identity"])
        self.assertFalse(result["any_images_saved_or_uploaded"])
        self.assertIn("ONE", result["limitations"])
        self.assertNotIn("/fake-package", json.dumps(result))
        for name, pixels in original.items():
            self.assertTrue(np.array_equal(pixels, before[name]))

    def test_diagnostics_allowlist_never_serializes_path_or_nonfinite_values(self):
        evidence = Evidence(
            verdict="unknown", reason="mock", algorithm="orb",
            image_a="/private/secret/one.jpg", image_b="/private/secret/two.jpg",
            keypoints_a=50, keypoints_b=70, mutual_matches=10, inliers=7,
            inlier_fraction=0.7,
        )
        result = stereo._public_stereo_evidence(evidence)
        self.assertEqual(result["mutual_matches"], 10)
        self.assertEqual(result["inliers"], 7)
        self.assertIsNone(result["rotation_degrees"])
        self.assertNotIn("/private", json.dumps(result))
        self.assertNotIn("image_a", json.dumps(result))
        from dataclasses import replace
        with self.assertRaisesRegex(ValueError, "Nonfinite"):
            stereo._public_stereo_evidence(replace(evidence, gray_correlation=float("nan")))
        with self.assertRaisesRegex(ValueError, "Inconsistent"):
            stereo._public_stereo_evidence(replace(evidence, inliers=11))
        with self.assertRaisesRegex(ValueError, "Nonfinite"):
            stereo._public_stereo_evidence(replace(evidence, mutual_matches=True))

    def test_numeric_evidence_rejects_impossible_but_finite_values(self):
        from dataclasses import replace
        original = Evidence(
            verdict="review", reason="mock", algorithm="sift",
            image_a="/secret/single.jpg", image_b="/secret/other.jpg",
            keypoints_a=30, keypoints_b=20, mutual_matches=10, inliers=7,
            inlier_fraction=0.7, spread_a=0.25, spread_b=0.2,
            gray_correlation=-0.3, gradient_correlation=0.6,
            overlap_a=0.8, overlap_b=0.8, local_consistency=0.5,
            texture_tiles=32, rotation_degrees=-30.0, scale=0.85,
        )
        self.assertEqual(stereo._public_stereo_evidence(original)["inliers"], 7)
        invalid = (
            ("keypoints_a", -1), ("keypoints_b", 1.5),
            ("mutual_matches", 21), ("inliers", -1),
            ("inliers", 11), ("texture_tiles", 65),
            ("texture_tiles", 1.0), ("inlier_fraction", 0.71),
            ("inlier_fraction", -0.1), ("spread_a", 1.01),
            ("spread_b", -0.01), ("overlap_a", 2.0),
            ("overlap_b", -0.01), ("local_consistency", 1.1),
            ("gray_correlation", -1.01), ("gradient_correlation", 1.01),
            ("rotation_degrees", 181.0), ("scale", -0.01),
            ("scale", None), ("gray_correlation", None),
        )
        for field, value in invalid:
            with self.subTest(field=field, value=value):
                with self.assertRaises(ValueError):
                    stereo._public_stereo_evidence(replace(original, **{field: value}))
        # Partial geometric evidence before affine estimation is legitimately
        # nullable: do not turn a real conservative Unknown into an exception.
        early_unknown = Evidence(
            verdict="unknown", reason="few features", algorithm="orb",
            image_a="private-a", image_b="private-b",
            keypoints_a=3, keypoints_b=5,
        )
        safe = stereo._public_stereo_evidence(early_unknown)
        self.assertIsNone(safe["rotation_degrees"])
        self.assertEqual(safe["inlier_fraction"], 0.0)
        self.assertNotIn("private-a", json.dumps(safe))

    def test_identical_camera_view_data_is_rejected_before_matching(self):
        source = textured(13, size=256)
        with patch.object(stereo, "_bundled_root", return_value=Path("/fake")), \
             patch.object(stereo, "_read_pinned_photo", return_value=source):
            with self.assertRaisesRegex(ValueError, "distinct"):
                stereo.run()

    def test_shape_mismatch_and_invalid_algorithms_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "Only existing"):
            stereo.run(algorithm="nonreviewed_model")
        a, b = textured(1, size=256), textured(2, size=300)
        with patch.object(stereo, "_bundled_root", return_value=Path("/fake")), \
             patch.object(stereo, "_read_pinned_photo", side_effect=[a, b]):
            with self.assertRaisesRegex(ValueError, "distinct"):
                stereo.run()


if __name__ == "__main__":
    unittest.main()
