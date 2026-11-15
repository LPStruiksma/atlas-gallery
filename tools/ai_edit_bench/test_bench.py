"""AIE-9 regression suite. Compatible with pytest and stdlib unittest."""
from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path

from tools.ai_edit_bench.bench import run, scorecards
from tools.ai_edit_bench.corpus import PROMPTS, generate_manifest, materialize, read_manifest
from tools.ai_edit_bench.review import create_pairwise, decode_votes


class BenchmarkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = generate_manifest()

    def test_manifest_has_156_diverse_and_sensitive_cases(self):
        self.assertEqual(len(self.cases), 156)
        categories = {c["category"] for c in self.cases}
        self.assertEqual(categories, set(PROMPTS) | {"lawful_sensitive"})
        for category in categories:
            subset = [c for c in self.cases if c["category"] == category]
            self.assertEqual(len(subset), 12)
            self.assertEqual(len({c["instruction"] for c in subset}), 12)
            self.assertTrue(any(c["difficulty"] == "hard" for c in subset))
        self.assertEqual(sum(c["sensitive"] for c in self.cases), 12)

    def test_materialized_source_and_mask_match_manifest_digests(self):
        case = self.cases[0]
        with tempfile.TemporaryDirectory() as temp:
            source, mask = materialize(case, Path(temp))
            self.assertTrue(source.read_bytes().startswith(b"\x89PNG"))
            self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(),
                             case["source"]["sha256"])
            self.assertEqual(hashlib.sha256(mask.read_bytes()).hexdigest(),
                             case["edit_mask"]["sha256"])

    def test_tampered_and_duplicate_manifests_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / "manifest.json"
            bad = [dict(self.cases[0])]
            bad[0]["source"] = dict(bad[0]["source"], sha256="a" * 64)
            file.write_text(json.dumps(bad), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "mismatch"):
                read_manifest(file)
            file.write_text(json.dumps([self.cases[0], self.cases[0]]), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate"):
                read_manifest(file)

    def test_refusals_degradations_and_errors_are_not_quality(self):
        cases = self.cases[:4]
        provider = "offline-test"
        base = {"provider": provider, "degraded": False, "accepted": False,
                "cost_usd": 0.25, "latency_ms": 20}
        records = [
            dict(base, case_id=cases[0]["id"], outcome="completed",
                 adherence=0.8, accepted=True, unchanged_ssim=0.9),
            dict(base, case_id=cases[1]["id"], outcome="completed",
                 adherence=0.1, degraded=True),
            dict(base, case_id=cases[2]["id"], outcome="refused"),
            dict(base, case_id=cases[3]["id"], outcome="error"),
        ]
        score = scorecards(cases, records)
        q = score["quality_scorecard"][provider]
        restriction = score["restriction_scorecard"][provider]["object_add"]
        self.assertEqual(q["eligible_completed"], 1)
        self.assertEqual(q["metrics"]["adherence"]["n"], 1)
        self.assertAlmostEqual(q["metrics"]["adherence"]["mean"], 0.8)
        self.assertEqual(q["metrics"]["unchanged_ssim"]["n"], 1)
        self.assertIsNone(q["metrics"]["identity_similarity"]["mean"])
        # All attempts were billed, but only one human-accepted edit succeeded.
        self.assertEqual(q["cost_per_accepted_usd"], 1.0)
        self.assertEqual(restriction["attempts"], 4)
        self.assertEqual(restriction["refusals"], 1)
        self.assertEqual(restriction["degraded"], 1)
        self.assertEqual(restriction["errors"], 1)
        self.assertEqual(restriction["refusal_rate"], 0.25)

    def test_unknown_price_cannot_be_misreported_as_free(self):
        cases = self.cases[:2]
        r = [
            {"case_id": cases[0]["id"], "provider": "offline", "outcome": "completed",
             "accepted": True, "cost_usd": 0.2},
            {"case_id": cases[1]["id"], "provider": "offline", "outcome": "refused"},
        ]
        q = scorecards(cases, r)["quality_scorecard"]["offline"]
        self.assertIsNone(q["cost_usd_total"])
        self.assertIsNone(q["cost_per_accepted_usd"])
        self.assertEqual(q["unpriced_attempts"], 1)

    def test_malformed_values_and_duplicates_fail_closed(self):
        case = self.cases[0]
        base = {"case_id": case["id"], "provider": "p", "outcome": "completed"}
        invalid = [
            dict(base, adherence=1.2),
            dict(base, cost_usd=-1),
            dict(base, latency_ms=float("nan")),
            dict(base, accepted=1),
            dict(base, artifact_tag="maybe"),
            dict(base, outcome="refused", adherence=0.5),
            dict(base, degraded="yes"),
        ]
        for record in invalid:
            with self.subTest(record=record), self.assertRaises(ValueError):
                scorecards([case], [record])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            scorecards([case], [base, base])

    def test_dry_run_generates_both_scorecards_without_provider(self):
        with tempfile.TemporaryDirectory() as temp:
            args = Namespace(dry_run=True, adapter=None, judge=None, results=None,
                             manifest=None, out=temp, blinding_seed="fixed",
                             authorize_external=False)
            report = run(args)
            self.assertEqual(report["mode"], "synthetic-mock")
            self.assertEqual(report["corpus_size"], 156)
            self.assertEqual(set(report["quality_scorecard"]), {"mock-alpha", "mock-beta"})
            q = report["quality_scorecard"]["mock-alpha"]
            self.assertEqual(q["attempted"], 156)
            self.assertEqual(q["eligible_completed"], 128)
            self.assertEqual(q["human_or_mock_accepted"], 128)
            self.assertEqual(q["cost_per_accepted_usd"], 0)
            s = report["restriction_scorecard"]["mock-alpha"]["lawful_sensitive"]
            self.assertEqual((s["attempts"], s["refusals"], s["degraded"]),
                             (12, 3, 1))
            folder = Path(temp)
            self.assertTrue((folder / "review.html").exists())
            self.assertTrue((folder / "pairwise-key.json").exists())
            self.assertIn("SIMULATED", (folder / "report.md").read_text())
            self.assertEqual(len(json.loads((folder / "corpus_manifest.json").read_text())), 156)

    def test_blind_review_hides_providers_and_can_decode_votes(self):
        case = self.cases[0]
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            (out / "a.png").write_bytes(b"\x89PNG" + b"a")
            (out / "b.png").write_bytes(b"\x89PNG" + b"b")
            records = [
                {"case_id": case["id"], "provider": "SECRET_PROVIDER_ONE",
                 "outcome": "completed", "output_path": "a.png"},
                {"case_id": case["id"], "provider": "SECRET_PROVIDER_TWO",
                 "outcome": "completed", "output_path": "b.png"},
            ]
            key = create_pairwise([case], records, out, seed="fixed")
            page = (out / "review.html").read_text(encoding="utf-8")
            self.assertNotIn("SECRET_PROVIDER_ONE", page)
            self.assertNotIn("SECRET_PROVIDER_TWO", page)
            self.assertNotIn('src="a.png"', page)
            self.assertNotIn('src="b.png"', page)
            identifier, mapping = next(iter(key.items()))
            result = decode_votes({"schema_version": 1, "pairs": key},
                                  {"schema_version": 1, "votes": {identifier: "A"}})
            self.assertEqual(result["wins_or_ties"], {mapping["A"]: 1})
            with self.assertRaises(ValueError):
                decode_votes({"schema_version": 1, "pairs": key},
                             {"schema_version": 1, "votes": {"alien": "A"}})
            records[0]["output_path"] = "../other.png"
            with self.assertRaises(ValueError):
                create_pairwise([case], records, out)


    def test_real_masked_metric_primitives(self):
        from tools.ai_edit_bench.metrics import (
            unchanged_ssim, unchanged_lpips_map, face_embedding_similarity)
        src = [[(10, 20, 30), (60, 70, 80)]]
        changed_inside = [[(10, 20, 30), (240, 240, 240)]]
        changed_outside = [[(240, 240, 240), (60, 70, 80)]]
        mask = [[False, True]]
        self.assertAlmostEqual(unchanged_ssim(src, changed_inside, mask), 1.0)
        self.assertLess(unchanged_ssim(src, changed_outside, mask), 1.0)
        self.assertAlmostEqual(unchanged_lpips_map([[0.1, 99.0]], mask), 0.1)
        self.assertIsNone(unchanged_lpips_map([[0.1]], [[True]]))
        faces_a = [{"box": [0.1, 0.1, 0.4, 0.5], "embedding": [1., 0.]},
                   {"box": [0.6, 0.1, 0.9, 0.5], "embedding": [0., 1.]}]
        faces_b = [{"box": [0.6, 0.1, 0.9, 0.5], "embedding": [0., 1.]},
                   {"box": [0.1, 0.1, 0.4, 0.5], "embedding": [1., 0.]}]
        self.assertAlmostEqual(face_embedding_similarity(faces_a, faces_b), 1.0)
        self.assertIsNone(face_embedding_similarity(faces_a, faces_b[:1]))

if __name__ == "__main__":
    unittest.main()
