"""Disposable synthetic-only SQLite rank tests; no public image assets in CI."""
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import unittest

import numpy as np

from tools.photo_lineage import public_blur_sqlite_rank as study


class PublicBlurRankTests(unittest.TestCase):
    def fake_sources(self):
        filenames = [v[0] for v in (*study.PINNED_PHOTOS.values(),
                                    *study.STEREO_SOURCES.values())]
        return {name: np.full((256, 256), 11 + i, np.uint8)
                for i, name in enumerate(filenames)}

    def test_end_to_end_real_sqlite_on_seven_fake_low_texture_sources(self):
        photos = self.fake_sources()
        before = {name: a.copy() for name, a in photos.items()}
        with patch.object(study, "_bundled_root", return_value=Path("/private/fake")), \
                patch.object(study, "_read_pinned_photo",
                             side_effect=lambda path, sha: photos[path.name].copy()):
            report = study.run(distractors=0)
        self.assertEqual(report["indexed_photos"], 7)
        self.assertEqual(report["blurred_queries_correlated"], 42)
        self.assertTrue(report["temporary_photo_derivatives_and_index_removed"])
        self.assertFalse(report["source_exposure_identity_verified"])
        self.assertFalse(report["actual_full_gallery_recall_calibrated"])
        self.assertNotIn("/private", json.dumps(report))
        for row in report["by_condition"].values():
            self.assertEqual(row["rank_at_most_20_sources"], 0)
            self.assertEqual(row["meeting_necessary_two_word_gate"], 0)
        for name, original in before.items():
            self.assertTrue(np.array_equal(photos[name], original))

    def test_mock_rank1_and_no_temp_path_output(self):
        photos = self.fake_sources()
        def fake_shortlist(query, db_path, **kwargs):
            parts = Path(query).stem.split("__")
            self.assertEqual(parts[0], "query")
            name = parts[1]
            return [SimpleNamespace(
                path=str(Path(db_path).parent / "public-and-fictional" / f"source__{name}.png"))]

        with patch.object(study, "_bundled_root", return_value=Path("/private/fake")), \
                patch.object(study, "_read_pinned_photo",
                             side_effect=lambda path, sha: photos[path.name].copy()), \
                patch.object(study, "_validated_words", return_value={1, 2}), \
                patch.object(study, "index_folder",
                             return_value={"errors": [], "total_cached": 7}), \
                patch.object(study, "shortlist", side_effect=fake_shortlist):
            report = study.run(distractors=0)
        for row in report["by_condition"].values():
            self.assertEqual(row["rank_1_sources"], 7)
            self.assertEqual(row["meeting_necessary_two_word_gate"], 7)
            self.assertEqual(row["word_eligible_but_not_in_top_20"], 0)
        self.assertNotIn("/private", json.dumps(report))

    def test_positive_end_to_end_sqlite_exact_synthetic_source_rank_one(self):
        """The prior constant-array smoke only exercised empty retrieval.

        Exact synthetic copies are a deliberately easy positive control,
        isolating SQLite write/read/rank semantics from blur robustness.
        """
        from tools.photo_lineage.benchmark import textured
        entries = (*study.PINNED_PHOTOS.values(), *study.STEREO_SOURCES.values())
        images = {
            entry[0]: textured(900 + index, 256)
            for index, entry in enumerate(entries)
        }
        original = {name: pixels.copy() for name, pixels in images.items()}
        with patch.object(study, "_bundled_root", return_value=Path("/private/unavailable")), \
                patch.object(study, "_read_pinned_photo",
                             side_effect=lambda path, sha: images[path.name].copy()), \
                patch.object(study, "apply_blur",
                             side_effect=lambda gray, kind, strength: gray.copy()):
            report = study.run(distractors=8)
        self.assertEqual(report["indexed_photos"], 15)
        self.assertEqual(report["blurred_queries_correlated"], 42)
        for row in report["by_condition"].values():
            self.assertEqual(row["meeting_necessary_two_word_gate"], 7)
            self.assertEqual(row["rank_1_sources"], 7)
            self.assertEqual(row["rank_at_most_20_sources"], 7)
            self.assertEqual(row["word_eligible_but_not_in_top_20"], 0)
        self.assertTrue(report["temporary_photo_derivatives_and_index_removed"])
        self.assertFalse(report["actual_full_gallery_recall_calibrated"])
        self.assertNotIn("/private", json.dumps(report))
        for name in images:
            self.assertTrue(np.array_equal(original[name], images[name]))

    def test_mocked_rank_three_is_not_falsely_reported_as_rank_one(self):
        photos = self.fake_sources()
        def hits_for_query(query, db_path, **kwargs):
            parts = Path(query).stem.split("__")
            target = str(Path(db_path).parent / "public-and-fictional" /
                         f"source__{parts[1]}.png")
            return [
                SimpleNamespace(path="fictional__otherA"),
                SimpleNamespace(path="fictional__otherB"),
                SimpleNamespace(path=target),
            ]

        with patch.object(study, "_bundled_root", return_value=Path("/private/fake")), \
                patch.object(study, "_read_pinned_photo",
                             side_effect=lambda path, sha: photos[path.name].copy()), \
                patch.object(study, "_validated_words", return_value={1, 2}), \
                patch.object(study, "index_folder",
                             return_value={"errors": [], "total_cached": 7}), \
                patch.object(study, "shortlist", side_effect=hits_for_query):
            report = study.run(distractors=0)
        for row in report["by_condition"].values():
            self.assertEqual(row["rank_1_sources"], 0)
            self.assertEqual(row["rank_at_most_5_sources"], 7)
            self.assertEqual(row["meeting_necessary_two_word_gate"], 7)
            self.assertEqual(row["word_eligible_but_not_in_top_20"], 0)
        self.assertNotIn("/private", json.dumps(report))

    def test_invalid_limits_shortlists_and_fingerprint_fail_closed(self):
        for n in (-1, 65, True, 2.5):
            with self.subTest(value=n), self.assertRaises(ValueError):
                study.run(distractors=n)
        for hitlist in (None, list(range(21)), [SimpleNamespace(path="x")] * 2):
            with self.subTest(list_type=type(hitlist).__name__):
                with self.assertRaises(ValueError):
                    study._rank(hitlist, Path("x"))
        self.assertIsNone(study._rank([], Path("missing")))
        self.assertEqual(study._rank([SimpleNamespace(path="right")], Path("right")), 1)
        with patch.object(study, "FINGERPRINT_VERSION", "changed"):
            with self.assertRaisesRegex(ValueError, "protocol"):
                study.run()


if __name__ == "__main__":
    unittest.main()
