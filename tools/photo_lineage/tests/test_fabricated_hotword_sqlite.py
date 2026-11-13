"""Bounded real SQLite numeric-row tests, no real camera images."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from PIL import Image

from tools.photo_lineage.benchmark import textured
from tools.photo_lineage.matcher import read_grayscale
from tools.photo_lineage.retrieval import MAX_SIDE, words_for_image
from tools.photo_lineage.fabricated_hotword_sqlite import run, _cold_word, _build_case


class FabricatedHotwordSQLiteTests(unittest.TestCase):
    def test_actual_sqlite_preflight_exact_budget_and_same_source(self):
        r = run(fake_docs=24)
        q = r["synthetic_query_term_count"]
        self.assertGreater(q, 2)
        self.assertEqual(r["genuine_camera_photo_count"], 0)
        self.assertEqual(r["real_fictional_pngs_created"], 2)
        self.assertEqual(list(r["conditions"]),
                         ["baseline", "one_common_extra", "two_common_extras"])
        for extras, name in enumerate(r["conditions"]):
            row = r["conditions"][name]
            self.assertEqual(row["measured_sqlite_postings_for_query"], q + 24 * extras)
            self.assertEqual(row["measured_sqlite_distinct_candidate_ids"],
                             1 if extras == 0 else 25)
            self.assertEqual(row["fake_ids_with_two_word_overlap"],
                             24 if extras == 2 else 0)
            self.assertTrue(row["exact_posting_boundary_refuses_one_below"])
            self.assertEqual(row["exact_distinct_id_boundary_refuses_one_below"],
                             bool(extras))
            self.assertTrue(row["inclusive_budgets_retrieve_only_materialized_fictional_source"])
            self.assertEqual(row["fake_photo_paths_created"], 0)
        self.assertTrue(r["incomplete_queries_never_reported_as_empty"])
        self.assertTrue(r["all_temporary_data_removed"])
        self.assertFalse(r["fake_paths_materialized"])
        self.assertNotIn("atlas-fabricated-hotwords-", json.dumps(r))

    def test_one_global_word_can_hit_doc_budget_below_posting_limit(self):
        r = run(fake_docs=10)
        p = r["conditional_150k_arithmetic_not_a_measurement"]
        self.assertEqual(p["baseline"]["posting_budget"], "within")
        self.assertEqual(p["baseline"]["distinct_id_budget"], "within")
        self.assertEqual(p["one_common_extra"]["posting_budget"], "within")
        self.assertEqual(p["one_common_extra"]["distinct_id_budget"], "abstain")
        self.assertEqual(p["two_common_extras"]["posting_budget"], "abstain")
        self.assertEqual(p["two_common_extras"]["distinct_id_budget"], "abstain")
        self.assertFalse(r["real_camera_word_frequency_measured"])
        self.assertFalse(r["true_shutter_source_identity_estimated"])

    def test_reject_bad_work_bounds_and_malformed_fake_word_inputs(self):
        for x in (True, False, 9, 0, -1, 5001, 12.5, None):
            with self.subTest(value=x), self.assertRaises(ValueError):
                run(fake_docs=x)
        self.assertEqual(_cold_word({0, 1}), 2)
        with TemporaryDirectory() as folder:
            root = Path(folder)
            f = root / "fictional.png"
            Image.fromarray(textured(1601, 256)).save(f)
            terms = tuple(sorted(words_for_image(read_grayscale(f, MAX_SIDE))))
            for n, e in ((9, 1), (24, True), (24, -1), (24, 3)):
                with self.subTest(n=n, e=e), self.assertRaises(ValueError):
                    _build_case(root / "never_created.sqlite", f, terms, n, e)
            self.assertFalse((root / "never_created.sqlite").exists())

    def test_eight_performance_workloads_not_silently_substituted(self):
        r = run(fake_docs=12)
        self.assertEqual(len(r["conditions"]), 3)
        self.assertTrue(r["extra_terms_under_research_per_photo_cap"])
        self.assertEqual(r["fabricated_numeric_documents_per_condition"], 12)
        self.assertTrue(r["no_original_user_media_touched"])


if __name__ == "__main__":
    unittest.main()
