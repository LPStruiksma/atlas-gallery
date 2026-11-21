"""Offline tests for the IDS-12A full-head quality evaluator (#772). Standard library only.

    python3 -m unittest tools.identity_bench.head.test_quality

Records here are constructed. Passing them proves the evaluator, not a head-swap backend.
"""
from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("head_quality", HERE / "quality.py")
q = importlib.util.module_from_spec(_spec)
sys.modules["head_quality"] = q
_spec.loader.exec_module(q)

N = 20
SHA = "ab" * 32


def m(value, method="synthetic"):
    return {"status": "measured", "value": value, "method": method}


def nm(reason="not run"):
    return {"status": "not_measured", "reason": reason}


def fence_hash(i):
    return hashlib.sha256(f"fence-{i}".encode()).hexdigest()


def make_cohort(n=N):
    pairs = []
    for i in range(n):
        pairs.append({
            "pair_id": f"p{i}", "target_id": f"t{i}", "reference_id": f"r{i}", "baseline_id": f"b{i}",
            "pose_tag": "profile" if i < 4 else "frontal", "hair_shape": f"shape{i % 3}",
            "hairline_change": i % 2 == 0, "hard_boundary": i % 5 == 0, "seed": 1000 + i,
            "engine": "engine-1", "model_sha256": SHA, "adapter_sha256": {"not_applicable": "no adapter"},
            "fence_hash": fence_hash(i),
            "licence": {"cleared": True, "source": "synthetic", "licence": "CC0", "permitted_use": "research"},
        })
    return {"rubric_revision": q.RUBRIC_REVISION, "pairs": pairs}


def votes(field_wins=(True, True)):
    out = []
    for j, win in enumerate(field_wins):
        out.append({"judge": f"judge{j}", "vote": "candidate" if win else "baseline", "presented_first": "baseline"})
    return out


def make_row(p):
    i = p["pair_id"]
    n = int(i[1:])
    return {
        "pair_id": i, "target_id": p["target_id"], "reference_id": p["reference_id"], "baseline_id": p["baseline_id"],
        "engine": p["engine"], "seed": p.get("seed"), "model_sha256": p["model_sha256"], "adapter_sha256": p["adapter_sha256"],
        "classification": m("full_head"), "target_pose_preserved": m(True),
        "target_head_span": m(100.0), "result_head_span": m(105.0),
        "outside_fence": m({"equal": True, "outside_pixels": 500, "fence_hash": fence_hash(n)}),
        "hair_votes": m(votes()), "seam_votes": m(votes()),
        "identity": m([{"source": "judgeA", "profile_capable": True, "outcome": "preserved"},
                       {"source": "judgeB", "profile_capable": True, "outcome": "preserved"}]),
        "claimed_success": True,
    }


def make_rows(cohort):
    return [make_row(p) for p in cohort["pairs"]]


def gate(report, name):
    return next(g for g in report["gates"] if g["gate"] == name)


class Passing(unittest.TestCase):
    def test_complete_synthetic_cohort_passes(self):
        c = make_cohort()
        r = q.evaluate(c, make_rows(c))
        self.assertEqual(r["verdict"], q.PASS, q.render_text(r))
        self.assertTrue(all(g["status"] == q.PASS for g in r["gates"]))

    def test_report_is_machine_readable_and_states_it_is_not_a_quality_claim(self):
        c = make_cohort()
        r = q.evaluate(c, make_rows(c))
        self.assertEqual(json.loads(json.dumps(r))["verdict"], q.PASS)
        self.assertIn("not a measured model-quality claim", r["note"])
        self.assertIn("PASS", q.render_text(r))


