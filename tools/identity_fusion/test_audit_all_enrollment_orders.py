#!/usr/bin/env python3
"""Fictional score-only order envelope: no model, media, People or biometric IDs."""
from __future__ import annotations

import contextlib
import copy
import csv
import io
import itertools
import json
from pathlib import Path
import tempfile
import unittest

from audit_all_enrollment_orders import _per_partition, audit_all_enrollment_orders, main
from audit_progressive_gallery import audit_enrollment_curve
from benchmark_reid_scores import FIELDS
from test_audit_gallery_growth import (
    provenance_for_fictional_rosters, scored_snapshots,
)
from test_audit_progressive_gallery import ranking
from verify_split_provenance import FIELDS as PROVENANCE_FIELDS


class AllEnrollmentOrdersAuditTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.old, cls.expanded = scored_snapshots()

    def test_detects_possible_risk_without_requiring_selected_enrollment_order(self):
        r = audit_all_enrollment_orders(self.old, self.expanded)
        self.assertEqual(r["similarity_floor_locked_to_original_calibration"], .9)
        self.assertEqual(r["candidate_additions_per_split"], {
            "calibration": 2, "holdout": 2})
        self.assertEqual(r["holdout"]["unknown"][
            "could_become_newly_unsafe_in_at_least_one_order"], 4)
        self.assertEqual(r["holdout"]["known"][
            "could_become_newly_unsafe_in_at_least_one_order"], 2)
        self.assertEqual(r["holdout"]["unknown"][
            "newly_unsafe_at_final_snapshot"], 4)
        self.assertEqual(r["calibration"]["unknown"][
            "could_become_newly_unsafe_in_at_least_one_order"], 0)
        self.assertFalse(r["holdout_used_to_choose_threshold_or_order"])
        self.assertFalse(r["production_approved"])
        self.assertFalse(r["auto_confirm_allowed"])
        self.assertFalse(r["holdout"]["simultaneous_global_worst_case_order_proven"])
        for private in ("holdoutProbe", "holdoutNew_0", "holdoutSession",
                        "candidate_uid", "probe_uid", "/storage"):
            self.assertNotIn(private, json.dumps(r))
        self.assertEqual(r, audit_all_enrollment_orders(
            list(reversed(self.old)), list(reversed(self.expanded))))

    def test_safe_final_endpoint_can_mask_possible_transient_unsafe_enrollment(self):
        new = copy.deepcopy(self.expanded)
        for row in new:
            if (row["split"] == "holdout"
                    and row["candidate_uid"] == "holdoutNew_1"
                    and ((row["truth_uid"] == "UNKNOWN"
                          and int(row["probe_uid"].rsplit("_", 1)[1]) < 4)
                         or (row["truth_uid"] != "UNKNOWN"
                             and int(row["probe_uid"].rsplit("_", 1)[1]) < 2))):
                row["similarity"] = ".96"
        r = audit_all_enrollment_orders(self.old, new)
        self.assertEqual(r["holdout"]["unknown"][
            "newly_unsafe_at_final_snapshot"], 0)
        self.assertEqual(r["holdout"]["unknown"][
            "could_be_unsafe_temporarily_even_if_final_safe"], 4)
        self.assertEqual(r["holdout"]["known"][
            "could_be_unsafe_temporarily_even_if_final_safe"], 2)
        self.assertEqual(r["holdout"]["unknown"][
            "multiple_single_candidate_witnesses"], 4)
        declared = audit_enrollment_curve(self.old, new, ranking())
        self.assertEqual(declared["ever_exposed_summary"]["holdout"][
            "new_unsafe_unknown_ever_exposed_observations"], 4)
        self.assertEqual(declared["curve"][-1]["holdout"][
            "new_unsafe_unknown_offers"], 0)

    def test_no_declared_order_risk_does_not_imply_all_orders_safe(self):
        old, new = copy.deepcopy(self.old), copy.deepcopy(self.expanded)
        # One Unknown probe is baseline ambiguous at .91, so not offered.
        # With margin .05, added .94 alone is not offered (.03).
        # Adding .97 after .94 still abstains (.03).
        # But adding .97 FIRST offers a wrong person (.06)!
        probe = "holdoutProbe_unknown_5"
        for row in old:
            if row["probe_uid"] == probe and row["candidate_uid"] in (
                    "holdoutKnown_0", "holdoutKnown_1"):
                row["similarity"] = ".91"
        for row in new:
            if row["probe_uid"] == probe:
                if row["candidate_uid"] in ("holdoutKnown_0", "holdoutKnown_1"):
                    row["similarity"] = ".91"
                if row["candidate_uid"] == "holdoutNew_0":
                    row["similarity"] = ".94"
                if row["candidate_uid"] == "holdoutNew_1":
                    row["similarity"] = ".97"
        envelope = audit_all_enrollment_orders(
            old, new, min_top_two_margin=.05)
        self.assertEqual(envelope["holdout"]["unknown"][
            "could_become_newly_unsafe_in_at_least_one_order"], 5)
        # Another order (0 then 1) misses this individual witness, but
        # cannot prove universal safety without inspecting all orders.
        early0 = audit_enrollment_curve(
            old, new, ranking(), min_top_two_margin=.05)
        early1 = audit_enrollment_curve(
            old, new, ranking(reverse_holdout=True),
            min_top_two_margin=.05)
        self.assertEqual(early0["curve"][-1]["holdout"],
                         early1["curve"][-1]["holdout"])
        self.assertEqual(early0["ever_exposed_summary"]["holdout"][
            "new_unsafe_unknown_ever_exposed_observations"], 4)
        self.assertEqual(early1["ever_exposed_summary"]["holdout"][
            "new_unsafe_unknown_ever_exposed_observations"], 5)
        self.assertEqual(envelope["holdout"]["unknown"][
            "newly_unsafe_at_final_snapshot"], 4)

    def test_strict_component_sidecar_bounds_possible_failures_without_source_claim(self):
        manifest = provenance_for_fictional_rosters(self.old)
        r = audit_all_enrollment_orders(
            self.old, self.expanded,
            provenance_rows=manifest, require_provenance=True)
        unknown = r["holdout"]["unknown"]["source_component_diagnostics"]
        self.assertEqual(unknown["components_with_possibly_new_unsafe_offer"], 1)
        self.assertEqual(unknown["declared_components"], 22)
        self.assertFalse(unknown["independent_physical_sources_proven"])
        with self.assertRaises(ValueError):
            audit_all_enrollment_orders(
                self.old, self.expanded, require_provenance=True)
        with self.assertRaises(ValueError):
            audit_all_enrollment_orders(
                self.old, self.expanded,
                provenance_rows=manifest[:-1], require_provenance=True)

    def test_changed_old_scores_or_incomplete_full_matrix_are_rejected(self):
        changed = copy.deepcopy(self.expanded)
        changed[0]["similarity"] = ".123"
        with self.assertRaises(ValueError):
            audit_all_enrollment_orders(self.old, changed)
        changed = self.expanded[:-1]
        with self.assertRaises(ValueError):
            audit_all_enrollment_orders(self.old, changed)
        with self.assertRaises(ValueError):
            audit_all_enrollment_orders(self.old, self.old)

    def test_exact_single_candidate_witness_vs_independent_full_reparse(self):
        # For every fixture Unknown with an initially safe baseline, check
        # both possible one-candidate-first permutations directly using the
        # independent progressive audit's complete-matrix replay.
        envelope = audit_all_enrollment_orders(self.old, self.expanded)
        ordinary = audit_enrollment_curve(self.old, self.expanded, ranking())
        reverse = audit_enrollment_curve(
            self.old, self.expanded, ranking(reverse_holdout=True))
        self.assertEqual(envelope["holdout"]["unknown"][
            "could_become_newly_unsafe_in_at_least_one_order"],
            max(ordinary["ever_exposed_summary"]["holdout"][
                    "new_unsafe_unknown_ever_exposed_observations"],
                reverse["ever_exposed_summary"]["holdout"][
                    "new_unsafe_unknown_ever_exposed_observations"]))
        # Existence of a witness is per-probe, NOT proof that one common
        # enrollment order attains all existential witnesses together.
        self.assertFalse(envelope["holdout"]["simultaneous_global_worst_case_order_proven"])

    def test_order_free_witness_matches_independent_all_permutation_oracle(self):
        # Exhaustively enumerate all six orders for each of 6,000 fictional
        # combinations of old/candidate scores, model margins and truth.
        # The independent oracle reconstructs top/tie/runner-up directly
        # from raw dictionaries rather than calling _append_candidate.
        # This validates the non-factorial witness theorem, including
        # exact ties and near-margin abstention.
        def independent_top(scores, truth):
            ranked = sorted(scores.items(), key=lambda pair: (-pair[1], pair[0]))
            score = ranked[0][1]
            tied = sum(s == score for _candidate, s in ranked) > 1
            gap = score - ranked[1][1] if len(ranked) > 1 else None
            return ("Probe", "Session", "back+lookalike", truth, score,
                    ranked[0][0], tied, gap)

        def independent_unsafe(trial, floor, margin):
            if trial[6] or trial[4] < floor:
                return False
            if margin and (trial[7] is None or trial[7] < margin):
                return False
            return trial[5] != trial[3]

        cases = 0
        old_options = ((.2, .2), (.55, .55), (.6, .2), (.8, .6))
        values = (.2, .5, .55, .6, .8)
        candidate_keys = ("Add0", "Add1", "Add2")
        for old_pair in old_options:
            old_scores = {"OldA": old_pair[0], "OldB": old_pair[1]}
            for added_values in itertools.product(values, repeat=3):
                added = dict(zip(candidate_keys, added_values))
                final_scores = dict(old_scores, **added)
                for truth in ("UNKNOWN", "OldA"):
                    baseline = independent_top(old_scores, truth)
                    final = independent_top(final_scores, truth)
                    for floor in (.5, .6):
                        for margin in (0., .05, .12):
                            kind = "unknown" if truth == "UNKNOWN" else "known"
                            report = _per_partition(
                                [baseline], [final],
                                {"Probe": sorted(old_scores.values(),
                                                 reverse=True)[1]},
                                {"Probe": dict(added)},
                                frozenset(old_scores),
                                frozenset(final_scores),
                                floor, margin, None,
                            )
                            observed = False
                            if not independent_unsafe(baseline, floor, margin):
                                for order in itertools.permutations(candidate_keys):
                                    scores = dict(old_scores)
                                    for name in order:
                                        scores[name] = added[name]
                                        if independent_unsafe(
                                                independent_top(scores, truth),
                                                floor, margin):
                                            observed = True
                                            break
                                    if observed:
                                        break
                            self.assertEqual(
                                report[kind][
                                    "could_become_newly_unsafe_in_at_least_one_order"],
                                int(observed),
                                msg="Synthetic order-free witness differs from "
                                    "permutation oracle at case " + str(cases))
                            cases += 1
        self.assertEqual(cases, 6000)

    def test_cli_never_prints_private_ids_or_modifies_any_local_csv(self):
        with tempfile.TemporaryDirectory(prefix="fictional-order-envelope-") as td:
            root = Path(td)
            base, expanded, provenance = (
                root / "base.csv", root / "expanded.csv", root / "source.csv")
            for path, rows, fields in (
                (base, self.old, FIELDS),
                (expanded, self.expanded, FIELDS),
                (provenance, provenance_for_fictional_rosters(self.old),
                 PROVENANCE_FIELDS),
            ):
                with path.open("w", newline="", encoding="utf-8") as stream:
                    writer = csv.DictWriter(stream, fieldnames=fields)
                    writer.writeheader()
                    writer.writerows(rows)
            before = {p: p.read_bytes() for p in (base, expanded, provenance)}
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                self.assertEqual(main([
                    str(base), str(expanded), "--provenance-csv",
                    str(provenance), "--require-provenance"]), 0)
            report = json.loads(stdout.getvalue())
            self.assertEqual(report["holdout"]["unknown"][
                "could_become_newly_unsafe_in_at_least_one_order"], 4)
            self.assertNotIn("holdoutProbe", stdout.getvalue())
            self.assertNotIn("SharedLineage", stdout.getvalue())
            self.assertEqual({p: p.read_bytes() for p in before}, before)
            provenance.write_text("photo_path,private_label\n/secret/portrait,John")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                self.assertEqual(main([str(base), str(expanded),
                                       "--provenance-csv", str(provenance),
                                       "--require-provenance"]), 2)
            self.assertNotIn("John", stderr.getvalue())
            self.assertNotIn(td, stderr.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
