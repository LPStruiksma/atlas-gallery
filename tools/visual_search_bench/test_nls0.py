"""Offline tests for the NLS-0 tooling (numpy only; no models, images or network)."""
import os
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_relational as br  # noqa: E402
import nls_bench as nb  # noqa: E402
import text_tower as tt  # noqa: E402


class Tokenisation(unittest.TestCase):
    def test_sticky_eos_padding_matches_big_vision(self):
        # big_vision ops_text.get_pp_tokenize docstring, eos="sticky", pad_value=0, max_len=3
        self.assertEqual(tt.pad_sticky([2], 3), [2, 1, 0])
        self.assertEqual(tt.pad_sticky([2, 2], 3), [2, 2, 1])
        self.assertEqual(tt.pad_sticky([2, 2, 2], 3), [2, 2, 1])
        self.assertEqual(len(tt.pad_sticky(list(range(5, 200)))), tt.SEQLEN)

    def test_lowercasing_is_ascii_only_like_tf_strings_lower(self):
        self.assertEqual(tt.ascii_lower("Een KERK, ÉÉN Café"), "een kerk, ÉÉn café")


class NumpyReference(unittest.TestCase):
    def test_tiny_random_tower_is_normalised_and_uses_last_position(self):
        rng = np.random.default_rng(0)
        d, h, k, m, L, layers = 8, 2, 4, 16, 5, 2
        blk, att = "Encoder_0/encoderblock/", "Encoder_0/encoderblock/MultiHeadDotProductAttention_0/"
        p = {"Embed_0/embedding": rng.normal(size=(11, d)), "pos_embedding": rng.normal(size=(1, L, d)),
             "Encoder_0/encoder_norm/scale": np.ones(d), "Encoder_0/encoder_norm/bias": np.zeros(d),
             "head/kernel": rng.normal(size=(d, d)), "head/bias": np.zeros(d)}
        for n in ("LayerNorm_0", "LayerNorm_1"):
            p[blk + n + "/scale"], p[blk + n + "/bias"] = np.ones((layers, d)), np.zeros((layers, d))
        for n in ("query", "key", "value"):
            p[att + n + "/kernel"] = rng.normal(size=(layers, d, h, k)) * 0.1
            p[att + n + "/bias"] = np.zeros((layers, h, k))
        p[att + "out/kernel"], p[att + "out/bias"] = rng.normal(size=(layers, h, k, d)) * 0.1, np.zeros((layers, d))
        p[blk + "MlpBlock_0/Dense_0/kernel"], p[blk + "MlpBlock_0/Dense_0/bias"] = rng.normal(size=(layers, d, m)) * 0.1, np.zeros((layers, m))
        p[blk + "MlpBlock_0/Dense_1/kernel"], p[blk + "MlpBlock_0/Dense_1/bias"] = rng.normal(size=(layers, m, d)) * 0.1, np.zeros((layers, d))
        tokens = np.array([[3, 4, 1, 0, 0], [3, 4, 1, 0, 0], [5, 6, 7, 1, 0]])
        out = tt.numpy_reference(p, tokens)
        np.testing.assert_allclose(np.linalg.norm(out, axis=1), 1.0, rtol=1e-9)
        np.testing.assert_allclose(out[0], out[1])
        self.assertGreater(float(np.abs(out[0] - out[2]).max()), 1e-4)  # other tokens reach the last position


class Metrics(unittest.TestCase):
    def test_condensed_list_ignores_unjudged_and_counts_hard_negative_pairs(self):
        scores = {"a": 0.9, "x": 0.85, "b": 0.8, "h": 0.7, "c": 0.6, "n": 0.1}
        judged = {"a": "pos", "b": "hn", "h": "pos", "c": "hn", "n": "neg"}  # "x" unjudged
        m = nb.query_metrics(scores, judged)
        self.assertEqual(m["n_judged"], 5)
        self.assertAlmostEqual(m["p10"], 2 / 5)
        self.assertAlmostEqual(m["r50"], 1.0)
        self.assertAlmostEqual(m["ap"], (1 / 1 + 2 / 3) / 2)
        # pairs (pos, hn): (a,b) ok, (a,c) ok, (h,b) wrong, (h,c) ok
        self.assertAlmostEqual(m["hn_err"], 1 / 4)
        ideal = 1 + 1 / np.log2(3)
        self.assertAlmostEqual(m["ndcg10"], (1 + 1 / np.log2(4)) / ideal)

    def test_no_positive_means_no_score(self):
        self.assertIsNone(nb.query_metrics({"a": 1.0}, {"a": "neg"}))


