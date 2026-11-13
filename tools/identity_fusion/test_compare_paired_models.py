#!/usr/bin/env python3
"""Model-free synthetic paired A/B evaluation; deliberately hard negatives."""
import contextlib
import csv
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from benchmark_reid_scores import FIELDS
from candidate_list_coverage import FIELDS as ROSTER_FIELDS
from compare_paired_models import compare_paired_models, main
from test_verify_split_provenance import made_provenance
from verify_split_provenance import FIELDS as PROVENANCE_FIELDS


def scenario():
    from test_benchmark_reid_scores import fixture
    a = fixture()
    b = [dict(r) for r in a]
    # Improvement in heldout data ONLY: five hard back-facing known matches,
    # four Unknown lookalikes. Calibration, roster and truth stay unchanged.
    for row in b:
        if row["split"] != "holdout":
            continue
        if (row["probe_uid"].startswith("HoldProbeK_")
                and int(row["probe_uid"].rsplit("_", 1)[1]) < 5
                and row["candidate_uid"] == row["truth_uid"]):
            row["similarity"] = ".99"
        if (row["probe_uid"].startswith("HoldProbeU_")
                and int(row["probe_uid"].rsplit("_", 1)[1]) < 4):
            row["similarity"] = ".1"
    roster = [{key: row[key] for key in ROSTER_FIELDS} for row in a]
    provenance = made_provenance(a)
    for row in provenance:
        if row["subject_cluster_uid"] == "UNREVIEWED":
            row["subject_cluster_uid"] = "Anonymous_" + row["probe_uid"]
    return a, b, roster, provenance


def write_csv(path, fields, rows):
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    return hashlib.sha256(path.read_bytes()).hexdigest()


