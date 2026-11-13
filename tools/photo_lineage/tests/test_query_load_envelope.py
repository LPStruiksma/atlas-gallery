"""Pure numeric scenario tests. No files, photos, indexes, SQLite or downloads."""
import unittest

from tools.photo_lineage.query_load_envelope import (
    estimate_footprint, scenario_sensitivity,
)


class QueryLoadEnvelopeTests(unittest.TestCase):
    def test_diverse_384_terms_have_384_visits_not_real_recall(self):
        r = estimate_footprint()
        self.assertEqual(r["posting_visits_required"], 384)
        self.assertEqual(r["visit_budget_envelope"], "within_visits_only_not_retrieval_or_safety_pass")
        self.assertEqual(r["postings_by_assumed_prevalence"]["rare"]["query_words"], 384)
        self.assertEqual(r["assumption_status"], "illustrative_what_if_not_measured")

    def test_one_common_word_can_exceed_budget_with_other_shared_words(self):
        r = estimate_footprint(
            globally_shared_words=1, scene_words=32, burst_words=64,
            scene_photos=5000, burst_photos=50,
        )
        self.assertEqual(r["posting_visits_required"], 313487)
        self.assertEqual(r["visit_budget_envelope"], "abstain_incomplete")
        self.assertEqual(r["over_budget_postings"], 63487)

    def test_shared_scene_not_automatically_assumed_a_false_match(self):
        r = estimate_footprint(scene_words=16, burst_words=64,
                               scene_photos=1000, burst_photos=60)
        self.assertEqual(r["posting_visits_required"], 20144)
        self.assertIn("geometric verification", r["note"])
        self.assertEqual(r["unused_posting_visit_budget"], 229856)

    def test_all_global_is_exact_full_stream_upper_extreme(self):
        r = estimate_footprint(globally_shared_words=384)
        self.assertEqual(r["posting_visits_required"], 57_600_000)
        self.assertEqual(r["over_budget_postings"], 57_350_000)
        self.assertEqual(r["postings_by_assumed_prevalence"]["globally_shared"]["postings"], 57_600_000)

    def test_fixed_archetypes_are_stably_documented_and_non_empirical(self):
        r = scenario_sensitivity()
        self.assertEqual(r["summary"]["scenarios_tested"], 5)
        self.assertEqual(r["summary"]["cases_abstaining_due_to_visit_budget"], 3)
        self.assertTrue(r["summary"]["no_real_camera_observations"])
        names = [x["scenario"] for x in r["scenarios"]]
        self.assertEqual(names, [
            "diverse_low_overlap", "shared_scene_no_global",
            "one_global_background_word", "many_repeated_patterns",
            "all_terms_global_upper_extreme",
        ])

    def test_small_descriptor_budget_and_smaller_correlated_cohort_scale(self):
        r = scenario_sensitivity(photos=40, query_words=32, max_posting_visits=50)
        self.assertEqual(r["summary"]["scenarios_tested"], 5)
        for case in r["scenarios"]:
            self.assertEqual(case["analysis"]["query_word_count"], 32)
            self.assertEqual(case["analysis"]["photo_count"], 40)
            counts = case["analysis"]["postings_by_assumed_prevalence"]
            self.assertEqual(sum(x["query_words"] for x in counts.values()), 32)
            self.assertLessEqual(case["analysis"]["posting_visits_required"], 40 * 32)
        self.assertEqual(r["scenarios"][-1]["analysis"]["posting_visits_required"], 1280)

    def test_exact_budget_boundary_is_allowed_and_one_over_abstains(self):
        r = estimate_footprint(photos=500, query_words=3,
                               globally_shared_words=1, scene_words=0,
                               burst_words=0, scene_photos=20, burst_photos=5,
                               max_posting_visits=502)
        self.assertEqual(r["posting_visits_required"], 502)
        self.assertEqual(r["visit_budget_envelope"], "within_visits_only_not_retrieval_or_safety_pass")
        r = estimate_footprint(photos=500, query_words=3,
                               globally_shared_words=1, scene_words=0,
                               burst_words=0, scene_photos=20, burst_photos=5,
                               max_posting_visits=501)
        self.assertEqual(r["visit_budget_envelope"], "abstain_incomplete")

    def test_class_count_sum_and_prevalence_order_fail_closed(self):
        cases = (
            {"globally_shared_words": 383, "scene_words": 2},
            {"burst_photos": 200, "scene_photos": 100},
            {"scene_photos": 151000},
        )
        for kwargs in cases:
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                estimate_footprint(**kwargs)

    def test_numeric_bounds_refuse_bool_float_negative(self):
        for kwargs in (
            {"photos": True}, {"query_words": False},
            {"globally_shared_words": 1.0}, {"burst_words": -1},
            {"scene_words": -1}, {"photos": 0}, {"query_words": 513},
            {"max_posting_visits": 0}, {"scene_photos": 0},
            {"burst_photos": 0}, {"max_posting_visits": True},
        ):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                estimate_footprint(**kwargs)


if __name__ == "__main__":
    unittest.main()
