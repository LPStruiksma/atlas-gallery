"""Synthetic-only, mocked seven-source visual-word audit."""
from pathlib import Path
from unittest.mock import patch
import json
import unittest
import numpy as np
from tools.photo_lineage import public_blur_word_overlap as study


class BlurWordOverlapTests(unittest.TestCase):
    def fake(self):
        names = [v[0] for v in (*study.PINNED_PHOTOS.values(), *study.STEREO_SOURCES.values())]
        return {name: np.full((256, 256), 15 + i, dtype=np.uint8)
                for i, name in enumerate(names)}

    def test_exact_budgets_counts_and_private_path_exclusion(self):
        fake = self.fake()
        before = {k: v.copy() for k, v in fake.items()}
        calls = []

        def read(path, sha):
            self.assertIn(path.name, fake)
            self.assertEqual(len(sha), 64)
            return fake[path.name].copy()

        def words(image):
            calls.append(image.shape)
            return {1, 2, 3}

        with patch.object(study, "_bundled_root", return_value=Path("/private/secret")), \
                patch.object(study, "_read_pinned_photo", side_effect=read), \
                patch.object(study, "words_for_image", side_effect=words):
            report = study.run()
        self.assertEqual(len(calls), 55)
        self.assertEqual(report["same_source_edit_pairs_correlated"], 42)
        self.assertEqual(report["different_viewpoint_stereo_pairs_correlated"], 6)
        self.assertEqual(report["public_source_count"], 7)
        self.assertEqual(report["source_scene_groups_at_most"], 6)
        self.assertFalse(report["two_words_sufficient_to_ensure_retrieval"])
        self.assertFalse(report["actual_sqlite_shortlist_executed"])
        self.assertFalse(report["source_exposure_identity_verified"])
        for row in report["condition_counts"].values():
            self.assertEqual(row["same_source_meeting_necessary_two_word_minimum"], 7)
            self.assertEqual(row["same_source_with_zero_shared_words"], 0)
            self.assertEqual(row["stereo_left_vs_blurred_right_shared_words"], 3)
        self.assertNotIn("/private", json.dumps(report))
        for k, p in fake.items():
            self.assertTrue(np.array_equal(p, before[k]))

    def test_exact_overlap_zero_one_two_boundary(self):
        self.assertEqual(len({1, 2} & set()), 0)
        self.assertEqual(len({1, 2} & {1}), 1)
        self.assertEqual(len({1, 2} & {1, 2}), 2)
        with patch.object(study, "words_for_image", return_value={1, 2}):
            self.assertEqual(study._validated_words(np.zeros((256, 256), np.uint8)), {1, 2})

    def test_malformed_word_type_overflow_and_protocol_refused(self):
        arr = np.zeros((256, 256), dtype=np.uint8)
        for value in ({-1}, {1 << 26}, {True}, set(range(385)), [], {1.0}):
            with self.subTest(count=len(value)):
                with patch.object(study, "words_for_image", return_value=value):
                    with self.assertRaisesRegex(ValueError, "visual-word"):
                        study._validated_words(arr)
        with patch.object(study, "FINGERPRINT_VERSION", "different"):
            with self.assertRaisesRegex(ValueError, "protocol"):
                study.run()

    def test_duplicate_stereo_source_refused_before_word_extraction(self):
        frame = np.full((256, 256), 42, dtype=np.uint8)
        with patch.object(study, "_bundled_root", return_value=Path("/fake")), \
                patch.object(study, "_read_pinned_photo", return_value=frame.copy()), \
                patch.object(study, "words_for_image", side_effect=AssertionError("not admitted")):
            with self.assertRaisesRegex(ValueError, "distinct stereo"):
                study.run()


if __name__ == "__main__":
    unittest.main()
