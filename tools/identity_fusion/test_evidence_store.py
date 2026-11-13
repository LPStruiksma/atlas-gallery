#!/usr/bin/env python3
"""Offline, made-up identities only. No native/V4 data, models or network."""
import math
from pathlib import Path
import sqlite3
import tempfile
import unittest

from evidence_store import (
    add_asset, add_observation, add_candidate_evidence,
    initialize, review_candidates
)


class IdentityEvidenceSafetyTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        initialize(self.db)
        add_asset(self.db, uid="photo-1",
                  exact_path="/storage/emulated/0/DCIM/Synthetic/example-1.jpg",
                  fingerprint_sha256="a" * 64)
        add_observation(self.db, uid="back-facing-1", asset_uid="photo-1",
                        region=(.1, .2, .4, .6), visibility="back",
                        occlusion_fraction=.3)

    def tearDown(self):
        self.db.close()

    def add(self, uid, pid, kind, group, polarity="supports"):
        add_candidate_evidence(
            self.db, uid=uid, observation_uid="back-facing-1",
            person_id=pid, family=kind, polarity=polarity,
            correlation_group=group,
            method_version="synthetic-v1", source_reference="fixture-observation"
        )

    def test_correlated_cues_do_not_multiply_a_session(self):
        self.add("shirt", 21, "clothing", "session-2018-01")
        self.add("body", 21, "body", "session-2018-01")
        self.add("nearby", 21, "context", "session-2018-01")
        report = review_candidates(self.db, "back-facing-1")
        assert report["unknown_always_allowed"]
        p = report["proposals"][0]
        self.assertEqual(p["independent_support_groups"], 1)
        self.assertEqual(p["evidence_families"], ["body", "clothing", "context"])
        self.assertEqual(p["confidence_probability"], None)
        self.assertEqual(p["review_only"], True)
        self.assertEqual(report["status"], "unconfirmed_suggestions_only")

    def test_temporal_independence_and_contradiction_are_not_hidden(self):
        self.add("same-session-shirt", 21, "clothing", "same-evening")
        self.add("different-camera-face", 21, "face", "independent-frame")
        self.add("negative-face", 21, "face", "contradiction",
                 polarity="contradicts")
        self.add("another-person", 23, "body", "body-evidence")
        p = review_candidates(self.db, "back-facing-1")
        by_id = {x["candidate_person_id"]: x for x in p["proposals"]}
        self.assertEqual(by_id[21]["independent_support_groups"], 2)
        self.assertEqual(by_id[21]["contradicting_groups"], 1)
        self.assertEqual(by_id[23]["independent_support_groups"], 1)
        self.assertEqual(p["total_candidates"], 2)
        self.assertTrue(p["unknown_always_allowed"])

    def test_hidden_runner_up_is_explicitly_tied_even_with_list_limit_one(self):
        # Two plausible subjects seen from behind: body+session supports
        # both equally. The smaller integer ID cannot become an identity.
        for pid in (73, 17):
            self.add("body-"+str(pid), pid, "body", "body-"+str(pid))
            self.add("session-"+str(pid), pid, "session", "session-"+str(pid))
        before = self.db.total_changes
        full = review_candidates(self.db, "back-facing-1", limit=12)
        single = review_candidates(self.db, "back-facing-1", limit=1)
        self.assertTrue(full["top_rank_tied_on_evidence"])
        self.assertTrue(single["top_rank_tied_on_evidence"])
        self.assertEqual(len(single["proposals"]), 1)
        self.assertEqual(single["total_candidates"], 2)
        self.assertTrue(single["numeric_person_id_is_presentation_tiebreak_only"])
        self.assertFalse(single["auto_confirm_allowed"])
        self.assertEqual(before, self.db.total_changes)
        # Reusing the same body correlation group cannot break a tie.
        self.add("same-group-shirt", 73, "clothing", "body-73")
        self.assertTrue(review_candidates(self.db, "back-facing-1")[
            "top_rank_tied_on_evidence"])
        # A truly distinct observation group *can* change the rank,
        # although its candidate is still unconfirmed.
        self.add("independent-day", 73, "body", "different-day")
        better = review_candidates(self.db, "back-facing-1")
        self.assertFalse(better["top_rank_tied_on_evidence"])
        self.assertEqual(better["proposals"][0]["candidate_person_id"], 73)
        self.assertFalse(better["auto_confirm_allowed"])

    def test_face_priority_breaks_support_count_tie_without_identity_claim(self):
        self.add("one-face", 80, "face", "face-from-other-day")
        self.add("face-context", 80, "session", "independent-time")
        self.add("other-body", 30, "body", "body-evidence")
        self.add("other-session", 30, "session", "other-evening")
        report = review_candidates(self.db, "back-facing-1")
        self.assertFalse(report["top_rank_tied_on_evidence"])
        self.assertEqual(report["proposals"][0]["candidate_person_id"], 80)
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertTrue(report["unknown_always_allowed"])

    def test_stronger_body_rank_cannot_hide_competing_face_in_short_list(self):
        for kind, grp in (("body", "body-camera"), ("session", "session-a"),
                          ("clothing", "different-outfit")):
            self.add("top-"+kind, 42, kind, grp)
        self.add("competing-face", 88, "face", "partial-face")
        before = self.db.total_changes
        result = review_candidates(self.db, "back-facing-1", limit=1)
        self.assertEqual(result["proposals"][0]["candidate_person_id"], 42)
        self.assertEqual(result["total_candidates"], 2)
        self.assertEqual(result["proposals"][0]["positive_face_support_groups"], 0)
        self.assertTrue(result["top_nonface_competing_face_evidence"])
        self.assertFalse(result["auto_confirm_allowed"])
        self.assertTrue(result["unknown_always_allowed"])
        self.assertEqual(before, self.db.total_changes)
        # A contradictory face cue for rival 88 invalidates it as an
        # alternative positive match; do not mask a safe unique hypothesis.
        self.add("contradict-face", 88, "face", "different-camera",
                 polarity="contradicts")
        safe = review_candidates(self.db, "back-facing-1", limit=1)
        self.assertFalse(safe["top_nonface_competing_face_evidence"])
        self.assertFalse(safe["auto_confirm_allowed"])

    def test_reading_candidates_is_read_only_and_limited(self):
        self.add("one", 21, "face", "different-photo")
        before = self.db.total_changes
        a = review_candidates(self.db, "back-facing-1")
        b = review_candidates(self.db, "back-facing-1")
        self.assertEqual(a, b)
        self.assertEqual(before, self.db.total_changes)
        with self.assertRaises(ValueError):
            review_candidates(self.db, "missing")
        with self.assertRaises(ValueError):
            review_candidates(self.db, "back-facing-1", limit=100000)

    def test_no_silent_reuse_of_identity_or_cross_photo_metadata(self):
        with self.assertRaises(sqlite3.IntegrityError):
            add_asset(self.db, uid="photo-1",
                      exact_path="/storage/emulated/0/DCIM/Other/moved.jpg")
        with self.assertRaises(sqlite3.IntegrityError):
            add_observation(self.db, uid="bad-foreign-key", asset_uid="missing",
                            region=(0,0,1,1), visibility="front",
                            occlusion_fraction=0)
        self.add("existing-evidence", 21, "face", "group")
        with self.assertRaises(sqlite3.IntegrityError):
            self.add("existing-evidence", 23, "body", "different-group")
        self.assertEqual(review_candidates(self.db, "back-facing-1")["total_candidates"], 1)

    def test_reject_invalid_geometry_and_unreconciled_paths(self):
        for region in [(0, 0, math.nan, .4), (.8, .8, .4, .4),
                       (0, 0, 0, 1), (0, 0, math.inf, 1)]:
            with self.assertRaises(ValueError):
                add_observation(self.db, uid="invalid", asset_uid="photo-1",
                                region=region, visibility="back",
                                occlusion_fraction=0)
        for path in ["/tmp/random.jpg", "../DCIM/foo.jpg",
                     "/storage/emulated/0/DCIM/../Other/move.jpg"]:
            with self.assertRaises(ValueError):
                add_asset(self.db, uid="untrusted", exact_path=path)
        for pid in [-1, 0, True]:
            with self.assertRaises(ValueError):
                self.add("invalid", pid, "face", "group")

    def test_unknown_occlusion_is_not_mistaken_for_unoccluded(self):
        add_observation(
            self.db, uid="visibility-missing", asset_uid="photo-1",
            region=(0.2,0.2,0.2,0.2), visibility="unknown",
            occlusion_fraction=None
        )
        self.assertEqual(
            self.db.execute(
                "SELECT occlusion_fraction,occlusion_known FROM observations "
                "WHERE observation_uid='visibility-missing'"
            ).fetchone(),
            (0.0,0)
        )
        self.assertEqual(
            self.db.execute(
                "SELECT occlusion_fraction,occlusion_known FROM observations "
                "WHERE observation_uid='back-facing-1'"
            ).fetchone(),
            (0.3,1)
        )

    def test_original_v1_store_migrates_additively_without_losing_observations(self):
        self.db.execute("ALTER TABLE observations DROP COLUMN occlusion_known")
        self.assertEqual(
            self.db.execute("SELECT COUNT(*) FROM observations").fetchone()[0],1
        )
        initialize(self.db)
        self.assertEqual(
            self.db.execute(
                "SELECT occlusion_known FROM observations "
                "WHERE observation_uid='back-facing-1'"
            ).fetchone(),
            (1,)
        )

    def test_schema_never_modifies_existing_v4_or_native_database(self):
        with tempfile.TemporaryDirectory(prefix="atlas-v5-safety-") as directory:
            db_path = Path(directory) / "synthetic-v4.db"
            with sqlite3.connect(db_path) as old:
                old.execute(
                    "CREATE TABLE assignments(face_id INTEGER PRIMARY KEY,person_id INTEGER)"
                )
                old.execute("INSERT INTO assignments VALUES(7,42)")
            initial_bytes = db_path.read_bytes()
            with sqlite3.connect(db_path) as old:
                with self.assertRaises(ValueError):
                    initialize(old)
                self.assertEqual(
                    old.execute("SELECT person_id FROM assignments").fetchone(), (42,)
                )
            self.assertEqual(db_path.read_bytes(), initial_bytes)
        initialize(self.db)  # Version 1 compatible re-open is idempotent.
        self.db.execute("PRAGMA user_version=2")
        with self.assertRaises(ValueError):
            initialize(self.db)


from test_region_correspondence import RegionCorrespondenceTests


from test_spatial_evidence_review import SpatialEvidenceReviewTests


from test_region_view_reprojection import ViewReprojectionTests


from test_audit_source_families import SourceFamilyAuditTests


from test_spatial_jitter_safety import SpatialJitterSafetyTests


if __name__ == "__main__":
    unittest.main(verbosity=2)
