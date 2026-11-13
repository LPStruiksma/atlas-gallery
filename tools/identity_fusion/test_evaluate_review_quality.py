#!/usr/bin/env python3
"""Entirely made-up evidence; no photos, embeddings or real private labels."""
import sqlite3
import unittest

from evidence_store import (initialize, add_asset, add_observation,
                            add_candidate_evidence, review_candidates)
from evaluate_review_quality import evaluate_review_quality


class ConservativeReviewMetricsTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        initialize(self.db)
        self.truth = []
        for uid, person, condition in [
            ("front-known", 21, "front"),
            ("back-known", 22, "back"),
            ("lookalike-unknown", None, "lookalike"),
            ("partial-known", 23, "partial"),
            ("back-with-contradiction", 24, "back"),
            ("unknown-no-cues", None, "unknown"),
            ("back-correlated-cues", 25, "back"),
        ]:
            add_asset(self.db, uid="asset-"+uid,
                      exact_path="/storage/emulated/0/DCIM/Synthetic/"+uid+".jpg")
            add_observation(self.db, uid=uid, asset_uid="asset-"+uid,
                            region=(0.1, 0.2, 0.4, 0.6), visibility="unknown",
                            occlusion_fraction=None)
            self.truth.append(dict(observation_uid=uid,
                                   truth_person_id=person, condition=condition))
        self.ordinal = 0

    def tearDown(self):
        self.db.close()

    def evidence(self, uid, person, family, group, polarity="supports"):
        self.ordinal += 1
        add_candidate_evidence(
            self.db, uid="fixture-%d" % self.ordinal, observation_uid=uid,
            person_id=person, family=family, polarity=polarity,
            correlation_group=group, method_version="synthetic-only",
            source_reference="fictional-cue"
        )

    def seed(self):
        self.evidence("front-known", 21, "face", "other-day-frame-A")
        self.evidence("front-known", 21, "face", "other-day-frame-B")
        self.evidence("back-known", 22, "body", "body-other-day")
        self.evidence("back-known", 22, "session", "separate-session")
        self.evidence("lookalike-unknown", 99, "body", "similar-build")
        self.evidence("lookalike-unknown", 99, "session", "misleading-event")
        self.evidence("partial-known", 23, "face", "partial-face")
        self.evidence("partial-known", 23, "session", "other-evening")
        self.evidence("back-with-contradiction", 24, "body", "body")
        self.evidence("back-with-contradiction", 24, "session", "session")
        self.evidence("back-with-contradiction", 24, "face", "different-photo",
                      polarity="contradicts")
        self.evidence("back-correlated-cues", 25, "clothing", "one-night")
        self.evidence("back-correlated-cues", 25, "context", "one-night")
        self.evidence("back-correlated-cues", 25, "body", "one-night")

    def test_four_evidence_ablations_and_unknown_errors(self):
        self.seed()
        before = self.db.total_changes
        report = evaluate_review_quality(self.db, self.truth)
        self.assertEqual(before, self.db.total_changes)
        self.assertEqual(report["status"], "research_metrics_only")
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertTrue(report["unknown_always_allowed"])
        self.assertIsNone(report["calibrated_probability"])
        variants = report["variants"]
        self.assertEqual(set(variants), {
            "face_only", "face_session", "face_body_session", "all_evidence"
        })
        face = variants["face_only"]["overall"]
        self.assertEqual((face["review_suggestions"], face["correct_person_suggestions"],
                          face["false_person_suggestions"]), (1,1,0))
        face_session = variants["face_session"]["overall"]
        self.assertEqual((face_session["review_suggestions"],
                          face_session["correct_person_suggestions"]), (2,2))
        body = variants["face_body_session"]["overall"]
        self.assertEqual((body["review_suggestions"], body["correct_person_suggestions"],
                          body["false_person_suggestions"]), (4,3,1))
        self.assertEqual(variants["all_evidence"]["overall"]["review_suggestions"], 4)
        self.assertEqual(variants["all_evidence"]["by_condition"]["back"]["observations"], 3)
        self.assertEqual(variants["all_evidence"]["by_condition"]["back"]["review_suggestions"], 1)
        self.assertEqual(variants["all_evidence"]["by_condition"]["lookalike"]["false_person_suggestions"], 1)
        self.assertEqual(variants["all_evidence"]["by_condition"]["unknown"]["correct_unknown_abstentions"], 1)
        self.assertEqual(body["coverage_fraction"], round(4/7, 4))
        self.assertEqual(body["false_suggestions_per_observation"], round(1/7, 4))
        self.assertEqual(body["false_suggestions_per_offer"], .25)
        # Distinguish unsafe offers to an Unknown person from a wrong
        # identification of a known person, and known people abstained on.
        self.assertEqual(body["known_truth_observations"], 5)
        self.assertEqual(body["unknown_truth_observations"], 2)
        self.assertEqual(body["wrong_known_person_suggestions"], 0)
        self.assertEqual(body["unknown_person_suggestions"], 1)
        self.assertEqual(body["missed_known_abstentions"], 2)
        self.assertEqual(body["unknown_false_offer_fraction"], .5)
        self.assertEqual(body["known_missed_abstention_fraction"], .4)
        self.assertEqual(
            body["false_person_suggestions"],
            body["unknown_person_suggestions"] + body["wrong_known_person_suggestions"]
        )
        self.assertEqual(
            body["abstentions"],
            body["correct_unknown_abstentions"] + body["missed_known_abstentions"]
        )
        self.assertEqual(
            variants["face_only"]["overall"]["unknown_false_offer_fraction"], 0.0
        )
        self.assertIsNone(
            variants["face_only"]["by_condition"]["front"]["unknown_false_offer_fraction"]
        )
        serialized = str(report)
        for sensitive in ("/storage/", "asset-", "front-known", "back-known",
                          "fictional-cue", "synthetic-only", "person_id"):
            self.assertNotIn(sensitive, serialized)
        self.assertEqual(report, evaluate_review_quality(self.db, self.truth))

    def test_wrong_known_identity_is_not_counted_as_unknown_false_offer(self):
        self.seed()
        # The confirmed truth is person 24, but its own face contradicts
        # the body+session support. A separate false person gets two groups.
        self.evidence("back-with-contradiction", 99, "body", "false-person-build")
        self.evidence("back-with-contradiction", 99, "session", "false-person-event")
        before = self.db.total_changes
        report = evaluate_review_quality(self.db, self.truth)
        body = report["variants"]["face_body_session"]["overall"]
        self.assertEqual(before, self.db.total_changes)
        self.assertEqual(body["review_suggestions"], 5)
        self.assertEqual(body["false_person_suggestions"], 2)
        self.assertEqual(body["unknown_person_suggestions"], 1)
        self.assertEqual(body["wrong_known_person_suggestions"], 1)
        self.assertEqual(body["missed_known_abstentions"], 1)
        self.assertEqual(body["correct_unknown_abstentions"], 1)
        self.assertEqual(body["known_missed_abstention_fraction"], .2)
        self.assertEqual(body["unknown_false_offer_fraction"], .5)

    def test_equal_strength_people_abstain_not_choose_smallest_numeric_id(self):
        self.seed()
        # Two equally plausible fictional people, each with body + session
        # from separately declared groups. Numeric person ID is not evidence.
        for pid in (77, 44):
            self.evidence("unknown-no-cues", pid, "body", "body-"+str(pid))
            self.evidence("unknown-no-cues", pid, "session", "event-"+str(pid))
        initial = self.db.total_changes
        out = evaluate_review_quality(self.db, self.truth)
        all_cues = out["variants"]["face_body_session"]["overall"]
        self.assertEqual(all_cues["review_suggestions"], 4)
        self.assertEqual(all_cues["ambiguous_evidence_abstentions"], 1)
        self.assertEqual(all_cues["unknown_person_suggestions"], 1)
        self.assertEqual(all_cues["correct_unknown_abstentions"], 1)
        self.assertEqual(self.db.total_changes, initial)
        self.assertNotIn("77", str(out))
        self.assertNotIn("44", str(out))
        # Multiple cues sharing one group do not manufacture independent
        # support or resolve the ambiguity.
        self.evidence("unknown-no-cues", 77, "clothing", "body-77")
        same_group = evaluate_review_quality(self.db, self.truth)
        self.assertEqual(same_group["variants"]["all_evidence"]["overall"][
            "ambiguous_evidence_abstentions"], 1)
        # A third independent body group changes the ranking and offers an
        # explicit *unconfirmed* candidate, not a People assignment.
        self.evidence("unknown-no-cues", 77, "body", "other-day-77")
        stronger = evaluate_review_quality(self.db, self.truth)
        self.assertEqual(stronger["variants"]["face_body_session"]["overall"][
            "ambiguous_evidence_abstentions"], 0)
        self.assertEqual(stronger["variants"]["face_body_session"]["overall"][
            "unknown_person_suggestions"], 2)
        self.assertFalse(stronger["auto_confirm_allowed"])

    def test_competing_face_source_vetoes_body_session_review_offer(self):
        self.seed()
        # Person 22 is inferred from two groups of body+session clues,
        # but a partial face positively supports a different candidate.
        # One positive face group is not enough to assert person 99,
        # nor may two non-face groups override it by simple counting.
        self.evidence("back-known", 99, "face", "partial-face-of-another")
        before = self.db.total_changes
        report = evaluate_review_quality(self.db, self.truth)
        body = report["variants"]["face_body_session"]["overall"]
        self.assertEqual(body["competing_face_evidence_abstentions"], 1)
        self.assertEqual(body["review_suggestions"], 3)
        self.assertEqual(body["correct_person_suggestions"], 2)
        self.assertEqual(body["unknown_person_suggestions"], 1)
        self.assertEqual(body["missed_known_abstentions"], 3)
        self.assertEqual(before, self.db.total_changes)
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertNotIn("partial-face-of-another", str(report))

    def test_candidate_list_truncation_must_fail_closed_not_fake_abstention(self):
        # review_candidates deliberately caps UI proposals at 100.
        # An evaluation with >100 distinct candidates must not silently
        # report an abstention after discarding potential eligible ones.
        for i in range(101):
            self.evidence("unknown-no-cues", 1000+i, "body",
                          "single-correlation-session")
        before = self.db.total_changes
        with self.assertRaisesRegex(ValueError, "truncated"):
            evaluate_review_quality(self.db, [
                dict(observation_uid="unknown-no-cues",
                     truth_person_id=None, condition="unknown")
            ])
        self.assertEqual(before, self.db.total_changes)

    def test_bad_truth_conditions_duplicate_trials_and_unsafe_thresholds_fail(self):
        self.seed()
        for cases in ([], self.truth+self.truth[:1],
                      [dict(observation_uid="front-known", condition="front")],
                      [dict(observation_uid="front-known", truth_person_id=21)],
                      [dict(truth_person_id=None, condition="unknown")],
                      [dict(observation_uid="front-known",
                            truth_person_id=True, condition="front")],
                      [dict(observation_uid="front-known",
                            truth_person_id=21, condition="made-up-private-tag")],
                      [dict(observation_uid="no-such-observation",
                            truth_person_id=21, condition="back")]):
            with self.assertRaises(ValueError):
                evaluate_review_quality(self.db, cases)
        with self.assertRaises(ValueError):
            evaluate_review_quality(self.db, self.truth, min_independent_groups=1)
        with self.assertRaises(ValueError):
            review_candidates(self.db, "front-known", allowed_families={"face"})
        with self.assertRaises(ValueError):
            review_candidates(self.db, "front-known",
                              allowed_families=frozenset({"unknown-family"}))
        face_only = review_candidates(
            self.db, "front-known", allowed_families=frozenset({"face"}))
        self.assertEqual(face_only["proposals"][0]["independent_support_groups"], 2)


    def test_paired_ablation_exposes_body_session_unknown_tradeoff(self):
        self.seed()
        before = self.db.total_changes
        report = evaluate_review_quality(self.db, self.truth)
        diff = report["matched_ablation"]["face_only_to_face_body_session"]
        self.assertEqual(diff["overall"]["observations"], 7)
        self.assertEqual(diff["overall"]["new_correct_known_offers"], 2)
        self.assertEqual(diff["overall"]["new_false_offers_to_unknown"], 1)
        self.assertEqual(diff["overall"]["new_wrong_known_offers"], 0)
        self.assertEqual(diff["overall"]["lost_correct_known_offers"], 0)
        self.assertIsNone(diff["overall"]["capture_component_diagnostics"])
        self.assertEqual(diff["suppressed_condition_count"], 5)
        self.assertEqual(diff["by_condition"], {})
        self.assertFalse(diff["causal_effect_of_body_or_session_established"])
        self.assertFalse(diff["auto_confirm_allowed"])
        self.assertEqual(before, self.db.total_changes)

    def test_capture_grouped_matched_ablation_collapses_correlated_errors(self):
        self.seed()
        # Eleven separate fictional Unknown frames from ONE declared photo
        # lineage each get an erroneous body+session proposal.
        cases = [dict(row, capture_component_uid="session-" + str(index))
                 for index, row in enumerate(self.truth)]
        for i in range(11):
            uid = "fictional-unknown-%d" % i
            add_asset(self.db, uid="asset-"+uid,
                      exact_path="/storage/emulated/0/DCIM/Synthetic/"+uid+".jpg")
            add_observation(self.db, uid=uid, asset_uid="asset-"+uid,
                            region=(.1, .2, .4, .6), visibility="back",
                            occlusion_fraction=None)
            self.evidence(uid, 99, "body", "fake-build-"+str(i))
            self.evidence(uid, 99, "session", "fake-session-"+str(i))
            cases.append(dict(observation_uid=uid, truth_person_id=None,
                              condition="lookalike",
                              capture_component_uid="one-declared-photo-lineage"))
        changes = self.db.total_changes
        report = evaluate_review_quality(self.db, cases)
        diff = report["matched_ablation"]["face_only_to_face_body_session"]
        self.assertEqual(diff["overall"]["new_false_offers_to_unknown"], 12)
        self.assertEqual(diff["overall"]["new_correct_known_offers"], 2)
        comp = diff["overall"]["capture_component_diagnostics"]
        self.assertEqual(comp["known_components"], 5)
        self.assertEqual(comp["unknown_components"], 3)
        self.assertEqual(comp["components_with_new_false_unknown_offer"], 2)
        self.assertFalse(comp["declared_components_proven_physically_independent"])
        self.assertTrue(comp["diagnostic_not_calibration_or_release_gate"])
        self.assertEqual(diff["by_condition"]["lookalike"]["observations"], 12)
        self.assertEqual(diff["by_condition"]["lookalike"][
            "new_false_offers_to_unknown"], 12)
        self.assertEqual(diff["by_condition"]["lookalike"][
            "capture_component_diagnostics"]["unknown_components"], 2)
        self.assertEqual(report, evaluate_review_quality(self.db, list(reversed(cases))))
        self.assertEqual(changes, self.db.total_changes)
        for opaque in ("one-declared-photo-lineage", "fictional-unknown",
                       "/storage/", "fake-build", "session-0"):
            self.assertNotIn(opaque, str(report))

    def test_partial_component_metadata_and_injected_keys_fail_closed(self):
        self.seed()
        cases = [dict(row, capture_component_uid="linked-source")
                 for row in self.truth]
        cases[0].pop("capture_component_uid")
        with self.assertRaisesRegex(ValueError, "Incomplete declared"):
            evaluate_review_quality(self.db, cases)
        for value in ("", "path/to/photo.jpg", "sp ace", "A" * 65,
                      True, 1, None):
            complete = [dict(row, capture_component_uid="valid")
                        for row in self.truth]
            complete[0]["capture_component_uid"] = value
            with self.subTest(value=value), self.assertRaisesRegex(
                    ValueError, "Invalid opaque reviewed"):
                evaluate_review_quality(self.db, complete)


if __name__ == "__main__":
    unittest.main(verbosity=2)