class Cohort(unittest.TestCase):
    def check(self, mutate, needle):
        c = make_cohort()
        mutate(c)
        status, probs = q.check_cohort(c)
        self.assertEqual(status, q.INSUFFICIENT)
        self.assertTrue(any(needle in p for p in probs), probs)
        self.assertEqual(q.evaluate(c, make_rows(c))["verdict"], q.INSUFFICIENT)

    def test_fewer_than_twenty_pairs(self):
        c = make_cohort(19)
        self.assertEqual(q.evaluate(c, make_rows(c))["verdict"], q.INSUFFICIENT)

    def test_changed_rubric_revision(self):
        self.check(lambda c: c.update(rubric_revision="IDS-12A@other"), "rubric revision")

    def test_missing_or_false_licence(self):
        self.check(lambda c: c["pairs"][3].pop("licence"), "licence")
        self.check(lambda c: c["pairs"][3]["licence"].update(cleared=False), "licence")
        self.check(lambda c: c["pairs"][3]["licence"].update(source=""), "licence")

    def test_repeated_pair_is_not_a_new_pair(self):
        def dup(c):
            c["pairs"][1].update(target_id="t0", reference_id="r0")
        self.check(dup, "repeated")

    def test_hard_case_coverage(self):
        def no_profile(c):
            for p in c["pairs"]:
                p["pose_tag"] = "frontal"
        self.check(no_profile, "profile")

        def one_hair(c):
            for p in c["pairs"]:
                p["hair_shape"] = "same"
        self.check(one_hair, "hair shapes")
        self.check(lambda c: [p.update(hairline_change=False) for p in c["pairs"]], "hairline")
        self.check(lambda c: [p.update(hard_boundary=False) for p in c["pairs"]], "boundary")

    def test_provenance_fields(self):
        self.check(lambda c: c["pairs"][0].update(model_sha256="nothex"), "model_sha256")
        self.check(lambda c: c["pairs"][0].pop("seed"), "seed")
        self.check(lambda c: c["pairs"][0].pop("fence_hash"), "fence_hash")
        self.check(lambda c: c["pairs"][0].update(engine="other"), "locked")


class WholeHeadAndPose(unittest.TestCase):
    def run_with(self, n_pass, how="face_only", pose=True):
        c = make_cohort()
        rows = make_rows(c)
        for r in rows[n_pass:]:
            r["claimed_success"] = False
            if how == "pose":
                r["target_pose_preserved"] = m(False)
            else:
                r["classification"] = m(how)
        return gate(q.evaluate(c, rows), "whole_head_and_pose")

    def test_boundary_18_of_20_passes_and_17_fails(self):
        self.assertEqual(self.run_with(18)["status"], q.PASS)
        g = self.run_with(17)
        self.assertEqual((g["status"], g["passed"], g["denominator"]), (q.FAIL, 17, 20))

    def test_reference_pose_copied_is_not_a_pass(self):
        self.assertEqual(self.run_with(17, how="pose")["status"], q.FAIL)

    def test_nonpass_classes_all_fail_the_count(self):
        for how in ("no_swap", "failed", "target_head_remains"):
            self.assertEqual(self.run_with(17, how=how)["status"], q.FAIL, how)

    def test_missing_row_stays_in_denominator(self):
        c = make_cohort()
        rows = make_rows(c)[:-1]
        r = q.evaluate(c, rows)
        g = gate(r, "whole_head_and_pose")
        self.assertEqual(g["denominator"], 20)
        self.assertEqual(g["passed"], 19)
        self.assertEqual(r["verdict"], q.INSUFFICIENT)  # 19/20 meets the count but a row is missing

    def test_missing_pose_measurement_is_nonpassing(self):
        c = make_cohort()
        rows = make_rows(c)
        rows[0]["target_pose_preserved"] = nm("no landmarks")
        r = q.evaluate(c, rows)
        self.assertEqual(gate(r, "whole_head_and_pose")["passed"], 19)
        self.assertNotEqual(r["verdict"], q.PASS)
        rows[0]["target_pose_preserved"] = {"status": "measured", "value": True}  # no method: malformed
        self.assertNotEqual(q.evaluate(c, rows)["verdict"], q.PASS)

    def test_contradictory_success_label_fails_even_with_high_count(self):
        for cls in ("face_only", "target_head_remains", "no_swap", "failed"):
            c = make_cohort()
            rows = make_rows(c)
            rows[7]["classification"] = m(cls)  # still claimed_success True
            g = gate(q.evaluate(c, rows), "whole_head_and_pose")
            self.assertEqual(g["status"], q.FAIL, cls)
            self.assertEqual(g["contradictions"], ["p7"])
            self.assertEqual(g["passed"], 19)

    def test_success_label_with_pose_not_preserved_contradicts(self):
        c = make_cohort()
        rows = make_rows(c)
        rows[2]["target_pose_preserved"] = m(False)
        self.assertEqual(gate(q.evaluate(c, rows), "whole_head_and_pose")["contradictions"], ["p2"])

    def test_unknown_class_is_not_a_pass(self):
        c = make_cohort()
        rows = make_rows(c)
        rows[0]["classification"] = m("convincing")
        self.assertNotEqual(q.evaluate(c, rows)["verdict"], q.PASS)


