#!/usr/bin/env python3
"""Pure fictional scored-person gallery subsets; no biometric model or photos."""
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

from audit_joint_enrollment_peak import _partition, audit_joint_enrollment_peak, main
from audit_all_enrollment_orders import audit_all_enrollment_orders
from audit_progressive_gallery import audit_enrollment_curve
from benchmark_reid_scores import FIELDS
from test_audit_gallery_growth import (
    provenance_for_fictional_rosters, scored_snapshots,
)
from test_audit_progressive_gallery import ranking
from verify_split_provenance import FIELDS as PROVENANCE_FIELDS


def incompatible_unknown_witnesses(old_source, expanded_source):
    old, expanded = copy.deepcopy(old_source), copy.deepcopy(expanded_source)
    # Remove all default hard negatives, then create TWO baseline-safe
    # Unknown probes with disjoint singleton witness sets. Both candidate
    # additions together are safe because the top-two gap shrinks below .05.
    for row in expanded:
        if (row["split"] == "holdout" and
                row["candidate_uid"].startswith("holdoutNew_")):
            row["similarity"] = ".10"
    for probe_index in (10, 11):
        uid = "holdoutProbe_unknown_" + str(probe_index)
        for inventory in (old, expanded):
            for row in inventory:
                if (row["probe_uid"] == uid and row["candidate_uid"] in
                        ("holdoutKnown_0", "holdoutKnown_1")):
                    row["similarity"] = ".90"
        for row in expanded:
            if row["probe_uid"] != uid:
                continue
            if row["candidate_uid"] == "holdoutNew_0":
                row["similarity"] = ".96" if probe_index == 10 else ".93"
            elif row["candidate_uid"] == "holdoutNew_1":
                row["similarity"] = ".93" if probe_index == 10 else ".96"
    return old, expanded


class JointSimultaneousPeakTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.old, cls.new = scored_snapshots()

    def test_existing_synthetic_gallery_reaches_six_errors_at_one_subset(self):
        study = audit_joint_enrollment_peak(self.old, self.new)
        self.assertEqual(study["floor_locked_to_original_calibration"], .9)
        self.assertTrue(study["independent_endpoint_and_order_free_envelope_reconciled"])
        self.assertFalse(study["auto_confirm_allowed"])
        self.assertFalse(study["production_approved"])
        hold = study["holdout"]
        self.assertEqual(hold["total_reachable_gallery_subsets_including_baseline"], 4)
        self.assertEqual(hold["peak_simultaneous_new_unknown_observations"], 4)
        self.assertEqual(hold["peak_simultaneous_new_known_observations"], 2)
        self.assertEqual(hold["peak_joint_simultaneous_new_unsafe_observations"], 6)
        self.assertEqual([x["max_joint_new_unsafe_observations"]
                          for x in hold["by_gallery_size"]], [0, 6, 6])
        self.assertEqual([x["distinct_reachable_gallery_subsets"]
                          for x in hold["by_gallery_size"]], [1, 2, 1])
        self.assertEqual(hold["newly_unsafe_unknown_possible_in_some_prefix"], 4)
        self.assertEqual(hold["newly_unsafe_known_possible_in_some_prefix"], 2)
        self.assertFalse(hold["physical_capture_independence_proven"])
        self.assertEqual(study, audit_joint_enrollment_peak(
            list(reversed(self.old)), list(reversed(self.new))))
        encoded = json.dumps(study)
        for forbidden in ("holdoutProbe", "holdoutNew_0", "holdoutSession",
                          "probe_uid", "candidate_uid", "/storage"):
            self.assertNotIn(forbidden, encoded)

    def test_individual_possible_two_but_joint_peak_only_one(self):
        old, expanded = incompatible_unknown_witnesses(self.old, self.new)
        study = audit_joint_enrollment_peak(
            old, expanded, min_top_two_margin=.05)
        envelope = audit_all_enrollment_orders(
            old, expanded, min_top_two_margin=.05)
        hold = study["holdout"]
        self.assertEqual(envelope["holdout"]["unknown"][
            "could_become_newly_unsafe_in_at_least_one_order"], 2)
        self.assertEqual(hold["newly_unsafe_unknown_possible_in_some_prefix"], 2)
        self.assertEqual(hold["peak_simultaneous_new_unknown_observations"], 1)
        self.assertEqual(hold["peak_joint_simultaneous_new_unsafe_observations"], 1)
        self.assertEqual([x["max_new_unsafe_unknown_observations"]
                          for x in hold["by_gallery_size"]], [0, 1, 0])
        self.assertEqual(hold["newly_unsafe_unknown_at_expanded_endpoint"], 0)
        self.assertEqual(hold["newly_unsafe_known_at_expanded_endpoint"], 0)
        self.assertTrue(study["worst_case_each_section_attainable_at_one_subgallery"])
        self.assertFalse(study["same_subset_simultaneously_maximizes_every_metric"])
        # Either concrete permutation exposes only ONE probe at stage one.
        for timeline in (ranking(), ranking(reverse_holdout=True)):
            progressive = audit_enrollment_curve(
                old, expanded, timeline, min_top_two_margin=.05)
            self.assertEqual(progressive["curve"][0]["holdout"][
                "new_unsafe_unknown_offers"], 1)
            self.assertEqual(progressive["curve"][1]["holdout"][
                "new_unsafe_unknown_offers"], 0)

    def test_connected_source_component_count_is_not_photo_count(self):
        sources = provenance_for_fictional_rosters(self.old)
        study = audit_joint_enrollment_peak(
            self.old, self.new, provenance_rows=sources,
            require_provenance=True)
        step = study["holdout"]["by_gallery_size"][1]
        self.assertEqual(step["max_new_unsafe_unknown_observations"], 4)
        self.assertEqual(step["max_unknown_connected_components_affected"], 1)
        self.assertEqual(step["max_known_connected_components_affected"], 2)
        self.assertFalse(step["physical_capture_independence_proven"])
        with self.assertRaises(ValueError):
            audit_joint_enrollment_peak(self.old, self.new,
                                        require_provenance=True)
        with self.assertRaises(ValueError):
            audit_joint_enrollment_peak(
                self.old, self.new, provenance_rows=sources[:-1],
                require_provenance=True)

    def test_candidate_counts_over_ten_refused_instead_of_factorial_expansion(self):
        old, expanded = copy.deepcopy(self.old), copy.deepcopy(self.new)
        # Each split already has 2 candidates; add 9 more to total 11.
        for row in old:
            if row["candidate_uid"] == "NONE":
                raise AssertionError("Fictional test expects complete gallery")
        probe_metadata = {}
        for row in old:
            probe_metadata[(row["split"], row["probe_uid"])] = row
        for (split, probe), row in probe_metadata.items():
            for i in range(9):
                expanded.append(dict(row,
                                     candidate_uid=split + "Extra_" + str(i),
                                     similarity=".1"))
        with self.assertRaisesRegex(ValueError, "1..10"):
            audit_joint_enrollment_peak(old, expanded)

    def test_missing_or_changed_scored_gallery_is_not_approved(self):
        with self.assertRaises(ValueError):
            audit_joint_enrollment_peak(self.old, self.old)
        broken = copy.deepcopy(self.new)
        broken[0]["similarity"] = ".999"
        with self.assertRaises(ValueError):
            audit_joint_enrollment_peak(self.old, broken)
        broken = [row for row in self.new
                  if not (row["probe_uid"] == "holdoutProbe_unknown_0"
                          and row["candidate_uid"] == "holdoutNew_1")]
        with self.assertRaises(ValueError):
            audit_joint_enrollment_peak(self.old, broken)
        with self.assertRaises(ValueError):
            audit_joint_enrollment_peak(self.old, self.new,
                                        min_top_two_margin=-.1)

    def test_independent_full_reparse_oracle_for_four_thousand_joint_subsets(self):
        # Independent rank oracle deliberately NEVER imports/reuses the V5
        # incremental candidate update, envelope or _decision routine.
        # Enumerates every selected candidate SUBSET from scratch. This
        # prevents one shared incremental ranking defect from making both
        # the joint peak and its 'independent' envelope agree incorrectly.
        def oracle_decision(scores, truth, threshold, margin):
            ranked = sorted(scores.items(), key=lambda item: (-item[1], item[0]))
            top_name, highest = ranked[0]
            runner = ranked[1][1] if len(ranked) > 1 else None
            abstain = (highest < threshold or
                       (runner is not None and highest == runner) or
                       (margin > 0 and (runner is None
                                        or highest - runner < margin)))
            return "abstain" if abstain else (
                "unsafe_" + ("unknown" if truth == "UNKNOWN" else "known")
                if top_name != truth else "correct_known"
            )

        old_options = ((.20, .20), (.55, .55), (.60, .20), (.80, .60))
        new_scores = (.20, .50, .55, .60, .80)
        cases = 0
        for baseline_scores in old_options:
            old = {"OldA": str(baseline_scores[0]),
                   "OldB": str(baseline_scores[1])}
            old_ranked = sorted(old.items(),
                                key=lambda item: (-float(item[1]), item[0]))
            old_trial_seed = (
                "Probe", "Session", "back", None,
                float(old_ranked[0][1]), old_ranked[0][0],
                old_ranked[0][1] == old_ranked[1][1],
                float(old_ranked[0][1]) - float(old_ranked[1][1]),
            )
            for triple in itertools.product(new_scores, repeat=3):
                additions = dict(zip(("NewA", "NewB", "NewC"),
                                     (str(v) for v in triple)))
                complete = dict(old, **additions)
                final_ranked = sorted(complete.items(),
                                      key=lambda item: (-float(item[1]), item[0]))
                for truth in ("UNKNOWN", "OldA"):
                    for floor in (.5, .6):
                        for margin in (0., .05):
                            baseline = list(old_trial_seed)
                            baseline[3] = truth
                            final = (
                                "Probe", "Session", "back", truth,
                                float(final_ranked[0][1]), final_ranked[0][0],
                                final_ranked[0][1] == final_ranked[1][1],
                                float(final_ranked[0][1]) - float(final_ranked[1][1]),
                            )
                            output = _partition(
                                [tuple(baseline)], {"Probe": old},
                                {"Probe": complete}, frozenset(old),
                                frozenset(complete), floor, margin, None)
                            oracle_old = oracle_decision(
                                {k: float(v) for k, v in old.items()},
                                truth, floor, margin)
                            oracle_by_size = [0] * 4
                            oracle_union = False
                            for mask in range(8):
                                score_map = {k: float(v) for k, v in old.items()}
                                for i, candidate in enumerate(additions):
                                    if mask & (1 << i):
                                        score_map[candidate] = float(
                                            additions[candidate])
                                flagged = (
                                    oracle_old != "unsafe_" + (
                                        "unknown" if truth == "UNKNOWN" else "known")
                                    and oracle_decision(score_map, truth, floor, margin)
                                    == "unsafe_" + (
                                        "unknown" if truth == "UNKNOWN" else "known"))
                                oracle_by_size[mask.bit_count()] = max(
                                    oracle_by_size[mask.bit_count()], int(flagged))
                                oracle_union |= flagged
                            cls = "unknown" if truth == "UNKNOWN" else "known"
                            self.assertEqual(
                                [stage["max_new_unsafe_" + cls + "_observations"]
                                 for stage in output["by_gallery_size"]],
                                oracle_by_size, msg="Case " + str(cases))
                            self.assertEqual(
                                output["newly_unsafe_" + cls +
                                       "_possible_in_some_prefix"],
                                int(oracle_union), msg="Case " + str(cases))
                            self.assertEqual(
                                output["peak_joint_simultaneous_new_unsafe_observations"],
                                max(oracle_by_size), msg="Case " + str(cases))
                            cases += 1
        self.assertEqual(cases, 4000)

    def test_read_only_cli_never_exports_subject_paths_or_staged_files(self):
        with tempfile.TemporaryDirectory(prefix="v5-fictional-joint-") as home:
            root = Path(home)
            base, expanded, source = (
                root / "base.csv", root / "expanded.csv", root / "sources.csv")
            for path, rows, fields in (
                (base, self.old, FIELDS),
                (expanded, self.new, FIELDS),
                (source, provenance_for_fictional_rosters(self.old),
                 PROVENANCE_FIELDS),
            ):
                with path.open("w", encoding="utf-8", newline="") as stream:
                    writer = csv.DictWriter(stream, fieldnames=fields)
                    writer.writeheader()
                    writer.writerows(rows)
            before = {file: file.read_bytes() for file in (base, expanded, source)}
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(main([str(base), str(expanded),
                                       "--provenance-csv", str(source),
                                       "--require-provenance"]), 0)
            data = json.loads(output.getvalue())
            self.assertEqual(data["holdout"]["peak_joint_simultaneous_new_unsafe_observations"], 6)
            self.assertNotIn(home, output.getvalue())
            self.assertNotIn("holdoutProbe", output.getvalue())
            self.assertEqual({file: file.read_bytes() for file in before}, before)
            source.write_bytes(b"photo_path,first_name\n/private/portrait,Lukas\n")
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                self.assertEqual(main([str(base), str(expanded),
                                       "--provenance-csv", str(source),
                                       "--require-provenance"]), 2)
            self.assertNotIn(home, err.getvalue())
            self.assertNotIn("Lukas", err.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
