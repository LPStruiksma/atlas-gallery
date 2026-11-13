"""Synthetic-only bounded blur fingerprint tradeoff tests."""
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import numpy as np
from tools.photo_lineage import blur_augmented_word_tradeoff as study


class BlurAugmentedWordsTests(unittest.TestCase):
    def fake(self):
        names = [v[0] for v in (*study.PINNED_PHOTOS.values(), *study.STEREO_SOURCES.values())]
        return {name: np.full((256, 256), 11 + i, np.uint8)
                for i, name in enumerate(names)}

    def test_exact_three_profiles_and_review_only_report(self):
        fake = self.fake()
        original = {k: v.copy() for k, v in fake.items()}
        calls = []
        def words(arr):
            calls.append(arr.shape)
            return {1, 2, 3}
        with patch.object(study, "_bundled_root", return_value=Path("/private/path")), \
                patch.object(study, "_read_pinned_photo",
                             side_effect=lambda p, sha: fake[p.name].copy()), \
                patch.object(study, "_validated_words", side_effect=words):
            r = study.run()
        self.assertEqual(len(calls), 7 * (2 + 1 + 2 + 3))
        self.assertEqual(set(r["profiles"]), {"baseline", "gauss2", "gauss2_4"})
        self.assertFalse(r["physical_exposure_identity_verified"])
        self.assertFalse(r["scene_independent_train_vs_test"])
        self.assertFalse(r["source_media_copied_written_uploaded"])
        for p in r["profiles"].values():
            self.assertEqual(set(p["conditions"]), {"gaussian_8", "motion_31"})
            for row in p["conditions"].values():
                self.assertEqual(row["known_source_eligible"], 7)
                self.assertTrue(row["stereo_other_exposure_would_be_offered"])
        self.assertNotIn("/private/path", json.dumps(r))
        for name, frame in fake.items():
            self.assertTrue(np.array_equal(original[name], frame))

    def test_no_query_strength_in_index_profiles(self):
        frame = np.full((256, 256), 100, np.uint8)
        for bad in ((8.0,), (4.0,), (31,), (2.0, 4.0, 8.0)):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                study.terms_with_augment(frame, bad)
        self.assertEqual(study.QUERY_CONDITIONS, (("gaussian", 8.0), ("motion", 31)))

    def test_stereo_alias_and_fingerprint_drift_abstain(self):
        with patch.object(study, "FINGERPRINT_VERSION", "unreviewed"):
            with self.assertRaisesRegex(ValueError, "protocol"):
                study.run()
        with patch.object(study, "_bundled_root", return_value=Path("/fictional")), \
                patch.object(study, "_read_pinned_photo",
                             return_value=np.full((256, 256), 50, np.uint8)):
            with self.assertRaisesRegex(ValueError, "Distinct"):
                study.run()

    def test_term_quota_is_checked_before_any_suggestion(self):
        arr = np.zeros((256, 256), np.uint8)
        with patch.object(study, "_validated_words",
                          side_effect=[set(range(384)), set(range(384, 768)),
                                       set(range(768, 1153))]):
            with self.assertRaisesRegex(ValueError, "budget"):
                study.terms_with_augment(arr, (2.0, 4.0))


if __name__ == "__main__":
    unittest.main()