class EditFence(unittest.TestCase):
    def test_tampered_pixels_outside_fence_fail(self):
        c = make_cohort()
        rows = make_rows(c)
        rows[4]["outside_fence"] = m({"equal": False, "outside_pixels": 500, "fence_hash": fence_hash(4)})
        r = q.evaluate(c, rows)
        self.assertEqual(gate(r, "edit_fence")["status"], q.FAIL)
        self.assertEqual(gate(r, "edit_fence")["failing"], ["p4"])
        self.assertNotEqual(r["verdict"], q.PASS)

    def test_fence_that_differs_from_predeclared_fails(self):
        c = make_cohort()
        rows = make_rows(c)
        rows[4]["outside_fence"] = m({"equal": True, "outside_pixels": 500, "fence_hash": fence_hash(99)})
        self.assertEqual(gate(q.evaluate(c, rows), "edit_fence")["status"], q.FAIL)

    def test_empty_outside_region_fails(self):
        c = make_cohort()
        rows = make_rows(c)
        rows[4]["outside_fence"] = m({"equal": True, "outside_pixels": 0, "fence_hash": fence_hash(4)})
        self.assertEqual(gate(q.evaluate(c, rows), "edit_fence")["status"], q.FAIL)

    def test_missing_measurement_is_insufficient(self):
        c = make_cohort()
        rows = make_rows(c)
        rows[4]["outside_fence"] = nm("mask missing")
        self.assertEqual(gate(q.evaluate(c, rows), "edit_fence")["status"], q.INSUFFICIENT)

    def test_check_outside_fence_is_exact_integer_equality(self):
        target = [10, 20, 30, 40, 50, 60]
        fence = [0, 1, 1, 0, 0, 0]
        same = q.check_outside_fence(target, [10, 99, 99, 40, 50, 60], fence)
        self.assertEqual((same["equal"], same["outside_pixels"]), (True, 4))
        self.assertEqual(same["fence_hash"], hashlib.sha256(bytes(fence)).hexdigest())
        off_by_one = q.check_outside_fence(target, [10, 20, 30, 40, 50, 61], fence)
        self.assertFalse(off_by_one["equal"])

    def test_check_outside_fence_rejects_degenerate_inputs(self):
        t = [1, 2, 3]
        self.assertFalse(q.check_outside_fence(t, t, [1, 1, 1])["equal"])  # all-frame fence: nothing outside
        self.assertFalse(q.check_outside_fence(t, t, [0, 1])["equal"])  # shape mismatch
        self.assertFalse(q.check_outside_fence([], [], [])["equal"])
        self.assertFalse(q.check_outside_fence([1.0, 2, 3], t, [0, 1, 0])["equal"])  # not integers
        self.assertFalse(q.check_outside_fence(t, t, [0, 2, 0])["equal"])  # malformed fence
        self.assertIsNone(q.check_outside_fence(t, t, [0, 2, 0])["fence_hash"])


class Scale(unittest.TestCase):
    def with_error(self, err, count=N):
        c = make_cohort()
        rows = make_rows(c)
        for r in rows[:count]:
            r["result_head_span"] = m(100.0 * (1 + err))
        return gate(q.evaluate(c, rows), "scale")

    def test_boundary_median_at_ten_percent_passes(self):
        g = self.with_error(0.10)
        self.assertEqual(g["status"], q.PASS)
        self.assertAlmostEqual(g["median_abs_rel_error"], 0.10)

    def test_just_above_ten_percent_fails(self):
        self.assertEqual(self.with_error(0.101)["status"], q.FAIL)

    def test_shrinking_counts_as_error_too(self):
        self.assertEqual(self.with_error(-0.20)["status"], q.FAIL)

    def test_median_not_mean(self):
        c = make_cohort()
        rows = make_rows(c)
        for r in rows[:4]:
            r["result_head_span"] = m(1000.0)  # four huge outliers, median stays 5%
        self.assertEqual(gate(q.evaluate(c, rows), "scale")["status"], q.PASS)

    def test_missing_or_invalid_spans_are_not_dropped(self):
        for bad in (nm("no silhouette"), m(float("nan")), m(float("inf")), m("wide")):
            c = make_cohort()
            rows = make_rows(c)
            rows[0]["result_head_span"] = bad
            g = gate(q.evaluate(c, rows), "scale")
            self.assertEqual(g["status"], q.INSUFFICIENT)
            self.assertEqual(g["incomplete"], ["p0"])
        c = make_cohort()
        rows = make_rows(c)
        rows[0]["target_head_span"] = m(0)
        self.assertEqual(gate(q.evaluate(c, rows), "scale")["status"], q.INSUFFICIENT)


