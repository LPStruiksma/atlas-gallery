"""Condition-stratified numeric retrieval tests: no camera photos or filesystem mutations."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from tools.photo_lineage.bounded_posting_query import open_pinned_queries
from tools.photo_lineage.condition_query_audit import CONDITIONS, audit_conditions
from tools.photo_lineage.external_index import build_snapshot


class ConditionQueryAuditTests(unittest.TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.index_path = Path(self.tmp.name) / "anonymous.u64"
        receipt = build_snapshot(
            [(0, (1, 2, 10)), (1, (1, 2)), (2, (1, 20)),
             (3, (1, 21)), (4, (30, 31))],
            self.index_path, reserve_free_bytes=0,
        )
        self.sha = receipt["sha256"]

    def test_recovery_and_two_abstention_causes_remain_per_edit(self):
        rows = [
            ("resized", [30, 31], 4),           # correct synthetic ID
            ("gamma", [30, 31], 0),             # wrong synthetic ID
            ("cropped", [1, 2], 0),             # distinct ID cap
            ("rotated_crop", [1, 2, 20], 0),    # posting visit cap
            ("jpeg_reencode", [30], 4),         # completed empty
        ]
        original = self.index_path.read_bytes()
        with open_pinned_queries(self.index_path, self.sha) as index:
            report = audit_conditions(
                index, (row for row in rows),
                max_queries=5, max_posting_visits=6,
                max_distinct_documents=2,
            )
        counts = report["total"]["aggregate"]
        self.assertEqual(counts["queries"], 5)
        self.assertEqual(counts["completed"], 3)
        self.assertEqual(counts["completed_with_shortlist"], 2)
        self.assertEqual(counts["completed_empty_shortlist"], 1)
        self.assertEqual(counts["abstained_posting_visits"], 1)
        self.assertEqual(counts["abstained_distinct_documents"], 1)
        self.assertEqual(report["total"]["complete_fraction"], 0.6)
        self.assertEqual(report["total"]["synthetic_conditional_top1_fraction"], 0.3333)
        self.assertEqual(report["total"]["synthetic_all_labeled_top1_censoring_bounds"], {
            "known_hits": 1, "unresolved_abstentions": 2,
            "all_labeled_denominator": 5, "lower_fraction": 0.2,
            "upper_fraction": 0.6,
        })
        self.assertIsNone(report["per_condition"]["unmodified"])
        self.assertEqual(report["conditions_not_measured"], ["unmodified"])
        self.assertEqual(report["per_condition"]["cropped"]["aggregate"]["abstained_distinct_documents"], 1)
        self.assertEqual(report["per_condition"]["rotated_crop"]["aggregate"]["abstained_posting_visits"], 1)
        self.assertEqual(report["per_condition"]["jpeg_reencode"]["aggregate"]["completed_empty_shortlist"], 1)
        self.assertNotIn(str(self.index_path), json.dumps(report))
        self.assertNotIn('"photo_id"', json.dumps(report))
        self.assertFalse(report["source_paths_or_query_ids_returned"])
        self.assertEqual(original, self.index_path.read_bytes())

    def test_zero_completed_labeled_in_one_condition_has_null_accuracy(self):
        rows = [
            ("resized", [30, 31], None),
            ("cropped", [1, 2, 20], 0),
        ]
        with open_pinned_queries(self.index_path, self.sha) as index:
            report = audit_conditions(index, rows, max_posting_visits=5)
        crop = report["per_condition"]["cropped"]
        self.assertIsNone(crop["synthetic_conditional_topk_fraction"])
        self.assertEqual(crop["synthetic_all_labeled_topk_censoring_bounds"]["lower_fraction"], 0)
        self.assertEqual(crop["synthetic_all_labeled_topk_censoring_bounds"]["upper_fraction"], 1)
        self.assertIsNone(report["per_condition"]["resized"]["synthetic_all_labeled_topk_censoring_bounds"]["lower_fraction"])

    def test_same_input_order_change_does_not_affect_aggregate(self):
        rows = [
            ("gamma", [30, 31], 4), ("cropped", [1, 2], 0),
            ("resized", [30], 4), ("rotated_crop", [1, 2, 20], 0),
        ]
        with open_pinned_queries(self.index_path, self.sha) as index:
            forward = audit_conditions(index, rows, max_posting_visits=6,
                                       max_distinct_documents=2)
            reverse = audit_conditions(index, reversed(rows), max_posting_visits=6,
                                       max_distinct_documents=2)
        self.assertEqual(forward, reverse)
        self.assertEqual(forward["condition_order"], list(CONDITIONS))

    def test_reject_missing_unknown_or_path_like_conditions(self):
        with open_pinned_queries(self.index_path, self.sha) as index:
            for bad in ("rotation", "faces", "/private/photos", None, True):
                with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, "condition"):
                    audit_conditions(index, [(bad, [30, 31], 4)])
            with self.assertRaisesRegex(ValueError, "No condition"):
                audit_conditions(index, [])
            with self.assertRaisesRegex(ValueError, "Expected"):
                audit_conditions(index, [("cropped", [1, 2])])

    def test_cap_unbounded_generator_and_malformed_values_without_partial_report(self):
        with open_pinned_queries(self.index_path, self.sha) as index:
            with self.assertRaisesRegex(ValueError, "declared bound"):
                audit_conditions(
                    index, (("resized", [30, 31], 4) for _ in range(5)),
                    max_queries=4,
                )
            for kwargs in (
                {"max_queries": True}, {"max_queries": 0},
                {"max_posting_visits": False}, {"max_distinct_documents": -1},
                {"top_k": 0},
            ):
                with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                    audit_conditions(index, [("resized", [30, 31], 4)], **kwargs)
            with self.assertRaisesRegex(ValueError, "ground-truth"):
                audit_conditions(index, [("resized", [30, 31], True)])
            with self.assertRaisesRegex(ValueError, "26-bit"):
                audit_conditions(index, [("cropped", [True], 4)])


if __name__ == "__main__":
    unittest.main()
