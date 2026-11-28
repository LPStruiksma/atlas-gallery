"""Offline tests for the IDS-12F cohort runner (#1091). Standard library only, no model.

    python3 -m unittest tools/identity_bench/head/test_run_cohort.py -v

They cover the cohort freeze (the committed cohort.json is the frozen plan and passes the
IDS-12A pre-registration checks), resumable runs, the quality.py record format of the
committed rows, and that the committed blinded sheets carry no image metadata.
"""
from __future__ import annotations

import copy
import importlib.util
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
_spec = importlib.util.spec_from_file_location("head_run_cohort", HERE / "run_cohort.py")
rc = importlib.util.module_from_spec(_spec)
sys.modules["head_run_cohort"] = rc
_spec.loader.exec_module(rc)
quality = rc.quality

RUNTIME_FIELDS = ("target_sha256", "reference_sha256", "fence_hash", "fence_inside_pixels", "fence_total_pixels",
                  "pose_tag", "target_yaw_proxy")
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def load(path: Path):
    return json.loads(path.read_text())


def fake_stage(status="ok", cls="full_head", pose=True, ts=40.0, rs=42.0, sface=0.55, glint=0.45, equal=True):
    if status != "ok":
        return {"status": "failed", "error": "ValueError: no face"}
    return {"status": "ok", "files_sha256": {"klein-Q4_0.gguf": "a" * 64, "loras/bfs_head_v1.safetensors": "b" * 64},
            "measure": {"classification": cls, "pose_kept": pose, "target_head_span": ts, "result_head_span": rs,
                        "outside_fence": {"equal": equal, "outside_pixels": 1000, "fence_hash": "c" * 64},
                        "output_mask_hash": "d" * 64,
                        "identity": {"sface": sface, "glintr100": glint, "glintr100_to_target": 0.1},
                        "score": {"id_to_reference": sface, "id_to_target": 0.2, "yaw": {"target": 0.0, "result": 0.05}}}}


class PlanTest(unittest.TestCase):
    def test_twenty_distinct_pairs_with_hard_cases(self):
        pairs = rc.plan_pairs()
        self.assertGreaterEqual(len(pairs), quality.MIN_PAIRS)
        self.assertEqual(len({p["pair_id"] for p in pairs}), len(pairs))
        self.assertEqual(len({(p["target_id"], p["reference_id"]) for p in pairs}), len(pairs))
        hc = rc.hard_case_ids(pairs)
        self.assertGreaterEqual(len(hc["profile_or_three_quarter"]), quality.MIN_PROFILE_PAIRS)
        self.assertGreaterEqual(len(hc["reference_hair_shapes"]), quality.MIN_HAIR_SHAPES)
        self.assertTrue(hc["hairline_change"] and hc["hard_boundary"])
        self.assertTrue(any(p["intended_pose"] == "profile" for p in pairs))

    def test_one_locked_engine_and_pinned_digests(self):
        pairs = rc.plan_pairs()
        self.assertEqual({p["engine"] for p in pairs}, {rc.CANDIDATE_ENGINE})
        self.assertEqual({p["model_sha256"] for p in pairs}, {rc.probe.DOWNLOADS["klein-Q4_0.gguf"][1]})
        self.assertEqual({p["adapter_sha256"] for p in pairs}, {rc.probe.DOWNLOADS["loras/bfs_head_v1.safetensors"][1]})
        self.assertEqual(len({p["seed"] for p in pairs}), len(pairs))

    def test_people_are_synthetic_prompts(self):
        for name, (prompt, seed) in rc.PEOPLE.items():
            self.assertRegex(prompt, r"^(waist-up|head and shoulders) (portrait )?photo of an? ", name)
            self.assertIsInstance(seed, int)
        for name in rc.probe.FIXTURES:  # IDS-3 people keep their recipe
            self.assertEqual(rc.PEOPLE[name][0], rc.probe.FIXTURES[name])

    def test_pose_tag_from_yaw(self):
        self.assertEqual(rc.pose_tag_from_yaw(0.05), "frontal")
        self.assertEqual(rc.pose_tag_from_yaw(-0.69), "three_quarter")
        self.assertEqual(rc.pose_tag_from_yaw(1.4), "profile")
        self.assertEqual(rc.pose_tag_from_yaw(None), "profile")

    def test_fence_hash_matches_evaluator(self):
        bits = [0, 1, 1, 0, 0, 1]
        got = quality.check_outside_fence([5] * 6, [5] * 6, bits)
        self.assertEqual(rc.fence_hash_from_bits(bytes(bits)), got["fence_hash"])

    def test_head_span(self):
        face = [0, 0, 10, 10, 10, 20, 30, 20, 20, 25, 12, 44, 28, 44]  # eyes at y=20, mouth at y=44
        self.assertEqual(rc.head_span(face), 24.0)
        self.assertIsNone(rc.head_span(None))

    def test_sheet_order_is_frozen(self):
        ids = [p["pair_id"] for p in rc.plan_pairs()]
        a, b = rc.sheet_order(ids), rc.sheet_order(ids)
        self.assertEqual(a, b)
        self.assertEqual(set(a.values()), {("candidate", "baseline"), ("baseline", "candidate")})