class Judges(unittest.TestCase):
    def build(self, hair_wins, field="hair_votes", mutate=None):
        """hair_wins: number of candidate votes out of 40, spread over two judges."""
        c = make_cohort()
        rows = make_rows(c)
        left = hair_wins
        for r in rows:
            pair = []
            for j in range(2):
                win = left > 0
                left -= 1 if win else 0
                pair.append(win)
            r[field] = m(votes(tuple(pair)))
        if mutate:
            mutate(rows)
        return q.evaluate(c, rows), c

    def test_boundary_28_of_40_passes_and_27_fails(self):
        r, _ = self.build(28)
        g = gate(r, "new_hair_hairline")
        self.assertEqual((g["status"], g["candidate_wins"], g["expected_votes"]), (q.PASS, 28, 40))
        r, _ = self.build(27)
        self.assertEqual(gate(r, "new_hair_hairline")["status"], q.FAIL)

    def test_seam_is_scored_separately_from_hair(self):
        r, _ = self.build(27, field="seam_votes")
        self.assertEqual(gate(r, "neck_seam")["status"], q.FAIL)
        self.assertEqual(gate(r, "new_hair_hairline")["status"], q.PASS)

    def test_one_vote_disagreement_is_reported_and_counts_once(self):
        def split(rows):
            rows[0]["hair_votes"] = m(votes((True, False)))
        r, _ = self.build(40, mutate=split)
        g = gate(r, "new_hair_hairline")
        self.assertEqual(g["candidate_wins"], 39)
        self.assertEqual(g["split_decisions"], ["p0"])
        self.assertEqual(g["per_judge_wins"], {"judge0": 20, "judge1": 19})
        self.assertEqual(g["status"], q.PASS)

    def test_ties_and_abstentions_stay_in_denominator_never_wins(self):
        def mixed(rows):
            for r in rows[:6]:
                r["hair_votes"] = m([{"judge": "judge0", "vote": "tie", "presented_first": "candidate"},
                                     {"judge": "judge1", "vote": "abstain", "reason": "image unreadable"}])
        r, _ = self.build(40, mutate=mixed)
        g = gate(r, "new_hair_hairline")
        self.assertEqual((g["candidate_wins"], g["expected_votes"]), (28, 40))
        self.assertEqual(g["status"], q.PASS)  # exactly 70% of 2N
        self.assertEqual(g["valid_votes"], 28 + 6)  # 28 wins + 6 ties; reasoned abstentions are complete but not votes

    def test_one_more_abstention_drops_below_threshold(self):
        def mixed(rows):
            for r in rows[:7]:
                r["hair_votes"] = m([{"judge": "judge0", "vote": "abstain", "reason": "x"},
                                     {"judge": "judge1", "vote": "abstain", "reason": "x"}])
        r, _ = self.build(40, mutate=mixed)
        self.assertEqual(gate(r, "new_hair_hairline")["status"], q.FAIL)  # 26/40 < 70%

    def test_same_judge_twice_is_not_independent(self):
        def same(rows):
            rows[0]["hair_votes"] = m([{"judge": "j", "vote": "candidate", "presented_first": "baseline"}] * 2)
        r, _ = self.build(40, mutate=same)
        g = gate(r, "new_hair_hairline")
        self.assertNotEqual(g["status"], q.PASS)
        self.assertEqual(g["candidate_wins"], 38)
        self.assertNotEqual(r["verdict"], q.PASS)

    def test_missing_blinded_order_or_votes_not_a_pass(self):
        def blind(rows):
            rows[0]["hair_votes"] = m([{"judge": "judge0", "vote": "candidate"},
                                       {"judge": "judge1", "vote": "candidate"}])
        r, _ = self.build(40, mutate=blind)
        self.assertEqual(gate(r, "new_hair_hairline")["status"], q.INSUFFICIENT)

        def gone(rows):
            rows[0]["hair_votes"] = nm("judge offline")
        r, _ = self.build(40, mutate=gone)
        self.assertEqual(gate(r, "new_hair_hairline")["status"], q.INSUFFICIENT)

    def test_missing_or_wrong_baseline_is_not_a_pass(self):
        def wrong(rows):
            rows[0]["baseline_id"] = "someone-else"
        r, _ = self.build(40, mutate=wrong)
        self.assertEqual(gate(r, "new_hair_hairline")["status"], q.INSUFFICIENT)
        self.assertNotEqual(r["verdict"], q.PASS)


