#!/usr/bin/env python3
"""Purely fictional geometry tests; no photographs, photosets or models."""
import json
import math
import unittest

from region_correspondence import audit_region_correspondence


def body(asset, uid, x, y, w, h):
    return dict(asset_uid=asset, observation_uid=uid,
                x=x, y=y, w=w, h=h)


def face(asset, uid, x, y, w, h):
    return dict(asset_uid=asset, face_uid=uid,
                x=x, y=y, w=w, h=h)


class RegionCorrespondenceTests(unittest.TestCase):
    def sample(self):
        bodies = [
            body("Asset001", "PersonAreaA", .05, .1, .35, .80),
            body("Asset001", "PersonAreaB", .60, .1, .33, .80),
        ]
        faces = [
            face("Asset001", "FaceA", .15, .19, .10, .12),
            face("Asset001", "FaceB", .70, .19, .10, .12),
        ]
        return bodies, faces

    def test_disjoint_person_regions_allow_geometric_review_not_identity(self):
        people, faces = self.sample()
        original_people = [dict(x) for x in people]
        original_faces = [dict(x) for x in faces]
        audit = audit_region_correspondence(people, faces)
        self.assertEqual(audit.for_face("FaceA").observation_uid, "PersonAreaA")
        self.assertEqual(audit.for_face("FaceB").observation_uid, "PersonAreaB")
        self.assertEqual(audit.for_face("FaceA").status, "geometry_candidate_only")
        self.assertEqual(audit.aggregate["geometry_candidate_only"], 2)
        self.assertEqual(audit.aggregate["ambiguous"], 0)
        self.assertFalse(audit.aggregate["identity_confirmed"])
        self.assertFalse(audit.aggregate["auto_confirm_allowed"])
        self.assertFalse(audit.aggregate["physical_face_body_identity_proven"])
        for token in ("Asset001", "PersonAreaA", "FaceA", "face_uid",
                      "observation_uid", "/storage/"):
            self.assertNotIn(token, json.dumps(audit.aggregate))
        self.assertEqual(people, original_people)
        self.assertEqual(faces, original_faces)
        self.assertEqual(audit, audit_region_correspondence(
            list(reversed(people)), list(reversed(faces))))

    def test_overlapping_people_face_between_them_must_be_ambiguous(self):
        people = [
            body("Asset001", "LeftBody", .10, .1, .45, .8),
            body("Asset001", "RightBody", .35, .1, .45, .8),
        ]
        f = [face("Asset001", "OnePartialFace", .41, .18, .08, .10)]
        result = audit_region_correspondence(people, f)
        self.assertEqual(result.aggregate["ambiguous"], 1)
        self.assertIsNone(result.for_face("OnePartialFace").observation_uid)
        # A nested detector box is also two plausible human regions.
        people[1] = body("Asset001", "NestedBody", .3, .12, .3, .65)
        result = audit_region_correspondence(people, f)
        self.assertEqual(result.aggregate["ambiguous"], 1)

    def test_face_bbox_crosses_second_person_despite_unique_center(self):
        people = [
            body("Asset001", "PersonA", .10, .10, .43, .80),
            body("Asset001", "PersonB", .52, .10, .35, .80),
        ]
        # Face center (.50) lies in A, but over 20% of its width overlaps B:
        # center-only matching would have made a false confident link.
        f = [face("Asset001", "CrossesBorder", .465, .19, .07, .12)]
        out = audit_region_correspondence(people, f)
        self.assertEqual(out.aggregate["ambiguous"], 1)
        self.assertIsNone(out.for_face("CrossesBorder").observation_uid)

    def test_back_facing_person_without_visible_face_is_valid_empty_audit(self):
        people = [body("RearViewPhoto", "BodyOnly", .2, .1, .45, .75)]
        audit = audit_region_correspondence(people, [])
        self.assertEqual(audit.aggregate["person_regions"], 1)
        self.assertEqual(audit.aggregate["face_regions"], 0)
        self.assertEqual(audit.aggregate["geometry_candidate_only"], 0)
        self.assertFalse(audit.aggregate["identity_confirmed"])
        self.assertEqual(audit.decisions, ())

    def test_duplicate_face_detections_and_multiple_faces_per_body_refuse(self):
        people = [body("Asset001", "Body", .1, .1, .7, .8)]
        duplicates = [
            face("Asset001", "DetectorA", .2, .18, .12, .12),
            face("Asset001", "DetectorB", .205, .18, .12, .12),
        ]
        out = audit_region_correspondence(people, duplicates)
        self.assertEqual(out.aggregate["ambiguous"], 2)
        for f in ("DetectorA", "DetectorB"):
            self.assertIsNone(out.for_face(f).observation_uid)
        # Two genuinely distinct faces in one body box likely indicate
        # occlusion / a false bounding box / multiple people, not one body.
        separate = [
            face("Asset001", "FaceLeft", .20, .18, .10, .10),
            face("Asset001", "FaceRight", .55, .18, .10, .10),
        ]
        out = audit_region_correspondence(people, separate)
        self.assertEqual(out.aggregate["ambiguous"], 2)

    def test_cross_asset_and_outside_face_are_unmatched_not_claimed(self):
        people = [body("FrameOne", "Body", .1, .1, .6, .8)]
        faces = [
            face("DifferentFrame", "FaceElsewhere", .2, .18, .12, .12),
            face("FrameOne", "Outside", .8, .2, .10, .10),
        ]
        out = audit_region_correspondence(people, faces)
        self.assertEqual(out.aggregate["unmatched"], 2)
        self.assertIsNone(out.for_face("FaceElsewhere").observation_uid)
        self.assertIsNone(out.for_face("Outside").observation_uid)

    def test_low_face_or_border_clip_is_uncertain_and_does_not_autolink(self):
        people = [body("Asset001", "PersonRegion", .1, .1, .6, .8)]
        faces = [
            face("Asset001", "LowerTorsoFace", .30, .72, .10, .10),
            face("Asset001", "BorderFace", .1, .18, .10, .10),
        ]
        out = audit_region_correspondence(people, faces)
        self.assertEqual(out.aggregate["uncertain"], 2)
        for item in out.decisions:
            self.assertIsNone(item.observation_uid)
            self.assertFalse(item.identity_confirmed)

    def test_other_frame_face_with_identical_geometry_does_not_mix(self):
        p = [
            body("Asset001", "AreaA", .1, .1, .6, .8),
            body("Asset002", "AreaB", .1, .1, .6, .8),
        ]
        f = [
            face("Asset001", "F1", .3, .18, .10, .10),
            face("Asset002", "F2", .3, .18, .10, .10),
        ]
        out = audit_region_correspondence(p, f)
        self.assertEqual(out.aggregate["geometry_candidate_only"], 2)
        self.assertEqual(out.for_face("F1").observation_uid, "AreaA")
        self.assertEqual(out.for_face("F2").observation_uid, "AreaB")

    def test_invalid_geometry_private_paths_duplicates_and_unbounded_input(self):
        people, faces = self.sample()
        mutations = [
            [dict(people[0], x=float("nan")), people[1]],
            [dict(people[0], y=float("inf")), people[1]],
            [dict(people[0], w=0), people[1]],
            [dict(people[0], w=.99), people[1]],
            [dict(people[0], x=True), people[1]],
            [dict(people[0], local_path="/storage/private.jpg"), people[1]],
            [dict(people[0], asset_uid="../../secret"), people[1]],
            [people[0], dict(people[1], observation_uid="PersonAreaA")],
            [people[0]] * 129,
            [],
        ]
        for i, changed in enumerate(mutations):
            with self.subTest(body_case=i):
                with self.assertRaises(ValueError):
                    audit_region_correspondence(changed, faces)
        bad_faces = [
            [faces[0], dict(faces[1], face_uid="FaceA")],
            [dict(faces[0], asset_uid="photo/private.jpg"), faces[1]],
            [dict(faces[0], h=-.1), faces[1]],
            [dict(faces[0], x=float("nan")), faces[1]],
            [dict(faces[0], coordinate_hint="private"), faces[1]],
            faces * 65,
        ]
        for i, altered in enumerate(bad_faces):
            with self.subTest(face_case=i):
                with self.assertRaises(ValueError):
                    audit_region_correspondence(people, altered)
        with self.assertRaises(ValueError):
            audit_region_correspondence(people, faces).for_face("NoFace")


if __name__ == "__main__":
    unittest.main(verbosity=2)
