#!/usr/bin/env python3
"""Fictional, model-free dependency stress cases; no photographed people."""
import json
import sqlite3
import unittest

from evidence_store import initialize, add_asset, add_observation, add_candidate_evidence
from audit_correlation_sensitivity import audit_correlation_sensitivity
from evaluate_review_quality import evaluate_review_quality


class CorrelationStressAuditTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        initialize(self.db)
        self.truth = []
        for label, truth, condition in [
            ("front", 21, "front"), ("back", 22, "back+occluded"),
            ("lookalike", None, "back+lookalike"), ("partial", 23, "partial"),
            ("wrongknown", 31, "back"), ("unknownnone", None, "unknown")
        ]:
            add_asset(self.db, uid="asset-"+label,
                      exact_path="/storage/emulated/0/DCIM/Synthetic/"+label+".jpg")
            add_observation(self.db, uid=label, asset_uid="asset-"+label,
                            region=(.1,.2,.5,.6), visibility="unknown",
                            occlusion_fraction=None)
            self.truth.append(dict(observation_uid=label, truth_person_id=truth,
                                   condition=condition))
        self.ordinal=0

    def tearDown(self):
        self.db.close()

    def cue(self, obs, person, family, group, source, polarity="supports"):
        self.ordinal += 1
        add_candidate_evidence(self.db, uid="fixture-"+str(self.ordinal),
                               observation_uid=obs, person_id=person,
                               family=family, polarity=polarity,
                               correlation_group=group, method_version="fictional-v1",
                               source_reference=source)

    def seed(self):
        self.cue("front",21,"face","photo-A","face-portrait-A")
        self.cue("front",21,"face","photo-B","face-portrait-B")
        self.cue("back",22,"body","body-photo","body-back-A")
        self.cue("back",22,"session","session-next","session-after")
        self.cue("lookalike",99,"body","misgroup-1","same-physical-source")
        self.cue("lookalike",99,"clothing","misgroup-2","same-physical-source")
        self.cue("partial",23,"face","partial-face","same-photo-1")
        self.cue("partial",23,"body","partial-body","same-photo-1")
        self.cue("partial",23,"session","other-day","other-day-2")
        self.cue("wrongknown",32,"body","false-build","wrong-A")
        self.cue("wrongknown",32,"session","false-context","wrong-B")

    def test_hidden_source_correlation_and_pessimistic_nonface_stress(self):
        self.seed()
        before = self.db.total_changes
        answer = audit_correlation_sensitivity(self.db, self.truth)
        self.assertEqual(self.db.total_changes, before)
        d = answer["scenarios"]
        self.assertEqual(set(d), {"declared_groups", "shared_source_joined",
                                  "nonface_collapsed",
                                  "shared_source_and_nonface_collapsed"})
        baseline = d["declared_groups"]["overall"]
        self.assertEqual(baseline["observations"], 6)
        self.assertEqual(baseline["correct_known_offers"], 3)
        self.assertEqual(baseline["wrong_known_offers"], 1)
        self.assertEqual(baseline["unknown_false_offers"], 1)
        joined = d["shared_source_joined"]["overall"]
        self.assertEqual(joined["unknown_false_offers"], 0)
        self.assertEqual(joined["correct_known_offers"], 3)
        self.assertEqual(joined["wrong_known_offers"], 1)
        self.assertEqual(d["shared_source_joined"]["versus_declared"][
            "retracted_baseline_offers"], 1)
        conservative = d["nonface_collapsed"]["overall"]
        self.assertEqual(conservative["review_offers"], 2)
        self.assertEqual(conservative["correct_known_offers"], 2)
        self.assertEqual(conservative["unknown_false_offers"], 0)
        self.assertEqual(conservative["wrong_known_offers"], 0)
        combined = d["shared_source_and_nonface_collapsed"]["overall"]
        self.assertEqual(combined["review_offers"], 1)
        self.assertEqual(combined["correct_known_offers"], 1)
        self.assertEqual(d["nonface_collapsed"]["versus_declared"][
            "retracted_baseline_offers"], 3)
        self.assertEqual(d["shared_source_and_nonface_collapsed"]["versus_declared"][
            "retracted_baseline_offers"], 4)
        self.assertFalse(answer["auto_confirm_allowed"])
        self.assertTrue(answer["unknown_always_allowed"])
        self.assertIsNone(answer["calibrated_identity_probability"])
        self.assertEqual(answer, audit_correlation_sensitivity(self.db, list(reversed(self.truth))))
        text = json.dumps(answer)
        for forbidden in ("asset-", "/storage/", "same-physical-source", "wrongknown",
                          "candidate_person_id", "observation_uid", "fixture-"):
            self.assertNotIn(forbidden, text)
        self.assertEqual(d["declared_groups"]["suppressed_intersections"], 6)

    def test_contradictory_face_cue_never_disappears_when_sources_collapsed(self):
        self.seed()
        self.cue("front",21,"face","contradictory-face-source","not-same-face",
                 polarity="contradicts")
        result = audit_correlation_sensitivity(self.db,self.truth)["scenarios"]
        for stage in result.values():
            self.assertEqual(stage["overall"]["correct_known_offers"],
                             2 if stage is result["declared_groups"]
                             or stage is result["shared_source_joined"] else
                             (1 if stage is result["nonface_collapsed"] else 0))

    def test_equal_rank_identity_groups_abstain_in_every_dependency_scenario(self):
        self.seed()
        # Two candidates have identical 2-source body+session rank but
        # different integer IDs. Every counterfactual must abstain instead
        # of manufacturing a correct/false offer from the smaller number.
        for pid in (99, 33):
            self.cue("unknownnone", pid, "body", "body-"+str(pid),
                     "body-camera-"+str(pid))
            self.cue("unknownnone", pid, "session", "session-"+str(pid),
                     "session-event-"+str(pid))
        start = self.db.total_changes
        scores = audit_correlation_sensitivity(self.db, self.truth)["scenarios"]
        self.assertEqual(start, self.db.total_changes)
        self.assertEqual(scores["declared_groups"]["overall"]["unknown_false_offers"], 1)
        self.assertEqual(scores["declared_groups"]["overall"][
            "correct_unknown_abstentions"], 1)
        self.assertEqual(scores["shared_source_joined"]["overall"][
            "unknown_false_offers"], 0)
        # A more independent candidate is reviewable, but only as a
        # hypothesis; we never save or confirm the identity.
        self.cue("unknownnone", 99, "body", "new-day-99", "new-photo-99")
        revised = audit_correlation_sensitivity(self.db, self.truth)
        self.assertEqual(revised["scenarios"]["declared_groups"]["overall"][
            "unknown_false_offers"], 2)
        self.assertFalse(revised["auto_confirm_allowed"])

    def test_other_candidates_partial_face_blocks_a_body_only_identity_offer(self):
        self.seed()
        self.cue("back", 99, "face", "different-partial-face",
                 "face-camera-new")
        before = self.db.total_changes
        result = audit_correlation_sensitivity(self.db, self.truth)
        actual = result["scenarios"]
        self.assertEqual(actual["declared_groups"]["overall"][
            "correct_known_offers"], 2)
        self.assertEqual(actual["shared_source_joined"]["overall"][
            "correct_known_offers"], 2)
        self.assertEqual(actual["declared_groups"]["overall"][
            "missed_known_abstentions"], 1)
        self.assertEqual(actual["declared_groups"]["overall"][
            "wrong_known_offers"], 1)
        self.assertEqual(before, self.db.total_changes)
        self.assertFalse(result["auto_confirm_allowed"])
        self.assertNotIn("face-camera-new", str(result))

    def test_reject_missing_truth_duplicate_trials_and_evidence_overflow(self):
        self.seed()
        invalids = (
            [], self.truth + self.truth[:1],
            [dict(observation_uid="front", condition="front")],
            [dict(observation_uid="front", truth_person_id=True, condition="front")],
            [dict(observation_uid="front", truth_person_id=21, condition="unknown+back")],
            [dict(observation_uid="nonexistent", truth_person_id=21, condition="front")],
        )
        for cases in invalids:
            with self.subTest(cases=len(cases)):
                with self.assertRaises(ValueError):
                    audit_correlation_sensitivity(self.db,cases)
        with self.assertRaises(ValueError):
            audit_correlation_sensitivity(self.db,self.truth,min_independent_groups=1)
        # Explicit limit: more than 100 distinct people must not silently
        # truncate an unknown-person evaluation into fake abstentions.
        for n in range(101):
            self.cue("unknownnone",500+n,"body","one-group","fake")
        with self.assertRaisesRegex(ValueError, "candidate"):
            audit_correlation_sensitivity(self.db,self.truth)

    def test_condition_marginals_are_overlapping_and_tiny_cells_suppressed(self):
        self.seed()
        report = audit_correlation_sensitivity(self.db,self.truth)["scenarios"]["declared_groups"]
        self.assertEqual(report["by_condition"], {})
        self.assertEqual(report["by_condition_tag"], {})
        self.assertEqual(report["suppressed_intersections"], 6)
        self.assertEqual(report["suppressed_condition_tags"], 6)
        self.assertEqual(report["overall"]["observations"], 6)


if __name__=="__main__":
    unittest.main(verbosity=2)
