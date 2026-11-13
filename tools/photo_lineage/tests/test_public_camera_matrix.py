"""Only fabricated source images; Photo DNA CI never reads real camera media."""
import json
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace

import numpy as np

from tools.photo_lineage import public_camera_matrix as matrix


class MatrixTests(unittest.TestCase):
    def test_fake_assets_are_read_only_and_no_source_path_leaks(self):
        assets = {row[0]: np.full((128, 128), i + 1, dtype=np.uint8)
                  for i, row in enumerate((*matrix.PINNED_PHOTOS.values(),
                                           *matrix.STEREO_SOURCES.values()))}
        originals = {key: value.copy() for key, value in assets.items()}
        calls = []

        def read(path, sha):
            self.assertIn(path.name, assets)
            self.assertEqual(len(sha), 64)
            return assets[path.name].copy()

        def verify(a, b, algorithm):
            self.assertEqual(algorithm, "orb")
            calls.append((a.shape, b.shape))
            return SimpleNamespace(verdict=("candidate" if len(calls) % 3 else "review"))

        with patch.object(matrix, "_bundled_root", return_value=Path("/private/fixture")), \
                patch.object(matrix, "_read_pinned_photo", side_effect=read), \
                patch.object(matrix, "compare_arrays", side_effect=verify):
            result = matrix.run(algorithm="orb")
        self.assertEqual(len(calls), 46)
        self.assertEqual(result["source_photo_count"], 7)
        self.assertEqual(result["source_derived_edit_total"], 35)
        self.assertEqual(result["real_different_scene_pairs"], 10)
        self.assertEqual(result["stereo_distinct_camera_viewpoint_pair_count"], 1)
        self.assertEqual(sum(result["source_edit_totals"].values()), 35)
        for name in matrix.PUBLIC_NAMES + matrix.VIEW_NAMES:
            self.assertEqual(set(result["source_edit_verdicts"][name]), set(matrix.EDIT_NAMES))
        self.assertFalse(result["shutter_source_identity_certified"])
        self.assertFalse(result["valid_for_deployment_false_match_rates"])
        self.assertNotIn("/private", json.dumps(result))
        self.assertNotIn("<array>", json.dumps(result))
        for name in assets:
            self.assertTrue(np.array_equal(assets[name], originals[name]))

    def test_unknown_algorithm_and_changed_source_registry_rejected(self):
        for algorithm in ("none", "superpoint", "ORB", "all", None):
            with self.assertRaisesRegex(ValueError, "classical"):
                matrix.run(algorithm=algorithm)
        with patch.object(matrix, "PINNED_PHOTOS", {"camera": matrix.PINNED_PHOTOS["camera"]}):
            with self.assertRaisesRegex(ValueError, "protocol"):
                matrix.run()

    def test_malformed_verdict_refuses_false_evidence(self):
        with patch.object(matrix, "compare_arrays", return_value=SimpleNamespace(verdict="automatic_merge")):
            with self.assertRaisesRegex(ValueError, "Unexpected"):
                matrix._verdict(np.zeros((128, 128), np.uint8), np.ones((128, 128), np.uint8), "orb")

    def test_duplicate_viewpoint_payload_rejected(self):
        fake = np.full((128, 128), 50, np.uint8)
        with patch.object(matrix, "_bundled_root", return_value=Path("/fake")), \
                patch.object(matrix, "_read_pinned_photo", return_value=fake.copy()):
            with self.assertRaisesRegex(ValueError, "distinct"):
                matrix.run()


if __name__ == "__main__":
    unittest.main()
