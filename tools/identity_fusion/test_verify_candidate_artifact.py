#!/usr/bin/env python3
"""Completely synthetic bytes and metadata: no pretrained model downloads."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from contextlib import redirect_stderr

from verify_candidate_artifact import validate_register, verify_local_file, main


class CandidateArtifactRegisterTests(unittest.TestCase):
    def setUp(self):
        self.document = json.loads(Path(__file__).with_name("candidate_model_artifacts.json").read_text())
        self.items = validate_register(self.document)

    def test_known_pins_differentiate_imagenet_and_reid_training(self):
        self.assertEqual(len(self.items), 3)
        self.assertEqual(self.items["torchreid_osnet_x025_imagenet"]["source_purpose"], "imagenet_only")
        self.assertEqual(self.items["torchreid_osnet_x025_msmt17"]["source_purpose"], "reid_trained")
        self.assertEqual(self.items["intel_omz_reid_0287_onnx"]["checksum_algorithm"], "sha384")
        for item in self.items.values():
            self.assertEqual(item["weight_rights_status"], "unverified")
            self.assertIs(item["production_approved"], False)

    def test_tampered_register_cannot_claim_license_or_production_authority(self):
        for key, value in (("production_approved", True),
                           ("weight_rights_status", "approved"),
                           ("checksum", "x" * 64),
                           ("bytes", -1),
                           ("upstream_page", "https://evil.invalid/weights"),
                           ("source_purpose", "magically_identity_proven")):
            changed = copy.deepcopy(self.document)
            changed["artifacts"][0][key] = value
            with self.subTest(key=key):
                with self.assertRaises(ValueError):
                    validate_register(changed)
        duplicate = copy.deepcopy(self.document)
        duplicate["artifacts"].append(dict(duplicate["artifacts"][0]))
        with self.assertRaises(ValueError):
            validate_register(duplicate)

    def test_local_synthetic_bytes_hash_without_executing_model(self):
        blob = b"fictional-model-bytes-not-a-real-checkpoint"
        digest = hashlib.sha384(blob).hexdigest()
        item = dict(self.items["intel_omz_reid_0287_onnx"], bytes=len(blob), checksum=digest)
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "fake.onnx"
            f.write_bytes(blob)
            before = f.read_bytes()
            report = verify_local_file(item, f)
            self.assertEqual(report["status"], "integrity_only_no_execution")
            self.assertTrue(report["checksum_matches_upstream_pin"])
            self.assertFalse(report["production_approved"])
            self.assertFalse(report["weight_rights_verified"])
            self.assertFalse(report["accuracy_benchmarked"])
            self.assertEqual(f.read_bytes(), before)
            f.write_bytes(blob + b"modified")
            with self.assertRaises(ValueError):
                verify_local_file(item, f)
            link = Path(tmp) / "symlink.onnx"
            link.symlink_to(f)
            with self.assertRaises(ValueError):
                verify_local_file(item, link)

    def test_cli_logs_never_disclose_private_local_artifact_path(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "SECRET-test-file.onnx"
            f.write_bytes(b"fake")
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                rc = main(["intel_omz_reid_0287_onnx", str(f)])
            self.assertEqual(rc, 2)
            self.assertNotIn(str(f), stderr.getvalue())
            self.assertNotIn("SECRET", stderr.getvalue())


    def test_nested_symlink_dir_rejected_even_if_leaf_itself_is_regular(self):
        blob = b"fictional-immutable-on-disk-model-bytes"
        item = dict(self.items["intel_omz_reid_0287_onnx"],
                    checksum=hashlib.sha384(blob).hexdigest(), bytes=len(blob))
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            physical = root / "actual"
            physical.mkdir()
            source = physical / "dummy.onnx"
            source.write_bytes(blob)
            alias = root / "aliased"
            alias.symlink_to(physical, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "unalias"):
                verify_local_file(item, alias / "dummy.onnx")
            self.assertEqual(source.read_bytes(), blob)

    def test_hardlink_alias_raises_even_with_matching_digest(self):
        blob = b"not-a-real-onnx"
        item = dict(self.items["intel_omz_reid_0287_onnx"],
                    checksum=hashlib.sha384(blob).hexdigest(), bytes=len(blob))
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            source = root / "source.onnx"
            source.write_bytes(blob)
            alias = root / "another-name.onnx"
            os.link(source, alias)
            with self.assertRaisesRegex(ValueError, "unaliased"):
                verify_local_file(item, source)
            with self.assertRaisesRegex(ValueError, "unaliased"):
                verify_local_file(item, alias)
            self.assertEqual(source.read_bytes(), blob)

    def test_fifo_and_sparse_oversize_refused_without_blocking(self):
        blob = b"fictional"
        item = dict(self.items["intel_omz_reid_0287_onnx"],
                    checksum=hashlib.sha384(blob).hexdigest(), bytes=None)
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            fifo = root / "non-regular.onnx"
            os.mkfifo(fifo)
            with self.assertRaisesRegex(ValueError, "unaliased"):
                verify_local_file(item, fifo)
            huge = root / "sparse-too-large.onnx"
            with huge.open("wb") as out:
                out.truncate(64 * 1024 * 1024 + 1)
            with self.assertRaisesRegex(ValueError, "bounded"):
                verify_local_file(item, huge)
            with self.assertRaises(ValueError):
                verify_local_file(item, root)
            with self.assertRaises(ValueError):
                verify_local_file(item, root / "missing.onnx")

    def test_symlink_replacement_before_leaf_open_is_not_followed(self):
        blob = b"finite-fictional-original"
        item = dict(self.items["intel_omz_reid_0287_onnx"],
                    checksum=hashlib.sha384(blob).hexdigest(), bytes=len(blob))
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            original = root / "model.onnx"
            original.write_bytes(blob)
            outside = root / "outside.onnx"
            outside.write_bytes(blob)
            archived = root / "moved.onnx"
            real_open = os.open
            switched = [False]
            def replace_then_open(candidate, flags, *args, **kwargs):
                if candidate == original.name and not switched[0]:
                    switched[0] = True
                    os.rename(original, archived)
                    original.symlink_to(outside)
                return real_open(candidate, flags, *args, **kwargs)
            with patch("verify_candidate_artifact.os.open", side_effect=replace_then_open):
                with self.assertRaisesRegex(ValueError, "unalias"):
                    verify_local_file(item, original)
            self.assertTrue(switched[0])
            self.assertEqual(outside.read_bytes(), blob)
            self.assertEqual(archived.read_bytes(), blob)

    def test_relative_parent_escape_and_malformed_artifact_not_accepted(self):
        blob = b"private-free-test-model"
        item = dict(self.items["intel_omz_reid_0287_onnx"],
                    checksum=hashlib.sha384(blob).hexdigest(), bytes=len(blob))
        with tempfile.TemporaryDirectory() as home:
            target = Path(home) / "mock.onnx"
            target.write_bytes(blob)
            self.assertEqual(verify_local_file(item, target)["bytes"], len(blob))
            with self.assertRaises(ValueError):
                verify_local_file(item, Path(home) / ".." / Path(home).name / "mock.onnx")
            for broken in (None, 5, "abc", True):
                with self.subTest(broken=broken), self.assertRaises(ValueError):
                    verify_local_file(item, broken)
            changed = dict(item, checksum="0" * 64)
            with self.assertRaises(ValueError):
                verify_local_file(changed, target)
            changed = dict(item, checksum="invalid")
            with self.assertRaises(ValueError):
                verify_local_file(changed, target)


if __name__ == "__main__":
    unittest.main(verbosity=2)
