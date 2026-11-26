"""Offline provenance contract, discovered by the editor-model CI job."""
import copy
import hashlib
import importlib.util
import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "provenance" / "validate.py"
SPEC = importlib.util.spec_from_file_location("editor_models_provenance", SCRIPT)
provenance = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(provenance)


class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((SCRIPT.parent / "manifest.json").read_text())
        self.catalogue = json.loads((ROOT / "candidates.json").read_text())

    def errors(self, complete=False):
        return provenance.validate(self.data, self.catalogue, complete)

    def test_inventory(self):
        self.assertEqual(self.errors(), [])

    def test_cli_structure_and_strict_incomplete(self):
        for args, code in (([], 0), (["--require-complete"], 1)):
            with self.subTest(args=args):
                result = subprocess.run(
                    [sys.executable, str(SCRIPT), *args],
                    capture_output=True, text=True, check=False,
                )
                self.assertEqual(result.returncode, code, result.stdout + result.stderr)
                if code:
                    self.assertIn("unresolved candidate", result.stdout)

    def synthetic_complete(self):
        # Fictional URLs and bytes: never write these into the live manifest.
        for row in self.data["candidates"]:
            row.update(status="blocked", reason="Synthetic prohibition",
                       checkpoint_grant_evidence="https://example.invalid/prohibition",
                       bytes=None, sha256=None, retrieved_url=None, retrieved_at_utc=None)
        row = self.data["candidates"][0]
        payload = b"synthetic fixture only"
        row.update(status="recorded", reason="Synthetic recorded fixture",
                   project_revision="a" * 40, checkpoint_revision="fixture-release-v1/model.bin",
                   original_url="https://example.invalid/model.bin", original_url_kind="checkpoint",
                   project_license_evidence="https://example.invalid/license",
                   checkpoint_grant_evidence="https://example.invalid/grant",
                   training_data_evidence="https://example.invalid/training",
                   retrieved_url="https://example.invalid/model.bin",
                   retrieved_at_utc="2026-01-01T12:00:00Z",
                   bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest())
        return row

    def test_synthetic_recorded_and_blocked_complete(self):
        self.synthetic_complete()
        self.assertEqual(self.errors(), [])
        self.assertEqual(self.errors(True), [])

    def test_reject_duplicate_and_missing_candidate(self):
        original = copy.deepcopy(self.data)
        self.data["candidates"].append(copy.deepcopy(self.data["candidates"][0]))
        self.assertTrue(self.errors())
        self.data = original
        self.data["candidates"].pop()
        self.assertTrue(self.errors())

    def test_missing_is_not_complete_and_cannot_claim_bytes(self):
        row = self.data["candidates"][0]
        self.assertTrue(self.errors(True))
        row["sha256"] = "a" * 64
        self.assertTrue(self.errors())

    def test_blocked_requires_evidence_even_in_structural_mode(self):
        self.synthetic_complete()
        row = self.data["candidates"][1]
        row["checkpoint_grant_evidence"] = None
        self.assertTrue(self.errors())
        self.assertTrue(self.errors(True))
        row["checkpoint_grant_evidence"] = "https://example.invalid/prohibition"
        row["bytes"] = 1
        self.assertTrue(self.errors())

    def test_invalid_recorded_fields(self):
        cases = {
            "checkpoint_revision": [None, " ", 42, "a" * 40],
            "project_revision": [None, "invalid"],
            "original_url_kind": ["discovery", "unknown"],
            "original_url": ["http://example.invalid/file", "https://[bad", "https://example.invalid/ file"],
            "retrieved_url": ["https://user@example.invalid/file", "https://example.invalid/file?secret=x"],
            "project_license_evidence": [None],
            "checkpoint_grant_evidence": [None],
            "training_data_evidence": [None],
            "retrieved_at_utc": ["2026-01-01Z", "2026-02-30T12:00:00Z", "2026-01-01T12:00:00+00:00"],
            "bytes": [None, 0, -1, True, 1.5],
            "sha256": [None, "x" * 64, "0" * 64],
        }
        for field, values in cases.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    row = self.synthetic_complete()
                    row[field] = value
                    self.assertTrue(self.errors())
                    self.assertTrue(self.errors(True))

    def test_missing_fields_and_malformed_rows(self):
        del self.data["candidates"][0]["sha256"]
        self.assertTrue(self.errors())
        self.data["candidates"][0] = None
        self.assertTrue(self.errors())
        self.data["candidates"] = {}
        self.assertTrue(self.errors())

    def test_unknown_status_or_url_kind(self):
        row = self.data["candidates"][0]
        row["status"] = "approved"
        self.assertTrue(self.errors())
        row["status"] = "missing"
        row["original_url_kind"] = "other"
        self.assertTrue(self.errors())
