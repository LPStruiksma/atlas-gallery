"""Offline tests for the plan B probe (synthetic vectors, no downloads)."""
import unittest

import numpy as np

from tools.identity_bench import fixtures
from tools.identity_bench.planb import encoder_swap_probe as probe


def synthetic(n=600, d_in=16, d_out=32, nonlinear=False, seed=0):
    rng = np.random.default_rng(seed)
    x = rng.normal(size=(n, d_in))
    w = rng.normal(size=(d_in, d_out))
    y = np.tanh(x @ w) if nonlinear else x @ w
    return x, y * 18.0          # GHOST-like norm


class Adapters(unittest.TestCase):
    def test_linear_recovers_a_linear_map(self):
        x, y = synthetic()
        a = probe.LinearAdapter(ridge=1e-6).fit(x, y)
        # the adapter works on unit inputs, so a linear map of raw x is only approximately linear in unit(x);
        # it must still point the right way and keep GHOST's scale
        self.assertGreater(probe.cosine_rows(a(x), y).mean(), 0.9)
        self.assertAlmostEqual(float(np.linalg.norm(a(x), axis=1).mean()), float(np.linalg.norm(y, axis=1).mean()), places=6)

    def test_mlp_fits_a_nonlinear_map_better_than_chance(self):
        x, y = synthetic(nonlinear=True, seed=1)
        m = probe.MlpAdapter(hidden=64, epochs=60, lr=3e-3).fit(x, y)
        self.assertGreater(probe.cosine_rows(m(x), y).mean(), 0.8)

    def test_mlp_is_deterministic(self):
        x, y = synthetic(n=200, seed=2)
        a = probe.MlpAdapter(hidden=32, epochs=5).fit(x, y)(x[:3])
        b = probe.MlpAdapter(hidden=32, epochs=5).fit(x, y)(x[:3])
        np.testing.assert_array_equal(a, b)

    def test_single_vector_call(self):
        x, y = synthetic(n=100)
        a = probe.LinearAdapter().fit(x, y)
        self.assertEqual(a(x[0]).shape, (1, y.shape[1]))


class Split(unittest.TestCase):
    def test_no_identity_on_both_sides(self):
        labels = [f"id{i // 4}" for i in range(200)]
        train, val = probe.split_by_identity(labels, share=0.2)
        self.assertEqual(len(train) + len(val), 200)
        self.assertFalse({labels[i] for i in train} & {labels[i] for i in val})
        self.assertEqual(len({labels[i] for i in val}), 10)


class LearningCurve(unittest.TestCase):
    def test_more_identities_do_not_hurt_a_linear_map(self):
        x, y = synthetic(n=400, seed=3)
        labels = [f"id{i // 4}" for i in range(400)]
        curve = probe.learning_curve(dict(x=x, y=y, labels=labels), shares=(0.1, 1.0))
        self.assertEqual([p["identities"] for p in curve], [8, 80])
        self.assertGreaterEqual(curve[1]["validation_cosine"], curve[0]["validation_cosine"] - 1e-6)


class Exclusions(unittest.TestCase):
    def test_benchmark_identities_are_excluded(self):
        skip = probe.excluded_identities()
        self.assertTrue(set(fixtures.IDENTITIES) <= skip)
        self.assertTrue(set(fixtures.DISTRACTORS) <= skip)
        for target in fixtures.TARGETS:
            for file_id in target.files:
                if file_id.startswith("flux/"):
                    self.assertIn(file_id.split("/")[1], skip)


class FixedSource(unittest.TestCase):
    def test_override_and_fallback(self):
        s = object.__new__(probe.FixedSourceSwapper)      # no model load
        s.current = np.arange(4.0)
        np.testing.assert_array_equal(s.source_vector(np.zeros(512)), np.arange(4.0, dtype=np.float32)[None])
        self.assertEqual(s.source_vector(np.zeros(512)).dtype, np.float32)


class Aggregate(unittest.TestCase):
    def test_paired_against_own(self):
        rows = []
        for k in range(10):
            for source, base in (("own", 0.70), ("sface-linear", 0.50)):
                rows.append(dict(target=f"T{k}", identity="a", source=source, status="ok",
                                 judge_ref=base + 0.01 * k, judge_orig=0.2, judge_rank=1 if base > 0.6 else 2,
                                 sface_ref=0.5))
        out = probe.aggregate(rows)
        self.assertEqual(out["own"]["n"], 10)
        self.assertAlmostEqual(out["sface-linear"]["minus_own"]["mean"], -0.20)
        self.assertEqual(out["own"]["rank1"], 1.0)
        self.assertEqual(out["sface-linear"]["rank1"], 0.0)
        self.assertNotIn("minus_own", out["own"])

    def test_lost_faces_are_left_out_of_every_source(self):
        rows = [dict(target="T1", identity="a", source="own", status="ok", judge_ref=0.7, judge_orig=0.2, judge_rank=1, sface_ref=0.5),
                dict(target="T1", identity="a", source="sface-mlp", status="face-lost-after-swap"),
                dict(target="T2", identity="a", source="own", status="ok", judge_ref=0.6, judge_orig=0.2, judge_rank=1, sface_ref=0.5),
                dict(target="T2", identity="a", source="sface-mlp", status="ok", judge_ref=0.5, judge_orig=0.2, judge_rank=1, sface_ref=0.5)]
        out = probe.aggregate(rows)
        self.assertEqual(out["own"]["n"], 1)
        self.assertEqual(out["sface-mlp"]["n"], 1)


if __name__ == "__main__":
    unittest.main()
