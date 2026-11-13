#!/usr/bin/env python3
"""Fictional immutable study protocol tests: no actual media or model files."""
import contextlib
import csv
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from benchmark_reid_scores import FIELDS as SCORE_FIELDS
from candidate_list_coverage import FIELDS as ROSTER_FIELDS
from preregistered_comparison import (
    CALIBRATION, METRIC, TRUTH_FIELDS,
    compare_under_protocol, pinned_protocol, validate_protocol, main,
)
from verify_split_provenance import FIELDS as PROVENANCE_FIELDS


def scenario_and_truth():
    # Import only when test executes; existing entrypoint includes this test
    # file and test_compare_paired_models, so module imports must not cycle.
    from test_compare_paired_models import scenario
    a, b, roster, provenance = scenario()
    unique = {}
    for row in a:
        key = (row["split"], row["probe_uid"])
        unique[key] = {k: row[k] for k in TRUTH_FIELDS}
    return a, b, roster, provenance, list(unique.values())


def encode_csv(fields, rows):
    buf = io.StringIO(newline="")
    out = csv.DictWriter(buf, fieldnames=fields, lineterminator="\n")
    out.writeheader()
    out.writerows(rows)
    return buf.getvalue().encode("utf-8")


def study_spec(truth, roster, provenance):
    return {
        "schema_version": 1,
        "study_uid": "Fictional_Study_v1",
        "frozen_truth_sha256": hashlib.sha256(encode_csv(TRUTH_FIELDS, truth)).hexdigest(),
        "frozen_roster_sha256": hashlib.sha256(encode_csv(ROSTER_FIELDS, roster)).hexdigest(),
        "frozen_provenance_sha256": hashlib.sha256(
            encode_csv(PROVENANCE_FIELDS, provenance)).hexdigest(),
        "model_a_uid": "Fictional_A",
        "model_b_uid": "Fictional_B",
        "model_a_checkpoint_sha256": "a" * 64,
        "model_b_checkpoint_sha256": "b" * 64,
        "model_a_margin": 0,
        "model_b_margin": 0,
        "max_false_offer_rate": .05,
        "primary_comparison": METRIC,
        "calibration_policy": CALIBRATION,
    }


