"""Bounded fabricated SQLite row benchmark, no user's media or index."""
import unittest

from tools.photo_lineage.benchmark_sqlite_budget import run


class SQLiteBudgetBenchmarkTests(unittest.TestCase):
    def test_real_sqlite_path_abstains_for_both_limits_then_completes_at_boundary(self):
        result = run(synthetic_docs=60, repetitions=2)
        self.assertEqual(result["fabricated_numeric_photo_ids"], 60)
        self.assertEqual(result["expected_matched_postings"], 120)
        self.assertEqual(result["postings_per_fabricated_id"], 2)
        self.assertEqual(result["actual_temporary_pngs"], 2)
        self.assertEqual(result["materialized_fake_photo_paths"], 0)
        self.assertEqual(result["completed_query_checks"], 2)
        self.assertEqual(result["complete_candidate_count_verified"], 1)
        self.assertEqual(set(result["explicit_abstention_latency_ms_linux"]),
                         {"posting_visits", "distinct_documents"})
        self.assertGreaterEqual(result["completed_query_p95_ms_linux"], 0)
        self.assertTrue(result["all_original_user_media_untouched"])
        self.assertIn("NOT a real", result["policy"])
        self.assertIn("Not 150k", result["limitations"])

    def test_all_numeric_size_bounds_rejected_before_sqlite_creation(self):
        for kwargs in (
            {"synthetic_docs": True}, {"synthetic_docs": 9},
            {"synthetic_docs": 10001}, {"synthetic_docs": 10.5},
            {"repetitions": True}, {"repetitions": 0},
            {"repetitions": 9}, {"repetitions": 1.5},
        ):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                run(**kwargs)


if __name__ == "__main__":
    unittest.main()
