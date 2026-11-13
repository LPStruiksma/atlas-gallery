"""Only synthetic arrays and mocked images; no camera-data CI dependency."""
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np

from tools.photo_lineage import pinned_rotation_matrix as sweep


class RotationCropTests(unittest.TestCase):
    def test_stimulus_reproducible_and_original_array_unchanged(self):
        source = np.arange(256 * 256, dtype=np.uint32).reshape(256, 256).astype(np.uint8)
        before = source.copy()
        for angle in sweep.ANGLES:
            with self.subTest(angle=angle):
                first = sweep.rotated_crop(source, angle)
                second = sweep.rotated_crop(source, angle)
                self.assertEqual(first.shape, (192, 192))
                self.assertTrue(np.array_equal(first, second))
        self.assertTrue(np.array_equal(source, before))

    def test_exact_fifty_fake_pairs_and_no_private_path_output(self):
        photos = {
            item[0]: np.full((256, 256), 20 + i, np.uint8)
            for i, item in enumerate(sweep.PINNED_PHOTOS.values())
        }
        before = {k: v.copy() for k, v in photos.items()}
        calls = []

        def read(path, expected_sha):
            self.assertIn(path.name, photos)
            self.assertEqual(len(expected_sha), 64)
            return photos[path.name].copy()

        def compare(a, b, algorithm):
            self.assertEqual(algorithm, "orb")
            self.assertEqual(a.shape, (256, 256))
            self.assertEqual(b.shape, (192, 192))
            calls.append(len(calls))
            return SimpleNamespace(verdict="unknown" if len(calls) % 5 == 0 else "candidate")

        with patch.object(sweep, "_bundled_root", return_value=Path("/secret/photos")), \
                patch.object(sweep, "_read_pinned_photo", side_effect=read), \
                patch.object(sweep, "compare_arrays", side_effect=compare):
            report = sweep.run(algorithm="orb")
        self.assertEqual(len(calls), 50)
        self.assertEqual(report["overall_correlated_edit_verdicts"],
                         {"candidate": 40, "review": 0, "unknown": 10})
        self.assertEqual(report["actual_separately_captured_shutters_compared"], 0)
        self.assertFalse(report["representative_recall_or_precision"])
        self.assertNotIn("/secret", json.dumps(report))
        for name, original in before.items():
            self.assertTrue(np.array_equal(photos[name], original))

    def test_invalid_algorithms_arrays_and_angles_refused(self):
        source = np.full((256, 256), 64, np.uint8)
        for angle in (14, -1, 360, 3.14, True):
            with self.subTest(angle=angle), self.assertRaises(ValueError):
                sweep.rotated_crop(source, angle)
        for arr in (source.astype(np.uint16), source[None],
                    np.zeros((20, 30), np.uint8)):
            with self.assertRaises(ValueError):
                sweep.rotated_crop(arr, 90)
        for algorithm in ("all", "none", "ORB", "auto", None):
            with self.assertRaises(ValueError):
                sweep.run(algorithm=algorithm)

    def test_new_or_malformed_verdict_refuses_overclaim(self):
        fake = {v[0]: np.full((256, 256), i + 2, np.uint8)
                for i, v in enumerate(sweep.PINNED_PHOTOS.values())}
        with patch.object(sweep, "_bundled_root", return_value=Path("/fictional")), \
                patch.object(sweep, "_read_pinned_photo", side_effect=lambda p, sha: fake[p.name]), \
                patch.object(sweep, "compare_arrays",
                             return_value=SimpleNamespace(verdict="automatic_delete")):
            with self.assertRaisesRegex(ValueError, "Unexpected matcher"):
                sweep.run()


if __name__ == "__main__":
    unittest.main()
