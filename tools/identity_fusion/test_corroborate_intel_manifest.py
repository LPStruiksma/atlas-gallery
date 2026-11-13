#!/usr/bin/env python3
"""Synthetic-only checkpoint source-manifest matching and tamper resistance."""
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from corroborate_intel_manifest import (
    OFFICIAL_LICENSE, SOURCE_PREFIX, MODEL_ID,
    corroborate_intel_model_manifest, main, parse_intel_manifest_subset,
    read_pinned_manifest,
)
from verify_candidate_artifact import validate_register


def register():
    return json.loads(Path(__file__).with_name(
        "candidate_model_artifacts.json").read_text(encoding="utf-8"))


def fixture(register_doc=None):
    model = validate_register(register_doc or register())[MODEL_ID]
    return (
        "# Copyright Intel\n"
        "description: manifest for fictional no-binary-source test\n"
        "files:\n"
        f"  - name: {model['file_name']}\n"
        f"    size: {model['bytes']}\n"
        f"    checksum: {model['checksum']}\n"
        f"    source: {SOURCE_PREFIX}{model['file_name']}\n"
        "input_info:\n"
        "  - name: data\n"
        "    shape: [1, 3, 256, 128]\n"
        "    layout: NCHW\n"
        "framework: dldt\n"
        f"license: {OFFICIAL_LICENSE}\n"
    )


def run_cli(argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        result = main(argv)
    return result, out.getvalue(), err.getvalue()


class IntelSourceManifestCorroborationTests(unittest.TestCase):
    def test_source_corrobates_registered_checksum_size_and_awkward_filename(self):
        report = corroborate_intel_model_manifest(register(), fixture())
        self.assertTrue(report["registered_size_and_sha384_match_manifest"])
        self.assertTrue(report["0287_directory_uses_0267_onnx_filename"])
        self.assertFalse(report["checkpoint_redistribution_rights_verified"])
        self.assertFalse(report["actual_model_bytes_or_graph_identity_verified"])
        self.assertFalse(report["open_set_identity_accuracy_verified"])
        self.assertFalse(report["auto_confirm_allowed"])
        raw = json.dumps(report)
        self.assertNotIn("person-reidentification-retail-0267.onnx", raw)
        self.assertNotIn("storage.openvinotoolkit", raw)
        parsed = parse_intel_manifest_subset(fixture())
        self.assertEqual(len(parsed["files"]), 1)
        self.assertEqual(parsed["files"][0]["size"], "3571437")

    def test_tampered_register_size_checksum_name_and_source_cannot_pass(self):
        for key, value in (
            ("bytes", 3),
            ("checksum", "a" * 96),
            ("file_name", "person-reidentification-retail-0287.onnx"),
            ("format", "pytorch_pickle"),
            ("source_purpose", "imagenet_only"),
        ):
            data = copy.deepcopy(register())
            target = next(x for x in data["artifacts"] if x["id"] == MODEL_ID)
            target[key] = value
            with self.subTest(field=key), self.assertRaises(ValueError):
                corroborate_intel_model_manifest(data, fixture())

    def test_fraudulent_malformed_source_refused_before_claiming_integrity(self):
        good = fixture()
        replacements = [
            ("size: 3571437", "size: 3"),
            ("checksum: c650c9", "checksum: f650c9"),
            ("shape: [1, 3, 256, 128]", "shape: [1, 3, 160, 80]"),
            ("license: " + OFFICIAL_LICENSE, "license: https://other.invalid/LICENSE"),
            ("source: " + SOURCE_PREFIX, "source: http://evil.invalid/"),
            ("  - name: person-", "  - name: ../person-"),
        ]
        for original, alternate in replacements:
            changed = good.replace(original, alternate, 1)
            with self.subTest(change=alternate[:25]), self.assertRaises(ValueError):
                corroborate_intel_model_manifest(register(), changed)
        for changed in (
            good.replace("files:\n", "files:\nfiles:\n", 1),
            good.replace("    size:", "    size: 3571437\n    size:", 1),
            good.replace("input_info:", "files:\ninput_info:", 1),
            good.replace("    checksum:", "    private_path: /sdcard\n    checksum:", 1),
        ):
            with self.assertRaises(ValueError):
                corroborate_intel_model_manifest(register(), changed)

    def test_user_supplied_pin_prevents_tampering_symlink_and_path_leakage(self):
        with tempfile.TemporaryDirectory(prefix="TOP-SECRET-manifest-") as root:
            path = Path(root) / "model.yml"
            path.write_text(fixture(), encoding="utf-8")
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual(read_pinned_manifest(path, digest), fixture())
            status, text, err = run_cli([
                "--manifest", str(path), "--manifest-sha256", digest
            ])
            self.assertEqual(status, 0, err)
            data = json.loads(text)
            self.assertTrue(data["source_manifest_sha256_checked_by_caller"])
            self.assertFalse(data["checkpoint_training_data_rights_verified"])
            self.assertNotIn(root, text)
            path.write_text(fixture() + "# changed\n", encoding="utf-8")
            status, output, err = run_cli([
                "--manifest", str(path), "--manifest-sha256", digest
            ])
            self.assertEqual(status, 2)
            self.assertEqual(output, "")
            self.assertNotIn(root, err)
            path.write_text(fixture(), encoding="utf-8")
            alias = Path(root) / "fake-link.yml"
            alias.symlink_to(path)
            self.assertEqual(run_cli([
                "--manifest", str(alias), "--manifest-sha256", digest
            ])[0], 2)
            self.assertEqual(run_cli([
                "--manifest", str(path), "--manifest-sha256", "invalid"
            ])[0], 2)


if __name__ == "__main__":
    unittest.main()
