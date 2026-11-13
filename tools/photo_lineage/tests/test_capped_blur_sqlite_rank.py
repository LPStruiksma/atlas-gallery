"""Fictional image fixtures only; actual disposable SQLite read/write/query."""
from pathlib import Path
import json
import unittest
from unittest.mock import patch

import numpy as np

from tools.photo_lineage import capped_blur_sqlite_rank as study
from tools.photo_lineage.benchmark import textured
from tools.photo_lineage.bounded_posting_query import PostingBudgetExceeded


class CappedSqliteRankTests(unittest.TestCase):
    @staticmethod
    def samples(with_texture: bool) -> dict:
        names = [row[0] for row in (*study.PINNED_PHOTOS.values(),
                                   *study.STEREO_SOURCES.values())]
        return {
            name: (textured(800 + i, 256) if with_texture
                   else np.full((256, 256), 20 + i, np.uint8))
            for i, name in enumerate(names)
        }

    def test_positive_actual_sqlite_and_reversible_extra_postings(self):
        images = self.samples(True)
        before = {key: photo.copy() for key, photo in images.items()}
        with patch.object(study, "_bundled_root",
                          return_value=Path("/private/not-present")), \
                patch.object(study, "_read_pinned_photo",
                             side_effect=lambda p, sha: images[p.name].copy()), \
                patch.object(study, "apply_blur",
                             side_effect=lambda a, kind, strength: a.copy()):
            baseline = study.run(cap=0, distractors=8)
            capped = study.run(cap=128, distractors=8)
        self.assertEqual(baseline["additional_postings"], 0)
        self.assertGreater(capped["additional_postings"], 0)
        self.assertGreater(capped["experimental_total_postings"],
                           baseline["experimental_total_postings"])
        self.assertTrue(capped["temporary_research_cache_destroyed"])
        self.assertTrue(capped["actual_sqlite_shortlist_executed"])
        self.assertFalse(capped["source_exposure_equivalence_verified"])
        self.assertFalse(capped["population_recall_or_false_review_estimated"])
        for result in capped["conditions"].values():
            self.assertEqual(result["known_source_eligible"], 7)
            self.assertEqual(result["known_source_rank1"], 7)
            self.assertEqual(result["eligible_but_absent_top20"], 0)
        self.assertNotIn("/private", json.dumps(capped))
        for name in images:
            self.assertTrue(np.array_equal(images[name], before[name]))


    def test_optional_geometry_funnel_distinguishes_same_source_and_other_view(self):
        from types import SimpleNamespace
        images = self.samples(True)
        def verifier(a, b, *, algorithm):
            self.assertEqual(algorithm, "orb")
            # A fictional known source is compared with its exact clone;
            # the stereo opposite source has distinct synthetic content.
            return SimpleNamespace(
                verdict="candidate" if np.array_equal(a, b) else "review")
        with patch.object(study, "_bundled_root", return_value=Path("/fictional")), \
                patch.object(study, "_read_pinned_photo",
                             side_effect=lambda p, sha: images[p.name].copy()), \
                patch.object(study, "apply_blur",
                             side_effect=lambda a, kind, amount: a.copy()), \
                patch.object(study, "compare_arrays", side_effect=verifier) as called:
            result = study.run(cap=128, distractors=8, verify_algorithm="orb")
        self.assertTrue(result["geometry_review_stage_enabled"])
        self.assertTrue(result["same_shutter_equality_never_inferred_from_geometry"])
        self.assertEqual(called.call_count, 16)
        for condition in result["conditions"].values():
            row = condition["optional_geometry_review"]
            self.assertEqual(row["source_edits_reviewable_even_without_retrieval"], 7)
            self.assertEqual(row["retrieved_source_and_geometry_reviewable"], 7)
            self.assertEqual(row["retrieved_source_but_geometry_unknown"], 0)
            self.assertEqual(row["geometry_reviewable_but_source_not_retrieved"], 0)
            self.assertEqual(row["different_viewpoint_stereo_geometry_verdict"], "review")
        self.assertFalse(result["source_exposure_equivalence_verified"])
        self.assertNotIn("/fictional", json.dumps(result))

    def test_geometric_abstention_after_successful_sqlite_retrieval(self):
        from types import SimpleNamespace
        images = self.samples(True)
        with patch.object(study, "_bundled_root", return_value=Path("/fictional")), \
                patch.object(study, "_read_pinned_photo",
                             side_effect=lambda p, sha: images[p.name].copy()), \
                patch.object(study, "apply_blur",
                             side_effect=lambda a, kind, amount: a.copy()), \
                patch.object(study, "compare_arrays",
                             return_value=SimpleNamespace(verdict="unknown")):
            result = study.run(cap=0, distractors=0, verify_algorithm="sift")
        for condition in result["conditions"].values():
            row = condition["optional_geometry_review"]
            self.assertEqual(row["source_edits_reviewable_even_without_retrieval"], 0)
            self.assertEqual(row["retrieved_source_and_geometry_reviewable"], 0)
            self.assertEqual(row["retrieved_source_but_geometry_unknown"], 7)

    def test_unapproved_geometric_verdict_refused(self):
        from types import SimpleNamespace
        images = self.samples(True)
        with patch.object(study, "_bundled_root", return_value=Path("/fictional")), \
                patch.object(study, "_read_pinned_photo",
                             side_effect=lambda p, sha: images[p.name].copy()), \
                patch.object(study, "apply_blur",
                             side_effect=lambda a, kind, amount: a.copy()), \
                patch.object(study, "compare_arrays",
                             return_value=SimpleNamespace(verdict="auto_merge")):
            with self.assertRaisesRegex(ValueError, "Unsupported geometric"):
                study.run(cap=0, distractors=0, verify_algorithm="orb")

    def test_untextured_samples_abstain_with_no_spurious_source(self):
        images = self.samples(False)
        with patch.object(study, "_bundled_root", return_value=Path("/fictional")), \
                patch.object(study, "_read_pinned_photo",
                             side_effect=lambda p, sha: images[p.name].copy()):
            report = study.run(cap=256, distractors=0)
        self.assertEqual(report["additional_postings"], 0)
        for row in report["conditions"].values():
            self.assertEqual(row["known_source_eligible"], 0)
            self.assertEqual(row["known_source_top20"], 0)
        self.assertTrue(report["temporary_research_cache_destroyed"])

    def test_actual_posting_budget_exhaustion_propagates_not_empty(self):
        images = self.samples(True)
        with patch.object(study, "_bundled_root", return_value=Path("/fictional")), \
                patch.object(study, "_read_pinned_photo",
                             side_effect=lambda p, sha: images[p.name].copy()), \
                patch.object(study, "apply_blur",
                             side_effect=lambda a, kind, strength: a.copy()):
            with self.assertRaises(PostingBudgetExceeded):
                study.run(cap=256, distractors=0, posting_budget=1)

    def test_strict_argument_rank_and_protocol_validation(self):
        for bad in (True, -1, 1, 65, 384, None):
            with self.subTest(cap=bad), self.assertRaises(ValueError):
                study.run(cap=bad)
        for bad in (True, -1, 33, 2.5):
            with self.subTest(distractors=bad), self.assertRaises(ValueError):
                study.run(distractors=bad)
        for bad in (0, 50001, True):
            with self.subTest(posting_budget=bad), self.assertRaises(ValueError):
                study.run(posting_budget=bad)
        for bad in (0, 1001, True):
            with self.subTest(doc_budget=bad), self.assertRaises(ValueError):
                study.run(document_budget=bad)
        for bad in (0, False, "all", "", "ORB"):
            with self.subTest(verifier=bad), self.assertRaises(ValueError):
                study.run(verify_algorithm=bad)
        self.assertIsNone(study._rank([], Path("missing")))
        with self.assertRaises(ValueError):
            study._rank([object()] * 21, Path("unknown"))
        with self.assertRaises(ValueError):
            study._rank([type("Hit", (), {"path": "duplicate"})()] * 2,
                        Path("duplicate"))
        with patch.object(study, "FINGERPRINT_VERSION", "new"):
            with self.assertRaisesRegex(ValueError, "protocol"):
                study.run()

    def test_duplicate_public_stereo_viewpoints_refused(self):
        same = np.full((256, 256), 42, np.uint8)
        with patch.object(study, "_bundled_root", return_value=Path("/fake")), \
                patch.object(study, "_read_pinned_photo",
                             return_value=same.copy()):
            with self.assertRaisesRegex(ValueError, "Distinct"):
                study.run()


if __name__ == "__main__":
    unittest.main()
