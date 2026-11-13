#!/usr/bin/env python3
"""Synthetic-only preregistered back/occluded A/B safety regression cases."""
import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from preregistered_comparison import (
    compare_under_protocol, pinned_protocol, validate_protocol,
)


def case():
    from test_preregistered_comparison import scenario_and_truth, study_spec
    a, b, roster, provenance, truth = scenario_and_truth()
    for row in a + b:
        if row["split"] == "calibration":
            row["condition"] = "back+occluded"
    for row in truth:
        if row["split"] == "calibration":
            row["condition"] = "back+occluded"
    spec = study_spec(truth, roster, provenance)
    spec["schema_version"] = 2
    spec["protected_condition_tags"] = ["back", "occluded"]
    return a, b, roster, provenance, truth, spec


class ProtectedPreregistrationTests(unittest.TestCase):
    def test_v2_locks_both_models_to_same_adequate_difficult_cohort(self):
        a, b, roster, provenance, truth, spec = case()
        report = compare_under_protocol(spec, truth, a, b, roster, provenance)
        self.assertTrue(report["protocol_audit"][
            "protected_condition_tags_explicitly_registered"])
        self.assertTrue(report["protocol_audit"][
            "protected_condition_tags_match_pinned_protocol"])
        self.assertEqual(report["protected_condition_tags"], ["back", "occluded"])
        self.assertEqual(report["model_a"]["calibration_floor"], .91)
        self.assertEqual(report["model_b"]["calibration_floor"], .91)
        self.assertEqual(report["paired_transitions"]["known"]["a_wrong_to_b_correct"], 5)
        self.assertEqual(report["paired_transitions"]["unknown"][
            "a_false_offer_to_b_abstain"], 4)
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertFalse(report["protocol_audit"][
            "external_pre_holdout_commitment_verified"])
        self.assertEqual(report, compare_under_protocol(
            spec, list(reversed(truth)), list(reversed(a)),
            list(reversed(b)), list(reversed(roster)),
            list(reversed(provenance))))

    def test_v1_legacy_stays_valid_but_has_no_protected_cohort_claim(self):
        from test_preregistered_comparison import scenario_and_truth, study_spec
        a, b, roster, provenance, truth = scenario_and_truth()
        legacy = study_spec(truth, roster, provenance)
        self.assertEqual(validate_protocol(legacy), legacy)
        report = compare_under_protocol(legacy, truth, a, b, roster, provenance)
        self.assertEqual(report["protected_condition_tags"], [])
        self.assertFalse(report["protocol_audit"][
            "protected_condition_tags_explicitly_registered"])
        for fields in (["back", "back"], ["unknown-person"], ["occluded", "back"],
                       [], [True], "back"):
            trial = dict(legacy, schema_version=2, protected_condition_tags=fields)
            with self.subTest(fields=fields):
                with self.assertRaises(ValueError):
                    validate_protocol(trial)
        with self.assertRaises(ValueError):
            validate_protocol(dict(legacy, protected_condition_tags=["back"]))

    def test_protected_known_back_failure_masked_by_aggregate_v1_is_rejected_in_v2(self):
        from test_preregistered_comparison import scenario_and_truth, study_spec
        a, b, roster, provenance, truth = scenario_and_truth()
        # 60 known and 60 Unknown calibration examples are back+occluded,
        # remaining frontal/lookalike observations conceal one wrong known.
        for rows in (a, b):
            for row in rows:
                if row["split"] != "calibration":
                    continue
                probe = row["probe_uid"]
                if probe.startswith("CalProbeK_") and int(probe.rsplit("_", 1)[1]) < 60:
                    row["condition"] = "back+occluded"
                if probe.startswith("CalProbeU_") and 1 <= int(probe.rsplit("_", 1)[1]) <= 60:
                    row["condition"] = "back+occluded"
        for row in truth:
            if row["split"] == "calibration" and (
                (row["probe_uid"].startswith("CalProbeK_")
                 and int(row["probe_uid"].rsplit("_", 1)[1]) < 60)
                or (row["probe_uid"].startswith("CalProbeU_")
                    and 1 <= int(row["probe_uid"].rsplit("_", 1)[1]) <= 60)
            ):
                row["condition"] = "back+occluded"
        for row in b:
            if row["probe_uid"] == "CalProbeK_0":
                row["similarity"] = ".72" if row["candidate_uid"] == row["truth_uid"] else ".97"
        old = study_spec(truth, roster, provenance)
        # Aggregate-only research sees one wrong known out of 100, one
        # Unknown false offer out of 100, so old policy may still pass.
        legacy = compare_under_protocol(old, truth, a, b, roster, provenance)
        self.assertEqual(legacy["model_b"]["heldout"]["known_observations"], 25)
        v2 = dict(old, schema_version=2, protected_condition_tags=["back"])
        with self.assertRaisesRegex(ValueError, "nontrivial"):
            compare_under_protocol(v2, truth, a, b, roster, provenance)

    def test_protocol_bytes_lock_specific_protected_conditions(self):
        a, b, roster, provenance, truth, spec = case()
        with tempfile.TemporaryDirectory(prefix="atlas-private-locked-cohorts-") as d:
            file = Path(d) / "study-protocol.json"
            raw = json.dumps(spec, sort_keys=True, separators=(",", ":")).encode("utf-8")
            file.write_bytes(raw)
            pin = hashlib.sha256(raw).hexdigest()
            self.assertEqual(pinned_protocol(file, pin), spec)
            changed = dict(spec, protected_condition_tags=["back"])
            file.write_bytes(json.dumps(changed, sort_keys=True).encode())
            with self.assertRaisesRegex(ValueError, "bytes differ"):
                pinned_protocol(file, pin)
            self.assertNotIn(d, json.dumps(compare_under_protocol(
                spec, truth, a, b, roster, provenance)))


if __name__ == "__main__":
    unittest.main(verbosity=2)
