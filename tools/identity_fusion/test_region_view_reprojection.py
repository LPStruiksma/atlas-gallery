#!/usr/bin/env python3
"""Synthetic, image-free EXIF-quadrant/mirror/crop ROI parity tests."""
import json
import random
import sqlite3
import unittest

from evidence_store import (
    initialize, add_asset, add_observation, add_candidate_evidence
)
from region_view_reprojection import reproject_regions, review_with_view_coordinates


def body(uid, x, y, w, h, asset="Asset001"):
    return dict(asset_uid=asset, observation_uid=uid, x=x, y=y, w=w, h=h)


def face(uid, x, y, w, h, asset="Asset001"):
    return dict(asset_uid=asset, face_uid=uid, x=x, y=y, w=w, h=h)


class ViewReprojectionTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        initialize(self.db)
        add_asset(self.db, uid="Asset001",
                  exact_path="/storage/emulated/0/DCIM/Synthetic/never_real.jpg")
        self.people = [
            body("PersonA", .10, .10, .40, .80),
            body("PersonB", .60, .10, .32, .80),
        ]
        self.faces = [
            face("FaceA", .20, .19, .12, .14),
            face("FaceB", .69, .19, .10, .14),
        ]
        for p in self.people:
            add_observation(
                self.db, uid=p["observation_uid"], asset_uid=p["asset_uid"],
                region=tuple(p[k] for k in ("x", "y", "w", "h")),
                visibility="partial", occlusion_fraction=None)
        self.add_face_source = lambda source: add_candidate_evidence(
            self.db, uid="FaceEvidence1", observation_uid="PersonA",
            person_id=101, family="face", polarity="supports",
            correlation_group="IndependentFaceCue",
            method_version="artificial-v1", source_reference=source)
        add_candidate_evidence(
            self.db, uid="BodyEvidence1", observation_uid="PersonA",
            person_id=101, family="body", polarity="supports",
            correlation_group="BodyGroup", method_version="artificial-v1",
            source_reference="BodyCue")

    def tearDown(self):
        self.db.close()

    def _view(self, *, crop=(0, 0, 1, 1), rotation=0, mirror=False,
              bodies=None, faces=None):
        kwargs = dict(crop=crop, rotation=rotation, mirror=mirror,
                      direction="source_to_view")
        return (
            reproject_regions(
                self.people if bodies is None else bodies, is_face=False, **kwargs),
            reproject_regions(
                self.faces if faces is None else faces, is_face=True, **kwargs)
        )

    def test_all_eight_dihedral_orientations_preserve_review_only_association(self):
        self.add_face_source("FaceA")
        start = self.db.total_changes
        for rotation in (0, 90, 180, 270):
            for mirror in (False, True):
                with self.subTest(rotation=rotation, mirror=mirror):
                    view_bodies, view_faces = self._view(
                        rotation=rotation, mirror=mirror)
                    result = review_with_view_coordinates(
                        self.db, "PersonA", view_body_regions=view_bodies,
                        view_face_regions=view_faces,
                        rotation=rotation, mirror=mirror, limit=1)
                    self.assertEqual(result["face_evidence_geometry_matched"], 1)
                    self.assertEqual(result["status"], "unconfirmed_suggestions_only")
                    self.assertEqual(result["proposals"][0]["candidate_person_id"], 101)
                    self.assertFalse(result["candidate_results_suppressed"])
                    self.assertTrue(result["reprojection_applied"])
                    self.assertFalse(
                        result["orientation_and_crop_metadata_independently_verified"])
                    self.assertFalse(result["arbitrary_angle_rotation_supported"])
                    self.assertFalse(result["physical_face_body_identity_proven"])
                    self.assertFalse(result["auto_confirm_allowed"])
                    self.assertNotIn("FaceA", json.dumps(result))
        self.assertEqual(start, self.db.total_changes)

    def test_nonclipping_crop_then_rotation_still_roundtrips_to_original_asset(self):
        self.add_face_source("FaceA")
        crop = (.05, .05, .90, .90)
        for rotation in (0, 90, 180, 270):
            with self.subTest(rotation=rotation):
                vb, vf = self._view(crop=crop, rotation=rotation, mirror=True)
                result = review_with_view_coordinates(
                    self.db, "PersonA", view_body_regions=vb,
                    view_face_regions=vf, source_crop=crop,
                    rotation=rotation, mirror=True)
                self.assertEqual(result["face_evidence_geometry_matched"], 1)
                self.assertFalse(result["auto_confirm_allowed"])

    def test_wrong_orientation_cannot_be_accepted_as_matching_stored_geometry(self):
        self.add_face_source("FaceA")
        vb, vf = self._view(rotation=90)
        with self.assertRaisesRegex(ValueError, "stored"):
            review_with_view_coordinates(
                self.db, "PersonA", view_body_regions=vb,
                view_face_regions=vf, rotation=0)
        vb, vf = self._view(mirror=True)
        with self.assertRaisesRegex(ValueError, "stored"):
            review_with_view_coordinates(
                self.db, "PersonA", view_body_regions=vb,
                view_face_regions=vf, mirror=False)

    def test_crop_clipping_person_roi_cannot_borrow_old_face_identity(self):
        self.add_face_source("FaceA")
        cropped = (.20, 0, .80, 1)
        with self.assertRaisesRegex(ValueError, "clipped"):
            self._view(crop=cropped)
        # Simulated detector sees only the visible partial body. Mapping
        # that partial ROI back cannot reproduce the original stored body.
        partial = [
            body("PersonA", 0, .10, .375, .80),
            body("PersonB", .50, .10, .40, .80),
        ]
        visible_faces = [
            face("FaceA", 0, .19, .15, .14),
            face("FaceB", (.69-.20)/.80, .19, .125, .14),
        ]
        with self.assertRaisesRegex(ValueError, "stored"):
            review_with_view_coordinates(
                self.db, "PersonA", view_body_regions=partial,
                view_face_regions=visible_faces, source_crop=cropped)

    def test_back_facing_no_face_is_valid_unconfirmed_not_unknown_proof(self):
        vb, _ = self._view(rotation=270, mirror=True)
        result = review_with_view_coordinates(
            self.db, "PersonA", view_body_regions=vb,
            view_face_regions=[], rotation=270, mirror=True)
        self.assertEqual(result["face_evidence_rows"], 0)
        self.assertEqual(result["spatial_guard_status"],
                         "no_same_frame_face_evidence")
        self.assertTrue(result["unknown_always_allowed"])
        self.assertFalse(result["auto_confirm_allowed"])
        self.assertEqual(len(result["proposals"]), 1)

    def test_competing_person_face_remains_rejected_after_rotation(self):
        self.add_face_source("FaceB")
        for rotation in (0, 90, 180, 270):
            vb, vf = self._view(rotation=rotation)
            result = review_with_view_coordinates(
                self.db, "PersonA", view_body_regions=vb,
                view_face_regions=vf, rotation=rotation)
            self.assertEqual(result["status"], "manual_region_review_required")
            self.assertEqual(result["proposals"], [])
            self.assertTrue(result["candidate_results_suppressed"])
            self.assertFalse(result["auto_confirm_allowed"])

    def test_seeded_roundtrip_randomized_rectangles_never_change_dimensions(self):
        rng = random.Random(20261017)
        for _ in range(40):
            x, y = rng.uniform(0, .7), rng.uniform(0, .7)
            w, h = rng.uniform(.02, min(.25, 1-x)), rng.uniform(.02, min(.25, 1-y))
            source = [body("RandomBody", x, y, w, h)]
            crop = (0, 0, 1, 1)
            for angle in (0, 90, 180, 270):
                for mirror in (False, True):
                    forward = reproject_regions(
                        source, is_face=False, crop=crop,
                        rotation=angle, mirror=mirror,
                        direction="source_to_view")
                    backward = reproject_regions(
                        forward, is_face=False, crop=crop,
                        rotation=angle, mirror=mirror,
                        direction="view_to_source")
                    for axis in ("x", "y", "w", "h"):
                        self.assertAlmostEqual(source[0][axis], backward[0][axis],
                                               delta=1e-12)

    def test_fails_closed_on_arbitrary_angles_unsafe_crop_or_private_columns(self):
        vb, vf = self._view()
        for rotation in (45, -90, 360, True, 90.0, "90"):
            with self.subTest(rotation=rotation):
                with self.assertRaises(ValueError):
                    reproject_regions(vb, is_face=False, rotation=rotation)
        for crop in ((0, 0, 0, 1), (.3, .2, .8, 1),
                     (float("nan"), 0, 1, 1), (0, 0, True, 1),
                     (0, 0, 1), "full"):
            with self.subTest(crop=str(crop)):
                with self.assertRaises(ValueError):
                    reproject_regions(vb, is_face=False, crop=crop)
        for changed in ([dict(vb[0], private_path="/storage/hidden.jpg"), vb[1]],
                        [dict(vb[0], x=float("nan")), vb[1]],
                        [dict(vb[0], w=0), vb[1]]):
            with self.assertRaises(ValueError):
                reproject_regions(changed, is_face=False)
        with self.assertRaises(ValueError):
            reproject_regions(vf, is_face=True, mirror=1)
        with self.assertRaises(ValueError):
            reproject_regions(vf, is_face=True, direction="unsupported")
        with self.assertRaises(ValueError):
            reproject_regions(vb, is_face=1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
