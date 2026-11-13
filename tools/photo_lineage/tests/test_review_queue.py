"""Self-contained fictional evidence tests: no user photos, network, or browser."""
import json
import os
from pathlib import Path
import tempfile
import unittest

from tools.photo_lineage.review_queue import normalize, render, write_new, _read_json


def edge(a, b, verdict="candidate", inliers=30):
    return {"image_a": a, "image_b": b, "verdict": verdict, "reason": "Distributed geometric inliers with compatible image structure; review required",
            "algorithm": "sift", "inliers": inliers, "local_consistency": 0.9}


class ReviewQueueTest(unittest.TestCase):
    def test_all_three_cli_evidence_shapes(self):
        pair = edge("/secret/a.png", "/secret/b.png")
        scan = {"candidate_edges": [pair, edge("/secret/b.png", "/secret/c.png", "review")]}
        search = {"candidate_edges": [{"retrieval": {"path": "/secret/b.png", "shared_visual_words": 4}, "evidence": pair}]}
        self.assertEqual(normalize(pair)[1]["candidate"], 1)
        self.assertEqual(normalize(scan)[1]["photos"], 3)
        self.assertEqual(normalize(search)[1]["candidate"], 1)

    def test_no_transitive_families_even_for_triangle(self):
        report = render({"candidate_edges": [edge("a", "b"), edge("b", "c")]})
        self.assertIn("2 edges", report)
        self.assertIn("A→B and B→C never implies A→C", report)
        self.assertNotIn("family_id", report)

    def test_freeform_metadata_cannot_leak_names(self):
        payload = edge("/private/path-a", "/private/path-b")
        payload["reason"] = "secret face GPS /storage/emulated/0/Personal"
        payload["algorithm"] = "/secret/method"
        page = render(payload)
        self.assertNotIn("/storage/", page)
        self.assertNotIn("/secret/", page)
        self.assertIn("Unrecognized reason (withheld", page)

    def test_private_paths_hidden_by_default_and_explicitly_revealed(self):
        payload = edge("/private/face_a.png", "/private/face_b.png")
        self.assertNotIn("/private/", render(payload))
        self.assertIn("/private/face_a.png", render(payload, show_names=True))

    def test_html_injection_and_external_url_not_emitted(self):
        payload = edge('<script src="https://bad.test/a"></script>', '" onmouseover="alert(1)')
        payload["reason"] = '<img src="https://bad.test/p">'
        page = render(payload, show_names=True)
        self.assertNotIn("<script", page)
        self.assertNotIn("<img", page)
        self.assertIn("Unrecognized reason (withheld", page)
        self.assertNotIn(' onmouseover="alert(1)"', page)
        self.assertNotIn("<iframe", page)

    def test_unknown_and_review_never_promoted_to_candidate(self):
        rows, stats = normalize({"candidate_edges": [edge("a", "b", "unknown"), edge("c", "d", "review")]})
        self.assertEqual(stats["candidate"], 0)
        self.assertEqual([x["verdict"] for x in rows], ["review", "unknown"])

    def test_malformed_or_nonfinite_fail_closed(self):
        bad = edge("a", "b")
        bad["inliers"] = float('nan')
        with self.assertRaises(ValueError):
            normalize(bad)
        for payload in ({"candidate_edges": [edge("a", "b"), edge("b", "a")]},
                        {"candidate_edges": [edge("a", "a")]},
                        {"candidate_edges": [edge("a", "b", "confirmed")]},
                        {"candidate_edges": [edge("a", "b"), edge("c", "d")]}):
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                normalize(payload, limit=1)
        with self.assertRaises(ValueError):
            normalize({"candidate_edges": [edge("a", "b"), edge("b", "a")]})

    def test_private_no_overwrite_publication(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder) / "report.html"
            write_new(p, render(edge("a", "b")))
            self.assertEqual(p.stat().st_mode & 0o777, 0o600)
            orig = p.read_bytes()
            with self.assertRaises(FileExistsError):
                write_new(p, "tampered")
            self.assertEqual(p.read_bytes(), orig)
            link = Path(folder) / "link.html"
            link.symlink_to(p)
            with self.assertRaises(ValueError):
                write_new(link, "not permitted")

    def test_bounded_input_no_symlink_and_duplicate_properties(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder) / "evidence.json"
            p.write_text('{"candidate_edges":[],"candidate_edges":[]}')
            with self.assertRaises(ValueError):
                _read_json(p)
            p.write_text(json.dumps(edge("a", "b")))
            self.assertEqual(normalize(_read_json(p))[1]["edges"], 1)
            q = Path(folder) / "link.json"
            q.symlink_to(p)
            with self.assertRaises(ValueError):
                _read_json(q)
            p.write_bytes(b"x" * 2_000_001)
            with self.assertRaises(ValueError):
                _read_json(p)

    def test_deterministic_card_sort_without_probabilities(self):
        payload = {"candidate_edges": [edge("a", "b", "review", 40), edge("c", "d", "candidate", 9)]}
        one = render(payload)
        self.assertEqual(one, render(payload))
        self.assertLess(one.index("Photo 0003 ↔ Photo 0004"), one.index("Photo 0001 ↔ Photo 0002"))
        self.assertNotIn("confidence%", one)


if __name__ == "__main__":
    unittest.main()
