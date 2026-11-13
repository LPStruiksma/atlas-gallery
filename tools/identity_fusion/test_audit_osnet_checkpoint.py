#!/usr/bin/env python3
"""Pure synthetic negative tests: no .pth weights, images, or ML packages."""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from audit_osnet_checkpoint import (
    CHECKPOINTS, MAX_CHECKPOINT_BYTES, REPO, REVISION, audit,
    main, verify_local_bytes,
)


class OsnetPretrainedAdmissionTests(unittest.TestCase):
    def test_all_publisher_pins_and_exact_fixed_revision(self):
        self.assertEqual(REPO, "kaiyangzhou/osnet")
        self.assertEqual(len(REVISION), 40)
        self.assertEqual(len(CHECKPOINTS), 4)
        self.assertEqual(set(CHECKPOINTS), {
            "osnet_x0_25_imagenet", "osnet_x0_25_msmt17",
            "osnet_x0_5_imagenet", "osnet_x0_5_msmt17",
        })
        for name, (filename, digest, stage, dataset) in CHECKPOINTS.items():
            with self.subTest(name=name):
                self.assertTrue(filename.endswith(".pth"))
                self.assertEqual(len(digest), 64)
                self.assertEqual(digest.lower(), digest)
                self.assertNotIn("/", filename)
                self.assertIn(stage, ("imagenet_backbone_only", "person_reid_trained"))
                self.assertEqual(dataset, "ImageNet" if "imagenet" in name else "MSMT17")
                self.assertEqual("imagenet" in filename, stage == "imagenet_backbone_only")

    def test_imagenet_backbone_must_never_be_called_trained_reid(self):
        for variant in ("osnet_x0_25_imagenet", "osnet_x0_5_imagenet"):
            record = audit(variant)
            self.assertTrue(record["imagenet_only_not_a_reid_evaluation_checkpoint"])
            self.assertFalse(record["reid_finetuning_claimed_by_published_artifact_name"])
            self.assertEqual(record["checkpoint_named_training_dataset"], "ImageNet")
            self.assertFalse(record["permitted_for_person_reid_inference"])
            self.assertFalse(record["local_staged_checkpoint_digest_verified"])

    def test_msmt17_reid_training_is_not_rights_or_performance_clearance(self):
        for variant in ("osnet_x0_25_msmt17", "osnet_x0_5_msmt17"):
            record = audit(variant)
            self.assertTrue(record["reid_finetuning_claimed_by_published_artifact_name"])
            self.assertFalse(record["imagenet_only_not_a_reid_evaluation_checkpoint"])
            self.assertEqual(record["checkpoint_named_training_dataset"], "MSMT17")
            for key in ("training_dataset_rights_verified",
                        "weight_redistribution_rights_independently_verified",
                        "published_digest_independently_signed_by_author",
                        "actual_model_weights_or_architecture_semantics_verified",
                        "android_runtime_or_accuracy_measured",
                        "permitted_for_native_bundle",
                        "validity_of_market1501_model_zoo_metric_for_this_checkpoint_proven",
                        "auto_person_confirmation_allowed"):
                self.assertFalse(record[key], key)

    def test_synthetic_exact_sha256_matches_without_deserializing(self):
        with tempfile.TemporaryDirectory() as home:
            file = Path(home) / "purely-invented-bytes.pth"
            data = b"This is NOT a torch checkpoint; only hash these bytes."
            file.write_bytes(data)
            before = file.read_bytes()
            self.assertEqual(verify_local_bytes(file, hashlib.sha256(data).hexdigest()),
                             len(data))
            self.assertEqual(file.read_bytes(), before)

    def test_wrong_hash_and_empty_file_rejected(self):
        with tempfile.TemporaryDirectory() as home:
            file = Path(home) / "fake.pth"
            file.write_bytes(b"fake")
            with self.assertRaisesRegex(ValueError, "differ"):
                verify_local_bytes(file, "0" * 64)
            file.write_bytes(b"")
            with self.assertRaises(ValueError):
                verify_local_bytes(file, hashlib.sha256(b"").hexdigest())

    def test_unbounded_sparse_checkpoint_rejected_before_read(self):
        with tempfile.TemporaryDirectory() as home:
            file = Path(home) / "sparse.pth"
            with file.open("wb") as stream:
                stream.truncate(MAX_CHECKPOINT_BYTES + 1)
            with self.assertRaisesRegex(ValueError, "bounded"):
                verify_local_bytes(file, "0" * 64)

    def test_reject_file_and_ancestor_symlinks(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            source = root / "safe.pth"
            source.write_bytes(b"known fictional")
            link = root / "alias.pth"
            link.symlink_to(source)
            for candidate in (link,):
                with self.assertRaisesRegex(ValueError, "symlink"):
                    verify_local_bytes(candidate,
                                       hashlib.sha256(source.read_bytes()).hexdigest())
            alias_dir = root / "aliasdir"
            alias_dir.symlink_to(root, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "symlink"):
                verify_local_bytes(alias_dir / source.name,
                                   hashlib.sha256(source.read_bytes()).hexdigest())

    def test_hardlinked_alias_must_not_be_read(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            source = root / "source.pth"
            source.write_bytes(b"not a real model")
            second = root / "second.pth"
            second.hardlink_to(source)
            with self.assertRaisesRegex(ValueError, "unaliased"):
                verify_local_bytes(source, hashlib.sha256(source.read_bytes()).hexdigest())

    def test_wrong_types_and_missing_files_fail(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            for value in (True, None, "0" * 64, "A" * 64, "g" * 64, ""):
                with self.subTest(value=value):
                    if value == "0" * 64:
                        continue
                    with self.assertRaises(ValueError):
                        verify_local_bytes(root / "missing", value)
            with self.assertRaisesRegex(ValueError, "regular"):
                verify_local_bytes(root / "does-not-exist.pth", "0" * 64)
            with self.assertRaises(ValueError):
                verify_local_bytes(root, "0" * 64)

    def test_reject_unregistered_variants_and_traversal(self):
        for value in (None, 1, True, "", "../osnet_x0_25_imagenet",
                      "osnet_x0_25_msmt17.pth", "osnet_x1_0_imagenet"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                audit(value)

    def test_cli_reads_no_images_and_reports_only_public_metadata(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(main(["--artifact", "osnet_x0_25_imagenet"]), 0)
        text = out.getvalue()
        self.assertIn("publisher_metadata_pin_only", text)
        self.assertNotIn("/storage/emulated", text)
        report = json.loads(text)
        self.assertFalse(report["auto_person_confirmation_allowed"])
        self.assertFalse(report["torch_pickle_executed"])
        self.assertFalse(report["permitted_for_person_reid_inference"])
        self.assertEqual(report["published_model_card_license_label"], "mit")

    def test_cli_local_checksum_failure_does_not_echo_path(self):
        with tempfile.TemporaryDirectory() as home:
            file = Path(home) / "fictional_private_filename_sentinel.pth"
            file.write_bytes(b"not a checkpoint")
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                exit_code = main(["--artifact", "osnet_x0_25_imagenet",
                                  "--file", str(file)])
            self.assertEqual(exit_code, 2)
            self.assertEqual(stdout.getvalue(), "")
            self.assertNotIn(file.name, stderr.getvalue())
            self.assertNotIn(home, stderr.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