class PairedModelComparisonTests(unittest.TestCase):
    def test_matching_cohort_detects_gains_and_unknown_safety_separately(self):
        a, b, roster, provenance = scenario()
        answer = compare_paired_models(a, b, roster_rows=roster,
                                       provenance_rows=provenance)
        self.assertEqual(answer["paired_probe_count"], 50)
        self.assertEqual(answer["model_a"]["calibration_floor"], .91)
        self.assertEqual(answer["model_b"]["calibration_floor"], .91)
        self.assertEqual(answer["model_a"]["heldout"]["wrong_known_offers"], 5)
        self.assertEqual(answer["model_b"]["heldout"]["wrong_known_offers"], 0)
        self.assertEqual(answer["model_a"]["heldout"]["unknown_false_offers"], 4)
        self.assertEqual(answer["model_b"]["heldout"]["unknown_false_offers"], 0)
        self.assertEqual(answer["paired_transitions"]["known"]["a_wrong_to_b_correct"], 5)
        self.assertEqual(answer["paired_transitions"]["unknown"][
            "a_false_offer_to_b_abstain"], 4)
        self.assertEqual(answer["distinct_known_wrong_offer_subject_groups"][
            "resolved_from_a"], 5)
        self.assertEqual(answer["distinct_anonymous_unknown_false_offer_groups"][
            "resolved_from_a"], 4)
        self.assertTrue(answer["paired_cohort_and_frozen_candidates_identical"])
        self.assertFalse(answer["model_comparison_pre_registered_proven"])
        self.assertFalse(answer["source_gallery_exhaustiveness_proven"])
        self.assertFalse(answer["auto_confirm_allowed"])
        for forbidden in ("HoldProbeK_", "HoldImpostor_", "Anonymous_",
                          "probe_uid", "candidate_uid", "/storage/"):
            self.assertNotIn(forbidden, json.dumps(answer))

    def test_reverse_model_comparison_is_pairwise_symmetric(self):
        a, b, roster, provenance = scenario()
        first = compare_paired_models(a, b, roster_rows=roster,
                                      provenance_rows=provenance)
        second = compare_paired_models(b, a, roster_rows=roster,
                                       provenance_rows=provenance)
        self.assertEqual(second["paired_transitions"]["known"]["a_correct_to_b_wrong"], 5)
        self.assertEqual(second["paired_transitions"]["unknown"][
            "a_abstain_to_b_false_offer"], 4)
        self.assertEqual(second["distinct_known_wrong_offer_subject_groups"]["new_in_b"], 5)
        self.assertEqual(first, compare_paired_models(
            list(reversed(a)), list(reversed(b)),
            roster_rows=list(reversed(roster)),
            provenance_rows=list(reversed(provenance))))

    def test_mismatched_galleries_or_reviewed_truth_are_not_comparable(self):
        a, b, roster, provenance = scenario()
        with self.assertRaises(ValueError):
            compare_paired_models(a, b[:-1], roster_rows=roster,
                                  provenance_rows=provenance)
        altered = [dict(r) for r in b]
        for row in altered:
            if row["probe_uid"] == "HoldProbeK_0":
                row["condition"] = "partial"
        with self.assertRaisesRegex(ValueError, "cohorts"):
            compare_paired_models(a, altered, roster_rows=roster,
                                  provenance_rows=provenance)
        with self.assertRaises(ValueError):
            compare_paired_models(a, b, roster_rows=roster[:-1],
                                  provenance_rows=provenance)
        with self.assertRaises(ValueError):
            compare_paired_models(a, b, roster_rows=roster,
                                  provenance_rows=provenance[:-1])

    def test_unreviewed_unknown_and_unsafe_calibration_refused(self):
        a, b, roster, provenance = scenario()
        prov = [dict(r) for r in provenance]
        for row in prov:
            if row["probe_uid"] == "HoldProbeU_0":
                row["subject_cluster_uid"] = "UNREVIEWED"
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            compare_paired_models(a, b, roster_rows=roster, provenance_rows=prov)
        broken = [dict(r) for r in b]
        for row in broken:
            if row["split"] == "calibration" and row["truth_uid"] == "UNKNOWN":
                row["similarity"] = "1.0"
        with self.assertRaisesRegex(ValueError, "nontrivial"):
            compare_paired_models(a, broken, roster_rows=roster,
                                  provenance_rows=provenance)

    def test_positive_margin_abstains_on_unobserved_second_best(self):
        a, b, roster, provenance = scenario()
        answer = compare_paired_models(a, b, roster_rows=roster,
                                       provenance_rows=provenance,
                                       margin_a=.05, margin_b=.05)
        self.assertEqual(answer["model_a"]["margin"], .05)
        self.assertEqual(answer["model_a"]["heldout"]["unknown_false_offers"], 0)
        self.assertEqual(answer["model_b"]["heldout"]["unknown_false_offers"], 0)
        self.assertGreater(answer["model_a"]["heldout"][
            "runner_up_unavailable_abstentions"], 0)

    def test_pinned_cli_refuses_mutation_without_leaking_local_paths(self):
        a, b, roster, provenance = scenario()
        with tempfile.TemporaryDirectory(prefix="ATLAS-Private-Local-A-B-") as folder:
            args = []
            paths = []
            for key, columns, rows in (
                ("a", FIELDS, a), ("b", FIELDS, b),
                ("roster", ROSTER_FIELDS, roster),
                ("provenance", PROVENANCE_FIELDS, provenance),
            ):
                path = Path(folder) / (key + "-private.csv")
                digest = write_csv(path, columns, rows)
                args += ["--" + key, str(path), "--" + key + "-sha256", digest]
                paths.append(path)
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(main(args), 0)
            payload = json.loads(output.getvalue())
            self.assertEqual(payload["paired_transitions"]["known"]["a_wrong_to_b_correct"], 5)
            paths[0].write_bytes(paths[0].read_bytes() + b"mutation")
            with contextlib.redirect_stderr(io.StringIO()) as err:
                self.assertEqual(main(args), 2)
            self.assertNotIn(folder, err.getvalue())
            self.assertNotIn("Private", err.getvalue())
            self.assertNotIn(folder, output.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
