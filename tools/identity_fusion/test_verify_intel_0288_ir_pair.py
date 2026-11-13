#!/usr/bin/env python3
"""Fictional Intel-style IR bytes only; no checkpoints/weights downloaded."""
from __future__ import annotations

import contextlib
import hashlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from verify_intel_0288_ir_pair import (
    FILE_NAMES, IR_PINS, MAX_IR_FILE_BYTES, SOURCE_COMMIT,
    SOURCE_MANIFEST_BLOB, _digest_open_descriptor, main, verify_ir_pair,
)


def invented_ir(root: Path):
    original = {
        "xml": b"<fictional-ir-not-a-real-openvino-graph/>",
        "bin": b"fictional-model-bytes-NEVER-load-into-runtime",
    }
    for extension in ("xml", "bin"):
        (root / FILE_NAMES[extension]).write_bytes(original[extension])
    synthetic_pins = {
        extension: (len(raw), hashlib.sha384(raw).hexdigest())
        for extension, raw in original.items()
    }
    return original, synthetic_pins


class Intel0288IRSourceTests(unittest.TestCase):
    def test_official_manifest_requires_complete_distinct_fp32_fp16_pairs(self):
        self.assertEqual(SOURCE_COMMIT,
                         "af810de42bcf460a18610481f639dcdc86249e69")
        self.assertEqual(SOURCE_MANIFEST_BLOB,
                         "4f8409e88de4399b2f9471b1962ea18a3242a1e2")
        self.assertEqual(set(IR_PINS), {"FP32", "FP16"})
        self.assertEqual(IR_PINS["FP32"]["xml"][0], 450664)
        self.assertEqual(IR_PINS["FP32"]["bin"][0], 728052)
        self.assertEqual(IR_PINS["FP16"]["xml"][0], 614533)
        self.assertEqual(IR_PINS["FP16"]["bin"][0], 364098)
        self.assertEqual(
            IR_PINS["FP32"]["xml"][1],
            "25082c8e783ad358396039d6c3969f9d862512de0cd3dd87b23c2e5790fabc64e288336e5d42369fcc4c347be15c19f2")
        self.assertEqual(
            IR_PINS["FP16"]["bin"][1],
            "0a5ca9d000ce63b078149a3aa3993cd6e92d52fdecf1ff58fbe717481c2043db348938c32fed964846817dc321825a4c")
        for precision, items in IR_PINS.items():
            with self.subTest(precision=precision):
                self.assertEqual(set(items), {"xml", "bin"})
                self.assertNotEqual(items["xml"][1], items["bin"][1])
                for size, checksum in items.values():
                    self.assertTrue(0 < size < MAX_IR_FILE_BYTES)
                    self.assertEqual(len(checksum), 96)
                    self.assertTrue(all(c in "0123456789abcdef" for c in checksum))

    def test_fictional_bytes_match_both_members_but_cannot_claim_official_clearance(self):
        with tempfile.TemporaryDirectory(prefix="fictional-intel-ir-") as home:
            root = Path(home)
            original, pins = invented_ir(root)
            for precision in ("FP32", "FP16"):
                report = verify_ir_pair(root, precision, _synthetic_pins=pins)
                self.assertEqual(report["format"], "openvino_ir_xml_plus_bin_NOT_ONNX")
                self.assertEqual(report["verification"], "fictional_synthetic_pair_only")
                self.assertFalse(report["publisher_manifest_pair_checksums_checked"])
                self.assertFalse(report["actual_graph_execution_or_semantics_checked"])
                self.assertFalse(report["native_onnx_runtime_loadable_without_conversion"])
                self.assertFalse(report["conversion_parity_or_android_backend_verified"])
                self.assertFalse(report["publisher_and_model_training_rights_verified"])
                self.assertFalse(report["auto_confirm_allowed"])
                self.assertFalse(report["production_approved"])
            for kind in original:
                self.assertEqual((root / FILE_NAMES[kind]).read_bytes(),
                                 original[kind])
            with self.assertRaises(ValueError):
                verify_ir_pair(root, "FP32")  # publisher bytes were NOT staged

    def test_missing_changed_or_swapped_members_refused(self):
        with tempfile.TemporaryDirectory(prefix="invented-ir-integrity-") as home:
            root = Path(home)
            original, pins = invented_ir(root)
            xml, binary = (root / FILE_NAMES[k] for k in ("xml", "bin"))
            binary.unlink()
            with self.assertRaises(ValueError):
                verify_ir_pair(root, "FP32", _synthetic_pins=pins)
            binary.write_bytes(original["bin"] + b"tampered")
            with self.assertRaises(ValueError):
                verify_ir_pair(root, "FP32", _synthetic_pins=pins)
            xml.write_bytes(original["bin"])
            binary.write_bytes(original["xml"])
            with self.assertRaises(ValueError):
                verify_ir_pair(root, "FP32", _synthetic_pins=pins)
            original, pins = invented_ir(root)
            with self.assertRaises(ValueError):
                verify_ir_pair(root, "invalid", _synthetic_pins=pins)
            with self.assertRaises(ValueError):
                verify_ir_pair(root, True, _synthetic_pins=pins)
            with self.assertRaises(ValueError):
                verify_ir_pair(root, "FP32", _synthetic_pins={"xml": pins["xml"]})
            with self.assertRaises(ValueError):
                verify_ir_pair(root, "FP32", _synthetic_pins={
                    "xml": pins["xml"], "bin": (0, pins["bin"][1])})

    def test_untrusted_directories_symlinks_hardlinks_and_fifo_refused(self):
        with tempfile.TemporaryDirectory(prefix="invented-ir-links-") as home:
            root = Path(home)
            real_dir = root / "actual"
            real_dir.mkdir()
            _, pins = invented_ir(real_dir)
            alias = root / "linked"
            alias.symlink_to(real_dir, target_is_directory=True)
            with self.assertRaises(ValueError):
                verify_ir_pair(alias, "FP32", _synthetic_pins=pins)
            alias.unlink()
            xml = real_dir / FILE_NAMES["xml"]
            xml_alias = root / "other.xml"
            os.link(xml, xml_alias)
            with self.assertRaises(ValueError):
                verify_ir_pair(real_dir, "FP32", _synthetic_pins=pins)
            xml_alias.unlink()
            binary = real_dir / FILE_NAMES["bin"]
            binary.unlink()
            binary.symlink_to(xml)
            with self.assertRaises(ValueError):
                verify_ir_pair(real_dir, "FP32", _synthetic_pins=pins)
            binary.unlink()
            if hasattr(os, "mkfifo"):
                os.mkfifo(binary)
                with self.assertRaises(ValueError):
                    verify_ir_pair(real_dir, "FP32", _synthetic_pins=pins)

    def test_mutation_to_previously_hashed_xml_while_bin_is_read_refused(self):
        with tempfile.TemporaryDirectory(prefix="invented-ir-race-") as home:
            root = Path(home)
            _, pins = invented_ir(root)
            actual_digest = _digest_open_descriptor
            calls = []

            def mutate_xml_during_bin(handle, size, checksum):
                calls.append(handle.name)
                if len(calls) == 2:
                    (root / FILE_NAMES["xml"]).write_bytes(
                        b"changed after first digest")
                return actual_digest(handle, size, checksum)

            with patch("verify_intel_0288_ir_pair._digest_open_descriptor",
                       side_effect=mutate_xml_during_bin):
                with self.assertRaisesRegex(ValueError, "mutated"):
                    verify_ir_pair(root, "FP32", _synthetic_pins=pins)
            self.assertEqual(len(calls), 2)

    def test_cli_generic_refusal_no_identifiers_or_private_path_leaked(self):
        with tempfile.TemporaryDirectory(prefix="fictional-secret-owner-") as home:
            root = Path(home)
            original, _ = invented_ir(root)
            before = [(root / FILE_NAMES[k]).read_bytes() for k in ("xml", "bin")]
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                rc = main(["FP32", str(root)])
            self.assertEqual(rc, 2)
            self.assertEqual(stdout.getvalue(), "")
            self.assertNotIn(home, stderr.getvalue())
            self.assertNotIn("secret", stderr.getvalue().lower())
            self.assertEqual(before, [(root / FILE_NAMES[k]).read_bytes()
                                      for k in ("xml", "bin")])


if __name__ == "__main__":
    unittest.main(verbosity=2)
