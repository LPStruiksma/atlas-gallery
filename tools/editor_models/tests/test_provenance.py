"""Offline provenance contract, discovered by the editor-model CI job."""
import copy
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
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

    def run_cli(self, script, *args):
        return subprocess.run(
            [sys.executable, str(script), *args],
            capture_output=True, text=True, check=False,
        )

    def test_cli_live_structure(self):
        result = self.run_cli(SCRIPT)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_cli_synthetic_incomplete_and_complete(self):
        self.synthetic_complete()
        with tempfile.TemporaryDirectory() as directory:
            # Mirror the CLI's relative file layout without modifying the checkout.
            root = Path(directory)
            script = root / "provenance" / SCRIPT.name
            script.parent.mkdir()
            shutil.copyfile(SCRIPT, script)
            (root / "candidates.json").write_text(json.dumps(self.catalogue), encoding="utf-8")
            manifest = script.parent / "manifest.json"
            complete = copy.deepcopy(self.data)
            self.synthetic_missing()
            for data, strict_code in ((self.data, 1), (complete, 0)):
                manifest.write_text(json.dumps(data), encoding="utf-8")
                with self.subTest(strict_code=strict_code):
                    structural = self.run_cli(script)
                    self.assertEqual(structural.returncode, 0, structural.stdout + structural.stderr)
                    strict = self.run_cli(script, "--require-complete")
                    self.assertEqual(strict.returncode, strict_code, strict.stdout + strict.stderr)
                    if strict_code:
                        self.assertIn("unresolved candidate", strict.stdout)
                    else:
                        self.assertNotIn("unresolved candidate", strict.stdout)

    def synthetic_complete(self):
        # Fictional URLs and bytes: never write these into the live manifest.
        self.data = {
            "schema_version": 1,
            "source_pin_receipt": "https://example.invalid/source-pins",
            "candidates": [
                dict(id=candidate["id"], status="blocked", reason="Synthetic prohibition",
                     project_url="https://example.invalid/project",
                     project_revision="b" * 40, checkpoint_revision=None,
                     original_url="https://example.invalid/discovery", original_url_kind="discovery",
                     project_license_note="Synthetic licence note",
                     checkpoint_grant_note="Synthetic grant note",
                     training_data_note="Synthetic training note",
                     project_license_evidence=None, training_data_evidence=None,
                     checkpoint_grant_evidence="https://example.invalid/prohibition",
                     bytes=None, sha256=None, retrieved_url=None, retrieved_at_utc=None)
                for candidate in self.catalogue["candidates"]
            ],
        }
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

    def synthetic_missing(self):
        row = self.data["candidates"][0]
        row.update(status="missing", reason="Synthetic unresolved candidate",
                   bytes=None, sha256=None, retrieved_url=None, retrieved_at_utc=None)
        return row

    def test_missing_is_not_complete_and_cannot_claim_bytes(self):
        self.synthetic_complete()
        row = self.synthetic_missing()
        self.assertEqual(self.errors(), [])
        self.assertEqual(self.errors(True), [f"{row['id']}: unresolved candidate"])
        row["sha256"] = "a" * 64
        self.assertIn(f"{row['id']}: unrecorded candidate must not claim byte evidence", self.errors())

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
