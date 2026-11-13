#!/usr/bin/env python3
"""Fictional, model-free photo-derivative source-family independence checks."""
import json
import unittest

from audit_source_families import audit_lineage_source_groups
from test_spatial_evidence_review import SpatialEvidenceReviewTests


class SourceFamilyAuditTests(unittest.TestCase):
    def test_distinct_crop_enhancement_sources_from_one_exposure_cannot_multiply_groups(self):
        trial = SpatialEvidenceReviewTests()
        trial.setUp()
        try:
            # A derivative image of the very same underlying body capture,
            # but a different source_reference and declared correlation ID.
            trial.cue("PersonA", 33, "clothing", "EnhancedCropOfBody", "OutfitGroup")
            sources = [
                {"source_reference": "BodySrc", "lineage_family_uid": "OriginalPhotoA"},
                {"source_reference": "EnhancedCropOfBody",
                 "lineage_family_uid": "OriginalPhotoA"},
                {"source_reference": "SessionSrc", "lineage_family_uid": "SeparateEventB"},
            ]
            before = trial.db.total_changes
            from spatial_evidence_review import review_with_spatial_sources
            result = review_with_spatial_sources(
                trial.db, "PersonA", body_regions=trial.bodies, face_regions=[],
                source_lineage_rows=sources, require_lineage_audit=True)
            self.assertEqual(result["status"], "manual_region_review_required")
            self.assertTrue(result["candidate_results_suppressed"])
            self.assertEqual(result["proposals"], [])
            audit = result["source_lineage_audit"]
            self.assertEqual(audit["same_candidate_source_family_group_conflicts"], 1)
            self.assertEqual(audit["declared_source_families"], 2)
            self.assertFalse(audit["actual_photo_lineage_independently_verified"])
            self.assertFalse(audit["physical_source_independence_proven"])
            self.assertFalse(audit["auto_confirm_allowed"])
            self.assertEqual(trial.db.total_changes, before)
            for secret in ("OriginalPhotoA", "EnhancedCropOfBody", "BodySrc",
                           "candidate_person_id", "private.jpg"):
                self.assertNotIn(secret, json.dumps(audit))
        finally:
            trial.tearDown()

    def test_one_source_family_one_group_and_distinct_family_remain_reviewable(self):
        trial = SpatialEvidenceReviewTests()
        trial.setUp()
        try:
            trial.cue("PersonA", 33, "clothing", "EnhancedCropOfBody", "BodyGroup")
            sources = [
                {"source_reference": "BodySrc", "lineage_family_uid": "ExposureA"},
                {"source_reference": "EnhancedCropOfBody",
                 "lineage_family_uid": "ExposureA"},
                {"source_reference": "SessionSrc", "lineage_family_uid": "ExposureB"},
            ]
            from spatial_evidence_review import review_with_spatial_sources
            result = review_with_spatial_sources(
                trial.db, "PersonA", body_regions=trial.bodies, face_regions=[],
                source_lineage_rows=sources, require_lineage_audit=True)
            self.assertEqual(result["status"], "unconfirmed_suggestions_only")
            self.assertEqual(result["source_lineage_audit"][
                "same_candidate_source_family_group_conflicts"], 0)
            self.assertEqual(result["proposals"][0]["independent_support_groups"], 2)
            self.assertFalse(result["auto_confirm_allowed"])
            self.assertTrue(result["unknown_always_allowed"])
        finally:
            trial.tearDown()

    def test_competing_people_share_photo_family_without_silent_identity_merge(self):
        evidence = [
            (11, "CropA", "FaceHypothesis11"),
            (22, "CropB", "FaceHypothesis22"),
        ]
        sources = [
            {"source_reference": "CropA", "lineage_family_uid": "ExposureOne"},
            {"source_reference": "CropB", "lineage_family_uid": "ExposureOne"},
        ]
        audit = audit_lineage_source_groups(evidence, sources)
        self.assertEqual(audit["same_candidate_source_family_group_conflicts"], 0)
        self.assertEqual(audit["declared_source_families"], 1)
        self.assertFalse(audit["auto_confirm_allowed"])
        self.assertFalse(audit["actual_photo_lineage_independently_verified"])

    def test_missing_duplicate_conflicted_private_and_extraneous_sources_refused(self):
        evidence = [
            (11, "CropA", "GroupOne"),
            (11, "CropB", "GroupTwo"),
        ]
        family = [
            {"source_reference": "CropA", "lineage_family_uid": "ExposureOne"},
            {"source_reference": "CropB", "lineage_family_uid": "ExposureTwo"},
        ]
        variants = [
            family[:-1],
            family + [dict(family[0])],
            family + [dict(family[0], lineage_family_uid="CompetingExposure")],
            family + [{"source_reference": "ExtraCrop",
                       "lineage_family_uid": "ExposureThree"}],
            [dict(family[0], media_path="/storage/private.jpg"), family[1]],
            [dict(family[0], source_reference="../secret.jpg"), family[1]],
            [dict(family[0], lineage_family_uid="UNREVIEWED"), family[1]],
            [dict(family[0], lineage_family_uid=42), family[1]],
        ]
        for idx, rows in enumerate(variants):
            with self.subTest(case=idx):
                with self.assertRaises(ValueError):
                    audit_lineage_source_groups(evidence, rows)
        with self.assertRaises(ValueError):
            audit_lineage_source_groups(
                [(True, "CropA", "GroupOne")], [family[0]])
        with self.assertRaises(ValueError):
            audit_lineage_source_groups(
                [(11, "CropA", "GroupOne")] * 5001, [family[0]])

    def test_research_wrapper_requires_lineage_manifest_only_when_strict(self):
        trial = SpatialEvidenceReviewTests()
        trial.setUp()
        try:
            from spatial_evidence_review import review_with_spatial_sources
            params = dict(body_regions=trial.bodies, face_regions=[])
            with self.assertRaisesRegex(ValueError, "manifest"):
                review_with_spatial_sources(
                    trial.db, "PersonA", require_lineage_audit=True, **params)
            with self.assertRaisesRegex(ValueError, "boolean"):
                review_with_spatial_sources(
                    trial.db, "PersonA", require_lineage_audit=1, **params)
            legacy = review_with_spatial_sources(trial.db, "PersonA", **params)
            self.assertEqual(legacy["source_lineage_audit"]["status"], "not_supplied")
            self.assertFalse(legacy["source_lineage_audit"][
                "complete_declared_lineage_coverage"])
            self.assertFalse(legacy["auto_confirm_allowed"])
        finally:
            trial.tearDown()

    def test_deceptively_distinct_family_ids_can_never_claim_real_independence(self):
        # A bad annotator may give two visually identical crops different
        # families. This cannot be caught without separate lineage review;
        # successful CSV audit MUST not claim physical independence.
        out = audit_lineage_source_groups(
            [(99, "CropA", "OnePhoto"), (99, "CropB", "AnotherPhoto")],
            [{"source_reference": "CropA", "lineage_family_uid": "FamilyA"},
             {"source_reference": "CropB", "lineage_family_uid": "FamilyB"}])
        self.assertEqual(out["same_candidate_source_family_group_conflicts"], 0)
        self.assertFalse(out["actual_photo_lineage_independently_verified"])
        self.assertFalse(out["physical_source_independence_proven"])
        self.assertFalse(out["auto_confirm_allowed"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
