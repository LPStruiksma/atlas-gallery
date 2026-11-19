"""Offline tests for the IDS-3 full-head probe (#470). Standard library only.

    python3 -m unittest tools/identity_bench/head/test_probe.py
"""
from __future__ import annotations

import importlib.util
import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("head_probe", HERE / "probe.py")
probe = importlib.util.module_from_spec(_spec)
sys.modules["head_probe"] = probe
_spec.loader.exec_module(probe)


def score(to_ref=0.7, to_tgt=0.1, hair=(0.5, 2.0, 1.9), d_ref=5.0, d_tgt=40.0, found=True, impostor=0.35):
    if not found:
        return {"face_found": False}
    return {"face_found": True, "id_to_reference": to_ref, "id_to_target": to_tgt, "id_reference_vs_target": impostor,
            "hair_ratio": {"target": hair[0], "reference": hair[1], "result": hair[2]},
            "hair_dE_to_reference": d_ref, "hair_dE_to_target": d_tgt, "outside_psnr_db": 99.0}


def record(engine="e", pair="p1", seconds=100.0, rss=4000, **kw):
    return {"schema": probe.SCHEMA, "engine": engine, "pair": pair, "seconds": {"total": seconds},
            "peak_rss_mb": rss, "host": {"cpu": "test"}, "score": score(**kw)}


class Classify(unittest.TestCase):
    def test_full_head_needs_identity_and_hair(self):
        self.assertEqual(probe.classify(score()), "full_head")

    def test_identity_without_hair_is_face_only_never_full_head(self):
        # Hair amount and colour stayed the target's: a face swap, whatever the engine is called.
        s = score(hair=(0.5, 2.0, 0.55), d_ref=40.0, d_tgt=4.0)
        self.assertEqual(probe.classify(s), "face_only")
        self.assertFalse(probe.convincing(s))

    def test_hair_colour_decides_when_everyone_has_hair(self):
        # Amount looks like the reference but the colour stayed the target's: face only.
        self.assertEqual(probe.classify(score(d_ref=40.0, d_tgt=4.0)), "face_only")
        # Framing makes the amount look like the target's, the colour is the reference's: full head.
        self.assertEqual(probe.classify(score(hair=(0.85, 1.71, 1.10), d_ref=12.0, d_tgt=36.0)), "full_head")

    def test_bald_reference_uses_hair_amount_only(self):
        s = score(hair=(1.5, 0.0, 0.05), d_ref=None, d_tgt=None)
        self.assertEqual(probe.classify(s), "full_head")
        s = score(hair=(1.5, 0.0, 1.4), d_ref=None, d_tgt=3.0)
        self.assertEqual(probe.classify(s), "face_only")

    def test_old_head_still_visible_is_not_a_head_swap(self):
        s = score()
        s["target_head_unchanged_frac"] = probe.TARGET_HEAD_MAY_REMAIN + 0.05
        self.assertEqual(probe.classify(s), "target_head_remains")
        self.assertFalse(probe.convincing(s))
        s["target_head_unchanged_frac"] = 0.03
        self.assertEqual(probe.classify(s), "full_head")

    def test_turned_head_is_not_convincing(self):
        s = score()
        s["yaw"] = {"target": -0.85, "result": -0.26}
        self.assertEqual(probe.classify(s), "full_head")
        self.assertFalse(probe.convincing(s))
        s["yaw"] = {"target": -0.85, "result": -0.68}
        self.assertTrue(probe.convincing(s))

    def test_identity_closer_to_target_is_no_swap(self):
        self.assertEqual(probe.classify(score(to_ref=0.4, to_tgt=0.7)), "no_swap")

    def test_identity_below_threshold_is_no_swap(self):
        self.assertEqual(probe.classify(score(to_ref=probe.SFACE_SAME_IDENTITY - 0.01, to_tgt=0.0)), "no_swap")

    def test_no_face_is_failed(self):
        self.assertEqual(probe.classify(score(found=False)), "failed")

    def test_identity_must_beat_the_target_by_a_margin(self):
        # Different synthetic people already score ~0.35-0.40 with this judge.
        self.assertEqual(probe.classify(score(to_ref=0.45, to_tgt=0.40)), "no_swap")
        self.assertEqual(probe.classify(score(to_ref=0.45, to_tgt=0.25)), "full_head")

    def test_convincing_needs_absolute_bar_and_impostor_margin(self):
        self.assertFalse(probe.convincing(score(to_ref=0.45)))
        self.assertTrue(probe.convincing(score(to_ref=probe.ID_CONVINCING)))
        self.assertFalse(probe.convincing(score(to_ref=0.62, impostor=0.5)))
        self.assertGreater(probe.ID_CONVINCING, probe.SFACE_SAME_IDENTITY)


class Tokens(unittest.TestCase):
    def test_token_counts(self):
        self.assertEqual(probe.latent_tokens(512, 512), probe.ANCHOR_TOKENS)
        self.assertEqual(probe.latent_tokens(768, 768), 2304)
        self.assertEqual(probe.edit_tokens((512, 512), [(512, 512), (512, 512)]), 3072)

    def test_rejects_sizes_the_model_cannot_take(self):
        for wh in ((500, 512), (0, 512), (-16, 16)):
            with self.assertRaises(ValueError):
                probe.latent_tokens(*wh)


