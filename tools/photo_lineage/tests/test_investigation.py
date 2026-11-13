"""Synthetic graph protocol tests: no photographs, identity assignments, or I/O."""
import json
import unittest

from tools.photo_lineage.investigation import investigation_plan


def edge(a, b, verdict="candidate"):
    return {"image_a": a, "image_b": b, "verdict": verdict,
            "reason": "Distributed geometric inliers with compatible image structure; review required",
            "algorithm": "orb", "inliers": 19}


def report(*edges, **kwargs):
    return investigation_plan({"candidate_edges": list(edges)}, **kwargs)


class InvestigationTests(unittest.TestCase):
    def test_chain_does_not_prove_transitive_family(self):
        data = report(edge("/secret/A.png", "/secret/B.png"),
                      edge("/secret/B.png", "/secret/C.png"))
        group = data["investigation_neighborhoods"][0]
        self.assertEqual(group["member_count"], 3)
        self.assertEqual(group["direct_reviewable_pairs"], 2)
        self.assertEqual(group["pairs_never_directly_assessed"], 1)
        self.assertEqual(group["direct_unknown_pairs_within_neighborhood"], 0)
        self.assertEqual(group["bridge_edges_without_alternate_direct_path"], 2)
        self.assertIn("NO evidence for A-C", data["policy"])
        self.assertNotIn("/secret/", json.dumps(data))
        self.assertIn("not_a_confirmed_photo_family", group["status"])

    def test_complete_triangle_still_is_not_duplicate_proof(self):
        data = report(edge("A", "B"), edge("B", "C"), edge("A", "C"))
        group = data["investigation_neighborhoods"][0]
        self.assertEqual(group["pairs_never_directly_assessed"], 0)
        self.assertEqual(group["bridge_edges_without_alternate_direct_path"], 0)
        self.assertTrue(data["all_original_photos_untouched"])
        self.assertIn("NOT certified", data["policy"])

    def test_unknown_direct_pair_is_not_a_never_tested_pair(self):
        data = report(edge("A", "B"), edge("B", "C", "review"), edge("A", "C", "unknown"))
        group = data["investigation_neighborhoods"][0]
        self.assertEqual(group["direct_unknown_pairs_within_neighborhood"], 1)
        self.assertEqual(group["pairs_never_directly_assessed"], 0)
        self.assertEqual(group["direct_review_suggestions"], 1)
        self.assertEqual(data["unknown_edges_not_used_for_connectivity"], 1)

    def test_unknown_edge_cannot_create_component(self):
        data = report(edge("A", "B", "unknown"))
        self.assertEqual(data["component_count"], 0)
        self.assertEqual(data["photos_with_only_unknown_edges"], 2)
        self.assertEqual(data["reviewable_direct_edges"], 0)

    def test_unknown_between_two_neighborhoods_does_not_link(self):
        data = report(edge("A", "B"), edge("C", "D"), edge("B", "C", "unknown"))
        self.assertEqual(data["component_count"], 2)
        self.assertEqual([x["member_count"] for x in data["investigation_neighborhoods"]], [2, 2])
        self.assertEqual(data["photos_with_only_unknown_edges"], 0)

    def test_private_names_opt_in_only(self):
        data = report(edge("/secret/a.jpg", "/secret/b.jpg"))
        self.assertNotIn("/secret/", json.dumps(data))
        private = report(edge("/secret/a.jpg", "/secret/b.jpg"), show_names=True)
        self.assertIn("/secret/a.jpg", json.dumps(private))
        self.assertTrue(private["source_paths_included"])

    def test_malformed_duplicate_or_nonnumeric_is_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            report(edge("A", "B"), edge("B", "A"))
        bad = edge("A", "B")
        bad["inliers"] = float("nan")
        with self.assertRaisesRegex(ValueError, "Invalid numeric"):
            report(bad)
        with self.assertRaisesRegex(ValueError, "boolean"):
            report(edge("A", "B"), show_names=1)

    def test_large_chain_iterative_bridge_no_recursion(self):
        edges = [edge(f"photo-{i:04}", f"photo-{i+1:04}") for i in range(2000)]
        data = report(*edges)
        self.assertEqual(data["component_count"], 1)
        group = data["investigation_neighborhoods"][0]
        self.assertEqual(group["member_count"], 2001)
        self.assertEqual(group["direct_reviewable_pairs"], 2000)
        self.assertEqual(group["bridge_edges_without_alternate_direct_path"], 2000)
        self.assertEqual(len(group["weak_bridge_examples"]), 12)
        self.assertEqual(len(group["evidence_edges"]), 2000)

    def test_bounded_edges(self):
        edges = [edge(f"A{i}", f"B{i}") for i in range(3)]
        with self.assertRaisesRegex(ValueError, "bound"):
            report(*edges, max_edges=2)


if __name__ == "__main__":
    unittest.main()
