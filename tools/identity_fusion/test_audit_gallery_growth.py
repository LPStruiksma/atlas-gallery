#!/usr/bin/env python3
"""Fictional identities and deterministic cosine scores; no model or image IO."""
from __future__ import annotations

import contextlib
import copy
import csv
import io
import json
from pathlib import Path
import tempfile
import unittest

from audit_gallery_growth import _inventory, audit_gallery_expansion, main
from benchmark_reid_scores import FIELDS
from verify_split_provenance import FIELDS as PROVENANCE_FIELDS


def scored_snapshots():
    """Two fully crossed scored galleries, same independently labelled probes."""
    baseline, expanded = [], []
    for split, known_count, unknown_count in (
        ("calibration", 60, 100), ("holdout", 25, 25)
    ):
        old_names = [f"{split}Known_{j}" for j in range(known_count)]
        new_names = [f"{split}New_0", f"{split}New_1"]
        for kind, count in (("known", known_count), ("unknown", unknown_count)):
            for i in range(count):
                probe = f"{split}Probe_{kind}_{i}"
                truth = old_names[i] if kind == "known" else "UNKNOWN"
                condition = ("back" if kind == "known" else "back+lookalike")
                session = f"{split}Session_{kind}_{i}"
                for candidate in old_names + new_names:
                    if candidate in old_names:
                        score = ".90" if candidate == truth else ".20"
                    else:
                        # A larger gallery gives independently reviewed
                        # holdout Unknown people NEW lookalike impostors.
                        # Separate, newly confusing known-person impostors
                        # exercise the wrong-known risk at the same floor.
                        if split == "holdout" and candidate == new_names[0] and (
                            (kind == "unknown" and i < 4) or
                            (kind == "known" and i < 2)
                        ):
                            score = ".96"
                        else:
                            score = ".10"
                    row = dict(
                        probe_uid=probe, split=split, truth_uid=truth,
                        session_uid=session, condition=condition,
                        candidate_uid=candidate, similarity=score,
                    )
                    expanded.append(row)
                    if candidate in old_names:
                        baseline.append(dict(row))
    return baseline, expanded


def provenance_for_fictional_rosters(rows):
    results, seen = [], set()
    for row in rows:
        probe, split = row["probe_uid"], row["split"]
        if probe in seen:
            continue
        seen.add(probe)
        results.append({
            "probe_uid": probe, "split": split,
            "photo_lineage_uid": "Lineage_" + probe,
            "event_cluster_uid": "Event_" + probe,
            "subject_cluster_uid": (row["truth_uid"] if row["truth_uid"] != "UNKNOWN"
                                    else "UNREVIEWED"),
        })
    # Four separate-scored holdout Unknown observations are one TRANSITIVELY
    # connected capture group even though their sessions remain different.
    subset = [next(p for p in results
                   if p["probe_uid"] == "holdoutProbe_unknown_" + str(i))
              for i in range(4)]
    subset[0]["photo_lineage_uid"] = "SharedLineage"
    subset[1]["photo_lineage_uid"] = "SharedLineage"
    subset[1]["event_cluster_uid"] = "SharedEvent"
    subset[2]["event_cluster_uid"] = "SharedEvent"
    subset[2]["subject_cluster_uid"] = "ReviewedUnknownCluster"
    subset[3]["subject_cluster_uid"] = "ReviewedUnknownCluster"
    return results


class GalleryGrowthSafetyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.old, cls.new = scored_snapshots()

    def test_more_gallery_candidates_cause_new_unknown_and_known_false_offers(self):
        report = audit_gallery_expansion(self.old, self.new)
        self.assertEqual(report["status"], "research_gallery_growth_stress_test_only")
        self.assertEqual(report["locked_floor_selected_only_from_baseline_calibration"], .9)
        self.assertEqual(report["gallery_candidates_by_split"]["calibration"],
                         {"baseline": 60, "expanded": 62, "added": 2})
        self.assertEqual(report["gallery_candidates_by_split"]["holdout"],
                         {"baseline": 25, "expanded": 27, "added": 2})
        h = report["holdout"]
        self.assertEqual(h["known_observations"], 25)
        self.assertEqual(h["unknown_observations"], 25)
        self.assertEqual(h["old_correct_known_offers"], 25)
        self.assertEqual(h["expanded_correct_known_offers"], 23)
        self.assertEqual(h["new_unsafe_unknown_offers"], 4)
        self.assertEqual(h["new_unsafe_known_offers"], 2)
        self.assertEqual(h["lost_correct_known_offers"], 2)
        self.assertEqual(h["sessions_with_new_unsafe_unknown_offer"], 4)
        self.assertEqual(h["sessions_with_new_unsafe_known_offer"], 2)
        self.assertEqual(report["calibration"]["new_unsafe_unknown_offers"], 0)
        self.assertEqual(report["holdout_baseline_metrics"]["unknown_false_offers"], 0)
        self.assertEqual(report["holdout_expanded_metrics"]["unknown_false_offers"], 4)
        for key in ("physical_gallery_exhaustiveness_or_training_split_independence_proven",
                    "gallery_addition_predeclared_before_holdout_verified",
                    "changed_model_weights_or_threshold",
                    "auto_confirm_allowed", "production_approved"):
            self.assertFalse(report[key], key)
        self.assertTrue(report["all_old_scores_frozen"])
        encoded = json.dumps(report)
        for token in ("calibrationKnown_1", "holdoutProbe", "holdoutSession",
                      "candidate_uid", "truth_uid", "/storage"):
            self.assertNotIn(token, encoded)
        self.assertEqual(report, audit_gallery_expansion(
            list(reversed(self.old)), list(reversed(self.new))))

    def test_incomplete_new_gallery_for_single_probe_fails_closed(self):
        old, new = copy.deepcopy(self.old), copy.deepcopy(self.new)
        new = [row for row in new if not (
            row["probe_uid"] == "holdoutProbe_unknown_0" and
            row["candidate_uid"] == "holdoutNew_0")]
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            audit_gallery_expansion(old, new)

    def test_existing_frozen_score_edit_even_small_rounding_is_rejected(self):
        for replacement in (".21", ".200", ".96"):
            old, new = copy.deepcopy(self.old), copy.deepcopy(self.new)
            self.assertEqual(new[0]["similarity"], ".90")
            new[0]["similarity"] = replacement
            with self.subTest(replacement=replacement), self.assertRaisesRegex(
                    ValueError, "Preexisting"):
                audit_gallery_expansion(old, new)

    def test_expansion_cannot_cherry_pick_or_change_reviewed_probe_set(self):
        old, new = copy.deepcopy(self.old), copy.deepcopy(self.new)
        new = [row for row in new if row["probe_uid"] != "holdoutProbe_unknown_0"]
        with self.assertRaises(ValueError):
            audit_gallery_expansion(old, new)
        with self.assertRaisesRegex(ValueError, "additional"):
            audit_gallery_expansion(self.old, self.old)

    def test_expansion_cannot_change_truth_session_or_pose(self):
        for field, value in (("truth_uid", "UNKNOWN"),
                             ("session_uid", "NewUndocumentedSession"),
                             ("condition", "front")):
            old, new = copy.deepcopy(self.old), copy.deepcopy(self.new)
            for row in new:
                if row["probe_uid"] == "holdoutProbe_known_0":
                    row[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                audit_gallery_expansion(old, new)

    def test_known_subject_missing_from_gallery_is_not_counted_as_model_failure(self):
        roster = copy.deepcopy(self.old)
        for row in roster:
            if row["probe_uid"] == "calibrationProbe_known_0":
                row["truth_uid"] = "calibrationNotInGallery"
        with self.assertRaisesRegex(ValueError, "Known truth absent"):
            _inventory(roster)

    def test_unknown_subject_is_never_a_candidate_and_split_leakage_rejected(self):
        old, new = copy.deepcopy(self.old), copy.deepcopy(self.new)
        for row in new:
            if row["candidate_uid"] == "holdoutNew_0":
                row["candidate_uid"] = "calibrationKnown_1"
        with self.assertRaises(ValueError):
            audit_gallery_expansion(old, new)
        old, new = copy.deepcopy(self.old), copy.deepcopy(self.new)
        for row in new:
            if row["candidate_uid"] == "holdoutNew_0":
                row["candidate_uid"] = "UNKNOWN"
        with self.assertRaises(ValueError):
            audit_gallery_expansion(old, new)

    def test_top_two_margin_changes_decisions_but_is_never_retuned_on_holdout(self):
        result = audit_gallery_expansion(
            self.old, self.new, min_top_two_margin=.05)
        self.assertEqual(result["minimum_top_two_margin"], .05)
        self.assertEqual(result["locked_floor_selected_only_from_baseline_calibration"], .9)
        self.assertEqual(result["holdout"]["new_unsafe_unknown_offers"], 4)

    def test_frozen_baseline_cannot_suppress_holdout_hard_negatives(self):
        old, new = copy.deepcopy(self.old), copy.deepcopy(self.new)
        for row in new:
            if row["split"] == "holdout" and row["candidate_uid"] == "holdoutNew_0":
                row["similarity"] = ".98"
        report = audit_gallery_expansion(old, new)
        self.assertEqual(report["locked_floor_selected_only_from_baseline_calibration"], .9)
        self.assertGreater(report["holdout"]["new_unsafe_unknown_offers"], 0)

    def test_empty_truncated_duplicate_private_columns_or_invalid_rate_fail(self):
        for altered in ([],
                        self.old + [self.old[0]],
                        [dict(r, private_photo_path="/sdcard") for r in self.old]):
            with self.assertRaises(ValueError):
                audit_gallery_expansion(altered, self.new)
        with self.assertRaises(ValueError):
            audit_gallery_expansion(self.old, self.new,
                                    max_false_offer_rate=True)


    def test_transitive_declared_sources_collapse_four_unknown_false_trials_into_one(self):
        evidence = provenance_for_fictional_rosters(self.old)
        report = audit_gallery_expansion(
            self.old, self.new, provenance_rows=evidence, require_provenance=True)
        self.assertTrue(report["reviewed_provenance_supplied"])
        hold = report["holdout"]
        self.assertEqual(hold["new_unsafe_unknown_offers"], 4)
        self.assertEqual(hold["sessions_with_new_unsafe_unknown_offer"], 4)
        components = hold["connected_source_component_diagnostics"]
        self.assertEqual(components["unknown_connected_components"], 22)
        self.assertEqual(components["components_with_new_unsafe_unknown_offer"], 1)
        self.assertEqual(components["components_with_new_unsafe_known_offer"], 2)
        self.assertFalse(components["actual_physical_independence_proven"])
        self.assertFalse(components["unknown_subject_independence_proven"])
        # The event+lineage+reviewed-subject chain is a connected set;
        # sorting or reversing opaque metadata cannot alter its count.
        self.assertEqual(
            report,
            audit_gallery_expansion(
                list(reversed(self.old)), list(reversed(self.new)),
                provenance_rows=list(reversed(evidence)), require_provenance=True))
        self.assertIsNone(
            audit_gallery_expansion(self.old, self.new)["holdout"][
                "connected_source_component_diagnostics"])

    def test_missing_incomplete_or_leaky_grouping_is_never_autofilled(self):
        with self.assertRaises(ValueError):
            audit_gallery_expansion(
                self.old, self.new, require_provenance=True)
        grouped = provenance_for_fictional_rosters(self.old)
        with self.assertRaises(ValueError):
            audit_gallery_expansion(
                self.old, self.new, provenance_rows=grouped[:-1],
                require_provenance=True)
        spoof = copy.deepcopy(grouped)
        cal_event = next(p["event_cluster_uid"] for p in spoof
                         if p["split"] == "calibration")
        next(p for p in spoof if p["split"] == "holdout")[
            "event_cluster_uid"] = cal_event
        with self.assertRaisesRegex(ValueError, "leaks across splits"):
            audit_gallery_expansion(
                self.old, self.new, provenance_rows=spoof,
                require_provenance=True)

    def test_cli_requires_exact_valid_provenance_and_emits_no_opaque_ids(self):
        with tempfile.TemporaryDirectory(prefix="gallery-group-fictional-") as home:
            root = Path(home)
            base = root / "old.csv"
            new = root / "new.csv"
            sidecar = root / "declared_sources.csv"
            for path, rows, columns in (
                (base, self.old, FIELDS), (new, self.new, FIELDS),
                (sidecar, provenance_for_fictional_rosters(self.old), PROVENANCE_FIELDS)
            ):
                with path.open("w", encoding="utf-8", newline="") as f:
                    writer = csv.DictWriter(f, fieldnames=columns)
                    writer.writeheader()
                    writer.writerows(rows)
            old_bytes = [p.read_bytes() for p in (base, new, sidecar)]
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                self.assertEqual(main([
                    str(base), str(new), "--provenance-csv", str(sidecar),
                    "--require-provenance"]), 0)
            report = json.loads(stdout.getvalue())
            self.assertEqual(report["holdout"][
                "connected_source_component_diagnostics"][
                "components_with_new_unsafe_unknown_offer"], 1)
            self.assertNotIn("SharedEvent", stdout.getvalue())
            self.assertNotIn("ReviewedUnknownCluster", stdout.getvalue())
            self.assertEqual([p.read_bytes() for p in (base, new, sidecar)],
                             old_bytes)
            sidecar.write_text("probe_uid,private_photo_path\n"
                               "fictional,/storage/private.jpg")
            error = io.StringIO()
            with contextlib.redirect_stderr(error):
                self.assertEqual(main([
                    str(base), str(new), "--provenance-csv", str(sidecar),
                    "--require-provenance"]), 2)
            self.assertNotIn(home, error.getvalue())
            self.assertNotIn("private.jpg", error.getvalue())

    def test_cli_publishes_aggregates_only_and_preserves_files(self):
        with tempfile.TemporaryDirectory(prefix="fictional-gallery-growth-") as home:
            root = Path(home)
            old_csv = root / "base.csv"
            new_csv = root / "expanded.csv"
            for path, rows in ((old_csv, self.old), (new_csv, self.new)):
                with path.open("w", encoding="utf-8", newline="") as f:
                    writer = csv.DictWriter(f, fieldnames=FIELDS)
                    writer.writeheader()
                    writer.writerows(rows)
            previous = old_csv.read_bytes(), new_csv.read_bytes()
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(main([str(old_csv), str(new_csv)]), 0)
            report = json.loads(out.getvalue())
            self.assertEqual(report["holdout"]["new_unsafe_unknown_offers"], 4)
            self.assertEqual((old_csv.read_bytes(), new_csv.read_bytes()), previous)
            self.assertEqual(sorted(p.name for p in root.iterdir()),
                             ["base.csv", "expanded.csv"])
            new_csv.write_bytes(b"probe_uid,\n/secret/owner_name.jpg")
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                self.assertEqual(main([str(old_csv), str(new_csv)]), 2)
            self.assertNotIn(home, err.getvalue())
            self.assertNotIn("owner_name", err.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