class Phone(unittest.TestCase):
    def test_factor_scales_with_host_speed(self):
        lo, hi = probe.phone_factor(probe.ANCHOR_PHONE_STEP_S)
        self.assertEqual((lo, hi), probe.SOC_SLOWDOWN)
        lo2, hi2 = probe.phone_factor(probe.ANCHOR_PHONE_STEP_S / 2)
        self.assertAlmostEqual(lo2, 2 * lo)
        self.assertAlmostEqual(hi2, 2 * hi)

    def test_estimate_is_a_range_never_a_point(self):
        est = probe.extrapolate_phone(600, 57.0)
        self.assertLess(est["low_s"], est["high_s"])
        self.assertEqual(est["low_s"], round(600 * 2 * probe.SOC_SLOWDOWN[0]))

    def test_bad_anchor_rejected(self):
        with self.assertRaises(ValueError):
            probe.phone_factor(0)

    def test_memory_fit_bands(self):
        low, high = probe.PHONE["app_budget_mb"]
        self.assertEqual(probe.memory_fit(low), "fits")
        self.assertEqual(probe.memory_fit(low + 1), "tight")
        self.assertEqual(probe.memory_fit(high + 1), "no")
        self.assertLess(high, probe.PHONE["ram_mb"])


class Cost(unittest.TestCase):
    def test_cost_per_edit(self):
        # 4 candidates of 3 s on a $0.69/h GPU, billed per second.
        self.assertAlmostEqual(probe.cost_per_edit(3, 4, 0.69), 0.0023, places=4)
        self.assertGreater(probe.cost_per_edit(3, 4, 0.69, cold_start_s=60), probe.cost_per_edit(3, 4, 0.69))

    def test_cost_rejects_nonsense(self):
        with self.assertRaises(ValueError):
            probe.cost_per_edit(3, 0, 0.69)
        with self.assertRaises(ValueError):
            probe.cost_per_edit(-1, 1, 0.69)


class Records(unittest.TestCase):
    def test_summary_keeps_failures_in_the_denominator(self):
        recs = [record(pair="p1"), record(pair="p2", found=False), record(pair="p3", to_ref=0.5)]
        s = probe.summarise(recs)["e"]
        self.assertEqual(s["n"], 3)
        self.assertEqual(s["classes"], {"full_head": 2, "failed": 1})
        self.assertEqual(s["convincing"], 1)
        self.assertEqual(s["median_id_to_reference"], 0.6)

    def test_summary_takes_worst_memory(self):
        s = probe.summarise([record(rss=3000), record(pair="p2", rss=9000)])["e"]
        self.assertEqual(s["peak_rss_mb"], 9000)

    def test_validate_record(self):
        probe.validate_record(record())
        for breaker in ({"schema": "other"}, {"seconds": {"total": 0}}, {"peak_rss_mb": 0}):
            bad = {**record(), **breaker}
            with self.assertRaises(ValueError):
                probe.validate_record(bad)
        bad = record()
        del bad["host"]
        with self.assertRaises(ValueError):
            probe.validate_record(bad)

    def test_report_names_face_only_as_face_only(self):
        recs = [record(engine="a"), record(engine="b", hair=(0.5, 2.0, 0.5), d_ref=40.0, d_tgt=4.0),
                record(engine="b", pair="p2", found=False)]
        text = probe.render_report(recs, host_anchor_step_s=57.0)
        self.assertIn("| b | p1 | face_only |", text)
        self.assertIn("| b | p2 | failed | no | n/a", text)
        self.assertIn("S24+ estimate", text)
        self.assertNotIn("S24+ estimate", probe.render_report(recs, None))


class Manifest(unittest.TestCase):
    def test_pairs_reference_known_fixtures(self):
        ids = set()
        for pair, tgt, ref, why in probe.PAIRS:
            self.assertIn(tgt, probe.FIXTURES)
            self.assertIn(ref, probe.FIXTURES)
            self.assertTrue(tgt.startswith("tgt_") and ref.startswith("ref_"))
            self.assertTrue(why)
            ids.add(pair)
        self.assertEqual(len(ids), len(probe.PAIRS))

    def test_fixture_prompts_name_no_real_person(self):
        for prompt in probe.FIXTURES.values():
            self.assertRegex(prompt, r"\d+ year old (Black )?(woman|man)")

    def test_every_download_is_pinned_and_from_an_original_host(self):
        for name, (url, pin) in probe.DOWNLOADS.items():
            self.assertRegex(pin, r"^[0-9a-f]{64}$", name)
            self.assertTrue(url.startswith(("https://huggingface.co/", "https://media.githubusercontent.com/media/opencv/")), name)

    def test_head_crop_box_stays_inside_the_image(self):
        for face in ((10, 10, 100, 120), (700, 700, 60, 60), (300, 200, 400, 500)):
            x0, y0, x1, y1 = probe.head_crop_box(face, 768, 768)
            self.assertTrue(0 <= x0 < x1 <= 768 and 0 <= y0 < y1 <= 768)
            self.assertEqual(x1 - x0, y1 - y0)


class Evidence(unittest.TestCase):
    """The committed measurements must be self-consistent with the code that reads them."""

    def test_committed_results_validate_and_report(self):
        path = HERE / "results" / "results.json"
        data = json.loads(path.read_text())
        self.assertEqual(data["schema"], probe.SCHEMA)
        self.assertTrue(data["records"])
        for rec in data["records"]:
            probe.validate_record(rec)
            self.assertIn(rec["pair"], {p[0] for p in probe.PAIRS})
        text = probe.render_report(data["records"], data["anchor"]["median_step_s"])
        self.assertIn("S24+ estimate", text)
        self.assertEqual(data["summary"], json.loads(json.dumps(probe.summarise(data["records"]))))

    def test_cli_help_runs_without_heavy_dependencies(self):
        buf = io.StringIO()
        with redirect_stdout(buf), self.assertRaises(SystemExit) as ctx:
            probe.main(["--help"])
        self.assertEqual(ctx.exception.code, 0)
        self.assertIn("swap", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
