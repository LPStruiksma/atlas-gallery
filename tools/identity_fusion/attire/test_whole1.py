import copy
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from audit_ort_admission import percentile, verify_regular_file, verify_rights_file
from evaluate_whole1 import ManifestError, evaluate


def pair(pair_id="p", label="match", score=.8, **changes):
    value = dict(pair_id=pair_id, label=label, face_proposed=True, score=score,
                 captured_a="2026-01-02T10:00:00Z", captured_b="2026-01-02T12:00:00Z",
                 event_a="event-1", event_b="event-1", camera_a="camera-1",
                 camera_b="camera-1", same_frame=False, shared_uniform=False,
                 outfit_change=False, detection_ok=True)
    value.update(changes)
    return value


def manifest(pairs):
    return {"schema": "atlas-whole-person-l1-eval-v1", "rights": "consented",
            "human_verified": True, "pairs": pairs}


class EvaluatorTests(unittest.TestCase):
    def test_question_metrics_are_denominator_explicit(self):
        report = evaluate(manifest([pair("a"), pair("b", "nonmatch", .9),
                                    pair("c", "nonmatch", .1)]), .5)
        self.assertEqual(report["augmented_question_candidate_recall"]["numerator"], 1)
        self.assertEqual(report["wrong_person_question_rate"]["denominator"], 2)
        self.assertEqual(report["question_precision"]["value"], .5)
        self.assertEqual(report["actual_identity_false_merges"]["value"], 0)

    def test_non_face_only_pair_cannot_be_created(self):
        report = evaluate(manifest([pair(face_proposed=False)]), .5)
        self.assertEqual(report["counts"]["eligible_match"], 1)
        self.assertEqual(report["baseline_face_question_recall"]["numerator"], 0)
        self.assertEqual(report["abstentions"], {"no_face_proposal": 1})

    def test_all_safety_abstentions(self):
        pairs = [pair("frame", same_frame=True), pair("uniform", shared_uniform=True),
                 pair("outfit", outfit_change=True), pair("detect", detection_ok=False),
                 pair("event", event_b="event-2"), pair("camera", camera_b="camera-2"),
                 pair("time", captured_b="2026-01-02T13:00:01Z")]
        report = evaluate(manifest(pairs), .5)
        self.assertEqual(report["counts"]["abstained"], 7)
        self.assertEqual(report["counts"]["questions"], 0)

    def test_rejects_unlicensed_noisy_and_duplicate_input(self):
        bad = manifest([pair()])
        bad["rights"] = "unknown"
        with self.assertRaises(ManifestError): evaluate(bad, .5)
        with self.assertRaises(ManifestError): evaluate(manifest([pair(score=float("nan"))]), .5)
        with self.assertRaises(ManifestError): evaluate(manifest([pair(), pair()]), .5)
        with self.assertRaises(ManifestError): evaluate(manifest([pair(captured_a="ambiguous")]), .5)


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
