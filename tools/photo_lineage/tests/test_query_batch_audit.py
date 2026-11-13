"""Controlled numeric-only batch audit tests. No real photos or media paths."""
import json
from pathlib import Path
import tempfile
import unittest

from tools.photo_lineage.bounded_posting_query import (
    PostingBudgetExceeded, open_pinned_queries,
)
from tools.photo_lineage.external_index import build_snapshot
from tools.photo_lineage.query_batch_audit import audit_query_batch


class BatchAbstentionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.snapshot = self.root / "numeric.u64"
        self.docs = [
            (0, (1, 2, 10)),
            (1, (1, 2)),
            (2, (1, 20)),
            (3, (1, 21)),
            (4, (30, 31)),
        ]
        self.sha = build_snapshot(self.docs, self.snapshot,
                                  reserve_free_bytes=0)["sha256"]

    def test_typed_reasons_do_not_need_freeform_exception_parsing(self):
        with open_pinned_queries(self.snapshot, self.sha) as index:
            with self.assertRaises(PostingBudgetExceeded) as over:
                index.query([1, 2, 20], max_posting_visits=6)
            self.assertEqual(over.exception.reason, "posting_visits")
            with self.assertRaises(PostingBudgetExceeded) as distinct:
                index.query([1, 2], max_posting_visits=6,
                            max_distinct_documents=2)
            self.assertEqual(distinct.exception.reason, "distinct_documents")
            with self.assertRaises(ValueError):
                PostingBudgetExceeded("unrecognized", "reason")

    def test_separate_visit_abstention_document_abstention_and_completed_empty(self):
        requests = [
            ([30, 31], 4),
            ([2, 10], 0),
            ([30, 31], 0),  # synthetic wrong expected ID
            ([30], 4),       # completed, no candidate meeting 2 words
            ([1, 2], 1),     # complete window fits, but distinct-ID budget fails
            ([1, 2, 20], 0), # posting-window preflight fails before scoring
        ]
        before = self.snapshot.read_bytes()
        with open_pinned_queries(self.snapshot, self.sha) as index:
            report = audit_query_batch(
                index, iter(requests), max_queries=6,
                max_posting_visits=6, max_distinct_documents=2,
            )
        counts = report["aggregate"]
        self.assertEqual(counts["queries"], 6)
        self.assertEqual(counts["completed"], 4)
        self.assertEqual(counts["completed_empty_shortlist"], 1)
        self.assertEqual(counts["completed_with_shortlist"], 3)
        self.assertEqual(counts["abstained_posting_visits"], 1)
        self.assertEqual(counts["abstained_distinct_documents"], 1)
        self.assertEqual(counts["labeled_completed_queries"], 4)
        self.assertEqual(counts["labeled_abstained_queries"], 2)
        self.assertEqual(counts["synthetic_top1_hits_among_completed"], 2)
        self.assertEqual(counts["synthetic_topk_hits_among_completed"], 2)
        self.assertEqual(report["complete_fraction"], 0.6667)
        self.assertEqual(report["synthetic_conditional_top1_fraction"], 0.5)
        # Four completed labels: 2 known hits, 2 completed misses,
        # plus 2 unresolved abstentions -> [2/6, 4/6].
        bounds = report["synthetic_all_labeled_topk_censoring_bounds"]
        self.assertEqual(bounds["all_labeled_denominator"], 6)
        self.assertEqual(bounds["known_hits"], 2)
        self.assertEqual(bounds["unresolved_abstentions"], 2)
        self.assertEqual(bounds["lower_fraction"], 0.3333)
        self.assertEqual(bounds["upper_fraction"], 0.6667)
        self.assertTrue(report["bounds_are_deterministic_not_confidence_intervals"])
        self.assertIn("selection-biased", report["warnings"])
        self.assertNotIn(str(self.snapshot), json.dumps(report))
        self.assertEqual(self.snapshot.read_bytes(), before)

    def test_all_abstained_has_null_conditional_accuracy_not_zero(self):
        with open_pinned_queries(self.snapshot, self.sha) as index:
            report = audit_query_batch(
                index, [([1, 2, 20], 0), ([1, 2, 20], 1)],
                max_posting_visits=5,
            )
        self.assertEqual(report["aggregate"]["completed"], 0)
        self.assertEqual(report["aggregate"]["abstained_posting_visits"], 2)
        self.assertEqual(report["complete_fraction"], 0)
        self.assertIsNone(report["synthetic_conditional_top1_fraction"])
        self.assertIsNone(report["mean_postings_visited_per_completed_query"])
        bounds = report["synthetic_all_labeled_top1_censoring_bounds"]
        self.assertEqual(bounds["lower_fraction"], 0)
        self.assertEqual(bounds["upper_fraction"], 1)
        self.assertEqual(bounds["unresolved_abstentions"], 2)

    def test_unlabeled_complete_requests_do_not_raise_accuracy_denominator(self):
        with open_pinned_queries(self.snapshot, self.sha) as index:
            report = audit_query_batch(
                index, [([30, 31], None), ([30], None)]
            )
        self.assertEqual(report["aggregate"]["completed"], 2)
        self.assertEqual(report["aggregate"]["labeled_completed_queries"], 0)
        self.assertIsNone(report["synthetic_conditional_topk_fraction"])
        bounds = report["synthetic_all_labeled_topk_censoring_bounds"]
        self.assertIsNone(bounds["lower_fraction"])
        self.assertIsNone(bounds["upper_fraction"])
        self.assertEqual(bounds["all_labeled_denominator"], 0)

    def test_empty_batch_rejected_and_input_bounded_without_partial_report(self):
        with open_pinned_queries(self.snapshot, self.sha) as index:
            with self.assertRaisesRegex(ValueError, "empty"):
                audit_query_batch(index, [])
            with self.assertRaisesRegex(ValueError, "bound"):
                audit_query_batch(index, (([30, 31], None) for _ in range(4)),
                                  max_queries=3)

    def test_bad_expected_label_bad_shape_and_invalid_budget_types_refused(self):
        with open_pinned_queries(self.snapshot, self.sha) as index:
            for request in (([30, 31], True), ([30, 31], -1),
                            ([30, 31], 1 << 32)):
                with self.subTest(request=request), self.assertRaisesRegex(ValueError, "ground-truth"):
                    audit_query_batch(index, [request])
            with self.assertRaisesRegex(ValueError, "request"):
                audit_query_batch(index, [["not-enough"]])
            for kwargs in ({"max_queries": True}, {"max_queries": 0},
                           {"max_posting_visits": False},
                           {"max_distinct_documents": -1},
                           {"top_k": 0}, {"top_k": 1.5}):
                with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                    audit_query_batch(index, [([30, 31], 4)], **kwargs)

    def test_bad_query_errors_are_not_misreported_as_abstentions(self):
        with open_pinned_queries(self.snapshot, self.sha) as index:
            with self.assertRaisesRegex(ValueError, "26-bit"):
                audit_query_batch(index, [([True, 1], None)])
            with self.assertRaisesRegex(ValueError, "Too many"):
                audit_query_batch(index, [(range(600), None)])


if __name__ == "__main__":
    unittest.main()