class QueryParser(unittest.TestCase):
    def test_counts_poses_and_negation_in_both_languages(self):
        self.assertEqual(nb.parse("A man and a woman standing inside a church.")["count"], 2)
        self.assertEqual(nb.parse("Een man en een vrouw die in een kerk staan.")["pose"], "standing")
        self.assertEqual(nb.parse("Drie mensen die uitkijken over de zee.")["count"], 3)
        self.assertEqual(nb.parse("An empty beach without people.")["count"], 0)
        self.assertEqual(nb.parse("Een leeg strand zonder mensen.")["negated"], "mensen")
        self.assertEqual(nb.parse("A person photographed from behind in a crowd.")["count"], "many")
        self.assertEqual(nb.parse("Eén persoon die alleen op een bankje zit.")["pose"], "sitting")


class Constraints(unittest.TestCase):
    def img(self, people=(), labels=None, small=0, group=False, faces=()):
        im = br.Image("id", "train")
        im.people = list(people)
        im.labels = dict(labels or {})
        im.small, im.group, im.faces = small, group, list(faces)
        return im

    def test_people_count_known_unknown_and_lower_bound(self):
        two = [("Man", (0.1, 0.3, 0.1, 0.9)), ("Woman", (0.5, 0.7, 0.1, 0.9))]
        c2 = {"kind": "people", "min": 2}
        self.assertTrue(br.evaluate(c2, self.img(two)))
        self.assertIsNone(br.evaluate(c2, self.img(two, small=1)))  # a background figure: unsure
        self.assertFalse(br.evaluate({"kind": "people", "min": 1}, self.img(two, small=1)))
        self.assertTrue(br.evaluate({"kind": "people", "min": 0}, self.img(labels={"Person": 0})))
        self.assertIsNone(br.evaluate({"kind": "people", "min": 0}, self.img()))

    def test_gender_and_side_by_side(self):
        mw = [("Man", (0.1, 0.3, 0.1, 0.9)), ("Woman", (0.5, 0.7, 0.15, 0.9))]
        g = {"kind": "gender", "counts": {"Man": 1, "Woman": 1}}
        self.assertTrue(br.evaluate(g, self.img(mw)))
        self.assertFalse(br.evaluate(g, self.img([("Man", mw[0][1]), ("Man", mw[1][1])])))
        self.assertIsNone(br.evaluate(g, self.img([("Man", mw[0][1]), ("Person", mw[1][1])])))
        self.assertTrue(br.evaluate({"kind": "side_by_side"}, self.img(mw)))
        far = [("Man", (0.1, 0.3, 0.1, 0.9)), ("Woman", (0.5, 0.55, 0.4, 0.6))]
        self.assertFalse(br.evaluate({"kind": "side_by_side"}, self.img(far)))

    def test_judge_marks_exactly_one_false_constraint_as_hard_negative(self):
        q = {"constraints": [{"kind": "label", "name": "Beach"}, {"kind": "people", "min": 2}]}
        one = [("Man", (0.1, 0.3, 0.1, 0.9))]
        self.assertEqual(br.judge(q, self.img(one, {"Beach": 1})), ("hn", "people=2"))
        self.assertEqual(br.judge(q, self.img(one, {"Beach": 0}))[0], "neg")
        self.assertEqual(br.judge(q, self.img(one))[0], "neg")  # beach unknown, count wrong: not relevant, not hard
        self.assertEqual(br.judge(q, self.img(one * 0, {"Beach": 1}))[0], None)


if __name__ == "__main__":
    unittest.main()
