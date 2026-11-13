"""Synthetic only: full 384-term numeric features, never image media."""
import unittest

from tools.photo_lineage.benchmark_dense_numeric import (
    GLOBAL_WORDS, SCENE_WORDS, BURST_WORDS, RARE_WORDS,
    WORDS_PER_PHOTO, numeric_words, run,
)


class DenseNumericBenchmarkTests(unittest.TestCase):
    def test_synthetic_word_regions_are_distinct_exactly_384_per_doc(self):
        self.assertEqual((GLOBAL_WORDS, SCENE_WORDS, BURST_WORDS, RARE_WORDS),
                         (4, 32, 64, 284))
        self.assertEqual(WORDS_PER_PHOTO, 384)
        a = set(numeric_words(0, scene_size=50, burst_size=10))
        b = set(numeric_words(1, scene_size=50, burst_size=10))
        c = set(numeric_words(11, scene_size=50, burst_size=10))
        d = set(numeric_words(51, scene_size=50, burst_size=10))
        self.assertEqual(len(a), 384)
        self.assertEqual(len(b), 384)
        self.assertEqual(len(a & b), 100)  # 4 global + 32 scene + 64 burst
        self.assertEqual(len(a & c), 36)   # 4 global + 32 scene
        self.assertEqual(len(a & d), 4)    # only four global words

    def test_actual_384_word_pinned_index_windows_match_closed_form(self):
        report = run(photos=200, scene_size=50, burst_size=10, queries=4)
        self.assertEqual(report["posting_records"], 200 * 384)
        self.assertEqual(report["model_visits_per_384_word_query"], 3324)
        self.assertEqual(report["actual_exact_pinned_index_visits"], 3324)
        self.assertTrue(report["one_posting_below_required_abstains"])
        self.assertEqual(report["numeric_top1_checks"], 4)
        self.assertGreater(report["index_mib"], 0)
        self.assertIn("NOT 150k", report["limitations"])

    def test_different_cohort_sizes_have_predictably_different_window_cost(self):
        x = run(photos=200, scene_size=50, burst_size=10, queries=2)
        y = run(photos=200, scene_size=100, burst_size=20, queries=2)
        self.assertEqual(y["model_visits_per_384_word_query"] -
                         x["model_visits_per_384_word_query"], 32 * 50 + 64 * 10)
        self.assertEqual(x["posting_records"], y["posting_records"])

    def test_input_quota_bounds_fail_before_any_index_allocation(self):
        for kwargs in (
            {"photos": True}, {"photos": 199}, {"photos": 5001},
            {"scene_size": True}, {"scene_size": 19},
            {"burst_size": True}, {"burst_size": 1},
            {"queries": True}, {"queries": 0}, {"queries": 21},
            {"photos": 201, "scene_size": 50},
            {"scene_size": 60, "burst_size": 11},
        ):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                run(**kwargs)


if __name__ == "__main__":
    unittest.main()
