import json
import os
import unittest

import speech_bench as sb

FIX = os.path.join(os.path.dirname(__file__), "fixtures")


class ScoringTests(unittest.TestCase):
    def test_normalizer_keeps_diacritics_and_joins_apostrophes(self):
        self.assertEqual(sb.normalize("Continuüm, [w]hile don't!"), ["continuüm", "while", "dont"])

    def test_edit_counts(self):
        self.assertEqual(sb.edit_counts("a b c".split(), "a b c".split()), (0, 0, 0))
        self.assertEqual(sb.edit_counts("a b c".split(), "a x c".split()), (1, 0, 0))
        self.assertEqual(sb.edit_counts("a b c".split(), "a c".split()), (0, 1, 0))
        self.assertEqual(sb.edit_counts("a c".split(), "a b c".split()), (0, 0, 1))

    def test_score_pair_is_case_and_punctuation_blind(self):
        r = sb.score_pair("Volgens Angel (2006), kunnen.", "volgens angel 2006 kunnen")
        self.assertEqual((r["sub"], r["del"], r["ins"]), (0, 0, 0))

    def test_selection_is_order_independent_and_seeded(self):
        meta = [json.loads(x) for x in open(os.path.join(FIX, "metadata.jsonl"))]
        a = sb.select_ids(meta, 3, "seed")
        self.assertEqual(a, sb.select_ids(list(reversed(meta)), 3, "seed"))
        self.assertNotEqual(a, sb.select_ids(meta, 3, "other"))
        self.assertEqual({k: len(v) for k, v in a.items()}, {"en": 3, "nl": 3})

    def test_aggregate_separates_languages_and_empty_references(self):
        obs = [json.loads(x) for x in open(os.path.join(FIX, "observations.jsonl"))]
        r = sb.aggregate(obs)
        self.assertEqual(r["en"]["micro_wer"], 0.1)
        self.assertEqual(r["en"]["rtf_total"], 0.15)
        self.assertEqual(r["nl"]["micro_wer"], 0.3)
        self.assertEqual(r["nl"]["empty_reference_clips"], 1)
        self.assertEqual(r["nl"]["hallucinated_on_empty"], 1)
        self.assertEqual(r["nl"]["scored_clips"], 1)

    def test_loop_guard(self):
        self.assertTrue(sb._looping([9, 1, 2, 3, 4] + [1, 2, 3, 4] * 2))
        self.assertFalse(sb._looping([1, 2, 3, 4, 1, 2, 3, 4, 5, 6, 7, 8]))

    def test_log_mel_shape_and_range(self):
        try:
            import numpy as np
        except ImportError:
            self.skipTest("numpy not installed")
        filt = np.ones((80, 201), dtype="float32") / 201
        m = sb.log_mel(np.zeros(16000, dtype="float32"), filt)
        self.assertEqual(m.shape, (80, 3000))
        self.assertTrue(np.isfinite(m).all())

    def test_report_has_no_text_fields(self):
        obs = [json.loads(x) for x in open(os.path.join(FIX, "observations.jsonl"))]
        for o in obs:
            self.assertFalse({"ref", "hyp", "text", "transcript"} & set(o))


if __name__ == "__main__":
    unittest.main()
