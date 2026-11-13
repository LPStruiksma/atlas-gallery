"""Synthetic-only bounded ORB augmentation tests, no photos on CI."""
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import numpy as np
from tools.photo_lineage import capped_blur_word_tradeoff as study


class CappedBlurWordTests(unittest.TestCase):
    def test_balanced_hash_selection_is_stable_and_bounded(self):
        baseline = {(band << 24) | i for band in range(4) for i in range(96)}
        blurred = {(band << 24) | i for band in range(4) for i in range(96, 192)}
        first = study.select_extra_words(baseline, blurred, 128)
        second = study.select_extra_words(set(reversed(sorted(baseline))),
                                          set(reversed(sorted(blurred))), 128)
        self.assertEqual(first, second)
        self.assertTrue(baseline <= first)
        self.assertEqual(len(first), 384 + 128)
        for band in range(4):
            self.assertEqual(sum(w >> 24 == band for w in first), 128)
        self.assertEqual(study.select_extra_words(baseline, blurred, 0), baseline)

    def test_refuses_unbounded_terms_and_unknown_capacity(self):
        baseline = {1, 2, 3}
        for wrong in (True, 1, 32, 384, -1, 65):
            with self.assertRaises(ValueError):
                study.select_extra_words(baseline, set(), wrong)
        for wrong in ({-1}, {1 << 26}, {True}, {1.0}, set(range(385)), [1, 2]):
            with self.assertRaises(ValueError):
                study.select_extra_words(baseline, wrong, 64)

    def test_synthetic_full_report_does_not_infer_shutter_identity(self):
        names = [v[0] for v in (*study.PINNED_PHOTOS.values(), *study.STEREO_SOURCES.values())]
        originals = {name: np.full((256, 256), 40 + i, np.uint8)
                     for i, name in enumerate(names)}
        before = {name: a.copy() for name, a in originals.items()}
        calls = []
        def mocked_words(image):
            calls.append(image.shape)
            return {1, 2, 3}
        with patch.object(study, "_bundled_root", return_value=Path("/private/foo")), \
                patch.object(study, "_read_pinned_photo",
                             side_effect=lambda path, digest: originals[path.name].copy()), \
                patch.object(study, "_validated_words", side_effect=mocked_words):
            out = study.run()
        self.assertEqual(len(calls), 7 * 4)
        self.assertEqual(set(out["results"]), {"0", "64", "128", "192", "256"})
        self.assertEqual(out["base_word_budget"], 384)
        for profile in out["results"].values():
            self.assertLessEqual(profile["strict_per_photo_bound"], 640)
            for condition in profile["conditions"].values():
                self.assertTrue(condition["stereo_viewpoint_would_enter_shortlist"])
        self.assertFalse(out["real_camera_source_identity_confirmed"])
        self.assertFalse(out["production_thresholds_or_index_changed"])
        self.assertNotIn("/private/foo", json.dumps(out))
        for name in originals:
            self.assertTrue(np.array_equal(before[name], originals[name]))

    def test_stereo_duplicate_and_fingerprint_drift_are_refused(self):
        with patch.object(study, "FINGERPRINT_VERSION", "wrong"):
            with self.assertRaisesRegex(ValueError, "configuration"):
                study.run()
        same = np.full((256, 256), 55, np.uint8)
        with patch.object(study, "_bundled_root", return_value=Path("/fake")), \
                patch.object(study, "_read_pinned_photo", return_value=same.copy()):
            with self.assertRaisesRegex(ValueError, "distinct"):
                study.run()


if __name__ == "__main__":
    unittest.main()
