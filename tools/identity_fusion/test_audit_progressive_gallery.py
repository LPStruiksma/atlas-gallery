#!/usr/bin/env python3
"""Only fabricated person tokens and scores: no images, models or real data."""
from __future__ import annotations

import contextlib
import copy
import csv
import io
import json
from pathlib import Path
import tempfile
import unittest

from audit_progressive_gallery import (
    ORDER_FIELDS, _append_candidate, _validate_order, audit_enrollment_curve,
    main,
)
from audit_gallery_growth import _inventory
from benchmark_reid_scores import FIELDS
from test_audit_gallery_growth import (
    scored_snapshots, provenance_for_fictional_rosters,
)
from verify_split_provenance import FIELDS as SOURCE_FIELDS


def ranking(reverse_holdout=False):
    rows = []
    for split in ("calibration", "holdout"):
        candidates = [split + "New_0", split + "New_1"]
        if reverse_holdout and split == "holdout":
            candidates.reverse()
        for rank, candidate in enumerate(candidates, 1):
            rows.append(dict(split=split, candidate_uid=candidate,
                             enrollment_rank=str(rank)))
    return rows


class ProgressiveGalleryExposureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.old, cls.expanded = scored_snapshots()

    def test_first_enrollment_creates_four_new_unknown_and_two_known_errors(self):
        report = audit_enrollment_curve(self.old, self.expanded, ranking())
        self.assertEqual(report["num_enrollment_steps"], 2)
        self.assertEqual(report["floor_locked_from_original_calibration"], .9)
        self.assertEqual([step["enrolled_added_candidates_per_split"]
                          for step in report["curve"]], [1, 2])
        self.assertEqual([step["holdout"]["new_unsafe_unknown_offers"]
                          for step in report["curve"]], [4, 4])
        self.assertEqual([step["holdout"]["new_unsafe_known_offers"]
                          for step in report["curve"]], [2, 2])
        exposure = report["ever_exposed_summary"]["holdout"]
        self.assertEqual(exposure["first_unsafe_unknown_exposure_by_step"],
                         [4, 0])
        self.assertEqual(exposure["first_unsafe_known_exposure_by_step"],
                         [2, 0])
        self.assertEqual(exposure["transient_unsafe_unknown_observations"], 0)
        self.assertEqual(exposure["transient_unsafe_known_observations"], 0)
        self.assertTrue(report["expanded_snapshot_matching_recomputed"])
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertFalse(report["production_approved"])
        self.assertFalse(report["actual_prior_preregistration_and_capture_independence_proven"])
        as_text = json.dumps(report)
        for secret in ("holdoutProbe", "holdoutNew_0", "holdoutSession",
                       "candidate_uid", "probe_uid", "/storage/emulated"):
            self.assertNotIn(secret, as_text)
        self.assertEqual(
            report,
            audit_enrollment_curve(
                list(reversed(self.old)), list(reversed(self.expanded)),
                list(reversed(ranking()))))

    def test_same_final_gallery_but_different_order_has_distinct_intermediate_risk(self):
        ordinary = audit_enrollment_curve(self.old, self.expanded, ranking())
        inverted = audit_enrollment_curve(
            self.old, self.expanded, ranking(reverse_holdout=True))
        self.assertEqual(ordinary["curve"][-1]["holdout"],
                         inverted["curve"][-1]["holdout"])
        self.assertEqual(ordinary["curve"][0]["holdout"]["new_unsafe_unknown_offers"], 4)
        self.assertEqual(inverted["curve"][0]["holdout"]["new_unsafe_unknown_offers"], 0)
        self.assertEqual(inverted["ever_exposed_summary"]["holdout"][
                         "first_unsafe_unknown_exposure_by_step"], [0, 4])

    def test_ephemeral_harm_hidden_by_safe_final_gallery_exact_tie(self):
        newer = copy.deepcopy(self.expanded)
        for row in newer:
            if row["split"] != "holdout" or row["candidate_uid"] != "holdoutNew_1":
                continue
            if ((row["truth_uid"] == "UNKNOWN" and
                 int(row["probe_uid"].rsplit("_", 1)[1]) < 4) or
                (row["truth_uid"] != "UNKNOWN" and
                 int(row["probe_uid"].rsplit("_", 1)[1]) < 2)):
                row["similarity"] = ".96"
        curve = audit_enrollment_curve(self.old, newer, ranking())
        early, final = [stage["holdout"] for stage in curve["curve"]]
        self.assertEqual(early["new_unsafe_unknown_offers"], 4)
        self.assertEqual(early["new_unsafe_known_offers"], 2)
        self.assertEqual(final["new_unsafe_unknown_offers"], 0)
        self.assertEqual(final["new_unsafe_known_offers"], 0)
        exposure = curve["ever_exposed_summary"]["holdout"]
        self.assertEqual(exposure["new_unsafe_unknown_ever_exposed_observations"], 4)
        self.assertEqual(exposure["transient_unsafe_unknown_observations"], 4)
        self.assertEqual(exposure["transient_unsafe_known_observations"], 2)
        self.assertEqual(exposure["maximum_simultaneous_new_unsafe_unknown_offers"], 4)
        self.assertFalse(curve["production_approved"])

    def test_connected_source_component_risk_stays_grouped_at_each_step(self):
        evidence = provenance_for_fictional_rosters(self.old)
        curve = audit_enrollment_curve(
            self.old, self.expanded, ranking(),
            provenance_rows=evidence, require_provenance=True)
        for stage in curve["curve"]:
            component = stage["holdout"]["connected_source_component_diagnostics"]
            self.assertEqual(component["components_with_new_unsafe_unknown_offer"], 1)
            self.assertFalse(component["actual_physical_independence_proven"])
        self.assertIsNotNone(curve["provenance_partition_report"])
        self.assertFalse(curve["actual_prior_preregistration_and_capture_independence_proven"])
        with self.assertRaises(ValueError):
            audit_enrollment_curve(
                self.old, self.expanded, ranking(),
                provenance_rows=evidence[:-1], require_provenance=True)
        with self.assertRaises(ValueError):
            audit_enrollment_curve(
                self.old, self.expanded, ranking(), require_provenance=True)

    def test_missing_wrong_out_of_range_duplicate_or_noncanonical_enrollment_refused(self):
        baseline, pool = _inventory(self.old)
        expanded, newpool = _inventory(self.expanded)
        valid = ranking()
        errors = [
            valid[:-1],
            valid + [valid[-1]],
            [dict(row) for row in valid],
            [{**row, "private_photo_path": "/sdcard/p.jpg"} for row in valid],
        ]
        errors[2][0]["candidate_uid"] = "holdoutNew_0"
        for rows in errors:
            with self.subTest(rows=len(rows)), self.assertRaises(ValueError):
                _validate_order(rows, pool, newpool)
        for invalid in ("0", "01", "+1", "1.0", "1e0", "9999", "True", ""):
            bad = copy.deepcopy(valid)
            bad[0]["enrollment_rank"] = invalid
            with self.subTest(raw=invalid), self.assertRaises(ValueError):
                _validate_order(bad, pool, newpool)
        bad = copy.deepcopy(valid)
        bad[0]["enrollment_rank"] = 1
        with self.assertRaises(ValueError):
            _validate_order(bad, pool, newpool)
        with self.assertRaises(ValueError):
            _validate_order([], pool, newpool)

    def test_staged_score_tie_and_runner_gap_match_full_parse(self):
        trial = ("P", "S", "back", "UNKNOWN", .9, "Old", False, .7)
        a, second = _append_candidate(trial, .2, "NewA", ".96")
        self.assertEqual(a[5], "NewA")
        self.assertEqual(a[7], .96 - .9)
        b, second = _append_candidate(a, second, "NewB", ".96")
        self.assertTrue(b[6])
        self.assertEqual(b[5], "NewA")
        self.assertEqual(b[7], 0)
        c, second = _append_candidate(b, second, "NewC", ".99")
        self.assertFalse(c[6])
        self.assertEqual(c[7], .99 - .96)
        self.assertEqual(c[5], "NewC")

    def test_changed_original_scores_missing_probes_or_gallery_entries_refused(self):
        changed = copy.deepcopy(self.expanded)
        for row in changed:
            if row["probe_uid"] == "holdoutProbe_unknown_0" and row[
                "candidate_uid"] == "holdoutKnown_0":
                row["similarity"] = ".21"
                break
        with self.assertRaises(ValueError):
            audit_enrollment_curve(self.old, changed, ranking())
        changed = [row for row in self.expanded
                   if not (row["probe_uid"] == "holdoutProbe_unknown_0"
                           and row["candidate_uid"] == "holdoutNew_1")]
        with self.assertRaises(ValueError):
            audit_enrollment_curve(self.old, changed, ranking())
        with self.assertRaises(ValueError):
            audit_enrollment_curve(self.old, self.old, ranking())

    def test_cli_aggregates_only_and_does_not_write_score_or_sidecar_files(self):
        with tempfile.TemporaryDirectory(prefix="fictional-progressive-v5-") as tmp:
            root = Path(tmp)
            oldp, newp = root / "old.csv", root / "new.csv"
            orderp, sourcep = root / "order.csv", root / "source.csv"
            for path, rows, headers in (
                (oldp, self.old, FIELDS),
                (newp, self.expanded, FIELDS),
                (orderp, ranking(), ORDER_FIELDS),
                (sourcep, provenance_for_fictional_rosters(self.old),
                 SOURCE_FIELDS),
            ):
                with path.open("w", newline="", encoding="utf-8") as output:
                    writer = csv.DictWriter(output, fieldnames=headers)
                    writer.writeheader()
                    writer.writerows(rows)
            original = {p: p.read_bytes() for p in (oldp, newp, orderp, sourcep)}
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(main([str(oldp), str(newp), str(orderp),
                                       "--provenance-csv", str(sourcep),
                                       "--require-provenance"]), 0)
            parsed = json.loads(out.getvalue())
            self.assertEqual(parsed["num_enrollment_steps"], 2)
            self.assertEqual(parsed["curve"][0]["holdout"][
                             "new_unsafe_unknown_offers"], 4)
            self.assertEqual(original,
                             {p: p.read_bytes() for p in original})
            orderp.write_text("split,candidate_uid,enrollment_rank,secret\n"
                              "holdout,/secret/private_photo.jpg,1,yes\n")
            error = io.StringIO()
            with contextlib.redirect_stderr(error):
                self.assertEqual(main([str(oldp), str(newp), str(orderp)]), 2)
            self.assertNotIn(tmp, error.getvalue())
            self.assertNotIn("private_photo", error.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
