import json
import unittest

import bench
import heuristic


class BenchTests(unittest.TestCase):
    def test_every_pair_has_both_languages(self):
        qs = bench.load_queries()
        pairs = {}
        for q in qs:
            pairs.setdefault(q["pair"], set()).add(q["lang"])
        self.assertTrue(all(v == {"en", "nl"} for v in pairs.values()))
        self.assertEqual(len({q["id"] for q in qs}), len(qs))

    def test_expected_values_stay_inside_context(self):
        ctx = bench.load_context()
        for q in bench.load_queries():
            e = q["expected"]
            for p in e.get("people", []) + e.get("exclude_people", []):
                self.assertIn(p, ctx["people"], q["id"])
            for t in e.get("tags", []) + e.get("exclude_tags", []):
                self.assertIn(t, ctx["tags"], q["id"])
            if e.get("place"):
                self.assertIn(e["place"], ctx["places"], q["id"])
            for k in ("date_from", "date_to"):
                if e.get(k):
                    self.assertRegex(e[k], r"^\d{4}-\d{2}-\d{2}$")

    def test_fewshot_disjoint_from_eval(self):
        eval_texts = {q["text"].lower() for q in bench.load_queries()}
        for s in bench.load_fewshot():
            self.assertNotIn(s["text"].lower(), eval_texts)

    def test_scorer_exact_and_wrong(self):
        exp = {"tags": ["beach"], "people": ["Alex"]}
        self.assertTrue(bench.score_one(exp, {"tags": ["beach"], "people": ["Alex"]})["exact"])
        self.assertFalse(bench.score_one(exp, {"tags": ["beach"]})["exact"])
        self.assertFalse(bench.score_one(exp, "not json")["valid"])
        self.assertTrue(bench.score_one({"intent": "unsupported"}, {"intent": "unsupported", "tags": ["x"]})["exact"])

    def test_missing_predictions_are_not_scored(self):
        qs = bench.load_queries()[:2]
        self.assertEqual(bench.summarise(qs, {})["n"], 0)

    def test_heuristic_reproducible_and_valid(self):
        ctx = bench.load_context()
        a = heuristic.parse("Alex and Sam in Rome", ctx)
        self.assertEqual(a, heuristic.parse("Alex and Sam in Rome", ctx))
        self.assertEqual(a["people"], ["Alex", "Sam"])
        self.assertEqual(a["place"], "Rome")
        self.assertEqual(heuristic.parse("snow zonder mensen", ctx)["exclude_tags"], ["people"])

    def test_checked_in_baseline_matches_code(self):
        res = json.loads((bench.DATA.parent / "results" / "baseline.json").read_text())
        ctx = bench.load_context()
        qs = bench.load_queries()
        fresh = bench.summarise(qs, {q["id"]: heuristic.parse(q["text"], ctx) for q in qs})
        self.assertEqual(res["summary"], fresh)


if __name__ == "__main__":
    unittest.main()
