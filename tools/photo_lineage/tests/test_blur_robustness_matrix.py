"""Synthetic-only CI: fake public images, no real data or downloads."""
from pathlib import Path
from types import SimpleNamespace
import json
import unittest
from unittest.mock import patch

import numpy as np

from tools.photo_lineage import blur_robustness_matrix as study


class BlurMatrixTests(unittest.TestCase):
    def test_six_fixed_blurs_deterministic_and_no_input_mutation(self):
        source = np.arange(256 * 256, dtype=np.uint32).reshape(256, 256).astype(np.uint8)
        original = source.copy()
        for kind, strength in study.CONDITIONS:
            with self.subTest(kind=kind, strength=strength):
                first = study.apply_blur(source, kind, strength)
                second = study.apply_blur(source, kind, strength)
                self.assertEqual(first.shape, source.shape)
                self.assertEqual(first.dtype, np.uint8)
                self.assertTrue(np.array_equal(first, second))
        self.assertTrue(np.array_equal(source, original))

    def test_exact_condition_pair_budget_and_no_private_paths(self):
        registry = (*study.PINNED_PHOTOS.values(), *study.STEREO_SOURCES.values())
        photos = {item[0]: np.full((256, 256), 10 + i, np.uint8)
                  for i, item in enumerate(registry)}
        before = {name: pixels.copy() for name, pixels in photos.items()}
        calls = []

        def read(path, sha):
            self.assertIn(path.name, photos)
            self.assertEqual(len(sha), 64)
            return photos[path.name].copy()

        def compare(a, b, algorithm):
            self.assertEqual(algorithm, "orb")
            self.assertEqual(a.shape, (256, 256))
            self.assertEqual(b.shape, (256, 256))
            calls.append((a.shape, b.shape))
            return SimpleNamespace(verdict="review" if len(calls) % 4 == 0 else "candidate")

        with patch.object(study, "_bundled_root", return_value=Path("/private/no-media")), \
                patch.object(study, "_read_pinned_photo", side_effect=read), \
                patch.object(study, "compare_arrays", side_effect=compare):
            result = study.run()
        self.assertEqual(len(calls), 48)
        self.assertEqual(result["same_source_edit_comparisons"], 42)
        self.assertEqual(result["different_viewpoint_stereo_comparisons_correlated"], 6)
        self.assertEqual(result["source_photo_count"], 7)
        self.assertEqual(result["independent_scene_groups_at_most"], 6)
        self.assertFalse(result["physical_shutter_independence_verified"])
        self.assertFalse(result["actual_photo_family_precision_or_recall_estimated"])
        for row in result["by_condition"].values():
            self.assertEqual(sum(row["same_source_edit_verdicts"].values()), 7)
        self.assertNotIn("/private", json.dumps(result))
        for name, pixels in photos.items():
            self.assertTrue(np.array_equal(pixels, before[name]))

    def test_bad_stimulus_type_algorithm_and_changed_registry_fail_closed(self):
        a = np.full((256, 256), 100, np.uint8)
        for kind, strength in [("gaussian", 2.1), ("motion", 9), ("motion", True),
                               ("gaussian", 4), ("motion", 7.0), ("bad", 15)]:
            with self.subTest(kind=kind, strength=strength):
                with self.assertRaises(ValueError):
                    study.apply_blur(a, kind, strength)
        for fake in (a.astype(np.float32), a[None], np.zeros((100, 100), np.uint8)):
            with self.assertRaises(ValueError):
                study.apply_blur(fake, "motion", 7)
        for bad in ("all", "none", "ORB", None, 0):
            with self.assertRaises(ValueError):
                study.run(algorithm=bad)
        with patch.object(study, "PINNED_PHOTOS", {"clock": study.PINNED_PHOTOS["clock"]}):
            with self.assertRaisesRegex(ValueError, "protocol"):
                study.run()

    def test_stereo_duplicate_view_and_unknown_verdict_refused(self):
        same = np.full((256, 256), 52, np.uint8)
        with patch.object(study, "_bundled_root", return_value=Path("/fixture")), \
                patch.object(study, "_read_pinned_photo", return_value=same.copy()):
            with self.assertRaisesRegex(ValueError, "distinct viewpoint"):
                study.run()
        fake = {item[0]: np.full((256, 256), i + 10, np.uint8)
                for i, item in enumerate((*study.PINNED_PHOTOS.values(),
                                          *study.STEREO_SOURCES.values()))}
        with patch.object(study, "_bundled_root", return_value=Path("/fixture")), \
                patch.object(study, "_read_pinned_photo", side_effect=lambda p, sha: fake[p.name]), \
                patch.object(study, "compare_arrays", return_value=SimpleNamespace(verdict="auto-delete")):
            with self.assertRaisesRegex(ValueError, "Unknown matcher verdict"):
                study.run()


if __name__ == "__main__":
    unittest.main()
