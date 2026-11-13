"""Only fabricated grayscale arrays and stubs, no optional public media."""
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import numpy as np

from tools.photo_lineage import hash_fallback_ablation as ablate


class HashAblationTests(unittest.TestCase):
    def test_synthetic_stereo_collision_exposes_hazard_at_or_above_threshold(self):
        assets = {record[0]: np.full((128, 128), i + 1, dtype=np.uint8)
                  for i, record in enumerate((*ablate.PINNED_PHOTOS.values(),
                                              *ablate.STEREO_SOURCES.values()))}
        before = {k: a.copy() for k, a in assets.items()}
        with patch.object(ablate, "_bundled_root", return_value=Path("/fake-private")), \
             patch.object(ablate, "_read_pinned_photo", side_effect=lambda p,s: assets[p.name].copy()), \
             patch.object(ablate, "_distance", return_value=4):
            report = ablate.run()
        self.assertEqual(report["source_image_count"], 7)
        self.assertEqual(report["derived_edit_pairs_correlated"], 35)
        self.assertEqual(report["real_different_scene_pairs"], 10)
        self.assertFalse(report["thresholds_are_exploratory_not_classification_gates"]["0"]["distinct_camera_stereo_pair_at_or_below"])
        self.assertTrue(report["thresholds_are_exploratory_not_classification_gates"]["4"]["distinct_camera_stereo_pair_at_or_below"])
        self.assertEqual(report["thresholds_are_exploratory_not_classification_gates"]["4"]["same_source_derived_edits_at_or_below"], 35)
        self.assertFalse(report["verified_shutter_independence"])
        self.assertFalse(report["representative_false_review_rate_measured"])
        self.assertNotIn("/fake-private", json.dumps(report))
        for k in assets:
            self.assertTrue(np.array_equal(before[k], assets[k]))

    def test_invalid_distance_and_registry_refused(self):
        for value in (-1, 65, 1.25, True, None):
            with patch.object(ablate, "cardinal_hash_distance", return_value=value):
                with self.subTest(value=value), self.assertRaises(ValueError):
                    ablate._distance(np.zeros((64, 64), np.uint8), np.zeros((64, 64), np.uint8))
        with patch.object(ablate, "STEREO_SOURCES", {"left_view": ("x", "a"*64)}):
            with self.assertRaisesRegex(ValueError, "registry"):
                ablate.run()

    def test_byte_equivalent_stereo_sources_refused(self):
        source = np.full((128, 128), 1, np.uint8)
        with patch.object(ablate, "_bundled_root", return_value=Path("/fictional")), \
             patch.object(ablate, "_read_pinned_photo", return_value=source):
            with self.assertRaisesRegex(ValueError, "distinct"):
                ablate.run()


if __name__ == "__main__":
    unittest.main()
