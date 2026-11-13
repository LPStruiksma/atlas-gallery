#!/usr/bin/env python3
"""Fictional face/body detector jitter stress; no images, models or media."""
import json
import unittest

from spatial_jitter_safety import audit_spatial_jitter, review_with_jitter_safety
from test_spatial_evidence_review import SpatialEvidenceReviewTests, region, face


class SpatialJitterSafetyTests(unittest.TestCase):
    def test_interior_regions_stay_associated_without_identity_confirmation(self):
        trial = SpatialEvidenceReviewTests()
        trial.setUp()
        try:
            trial.cue("PersonA", 33, "face", "FaceA", "FaceOwnSource")
            before = trial.db.total_changes
            audit = audit_spatial_jitter(trial.bodies, trial.faces, epsilon=.01)
            self.assertEqual(audit.aggregate["base_geometry_candidates"], 2)
            self.assertEqual(audit.aggregate["stable_geometry_candidates"], 2)
            self.assertTrue(audit.for_face("FaceA").stable_under_sampled_jitter)
            self.assertEqual(audit.for_face("FaceA").observation_uid, "PersonA")
            self.assertFalse(audit.aggregate["actual_detector_error_distribution_measured"])
            self.assertFalse(audit.aggregate["multi_axis_joint_perturbations_tested"])
            result = review_with_jitter_safety(
                trial.db, "PersonA", body_regions=trial.bodies,
                face_regions=trial.faces, epsilon=.01, limit=1)
            self.assertFalse(result["candidate_results_suppressed"])
            self.assertEqual(result["proposals"][0]["candidate_person_id"], 33)
            self.assertEqual(result["unstable_face_evidence_sources"], 0)
            self.assertFalse(result["auto_confirm_allowed"])
            self.assertEqual(trial.db.total_changes, before)
            for private in ("FaceA", "PersonA", "Frame001", "/storage/"):
                self.assertNotIn(private, json.dumps(audit.aggregate))
            self.assertEqual(audit, audit_spatial_jitter(
                list(reversed(trial.bodies)), list(reversed(trial.faces)),
                epsilon=.01))
        finally:
            trial.tearDown()

    def test_face_just_inside_person_edge_is_a_jitter_sensitive_candidate(self):
        trial = SpatialEvidenceReviewTests()
        trial.setUp()
        try:
            trial.faces[0] = face("FaceA", .111, .19, .12, .14)
            trial.cue("PersonA", 33, "face", "FaceA", "FaceOwnSource")
            # An unperturbed point estimate appears valid.
            self.assertFalse(trial.audit()["candidate_results_suppressed"])
            jitter = audit_spatial_jitter(trial.bodies, trial.faces, epsilon=.01)
            decision = jitter.for_face("FaceA")
            self.assertFalse(decision.stable_under_sampled_jitter)
            self.assertIsNone(decision.observation_uid)
            self.assertGreater(decision.changed_owner_or_status, 0)
            out = review_with_jitter_safety(
                trial.db, "PersonA", body_regions=trial.bodies,
                face_regions=trial.faces, epsilon=.01)
            self.assertEqual(out["status"], "manual_region_review_required")
            self.assertTrue(out["candidate_results_suppressed"])
            self.assertEqual(out["proposals"], [])
            self.assertEqual(out["unstable_face_evidence_sources"], 1)
            self.assertGreater(out["total_candidates"], 0)
            self.assertFalse(out["auto_confirm_allowed"])
        finally:
            trial.tearDown()

    def test_nearby_second_body_can_cross_face_under_detector_shift(self):
        bodies = [region("PersonA", .10, .10, .40, .80),
                  region("PersonB", .515, .10, .35, .80)]
        faces = [face("FaceA", .39, .20, .10, .12)]
        result = audit_spatial_jitter(bodies, faces, epsilon=.05)
        self.assertEqual(result.aggregate["base_geometry_candidates"], 1)
        self.assertEqual(result.aggregate["stable_geometry_candidates"], 0)
        self.assertGreater(result.for_face("FaceA").changed_owner_or_status, 0)

    def test_body_only_rear_view_never_reclassified_as_unobserved_human(self):
        trial = SpatialEvidenceReviewTests()
        trial.setUp()
        try:
            report = review_with_jitter_safety(
                trial.db, "PersonA", body_regions=trial.bodies,
                face_regions=[], epsilon=.01)
            self.assertEqual(report["face_evidence_rows"], 0)
            self.assertEqual(report["unstable_face_evidence_sources"], 0)
            self.assertEqual(report["spatial_jitter_audit"]["faces_evaluated"], 0)
            self.assertTrue(report["unknown_always_allowed"])
            self.assertFalse(report["auto_confirm_allowed"])
            self.assertEqual(len(report["proposals"]), 1)
        finally:
            trial.tearDown()

    def test_invalid_jitter_out_of_image_and_large_sets_refuse_confident_claim(self):
        bodies = [region("NearEdge", 0, 0, .6, .8)]
        faces = [face("EdgeFace", .015, .11, .10, .12)]
        audit = audit_spatial_jitter(bodies, faces, epsilon=.02)
        self.assertEqual(audit.aggregate["base_geometry_candidates"], 1)
        self.assertGreater(audit.for_face("EdgeFace").invalid_perturbations, 0)
        self.assertFalse(audit.for_face("EdgeFace").stable_under_sampled_jitter)
        for val in (0, .051, True, None, "0.01", float("nan"),
                    float("inf"), -1):
            with self.subTest(jitter=str(val)):
                with self.assertRaises(ValueError):
                    audit_spatial_jitter(bodies, faces, epsilon=val)
        big = [
            region("B_" + str(n), .01, .01, .3, .7, asset="Frame_" + str(n))
            for n in range(24)
        ]
        with self.assertRaisesRegex(ValueError, "Bounded"):
            audit_spatial_jitter(big, faces, epsilon=.01)

    def test_joint_head_zone_contraction_can_fail_while_all_single_axis_shifts_pass(self):
        # A face is safely in the upper body region for EVERY one-axis
        # ±2% detector error, yet simultaneous face down/height expansion
        # and body up/height contraction move it beyond the head zone.
        bodies = [region("OnlyBody", .10, .10, .60, .80)]
        faces = [face("OnlyFace", .30, .30, .10, .20)]
        single = audit_spatial_jitter(bodies, faces, epsilon=.02)
        self.assertEqual(single.aggregate["base_geometry_candidates"], 1)
        self.assertEqual(single.aggregate["stable_geometry_candidates"], 1)
        self.assertFalse(single.aggregate["multi_axis_joint_perturbations_tested"])
        joint = audit_spatial_jitter(
            bodies, faces, epsilon=.02, joint_cases=8)
        self.assertEqual(joint.aggregate["base_geometry_candidates"], 1)
        self.assertEqual(joint.aggregate["stable_geometry_candidates"], 0)
        self.assertGreater(joint.aggregate["changed_joint_owner_or_status"], 0)
        self.assertGreaterEqual(joint.aggregate["joint_perturbations_checked"], 1)
        self.assertTrue(joint.aggregate["multi_axis_joint_perturbations_tested"])
        self.assertIsNone(joint.for_face("OnlyFace").observation_uid)
        self.assertFalse(joint.aggregate["auto_confirm_allowed"])

    def test_joint_guard_withholds_brittle_face_even_when_individual_shifts_pass(self):
        trial = SpatialEvidenceReviewTests()
        trial.setUp()
        try:
            trial.faces[0] = face("FaceA", .20, .30, .12, .20)
            trial.cue("PersonA", 33, "face", "FaceA", "OwnFace")
            kwargs = dict(body_regions=trial.bodies, face_regions=trial.faces,
                          epsilon=.02)
            single = review_with_jitter_safety(
                trial.db, "PersonA", **kwargs)
            self.assertFalse(single["candidate_results_suppressed"])
            before = trial.db.total_changes
            joint = review_with_jitter_safety(
                trial.db, "PersonA", joint_cases=8, **kwargs)
            self.assertEqual(joint["status"], "manual_region_review_required")
            self.assertEqual(joint["proposals"], [])
            self.assertTrue(joint["candidate_results_suppressed"])
            self.assertEqual(joint["unstable_face_evidence_sources"], 1)
            self.assertGreater(joint["total_candidates"], 0)
            self.assertEqual(trial.db.total_changes, before)
            self.assertFalse(joint["auto_confirm_allowed"])
        finally:
            trial.tearDown()

    def test_seeded_joint_scenarios_repeat_and_ignore_row_insertion_order(self):
        trial = SpatialEvidenceReviewTests()
        trial.setUp()
        try:
            a = audit_spatial_jitter(
                trial.bodies, trial.faces, epsilon=.005, joint_cases=32)
            b = audit_spatial_jitter(
                list(reversed(trial.bodies)), list(reversed(trial.faces)),
                epsilon=.005, joint_cases=32)
            self.assertEqual(a, b)
            self.assertEqual(a.aggregate["base_geometry_candidates"], 2)
            self.assertEqual(a.aggregate["stable_geometry_candidates"], 2)
            self.assertEqual(a.aggregate["joint_perturbations_checked"], 64)
            self.assertEqual(a.aggregate["joint_scenarios_predeclared"], 32)
            self.assertTrue(a.aggregate["multi_axis_joint_perturbations_tested"])
            self.assertFalse(a.aggregate["actual_detector_error_distribution_measured"])
            self.assertFalse(a.aggregate["geometry_stability_is_identity"])
            for private in ("FaceA", "PersonA", "Frame001"):
                self.assertNotIn(private, json.dumps(a.aggregate))
        finally:
            trial.tearDown()

    def test_invalid_joint_scenario_count_fails_closed_not_cherry_picked(self):
        bodies = [region("Body", .1, .1, .6, .8)]
        faces = [face("Face", .3, .2, .1, .1)]
        for n in (-1, 1, 7, 65, True, "8", 8.0, None):
            with self.subTest(value=str(n)):
                with self.assertRaises(ValueError):
                    audit_spatial_jitter(bodies, faces, joint_cases=n)
        self.assertEqual(audit_spatial_jitter(
            bodies, faces, joint_cases=0).aggregate["joint_scenarios_predeclared"], 0)

    def test_rear_view_with_no_face_retains_manual_body_hypothesis_under_joint_mode(self):
        trial = SpatialEvidenceReviewTests()
        trial.setUp()
        try:
            out = review_with_jitter_safety(
                trial.db, "PersonA", body_regions=trial.bodies,
                face_regions=[], epsilon=.01, joint_cases=16)
            self.assertEqual(out["spatial_jitter_audit"]["faces_evaluated"], 0)
            self.assertEqual(out["unstable_face_evidence_sources"], 0)
            self.assertEqual(len(out["proposals"]), 1)
            self.assertFalse(out["spatial_jitter_audit"][
                "multi_axis_joint_perturbations_tested"])
            self.assertFalse(out["auto_confirm_allowed"])
        finally:
            trial.tearDown()

    def test_source_lineage_policy_remains_independent_of_geometry_jitter(self):
        trial = SpatialEvidenceReviewTests()
        trial.setUp()
        try:
            trial.cue("PersonA", 33, "face", "FaceA", "OwnFaceGroup")
            # BodySrc, SessionSrc and FaceA each have their own group.
            manifest = [
                {"source_reference": "BodySrc", "lineage_family_uid": "Family1"},
                {"source_reference": "SessionSrc", "lineage_family_uid": "Family2"},
                {"source_reference": "FaceA", "lineage_family_uid": "Family3"},
            ]
            good = review_with_jitter_safety(
                trial.db, "PersonA", body_regions=trial.bodies,
                face_regions=trial.faces, source_lineage_rows=manifest,
                require_lineage_audit=True)
            self.assertEqual(good["source_lineage_audit"][
                "same_candidate_source_family_group_conflicts"], 0)
            self.assertFalse(good["candidate_results_suppressed"])
            # If face/photo is later revealed to be the SAME source as
            # body, distinct groups cannot count as independent support.
            manifest[2]["lineage_family_uid"] = "Family1"
            blocked = review_with_jitter_safety(
                trial.db, "PersonA", body_regions=trial.bodies,
                face_regions=trial.faces, source_lineage_rows=manifest,
                require_lineage_audit=True)
            self.assertTrue(blocked["candidate_results_suppressed"])
            self.assertEqual(blocked["proposals"], [])
            self.assertFalse(blocked["auto_confirm_allowed"])
        finally:
            trial.tearDown()


if __name__ == "__main__":
    unittest.main(verbosity=2)
