"""Full 384-numeric-word adversarial batch: accuracy censoring vs completion.

Fixtures are synthetic posting IDs ONLY, no images/camera labels/private media.
"""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from tools.photo_lineage.benchmark_dense_numeric import (
    GLOBAL_WORDS, SCENE_WORDS, WORDS_PER_PHOTO, numeric_words,
)
from tools.photo_lineage.bounded_posting_query import open_pinned_queries
from tools.photo_lineage.external_index import build_snapshot
from tools.photo_lineage.query_batch_audit import audit_query_batch


class DenseCensoredBatchTests(unittest.TestCase):
    def test_four_easy_true_hits_one_completed_miss_two_budget_abstentions(self):
        with TemporaryDirectory(prefix="atlas-dense-batch-fixture-") as folder:
            output = Path(folder) / "dense.u64"
            report = build_snapshot(
                ((photo_id, numeric_words(photo_id, scene_size=50, burst_size=10))
                 for photo_id in range(200)),
                output, max_postings=200 * WORDS_PER_PHOTO,
                chunk_records=8192, reserve_free_bytes=0,
            )
            original = output.read_bytes()
            self.assertEqual(report["postings"], 76_800)
            def easy(photo_id):
                return list(numeric_words(photo_id, scene_size=50, burst_size=10))[
                    GLOBAL_WORDS + SCENE_WORDS:
                ]
            # Four 348-word positives with a unique self match. Their 924
            # posting windows fit the 1k budget and at most 10 candidate IDs.
            # A 32-word shared scene costs 1600 postings (visit abstention).
            # Four global words cost 800 visits but 200 distinct IDs (ID abstention).
            # One absent word completes with an empty shortlist, and is a
            # **known miss** for the synthetic labelled target.
            requests = (
                [(easy(i), i) for i in (0, 1, 51, 52)]
                + [([10_000 + j for j in range(SCENE_WORDS)], 0)]
                + [(list(range(GLOBAL_WORDS)), 0)]
                + [([((1 << 26) - 1)], 0)]
            )
            with open_pinned_queries(output, report["sha256"]) as index:
                result = audit_query_batch(
                    index, requests, max_queries=7,
                    max_posting_visits=1000,
                    max_distinct_documents=20,
                )
            totals = result["aggregate"]
            self.assertEqual(totals["queries"], 7)
            self.assertEqual(totals["completed"], 5)
            self.assertEqual(totals["completed_with_shortlist"], 4)
            self.assertEqual(totals["completed_empty_shortlist"], 1)
            self.assertEqual(totals["abstained_posting_visits"], 1)
            self.assertEqual(totals["abstained_distinct_documents"], 1)
            self.assertEqual(totals["synthetic_top1_hits_among_completed"], 4)
            self.assertEqual(result["complete_fraction"], 0.7143)
            self.assertEqual(result["synthetic_conditional_top1_fraction"], 0.8)
            lower_upper = result["synthetic_all_labeled_top1_censoring_bounds"]
            self.assertEqual(lower_upper["known_hits"], 4)
            self.assertEqual(lower_upper["unresolved_abstentions"], 2)
            self.assertEqual(lower_upper["all_labeled_denominator"], 7)
            self.assertEqual(lower_upper["lower_fraction"], 0.5714)
            self.assertEqual(lower_upper["upper_fraction"], 0.8571)
            self.assertIn("not statistical confidence", result["warnings"])
            self.assertNotIn(str(output), json.dumps(result))
            self.assertEqual(original, output.read_bytes())

    def test_completed_only_can_look_perfect_while_half_abstain(self):
        with TemporaryDirectory(prefix="atlas-selection-bias-") as folder:
            output = Path(folder) / "dense.u64"
            receipt = build_snapshot(
                ((i, numeric_words(i, scene_size=50, burst_size=10)) for i in range(200)),
                output, max_postings=76_800, reserve_free_bytes=0,
            )
            easy = [
                (list(numeric_words(i, scene_size=50, burst_size=10))[36:], i)
                for i in (0, 1)
            ]
            hard = [
                (list(range(4)), 0),
                (list(range(10_000, 10_032)), 0),
            ]
            with open_pinned_queries(output, receipt["sha256"]) as index:
                result = audit_query_batch(
                    index, easy + hard, max_queries=4,
                    max_posting_visits=1000, max_distinct_documents=20,
                )
            self.assertEqual(result["synthetic_conditional_topk_fraction"], 1)
            self.assertEqual(result["complete_fraction"], 0.5)
            bounds = result["synthetic_all_labeled_topk_censoring_bounds"]
            self.assertEqual(bounds["lower_fraction"], 0.5)
            self.assertEqual(bounds["upper_fraction"], 1.0)
            self.assertTrue(result["no_camera_photo_or_android_claim"])


if __name__ == "__main__":
    unittest.main()
