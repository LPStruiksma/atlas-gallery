"""Only fictional image frames; no actual cameras, photo library or uploads."""
from pathlib import Path
from types import SimpleNamespace
import json
import unittest
from unittest.mock import patch

import numpy as np

from tools.photo_lineage import synthetic_exposure_funnel as experiment
from tools.photo_lineage.bounded_posting_query import PostingBudgetExceeded


class SyntheticExposureFunnelTests(unittest.TestCase):
    def test_end_to_end_real_sqlite_and_geometry_no_shutter_claim(self):
        report = experiment.run(scenes=1, frames_per_scene=2, size=256,
                                distractors=0, foreground_mode="absent",
                                cap=0, verify_algorithm="orb")
        self.assertEqual(report["fictional_source_frames"], 2)
        self.assertTrue(report["actual_temporary_sqlite_and_geometry_used"])
        self.assertTrue(report["temporary_files_removed_after_completion"])
        self.assertFalse(report["source_identity_or_common_shutter_verified"])
        self.assertFalse(report["population_precision_or_recall_estimated"])
        self.assertEqual(report["independent_camera_exposures_observed"], 0)
        self.assertEqual(report["extra_source_postings"], 0)
        self.assertFalse(report["original_user_images_read_modified_uploaded_or_deleted"])
        for condition in experiment.CONDITIONS:
            row = report["conditions"][condition]
            self.assertEqual(row["simulated_derived_queries"], 2)
            self.assertLessEqual(row["known_original_rank1"],
                                 row["known_original_retrieved"])
            self.assertLessEqual(row["known_edit_retrieved_and_geometry_reviewable"],
                                 row["known_edit_direct_geometry_reviewable"])
            self.assertLessEqual(row["separately_rendered_same_scene_candidate_after_geometry"] +
                                 row["separately_rendered_same_scene_review_after_geometry"] +
                                 row["separately_rendered_same_scene_unknown_after_geometry"], 2)
        self.assertNotIn("atlas-fictional-exposure-funnel-", json.dumps(report))

    def test_synthetic_distinct_time_step_offer_can_be_strong_not_identity(self):
        def fake_shortlist(query, database, **kwargs):
            stem = Path(query).stem
            # Source names are known fictional scratch paths only; no user paths.
            parts = stem.split("_")
            scene, frame = int(parts[1]), int(parts[3])
            root = Path(database).parent / "fictional"
            return [
                SimpleNamespace(path=str(root / f"scene_{scene:02d}_frame_{frame:02d}.png")),
                SimpleNamespace(path=str(root / f"scene_{scene:02d}_frame_{1-frame:02d}.png")),
            ]
        with patch.object(experiment, "shortlist", side_effect=fake_shortlist), \
                patch.object(experiment, "compare_arrays",
                             return_value=SimpleNamespace(verdict="candidate")):
            report = experiment.run(scenes=1, frames_per_scene=2, size=256,
                                    distractors=0, foreground_mode="stationary",
                                    cap=128, verify_algorithm="orb")
        for condition in experiment.CONDITIONS:
            row = report["conditions"][condition]
            self.assertEqual(row["known_original_retrieved"], 2)
            self.assertEqual(row["known_edit_retrieved_and_geometry_reviewable"], 2)
            self.assertEqual(row["separately_rendered_same_scene_shortlist_offers"], 2)
            self.assertEqual(row["separately_rendered_same_scene_candidate_after_geometry"], 2)
            self.assertEqual(row["scene_groups_with_any_distinct_step_candidate"], 1)
        self.assertFalse(report["source_identity_or_common_shutter_verified"])
        self.assertNotIn("scene_00_frame", json.dumps(report))

    def test_invalid_budgets_and_alias_outputs_fail_closed(self):
        for kwargs in ({"scenes": True}, {"scenes": 0}, {"scenes": 4},
                       {"frames_per_scene": 1}, {"frames_per_scene": 4},
                       {"size": True}, {"size": 500},
                       {"distractors": 13}, {"distractors": 2.5},
                       {"cap": True}, {"cap": 65},
                       {"verify_algorithm": "none"},
                       {"foreground_mode": "frozen"}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                experiment.run(**kwargs)
        with self.assertRaises(ValueError):
            experiment._checked_hits([SimpleNamespace(path="duplicate")] * 2)
        with self.assertRaises(ValueError):
            experiment._checked_hits([SimpleNamespace(path="ok")] * 11)
        with self.assertRaises(ValueError):
            experiment._checked_hits([object()])

    def test_posting_budget_exhaustion_never_masquerades_as_no_relatives(self):
        with patch.object(experiment, "shortlist",
                          side_effect=PostingBudgetExceeded("posting_visits",
                                                            "synthetic incomplete")):
            with self.assertRaises(PostingBudgetExceeded):
                experiment.run(scenes=1, frames_per_scene=2, size=256,
                               distractors=0, cap=0)

    def test_bad_geometry_verdict_aborts_without_false_safety_report(self):
        with patch.object(experiment, "compare_arrays",
                          return_value=SimpleNamespace(verdict="automatic_merge")):
            with self.assertRaisesRegex(ValueError, "Unsupported"):
                experiment.run(scenes=1, frames_per_scene=2, size=256,
                               distractors=0, cap=0)


if __name__ == "__main__":
    unittest.main()
