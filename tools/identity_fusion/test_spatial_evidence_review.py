#!/usr/bin/env python3
"""Fictional, zero-image integration tests of explicit face-source evidence."""
import json
import sqlite3
import unittest

from evidence_store import (
    add_asset, add_candidate_evidence, add_observation, initialize
)
from spatial_evidence_review import review_with_spatial_sources


def region(uid, x, y, w, h, asset="Frame001"):
    return {"asset_uid": asset, "observation_uid": uid,
            "x": x, "y": y, "w": w, "h": h}


def face(uid, x, y, w, h, asset="Frame001"):
    return {"asset_uid": asset, "face_uid": uid,
            "x": x, "y": y, "w": w, "h": h}


class SpatialEvidenceReviewTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        initialize(self.db)
        add_asset(
            self.db, uid="Frame001",
            exact_path="/storage/emulated/0/DCIM/Synthetic/not_real_photo.jpg")
        add_asset(
            self.db, uid="Frame002",
            exact_path="/storage/emulated/0/DCIM/Synthetic/other_fake_photo.jpg")
        for uid, area, asset in (
            ("PersonA", (.10, .10, .40, .80), "Frame001"),
            ("PersonB", (.60, .10, .32, .80), "Frame001"),
            ("PersonOtherFrame", (.10, .10, .40, .80), "Frame002"),
        ):
            add_observation(
                self.db, uid=uid, asset_uid=asset, region=area,
                visibility="partial", occlusion_fraction=None)
        self.bodies = [
            region("PersonA", .10, .10, .40, .80),
            region("PersonB", .60, .10, .32, .80),
        ]
        self.faces = [
            face("FaceA", .20, .19, .12, .14),
            face("FaceB", .69, .19, .10, .14),
        ]
        self.ordinal = 0
        self.cue("PersonA", 33, "body", "BodySrc", "BodyGroup")
        self.cue("PersonA", 33, "session", "SessionSrc", "SessionGroup")

    def tearDown(self):
        self.db.close()

    def cue(self, observation, person, family, source, group="FaceGroup"):
        self.ordinal += 1
        add_candidate_evidence(
            self.db, uid="SyntheticCue_%d" % self.ordinal,
            observation_uid=observation, person_id=person,
            family=family, polarity="supports",
            correlation_group=group,
            method_version="local-synthetic-only",
            source_reference=source,
        )

    def audit(self, *, bodies=None, faces=None, observation="PersonA", limit=1):
        return review_with_spatial_sources(
            self.db, observation,
            body_regions=self.bodies if bodies is None else bodies,
            face_regions=self.faces if faces is None else faces,
            limit=limit,
        )

    def test_exact_same_frame_source_supports_manual_review_only(self):
        self.cue("PersonA", 33, "face", "FaceA", "FaceFromOwnHead")
        changes = self.db.total_changes
        report = self.audit()
        self.assertEqual(report["status"], "unconfirmed_suggestions_only")
        self.assertEqual(report["spatial_guard_status"],
                         "all_same_frame_face_sources_geometry_matched")
        self.assertEqual(report["face_evidence_rows"], 1)
        self.assertEqual(report["face_evidence_geometry_matched"], 1)
        self.assertEqual(len(report["proposals"]), 1)
        self.assertEqual(report["proposals"][0]["candidate_person_id"], 33)
        self.assertFalse(report["candidate_results_suppressed"])
        self.assertFalse(report["physical_face_body_identity_proven"])
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertTrue(report["unknown_always_allowed"])
        self.assertEqual(self.db.total_changes, changes)
        self.assertNotIn("FaceA", json.dumps(report))
        self.assertNotIn("Frame001", json.dumps(report))
        self.assertNotIn("FaceFromOwnHead", json.dumps(report))
        self.assertNotIn("not_real_photo.jpg", json.dumps(report))

    def test_swapped_face_from_second_person_suppresses_even_body_support(self):
        self.cue("PersonA", 33, "face", "FaceB", "WrongHeadCue")
        before = self.db.total_changes
        report = self.audit(limit=1)
        self.assertEqual(report["status"], "manual_region_review_required")
        self.assertEqual(report["proposals"], [])
        self.assertGreater(report["total_candidates"], 0)
        self.assertTrue(report["candidate_results_suppressed"])
        self.assertEqual(report["face_evidence_geometry_matched"], 0)
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertEqual(before, self.db.total_changes)
        self.assertNotIn("FaceB", json.dumps(report))

    def test_multi_person_overlap_or_duplicate_face_source_blocks_review(self):
        self.cue("PersonA", 33, "face", "FaceA")
        overlaps = [
            region("PersonA", .10, .10, .40, .80),
            region("PersonB", .28, .10, .49, .80),
        ]
        self.assertTrue(self.audit(bodies=overlaps)["candidate_results_suppressed"])
        repeats = [
            face("FaceA", .20, .19, .12, .14),
            face("SecondDetector", .205, .19, .12, .14),
        ]
        self.assertTrue(self.audit(faces=repeats)["candidate_results_suppressed"])
        self.assertFalse(self.audit(faces=repeats)["auto_confirm_allowed"])

    def test_unresolved_or_cross_frame_sources_are_not_converted_to_unknown_truth(self):
        for source in ("MissingFace", "FaceFromOtherFrame", "../private.jpg"):
            with self.subTest(source=source):
                db = sqlite3.connect(":memory:")
                try:
                    initialize(db)
                    add_asset(db, uid="Frame001",
                              exact_path="/storage/emulated/0/DCIM/Synthetic/fake.jpg")
                    add_observation(db, uid="PersonA", asset_uid="Frame001",
                                    region=(.10, .10, .40, .80), visibility="back",
                                    occlusion_fraction=None)
                    add_candidate_evidence(
                        db, uid="FaceCue", observation_uid="PersonA",
                        person_id=42, family="face", polarity="supports",
                        correlation_group="g1", method_version="synthetic",
                        source_reference=source)
                    result = review_with_spatial_sources(
                        db, "PersonA", body_regions=self.bodies,
                        face_regions=[
                            face("FaceFromOtherFrame", .20, .19, .12, .14,
                                 asset="Frame002")
                        ])
                    self.assertEqual(result["status"], "manual_region_review_required")
                    self.assertTrue(result["candidate_results_suppressed"])
                    self.assertFalse(result["auto_confirm_allowed"])
                    self.assertNotIn(source, json.dumps(result))
                finally:
                    db.close()

    def test_all_face_sources_must_match_no_partial_trust(self):
        self.cue("PersonA", 33, "face", "FaceA")
        self.cue("PersonA", 99, "face", "FaceB", "AnotherFaceGroup")
        result = self.audit(limit=1)
        self.assertEqual(result["face_evidence_rows"], 2)
        self.assertEqual(result["face_evidence_geometry_matched"], 1)
        self.assertEqual(result["proposals"], [])
        self.assertTrue(result["candidate_results_suppressed"])
        self.assertFalse(result["auto_confirm_allowed"])

    def test_rear_view_with_no_face_evidence_remains_review_only_not_no_person(self):
        out = self.audit(faces=[])
        self.assertEqual(out["spatial_guard_status"], "no_same_frame_face_evidence")
        self.assertEqual(out["face_evidence_rows"], 0)
        self.assertEqual(out["spatial_region_aggregate"]["face_regions"], 0)
        self.assertEqual(len(out["proposals"]), 1)
        self.assertEqual(out["proposals"][0]["candidate_person_id"], 33)
        self.assertFalse(out["auto_confirm_allowed"])
        self.assertFalse(out["physical_face_body_identity_proven"])

    def test_one_body_source_under_two_group_labels_does_not_count_twice(self):
        # Same underlying source, same person, two supposed independent
        # groups: strict review must refuse inflated body+outfit support.
        self.cue("PersonA", 33, "clothing", "BodySrc", "PretendOtherDay")
        changes = self.db.total_changes
        result = self.audit(faces=[])
        self.assertEqual(result["status"], "manual_region_review_required")
        self.assertEqual(result["source_alias_conflicts"], 1)
        self.assertTrue(result["source_independence_checked"])
        self.assertTrue(result["candidate_results_suppressed"])
        self.assertEqual(result["proposals"], [])
        self.assertFalse(result["auto_confirm_allowed"])
        self.assertEqual(self.db.total_changes, changes)
        self.assertNotIn("BodySrc", json.dumps(result))

    def test_one_face_crop_is_not_two_independent_support_groups(self):
        self.cue("PersonA", 33, "face", "FaceA", "FirstFaceObservation")
        self.cue("PersonA", 33, "face", "FaceA", "RebrandedFaceObservation")
        result = self.audit()
        self.assertEqual(result["source_alias_conflicts"], 1)
        self.assertEqual(result["face_evidence_geometry_matched"], 2)
        self.assertTrue(result["candidate_results_suppressed"])
        self.assertEqual(result["proposals"], [])
        self.assertFalse(result["auto_confirm_allowed"])

    def test_competing_people_can_reference_one_face_without_fabricated_consensus(self):
        # One ambiguous face exemplar may point to multiple *hypothetical*
        # identities. Sharing a face across different candidate IDs is NOT
        # a same-person source alias and must not auto-merge them.
        self.cue("PersonA", 33, "face", "FaceA", "FaceSource33")
        self.cue("PersonA", 44, "face", "FaceA", "FaceSource44")
        result = self.audit(limit=1)
        self.assertEqual(result["source_alias_conflicts"], 0)
        self.assertTrue(result["source_independence_checked"])
        self.assertFalse(result["candidate_results_suppressed"])
        self.assertEqual(result["total_candidates"], 2)
        self.assertFalse(result["auto_confirm_allowed"])
        self.assertFalse(result["physical_face_body_identity_proven"])

    def test_unsafe_nonface_source_names_are_refused_without_leaking_paths(self):
        self.cue("PersonA", 33, "clothing", "../private/stolen.jpg",
                 "CorrelatedPhoto")
        result = self.audit(faces=[])
        self.assertTrue(result["candidate_results_suppressed"])
        self.assertEqual(result["status"], "manual_region_review_required")
        self.assertNotIn("stolen", json.dumps(result))
        self.assertFalse(result["auto_confirm_allowed"])

    def test_stale_or_false_body_region_geometry_never_validates_face_source(self):
        self.cue("PersonA", 33, "face", "FaceA")
        altered = [
            region("PersonA", .11, .10, .39, .80),
            region("PersonB", .60, .10, .32, .80),
        ]
        with self.assertRaisesRegex(ValueError, "stored"):
            self.audit(bodies=altered)
        wrong_asset = [
            region("PersonA", .10, .10, .40, .80, asset="Frame002"),
            region("PersonB", .60, .10, .32, .80),
        ]
        with self.assertRaisesRegex(ValueError, "stored"):
            self.audit(bodies=wrong_asset)
        with self.assertRaises(ValueError):
            self.audit(observation="Nonexistent")

    def test_reject_excessive_face_evidence_and_invalid_limit_fail_closed(self):
        with self.assertRaises(ValueError):
            self.audit(limit=0)
        with self.assertRaises(ValueError):
            self.audit(limit=True)
        with self.assertRaises(ValueError):
            self.audit(observation="../private")
        # Excess rows are bounded before the read-only review is performed.
        for index in range(2001):
            self.cue("PersonA", 71, "face", "FaceA",
                     "synthetic_group_" + str(index))
        with self.assertRaisesRegex(ValueError, "Excessive"):
            self.audit()


if __name__ == "__main__":
    unittest.main(verbosity=2)