class FrozenStudyProtocolTests(unittest.TestCase):
    def test_frozen_truth_and_locked_model_parameters_reproduce_paired_counts(self):
        a, b, roster, provenance, truth = scenario_and_truth()
        spec = study_spec(truth, roster, provenance)
        out = compare_under_protocol(spec, truth, a, b, roster, provenance)
        self.assertTrue(out["protocol_audit"]["frozen_truth_metadata_matches_both_models"])
        self.assertFalse(out["protocol_audit"]["raw_manifest_hashes_checked_by_cli"])
        self.assertFalse(out["protocol_audit"]["checkpoint_execution_verified"])
        self.assertFalse(out["protocol_audit"]["external_pre_holdout_commitment_verified"])
        self.assertFalse(out["protocol_audit"]["holdout_inspection_count_verified"])
        self.assertEqual(out["paired_transitions"]["known"]["a_wrong_to_b_correct"], 5)
        self.assertEqual(out["paired_transitions"]["unknown"][
            "a_false_offer_to_b_abstain"], 4)
        self.assertEqual(out, compare_under_protocol(
            spec, list(reversed(truth)), list(reversed(a)), list(reversed(b)),
            list(reversed(roster)), list(reversed(provenance))))
        rendered = json.dumps(out)
        for token in ("CalProbeK_", "HoldProbeU_", "Anonymous_", "probe_uid"):
            self.assertNotIn(token, rendered)

    def test_truth_drift_or_missing_frozen_probes_rejected(self):
        a, b, roster, provenance, truth = scenario_and_truth()
        spec = study_spec(truth, roster, provenance)
        cases = []
        changed = [dict(r) for r in a]
        for row in changed:
            if row["probe_uid"] == "HoldProbeK_0":
                row["truth_uid"] = "UNKNOWN"
        cases.append((truth, changed, b))
        condition = [dict(r) for r in b]
        for row in condition:
            if row["probe_uid"] == "HoldProbeK_0":
                row["condition"] = "partial"
        cases.append((truth, a, condition))
        cases.append((truth[:-1], a, b))
        cases.append((truth + [dict(truth[0])], a, b))
        for frozen, score_a, score_b in cases:
            with self.subTest(items=len(frozen)):
                with self.assertRaises(ValueError):
                    compare_under_protocol(spec, frozen, score_a, score_b,
                                           roster, provenance)

    def test_manifest_rejects_metric_selection_checkpoint_and_margin_changes(self):
        a, b, roster, provenance, truth = scenario_and_truth()
        baseline = study_spec(truth, roster, provenance)
        for field, bad in (
            ("schema_version", True),
            ("study_uid", "../../private"),
            ("model_b_uid", "Fictional_A"),
            ("model_a_checkpoint_sha256", "bad"),
            ("model_a_margin", float("nan")),
            ("model_a_margin", 1.1),
            ("model_b_margin", True),
            ("max_false_offer_rate", .3),
            ("primary_comparison", "pick_best_accuracy_after_holdout"),
            ("calibration_policy", "tune_on_holdout"),
            ("frozen_roster_sha256", "0"),
        ):
            altered = dict(baseline, **{field: bad})
            with self.subTest(field=field):
                with self.assertRaises(ValueError):
                    validate_protocol(altered)
        with self.assertRaises(ValueError):
            validate_protocol(dict(baseline, human_name="private"))

    def test_checksum_pinned_cli_exposes_only_aggregate_outputs(self):
        a, b, roster, provenance, truth = scenario_and_truth()
        spec = study_spec(truth, roster, provenance)
        with tempfile.TemporaryDirectory(prefix="ATLAS-private-frozen-") as d:
            args = []
            paths = {}
            for name, columns, rows in (
                ("truth", TRUTH_FIELDS, truth), ("a", SCORE_FIELDS, a),
                ("b", SCORE_FIELDS, b), ("roster", ROSTER_FIELDS, roster),
                ("provenance", PROVENANCE_FIELDS, provenance),
            ):
                path = Path(d) / (name + "-private.csv")
                raw = encode_csv(columns, rows)
                path.write_bytes(raw)
                paths[name] = path
                args.extend(["--" + name, str(path), "--" + name + "-sha256",
                             hashlib.sha256(raw).hexdigest()])
            protocol = Path(d) / "protocol-private.json"
            raw_spec = json.dumps(spec, sort_keys=True, separators=(",", ":")).encode()
            protocol.write_bytes(raw_spec)
            args.extend(["--protocol", str(protocol), "--protocol-sha256",
                         hashlib.sha256(raw_spec).hexdigest()])
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(main(args), 0)
            result = json.loads(output.getvalue())
            self.assertTrue(result["protocol_audit"]["raw_manifest_hashes_checked_by_cli"])
            self.assertFalse(result["protocol_audit"]["external_pre_holdout_commitment_verified"])
            self.assertFalse(result["auto_confirm_allowed"])
            self.assertNotIn(d, output.getvalue())
            paths["truth"].write_bytes(paths["truth"].read_bytes() + b"changed")
            with contextlib.redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(main(args), 2)
            self.assertNotIn(d, errors.getvalue())
            self.assertNotIn("private", errors.getvalue())

    def test_protocol_rejects_symlink_duplicate_keys_unpinned_changes(self):
        a, b, roster, provenance, truth = scenario_and_truth()
        spec = study_spec(truth, roster, provenance)
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "protocol.json"
            raw = json.dumps(spec, sort_keys=True).encode()
            p.write_bytes(raw)
            pin = hashlib.sha256(raw).hexdigest()
            self.assertEqual(pinned_protocol(p, pin), spec)
            q = Path(d) / "link.json"
            q.symlink_to(p)
            with self.assertRaises(ValueError):
                pinned_protocol(q, pin)
            with self.assertRaises(ValueError):
                pinned_protocol(p, "0" * 64)
            p.write_bytes(b'{"schema_version":1,"schema_version":1}')
            with self.assertRaises(ValueError):
                pinned_protocol(p, hashlib.sha256(p.read_bytes()).hexdigest())
            p.write_bytes(raw + b"changed")
            with self.assertRaises(ValueError):
                pinned_protocol(p, pin)


if __name__ == "__main__":
    unittest.main(verbosity=2)
