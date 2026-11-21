import copy
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from audit_ort_admission import percentile, verify_regular_file, verify_rights_file
from evaluate_whole1 import ManifestError, evaluate


def pair(pair_id="p", label="match", score=.8, **changes):
    value = dict(pair_id=pair_id, queue_id="queue-1", label=label,
                 visibility="clear", face_proposed=True, face_score=.6, score=score,
                 captured_a="2026-01-02T10:00:00Z", captured_b="2026-01-02T12:00:00Z",
                 event_a="event-1", event_b="event-1", camera_a="camera-1",
                 camera_b="camera-1", same_frame=False, shared_uniform=False,
                 outfit_change=False, detection_ok=True)
    value.update(changes)
    return value


def manifest(pairs):
    return {"schema": "atlas-whole-person-l1-eval-v2", "rights": "consented",
            "human_verified": True, "pairs": pairs}


class EvaluatorTests(unittest.TestCase):
    def test_question_metrics_are_denominator_explicit(self):
        report = evaluate(manifest([pair("a"), pair("b", "nonmatch", .9, face_score=.8),
                                    pair("c", "nonmatch", .1, face_score=.1)]), .5, 2)
        self.assertEqual(report["augmented_question_candidate_recall"]["numerator"], 1)
        self.assertEqual(report["wrong_person_question_rate"]["denominator"], 2)
        self.assertEqual(report["question_precision"]["value"], .5)
        self.assertEqual(report["actual_identity_false_merges"]["value"], 0)
        self.assertEqual(report["counts"]["eligible_match"] + report["counts"]["eligible_nonmatch"] + report["counts"]["abstained"], 3)

    def test_non_face_only_pair_cannot_be_created(self):
        report = evaluate(manifest([pair(face_proposed=False, face_score=None)]), .5, 1)
        self.assertEqual(report["counts"]["eligible_match"], 0)
        self.assertEqual(report["counts"]["cohort_match"], 1)
        self.assertEqual(report["counts"]["abstained"], 1)
        self.assertEqual(report["baseline_face_question_recall"]["denominator"], 1)
        self.assertEqual(report["augmented_question_candidate_recall"]["numerator"], 0)
        self.assertEqual(report["abstentions"], {"no_face_proposal": 1})

    def test_fixed_budget_ranking_lift_on_existing_face_pairs(self):
        rows = [pair("real", score=1., face_score=.5, visibility="rear"),
                pair("decoy", "nonmatch", score=.1, face_score=.7),
                pair("out-of-face", score=1., face_proposed=False, face_score=None,
                     visibility="small")]
        report = evaluate(manifest(rows), .5, 1)
        self.assertEqual(report["baseline_face_question_recall"]["numerator"], 0)
        self.assertEqual(report["augmented_question_candidate_recall"]["numerator"], 1)
        self.assertEqual(report["augmented_question_candidate_recall"]["denominator"], 2)
        self.assertEqual(report["stratified_budgeted_recall"]["rear"]["attire_reranked"]["numerator"], 1)
        self.assertEqual(report["stratified_budgeted_recall"]["small"]["attire_reranked"]["numerator"], 0)
        self.assertEqual(report["true_pair_rank_improvement"]["improved"], 1)
        self.assertEqual(report["wrong_person_question_rate"]["value"], 0)
        self.assertEqual(report["counts"]["input"], 3)
        self.assertEqual(report["counts"]["abstained"], 1)
        self.assertEqual(report["counts"]["eligible_match"] + report["counts"]["eligible_nonmatch"] + report["counts"]["abstained"], 3)

    def test_budget_is_per_queue_and_ties_are_stable(self):
        rows = [pair("z", score=.5, face_score=.5), pair("a", "nonmatch", .5, face_score=.5),
                pair("q2", queue_id="queue-2", face_score=.1)]
        report = evaluate(manifest(rows), .5, 1)
        self.assertEqual(report["counts"]["queues"], 2)
        self.assertEqual(report["counts"]["questions"], 2)
        self.assertEqual(report["question_precision"]["numerator"], 1)
        self.assertEqual(report["true_pair_rank_improvement"]["improved"], 0)

    def test_all_safety_abstentions(self):
        rows = [pair("frame", same_frame=True), pair("uniform", shared_uniform=True),
                pair("outfit", outfit_change=True), pair("detect", detection_ok=False),
                pair("event", event_b="event-2"), pair("camera", camera_b="camera-2"),
                pair("time", captured_b="2026-01-02T13:00:01Z")]
        report = evaluate(manifest(rows), .5, 1)
        self.assertEqual(report["counts"]["abstained"], 7)
        self.assertEqual(report["counts"]["questions"], 0)
        self.assertEqual(report["counts"]["eligible_match"], 0)

    def test_missing_blank_or_wrong_type_provenance_is_rejected(self):
        for field in ("event_a", "event_b", "camera_a", "camera_b", "queue_id"):
            for value in (None, "", "  ", 42, []):
                with self.subTest(field=field, value=value):
                    with self.assertRaises(ManifestError):
                        evaluate(manifest([pair(**{field: value})]), .5, 1)

    def test_rejects_unlicensed_noisy_and_duplicate_input(self):
        bad = manifest([pair()])
        bad["rights"] = "unknown"
        with self.assertRaises(ManifestError): evaluate(bad, .5, 1)
        with self.assertRaises(ManifestError): evaluate(manifest([pair(score=float("nan"))]), .5, 1)
        with self.assertRaises(ManifestError): evaluate(manifest([pair(), pair()]), .5, 1)
        with self.assertRaises(ManifestError): evaluate(manifest([pair(captured_a="ambiguous")]), .5, 1)
        with self.assertRaises(ManifestError): evaluate(manifest([pair(captured_a="2026-01-02T10:00:00")]), .5, 1)
        with self.assertRaises(ManifestError): evaluate(manifest([pair(face_score=True)]), .5, 1)
        with self.assertRaises(ManifestError): evaluate(manifest([pair(visibility="side")]), .5, 1)
        with self.assertRaises(ManifestError): evaluate(manifest([pair(face_proposed=False)]), .5, 1)
        for value in (0, -1, True, 1.5):
            with self.assertRaises(ManifestError): evaluate(manifest([pair()]), .5, value)


class AdmissionHelpersTests(unittest.TestCase):
    def test_exact_byte_verification_and_size(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "model.onnx")
            path.write_bytes(b"fictional-not-a-graph")
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual(verify_regular_file(path, digest), 21)
            with self.assertRaises(ValueError): verify_regular_file(path, "0" * 64)

    def test_percentile_is_bounded_and_deterministic(self):
        self.assertEqual(percentile([5, 1, 4, 2, 3], .95), 4)

    def test_rights_approval_is_bound_to_exact_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "rights.txt")
            digest = "a" * 64
            path.write_text(f"APPROVED FOR LOCAL EVALUATION\n{digest}\n", encoding="utf-8")
            verify_rights_file(path, digest)
            with self.assertRaises(ValueError): verify_rights_file(path, "b" * 64)


if __name__ == "__main__":
    unittest.main()
