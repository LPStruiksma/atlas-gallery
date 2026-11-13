#!/usr/bin/env python3
"""Fictional rear/partial/occlusion tags: no model, photos, or People DB."""
from __future__ import annotations

import contextlib
import copy
import csv
import io
import json
from pathlib import Path
import tempfile
import unittest

from audit_joint_visibility import audit_joint_visibility, main
from benchmark_reid_scores import FIELDS
from test_audit_gallery_growth import (
    provenance_for_fictional_rosters, scored_snapshots,
)
from verify_split_provenance import FIELDS as PROVENANCE_FIELDS


def relabel_probe(old, new, probe, condition):
    updated_old, updated_new = copy.deepcopy(old), copy.deepcopy(new)
    for rows in (updated_old, updated_new):
        for row in rows:
            if row["probe_uid"] == probe:
                row["condition"] = condition
    return updated_old, updated_new


class VisibilityJointAuditTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.old, cls.new = scored_snapshots()

    def test_exact_conditions_and_marginal_tags_are_different_truth_cohorts(self):
        report = audit_joint_visibility(
            self.old, self.new,
            require_unknown_tags=("back", "lookalike"),
            require_unknown_intersections=("back+lookalike",))
        h = report["holdout"]
        exact = h["exact_intersections"]["cohorts"]
        tagged = h["overlapping_tags"]["cohorts"]
        self.assertEqual(set(exact), {"back", "back+lookalike"})
        self.assertEqual(set(tagged), {"back", "lookalike"})
        self.assertEqual((exact["back"]["known_reviewed_observations"],
                          exact["back"]["unknown_reviewed_observations"]),
                         (25, 0))
        self.assertEqual((exact["back+lookalike"]["known_reviewed_observations"],
                          exact["back+lookalike"]["unknown_reviewed_observations"]),
                         (0, 25))
        self.assertEqual(exact["back"]["peak_joint_new_unsafe_observations"], 2)
        self.assertEqual(exact["back+lookalike"][
                         "peak_new_unsafe_unknown_observations"], 4)
        self.assertEqual(tagged["back"]["peak_joint_new_unsafe_observations"], 6)
        self.assertEqual(tagged["lookalike"]["peak_joint_new_unsafe_observations"], 4)
        self.assertEqual(tagged["back"]["max_joint_new_unsafe_by_gallery_size"],
                         [0, 6, 6])
        self.assertEqual(tagged["back"]["unknown_reviewed_observations"], 25)
        self.assertEqual(tagged["back"]["known_reviewed_observations"], 25)
        self.assertFalse(report["sufficient_empirical_protection_or_accuracy_proven"])
        self.assertFalse(report["holdout_selected_model_or_threshold"])
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertFalse(report["production_approved"])
        self.assertTrue(report["overlapping_condition_tags_are_not_disjoint_samples"])
        self.assertEqual(report, audit_joint_visibility(
            list(reversed(self.old)), list(reversed(self.new)),
            require_unknown_tags=("lookalike", "back"),
            require_unknown_intersections=("back+lookalike",)))
        serial = json.dumps(report)
        for secret in ("holdoutProbe", "holdoutNew_", "holdoutSession",
                       "probe_uid", "candidate_uid", "/storage/emulated"):
            self.assertNotIn(secret, serial)

    def test_tiny_sparse_occlusion_cohort_is_suppressed_not_marked_safe(self):
        old, new = relabel_probe(
            self.old, self.new, "holdoutProbe_unknown_0", "back+occluded")
        result = audit_joint_visibility(old, new)
        h = result["holdout"]
        self.assertNotIn("back+occluded", h["exact_intersections"]["cohorts"])
        self.assertNotIn("occluded", h["overlapping_tags"]["cohorts"])
        self.assertEqual(h["exact_intersections"][
                         "suppressed_underpowered_cohort_count"], 1)
        self.assertEqual(h["overlapping_tags"][
                         "suppressed_underpowered_cohort_count"], 1)
        self.assertFalse(result["sparse_condition_cohorts_suppressed_not_declared_safe"]
                         is False)
        for requirement in (
            {"require_unknown_tags": ["occluded"]},
            {"require_unknown_intersections": ["back+occluded"]},
        ):
            with self.subTest(requirement=requirement), self.assertRaisesRegex(
                    ValueError, "Protected|Missing"):
                audit_joint_visibility(old, new, **requirement)

    def test_single_known_person_inside_unknown_stratum_suppresses_full_cohort(self):
        old, new = relabel_probe(
            self.old, self.new, "holdoutProbe_known_0", "back+lookalike")
        result = audit_joint_visibility(old, new)
        self.assertNotIn("back+lookalike",
                         result["holdout"]["exact_intersections"]["cohorts"])
        self.assertEqual(result["holdout"]["exact_intersections"][
                         "suppressed_underpowered_cohort_count"], 1)
        with self.assertRaisesRegex(ValueError, "Protected"):
            audit_joint_visibility(
                old, new, require_unknown_intersections=("back+lookalike",))

    def test_protected_missing_or_unreviewed_unknown_conditions_fail_closed(self):
        for kwargs in (
            {"require_unknown_tags": ("occluded",)},
            {"require_unknown_tags": ("partial",)},
            {"require_unknown_intersections": ("back+occluded",)},
            {"require_unknown_tags": ("unknown",)},
            {"require_unknown_tags": ("back", "back")},
            {"require_unknown_tags": ("front+back",)},
            {"require_unknown_intersections": ("lookalike+back",)},
            {"require_unknown_intersections": ("back",)},
            {"require_unknown_intersections": ("back+unknown",)},
            {"require_unknown_tags": ("back",) * 9},
        ):
            with self.subTest(protected=kwargs), self.assertRaises(ValueError):
                audit_joint_visibility(self.old, self.new, **kwargs)

    def test_provenance_groups_four_pictures_as_one_declared_unknown_source(self):
        rows = provenance_for_fictional_rosters(self.old)
        result = audit_joint_visibility(
            self.old, self.new, provenance_rows=rows, require_provenance=True,
            require_unknown_tags=("back",),
            require_unknown_intersections=("back+lookalike",))
        h = result["holdout"]["exact_intersections"]["cohorts"][
            "back+lookalike"]
        self.assertEqual(h["peak_new_unsafe_unknown_observations"], 4)
        self.assertEqual(
            h["max_unknown_declared_source_components_by_gallery_size"],
            [0, 1, 1])
        self.assertFalse(h["physical_capture_independence_proven"])
        with self.assertRaises(ValueError):
            audit_joint_visibility(self.old, self.new, require_provenance=True)
        with self.assertRaises(ValueError):
            audit_joint_visibility(
                self.old, self.new, provenance_rows=rows[:-1],
                require_provenance=True)

    def test_no_score_mutation_no_deleted_probe_or_retrained_model_accepted(self):
        with self.assertRaises(ValueError):
            audit_joint_visibility(self.old, self.old)
        changed = copy.deepcopy(self.new)
        changed[0]["similarity"] = ".999"
        with self.assertRaises(ValueError):
            audit_joint_visibility(self.old, changed)
        incomplete = self.new[:-1]
        with self.assertRaises(ValueError):
            audit_joint_visibility(self.old, incomplete)
        with self.assertRaises(ValueError):
            audit_joint_visibility(
                self.old, self.new, min_top_two_margin=-.1)

    def test_read_only_cli_privacy_and_required_conditions(self):
        with tempfile.TemporaryDirectory(prefix="fictional-v5-cohort-") as tmp:
            root = Path(tmp)
            p1, p2, p3 = (
                root / "baseline.csv", root / "expanded.csv",
                root / "groups.csv")
            for path, rows, fields in (
                (p1, self.old, FIELDS),
                (p2, self.new, FIELDS),
                (p3, provenance_for_fictional_rosters(self.old), PROVENANCE_FIELDS),
            ):
                with path.open("w", encoding="utf-8", newline="") as stream:
                    writer = csv.DictWriter(stream, fieldnames=fields)
                    writer.writeheader()
                    writer.writerows(rows)
            before = {path: path.read_bytes() for path in (p1, p2, p3)}
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(main([
                    str(p1), str(p2), "--provenance-csv", str(p3),
                    "--require-provenance", "--require-unknown-tag", "back",
                    "--require-unknown-intersection", "back+lookalike",
                ]), 0)
            report = json.loads(output.getvalue())
            self.assertEqual(report["holdout"]["overlapping_tags"][
                "cohorts"]["back"]["peak_joint_new_unsafe_observations"], 6)
            self.assertNotIn("holdoutProbe", output.getvalue())
            self.assertNotIn("SharedLineage", output.getvalue())
            self.assertEqual(before, {path: path.read_bytes() for path in before})
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(main([
                    str(p1), str(p2), "--require-unknown-tag", "occluded",
                ]), 2)
            self.assertEqual(output.getvalue(), "")
            p3.write_text("photo_path,name\n/private/portrait.jpg,Owner\n")
            error = io.StringIO()
            with contextlib.redirect_stderr(error):
                self.assertEqual(main([
                    str(p1), str(p2), "--provenance-csv", str(p3),
                    "--require-provenance"]), 2)
            self.assertNotIn("Owner", error.getvalue())
            self.assertNotIn(tmp, error.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