class FreezeTest(unittest.TestCase):
    """The committed cohort.json is the frozen plan, locked before any result."""

    @classmethod
    def setUpClass(cls):
        cls.cohort = load(rc.COHORT_PATH)

    def test_matches_plan(self):
        frozen = [{k: v for k, v in p.items() if k not in RUNTIME_FIELDS} for p in self.cohort["pairs"]]
        self.assertEqual(frozen, rc.plan_pairs())
        for p in self.cohort["pairs"]:
            for k in ("target_sha256", "reference_sha256", "fence_hash"):
                self.assertRegex(p[k], HEX64, f"{p['pair_id']} {k}")
            self.assertGreater(p["fence_inside_pixels"], 0)
            self.assertLess(p["fence_inside_pixels"], p["fence_total_pixels"])  # never all-frame
            self.assertEqual(p["target_sha256"], self.cohort["people"][p["target_id"]]["sha256"])
            self.assertEqual(p["reference_sha256"], self.cohort["people"][p["reference_id"]]["sha256"])
            self.assertEqual(p["pose_tag"], rc.pose_tag_from_yaw(p["target_yaw_proxy"]))

    def test_digest_and_preregistration(self):
        self.assertEqual(rc.pairs_digest(self.cohort["pairs"]), self.cohort["pairs_digest"])
        self.assertEqual(self.cohort["rubric_revision"], quality.RUBRIC_REVISION)
        self.assertTrue(self.cohort["frozen_before_results"])
        status, problems = quality.check_cohort(self.cohort)
        self.assertEqual(status, quality.PASS, problems)

    def test_tamper_is_detected(self):
        c = copy.deepcopy(self.cohort)
        c["pairs"][0]["seed"] += 1
        self.assertNotEqual(rc.pairs_digest(c["pairs"]), c["pairs_digest"])

    def test_people_match_plan(self):
        self.assertEqual(set(self.cohort["people"]), set(rc.PEOPLE))
        for name, (prompt, seed) in rc.PEOPLE.items():
            person = self.cohort["people"][name]
            self.assertEqual((person["prompt"], person["seed"]), (prompt, seed))
            self.assertRegex(person["sha256"], HEX64)