class Identity(unittest.TestCase):
    def set_identity(self, ev):
        c = make_cohort()
        rows = make_rows(c)
        rows[3]["identity"] = m(ev)
        return q.evaluate(c, rows)

    def test_unscorable_profile_is_insufficient_not_a_pass(self):
        r = self.set_identity([{"source": "sface", "profile_capable": False, "outcome": "abstain"},
                               {"source": "judgeB", "profile_capable": True, "outcome": "preserved"}])
        g = gate(r, "profile_identity")
        self.assertEqual(g["status"], q.INSUFFICIENT)
        self.assertEqual(g["incomplete"], ["p3"])
        self.assertEqual(r["verdict"], q.INSUFFICIENT)

    def test_frontal_only_score_is_not_identity_proof(self):
        r = self.set_identity([{"source": "sface", "profile_capable": False, "outcome": "preserved"},
                               {"source": "judgeB", "profile_capable": True, "outcome": "preserved"}])
        self.assertEqual(gate(r, "profile_identity")["status"], q.INSUFFICIENT)

    def test_disagreement_is_insufficient_and_reported(self):
        r = self.set_identity([{"source": "a", "profile_capable": True, "outcome": "preserved"},
                               {"source": "b", "profile_capable": True, "outcome": "not_preserved"}])
        g = gate(r, "profile_identity")
        self.assertEqual((g["status"], g["disagreements"]), (q.INSUFFICIENT, ["p3"]))

    def test_one_source_twice_is_not_two_independent_sources(self):
        r = self.set_identity([{"source": "a", "profile_capable": True, "outcome": "preserved"}] * 2)
        self.assertEqual(gate(r, "profile_identity")["status"], q.INSUFFICIENT)

    def test_single_source_is_insufficient(self):
        r = self.set_identity([{"source": "a", "profile_capable": True, "outcome": "preserved"}])
        self.assertEqual(gate(r, "profile_identity")["status"], q.INSUFFICIENT)


class Rows(unittest.TestCase):
    def test_row_that_differs_from_locked_cohort_is_not_a_pass(self):
        for key, value in (("seed", 1), ("engine", "engine-2"), ("model_sha256", "cd" * 32), ("target_id", "x")):
            c = make_cohort()
            rows = make_rows(c)
            rows[0][key] = value
            r = q.evaluate(c, rows)
            self.assertEqual(r["verdict"], q.INSUFFICIENT, key)
            self.assertTrue(any("differs" in p for p in r["row_problems"]))

    def test_extra_or_duplicate_rows_are_not_a_pass(self):
        c = make_cohort()
        rows = make_rows(c)
        extra = copy.deepcopy(rows[0])
        extra["pair_id"] = "p99"
        self.assertEqual(q.evaluate(c, rows + [extra])["verdict"], q.INSUFFICIENT)
        self.assertEqual(q.evaluate(c, rows + [copy.deepcopy(rows[0])])["verdict"], q.INSUFFICIENT)

    def test_garbage_input_fails_closed(self):
        for cohort, rows in (({}, []), (None, None), ({"pairs": "x"}, [1]), (make_cohort(), "nope"),
                             (make_cohort(), [None, 3, {}])):
            self.assertEqual(q.evaluate(cohort, rows)["verdict"], q.INSUFFICIENT)

    def test_overall_fail_when_a_complete_gate_fails(self):
        c = make_cohort()
        rows = make_rows(c)
        for r in rows[:5]:
            r["classification"] = m("no_swap")
            r["claimed_success"] = False
        self.assertEqual(q.evaluate(c, rows)["verdict"], q.FAIL)


class Cli(unittest.TestCase):
    def test_main_exit_codes_and_json(self):
        import contextlib
        import io
        import tempfile
        c = make_cohort()
        with tempfile.TemporaryDirectory() as d:
            cp, rp, op = Path(d) / "c.json", Path(d) / "r.json", Path(d) / "out.json"
            cp.write_text(json.dumps(c))
            rp.write_text(json.dumps(make_rows(c)))
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(q.main([str(cp), str(rp), "--json", str(op)]), 0)
                self.assertEqual(json.loads(op.read_text())["verdict"], q.PASS)
                rp.write_text("[]")
                self.assertEqual(q.main([str(cp), str(rp)]), 1)
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(q.main([]), 2)


class Frozen(unittest.TestCase):
    def test_thresholds_match_the_preregistration(self):
        self.assertEqual((q.MIN_PAIRS, q.MIN_PROFILE_PAIRS, q.MIN_HAIR_SHAPES), (20, 4, 2))
        self.assertEqual((q.WHOLE_HEAD_POSE_MIN, q.SCALE_MEDIAN_MAX, q.JUDGE_PREFER_MIN), (0.90, 0.10, 0.70))
        doc = (HERE.parents[2] / "docs" / "identity-studio" / "HEAD_ACCEPTANCE.md").read_text(encoding="utf-8")
        for needle in ("**20 distinct", "at least **90%", "≤ 0.10", "at least **70%"):
            self.assertIn(needle, doc)


if __name__ == "__main__":
    unittest.main()