class ResumeTest(unittest.TestCase):
    def test_restart_loses_at_most_the_pair_in_flight(self):
        ids = [f"f{i:02d}" for i in range(1, 21)]
        with tempfile.TemporaryDirectory() as tmp:
            work, calls = Path(tmp), []
            done = lambda pid: rc.checkpoint_path(work, pid).exists()  # noqa: E731

            def crashing(pid):
                calls.append(pid)
                if pid == "f07":
                    raise KeyboardInterrupt  # session restart mid-pair: no checkpoint written
                rc.write_atomic(rc.checkpoint_path(work, pid), json.dumps({"pair_id": pid}))

            with self.assertRaises(KeyboardInterrupt):
                rc.run_pending(ids, done, crashing)
            self.assertEqual(sum(done(p) for p in ids), 6)

            def ok(pid):
                calls.append(pid)
                rc.write_atomic(rc.checkpoint_path(work, pid), json.dumps({"pair_id": pid}))

            ran = rc.run_pending(ids, done, ok)
            self.assertEqual(ran, ids[6:])
            self.assertEqual(calls.count("f01"), 1)  # finished pairs never repeat
            self.assertEqual(rc.run_pending(ids, done, ok), [])
            self.assertEqual(list((work / "pairs").glob("*.tmp")), [])

    def test_only_filter(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            ran = rc.run_pending(["a", "b"], lambda p: False,
                                 lambda p: rc.write_atomic(rc.checkpoint_path(work, p), "{}"), only=["b"])
            self.assertEqual(ran, ["b"])


class RowFormatTest(unittest.TestCase):
    AUTOMATIC = ("classification", "target_pose_preserved", "outside_fence", "target_head_span", "result_head_span",
                 "output_mask_hash", "identity", "head_scale_rel_error", "licence_evidence", "claimed_success",
                 "hair_votes", "seam_votes", "baseline")

    def check_fields(self, row):
        for k in RowFormatTest.AUTOMATIC:
            state, _ = quality.measured(row, k)
            self.assertIn(state, ("ok", "not_measured"), f"{row['pair_id']} {k}: {row.get(k)}")
        for k in ("hair_votes", "seam_votes"):
            self.assertEqual(quality.measured(row, k)[0], "not_measured")

    def test_rows_from_checkpoints(self):
        pairs = rc.plan_pairs()
        for p in pairs:
            p["pose_tag"] = p["intended_pose"]
        rows = [rc.build_row(p, fake_stage(), fake_stage(cls="face_only")) for p in pairs]
        for r in rows:
            self.check_fields(r)
            ident = quality.measured(r, "identity")[1]
            self.assertEqual([x["source"] for x in ident], ["sface", "glintr100"])
            self.assertEqual([x["profile_capable"] for x in ident], [False, True])
        cohort = {"rubric_revision": quality.RUBRIC_REVISION,
                  "pairs": [dict(p, fence_hash="c" * 64) for p in pairs]}
        report = quality.evaluate(cohort, rows)
        self.assertEqual(report["row_problems"], [])
        gates = {g["gate"]: g["status"] for g in report["gates"]}
        self.assertEqual(gates["whole_head_and_pose"], quality.PASS)
        self.assertEqual(gates["edit_fence"], quality.PASS)
        self.assertEqual(gates["new_hair_hairline"], quality.INSUFFICIENT)  # votes are the follow-up
        self.assertEqual(gates["profile_identity"], quality.INSUFFICIENT)  # one profile-capable method only
        self.assertNotEqual(report["verdict"], quality.PASS)

    def test_failed_pair_stays_with_reasons(self):
        p = dict(rc.plan_pairs()[9], pose_tag="profile")
        row = rc.build_row(p, fake_stage(status="failed"), None)
        self.check_fields(row)
        self.assertEqual(quality.measured(row, "classification")[0], "not_measured")
        self.assertIn("no face", row["classification"]["reason"])
        self.assertIn("not run", row["baseline"]["reason"])

    def test_missing_pose_is_not_inferred(self):
        row = rc.build_row(dict(rc.plan_pairs()[0], pose_tag="frontal"), fake_stage(pose=None, rs=None, sface=None, glint=None), None)
        self.check_fields(row)
        self.assertEqual(quality.measured(row, "target_pose_preserved")[0], "not_measured")
        self.assertEqual(quality.measured(row, "head_scale_rel_error")[0], "not_measured")
        self.assertEqual({x["outcome"] for x in quality.measured(row, "identity")[1]}, {"abstain"})


def frozen_pair(i=0):
    p = dict(rc.plan_pairs()[i], target_sha256="1" * 64, reference_sha256="2" * 64, fence_hash="3" * 64)
    p["pose_tag"] = p["intended_pose"]
    return p


FAKE_COHORT = {"pairs_digest": "9" * 64,
               "candidate": {"components_sha256": {"klein-Q4_0.gguf": "a" * 64, "loras/bfs_head_v1.safetensors": "b" * 64}}}


class ProvenanceTest(unittest.TestCase):
    """Resume and export accept only checkpoints bound to the frozen cohort with unchanged outputs."""

    def make(self, tmp, stage="candidate", status="ok"):
        pair, d = frozen_pair(), Path(tmp)
        for name in rc.stage_outputs(pair["pair_id"], stage):
            (d / name).write_bytes(name.encode())
        rec = {"pair_id": pair["pair_id"], "stage": stage, "status": status,
               "binding": rc.binding(FAKE_COHORT, pair, stage),
               "outputs_sha256": {n: rc.file_sha256(d / n) for n in rc.stage_outputs(pair["pair_id"], stage)}}
        if stage == "candidate":
            rec["files_sha256"] = dict(FAKE_COHORT["candidate"]["components_sha256"])
        return pair, d, rec

    def test_bound_checkpoint_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            for stage in rc.STAGES:
                pair, d, rec = self.make(tmp, stage)
                self.assertEqual(rc.validate_checkpoint(FAKE_COHORT, pair, stage, rec, d), [])

    def test_altered_output_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            pair, d, rec = self.make(tmp)
            (d / f"{pair['pair_id']}.candidate.png").write_bytes(b"replaced")
            self.assertTrue(any("differs from its recorded hash" in x for x in rc.validate_checkpoint(FAKE_COHORT, pair, "candidate", rec, d)))
            (d / f"{pair['pair_id']}.candidate.png").unlink()
            self.assertTrue(any("missing" in x for x in rc.validate_checkpoint(FAKE_COHORT, pair, "candidate", rec, d)))

    def test_stale_or_foreign_checkpoint_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            pair, d, rec = self.make(tmp)
            other = dict(FAKE_COHORT, pairs_digest="8" * 64)
            self.assertTrue(rc.validate_checkpoint(other, pair, "candidate", rec, d))  # another freeze
            self.assertTrue(rc.validate_checkpoint(FAKE_COHORT, dict(pair, seed=pair["seed"] + 1), "candidate", rec, d))
            self.assertTrue(rc.validate_checkpoint(FAKE_COHORT, dict(pair, target_sha256="4" * 64), "candidate", rec, d))
            self.assertTrue(rc.validate_checkpoint(FAKE_COHORT, pair, "baseline", rec, d))  # wrong stage
            self.assertTrue(rc.validate_checkpoint(FAKE_COHORT, pair, "candidate", {k: v for k, v in rec.items() if k != "binding"}, d))
            self.assertTrue(rc.validate_checkpoint(FAKE_COHORT, pair, "candidate", dict(rec, outputs_sha256=None), d))

    def test_component_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            pair, d, rec = self.make(tmp)
            rec["files_sha256"] = dict(rec["files_sha256"], **{"qwen3-4b-Q4_K_M.gguf": "e" * 64})
            self.assertTrue(any("frozen components" in x for x in rc.validate_checkpoint(FAKE_COHORT, pair, "candidate", rec, d)))

    def test_failed_pair_needs_binding_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            pair, d, rec = self.make(tmp, status="failed")
            self.assertEqual(rc.validate_checkpoint(FAKE_COHORT, pair, "candidate", dict(rec, outputs_sha256=None), d), [])

    def test_resume_fails_closed_on_bad_checkpoint(self):
        def done(pid):
            raise RuntimeError("binding differs")
        with self.assertRaises(RuntimeError):
            rc.run_pending(["f01"], done, lambda pid: None)

    def test_bind_legacy(self):
        with tempfile.TemporaryDirectory() as tmp:
            pair, d, rec = self.make(tmp)
            legacy = {k: v for k, v in rec.items() if k not in ("binding", "outputs_sha256")}
            bound = rc.bind_legacy(FAKE_COHORT, pair, "candidate", legacy, d)
            self.assertEqual(rc.validate_checkpoint(FAKE_COHORT, pair, "candidate", bound, d), [])
            self.assertIn("backfilled", bound["binding_recorded"])
            with self.assertRaises(ValueError):
                rc.bind_legacy(FAKE_COHORT, pair, "candidate", bound, d)  # never re-bind
            with self.assertRaises(ValueError):
                rc.bind_legacy(FAKE_COHORT, pair, "candidate", dict(legacy, files_sha256={"x": "y"}), d)
            with self.assertRaises(ValueError):
                rc.bind_legacy(FAKE_COHORT, pair, "baseline", legacy, d)


class GateSummaryTest(unittest.TestCase):
    def reconcile(self, summary, report, pids):
        w = summary["whole_head_and_pose"]
        self.assertEqual(sorted(w["passed"] + list(w["failed"])), sorted(pids))
        self.assertFalse(set(w["passed"]) & set(w["failed"]))
        self.assertTrue(all(w["failed"].values()))  # every failed pair names a reason
        gate = {g["gate"]: g for g in report["gates"]}
        self.assertEqual(len(w["passed"]), gate["whole_head_and_pose"]["passed"])
        self.assertEqual(summary["denominator"], gate["whole_head_and_pose"]["denominator"])
        f = summary["edit_fence"]
        self.assertEqual(sorted(f["passed"] + list(f["failed"])), sorted(pids))
        sc = summary["scale"]
        self.assertEqual(sorted(list(sc["errors"]) + list(sc["not_measured"])), sorted(pids))
        self.assertFalse(set(sc["errors"]) & set(sc["not_measured"]))

    def test_partition_with_failures(self):
        pairs = [frozen_pair(i) for i in range(20)]
        stages = [fake_stage(cls="no_swap") if i < 5 else fake_stage(pose=False) if i < 7 else
                  fake_stage(status="failed") if i == 7 else fake_stage() for i in range(20)]
        rows = [rc.build_row(p, c, None) for p, c in zip(pairs, stages)]
        cohort = {"rubric_revision": quality.RUBRIC_REVISION, "pairs": pairs}
        report = quality.evaluate(cohort, rows)
        summary = rc.gate_summary(cohort, rows, report)
        self.reconcile(summary, report, [p["pair_id"] for p in pairs])
        w = summary["whole_head_and_pose"]
        self.assertEqual(len(w["passed"]), 12)
        self.assertEqual(w["failed"]["f01"], ["classification=no_swap"])
        self.assertEqual(w["failed"]["f06"], ["target pose not preserved"])
        self.assertTrue(w["failed"]["f08"][0].startswith("classification not measured"))
        self.assertIn("profile-capable", summary["profile_identity"]["pairs"]["f01"]["reason"])


class CommittedResultsTest(unittest.TestCase):
    """The rows and sheets from the real run, once committed."""

    def setUp(self):
        if not (rc.RESULTS / "rows.json").exists():
            self.skipTest("no committed run yet")
        self.cohort, self.rows = load(rc.COHORT_PATH), load(rc.RESULTS / "rows.json")

    def test_one_row_per_frozen_pair(self):
        self.assertEqual([r["pair_id"] for r in self.rows], [p["pair_id"] for p in self.cohort["pairs"]])
        for r in self.rows:
            RowFormatTest.check_fields(self, r)
            for k, pin in (("model_file_sha256", "model_sha256"), ("adapter_file_sha256", "adapter_sha256")):
                if k in r:
                    self.assertEqual(r[k]["value"], r[pin])

    def test_gate_summary_lists_every_id(self):
        report = load(rc.RESULTS / "evaluator-report.json")
        summary = load(rc.RESULTS / "gate-summary.json")
        self.assertEqual(summary, json.loads(json.dumps(rc.gate_summary(self.cohort, self.rows, report))))
        GateSummaryTest.reconcile(self, summary, report, [p["pair_id"] for p in self.cohort["pairs"]])

    def test_provenance_binds_every_stage(self):
        prov = load(rc.RESULTS / "provenance.json")
        self.assertEqual(prov["cohort_digest"], self.cohort["pairs_digest"])
        self.assertTrue(prov["fixtures_sha256_verified"])
        for p in self.cohort["pairs"]:
            for stage in rc.STAGES:
                entry = prov["pairs"][p["pair_id"]][stage]
                self.assertEqual(entry["binding"], rc.binding(self.cohort, p, stage))
                self.assertTrue(entry["binding_recorded"])
                self.assertRegex(entry["checkpoint_sha256"], HEX64)
                if entry["status"] == "ok":
                    self.assertEqual(sorted(entry["outputs_sha256"]), sorted(rc.stage_outputs(p["pair_id"], stage)))
                if stage == "candidate":
                    self.assertEqual(entry["components_sha256"], self.cohort["candidate"]["components_sha256"])

    def test_rows_carry_all_frozen_components(self):
        for r in self.rows:
            if quality.measured(r, "classification")[0] == "ok":
                self.assertEqual(r["component_files_sha256"]["value"], self.cohort["candidate"]["components_sha256"])

    def test_report_is_the_evaluator_output(self):
        report = load(rc.RESULTS / "evaluator-report.json")
        self.assertEqual(report, json.loads(json.dumps(quality.evaluate(self.cohort, self.rows), sort_keys=True)))
        self.assertNotEqual(report["verdict"], quality.PASS)  # judge votes are not collected in this unit

    def test_sheets_blinded_without_metadata(self):
        sheets = sorted((rc.RESULTS / "sheets").glob("*.jpg"))
        self.assertEqual([s.stem for s in sheets], [p["pair_id"] for p in self.cohort["pairs"]])
        for s in sheets:
            self.assertFalse(rc.jpeg_has_metadata(s.read_bytes()), s.name)
        key = load(rc.RESULTS / "sheet-key.json")
        order = rc.sheet_order([p["pair_id"] for p in self.cohort["pairs"]], self.cohort["blind_seed"])
        self.assertEqual(key["pairs"], {pid: {"A": a, "B": b} for pid, (a, b) in order.items()})
        self.assertFalse(list((rc.RESULTS / "sheets").glob("*key*")))  # the key lives outside the sheets folder

    def test_no_raw_images_committed(self):
        allowed = {".json", ".jpg", ".csv", ".md"}  # no PNG outputs, fixtures or weights
        for f in rc.RESULTS.rglob("*"):
            if f.is_file():
                self.assertIn(f.suffix, allowed, f)


class JpegMetadataTest(unittest.TestCase):
    def test_detects_exif_and_comment(self):
        jfif = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00\xff\xda\x00\x02"
        self.assertFalse(rc.jpeg_has_metadata(jfif))
        exif = b"\xff\xd8\xff\xe1\x00\x08Exif\x00\x00\xff\xda\x00\x02"
        self.assertTrue(rc.jpeg_has_metadata(exif))
        com = b"\xff\xd8\xff\xfe\x00\x05hi!\xff\xda\x00\x02"
        self.assertTrue(rc.jpeg_has_metadata(com))
        self.assertTrue(rc.jpeg_has_metadata(b"\x89PNG"))


if __name__ == "__main__":
    unittest.main()
