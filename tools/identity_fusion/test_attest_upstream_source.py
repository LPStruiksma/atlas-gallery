#!/usr/bin/env python3
"""Entirely fictional offline upstream-commit provenance tests; no checkpoint."""
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import attest_upstream_source as subject
from test_corroborate_intel_manifest import fixture, register


LICENSE = (b"                                 Apache License\n"
           b"                           Version 2.0, January 2004\n"
           b"                        http://www.apache.org/licenses/\n")


VALIDATION = (
    b"models:\n"
    b"  - name: person-reidentification-retail-0287\n"
    b"    datasets:\n"
    b"      - name: market1501\n"
    b"        preprocessing:\n"
    b"          - type: resize\n"
    b"            dst_width: 128\n"
    b"            dst_height: 256\n"
    b"            use_pillow: True\n"
    b"            interpolation: BILINEAR\n"
)


def synthetic_pins(manifest: bytes, license_bytes: bytes) -> dict:
    original = json.loads(subject.PINS_FILE.read_text(encoding="utf-8"))
    # Synthetic legacy-v2 fixtures may supply invented source bytes while
    # still testing historical behavior. Only official v3 can claim the new
    # independent strong-source integrity gates.
    for key in subject.V3_EXTRA_FIELDS:
        original.pop(key, None)
    return dict(original, schema_version=2,
                manifest_git_blob_sha1=subject.git_blob_sha1(manifest),
                license_git_blob_sha1=subject.git_blob_sha1(license_bytes))


def sha(data):
    return hashlib.sha256(data).hexdigest()


def call(args):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        rc = subject.main(args)
    return rc, out.getvalue(), err.getvalue()


class GitSourceObjectEvidenceTests(unittest.TestCase):
    def test_known_git_blob_header_and_pinned_real_upstream_metadata(self):
        # Independently fixed Git object hash, not a second copy of the
        # implementation expression (git hash-object --stdin, hello).
        self.assertEqual(subject.git_blob_sha1(b"hello"),
                         "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0")
        pins = json.loads(subject.PINS_FILE.read_text(encoding="utf-8"))
        self.assertEqual(subject.validate_pins(pins), pins)
        self.assertEqual(pins["source_commit_sha1"],
                         "af810de42bcf460a18610481f639dcdc86249e69")
        self.assertEqual(pins["manifest_git_blob_sha1"],
                         "66bd141ab025c72eac575c0491a32a83b546e60f")
        self.assertEqual(pins["license_git_blob_sha1"],
                         "261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64")
        self.assertIs(pins["production_approved"], False)

    def test_fictional_good_bytes_match_sha1_objects_without_model_claims(self):
        model = fixture().encode("utf-8")
        report = subject.corroborate_source_bytes(
            synthetic_pins(model, LICENSE), register(), model, LICENSE)
        self.assertTrue(report["manifest_git_blob_sha1_matches"])
        self.assertTrue(report["license_git_blob_sha1_matches"])
        self.assertTrue(report["manifest_and_license_agree_with_registered_intel_0287_metadata"])
        self.assertFalse(report["source_commit_tree_and_identity_cryptographically_authenticated"])
        self.assertFalse(report["git_sha1_collision_resistance_guaranteed"])
        self.assertFalse(report["model_weights_downloaded_or_executed"])
        self.assertFalse(report["specific_weight_redistribution_rights_verified"])
        self.assertFalse(report["training_data_rights_verified"])
        self.assertFalse(report["production_approved"])
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertNotIn("fake-person", json.dumps(report))
        self.assertFalse(report["source_sha256_pins_checked_by_cli"])

    def test_pinned_official_0287_market1501_resize_is_research_only(self):
        manifest = fixture().encode()
        pins = synthetic_pins(manifest, LICENSE)
        pins["accuracy_check_git_blob_sha1"] = subject.git_blob_sha1(VALIDATION)
        report = subject.corroborate_source_bytes(
            pins, register(), manifest, LICENSE, accuracy_check_raw=VALIDATION)
        self.assertTrue(report["validation_source_object_verified"])
        pre = report["published_validation_preprocessing"]
        self.assertTrue(pre["published_validation_resize_uses_pillow_bilinear"])
        self.assertEqual((pre["published_validation_target_width"],
                          pre["published_validation_target_height"]), (128, 256))
        self.assertEqual(pre["published_validation_dataset"], "market1501")
        self.assertFalse(pre["native_intel_pixel_scale_and_resize_parity_verified"])
        self.assertFalse(report["production_approved"])
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertFalse(subject.corroborate_source_bytes(
            pins, register(), manifest, LICENSE)["validation_source_object_verified"])
        for changed in (
            VALIDATION.replace(b"interpolation: BILINEAR", b"interpolation: NEAREST"),
            VALIDATION.replace(b"dst_width: 128", b"dst_width: 256"),
            VALIDATION + b"# altered upstream post-commit\\n",
        ):
            with self.subTest(bytes=len(changed)), self.assertRaises(ValueError):
                subject.corroborate_source_bytes(
                    pins, register(), manifest, LICENSE, accuracy_check_raw=changed)

    def test_accuracy_source_is_optional_and_v1_still_accepted(self):
        manifest = fixture().encode()
        pins = synthetic_pins(manifest, LICENSE)
        v1 = dict(pins)
        v1["schema_version"] = 1
        del v1["accuracy_check_path"]
        del v1["accuracy_check_git_blob_sha1"]
        subject.validate_pins(v1)
        report = subject.corroborate_source_bytes(v1, register(), manifest, LICENSE)
        self.assertFalse(report["validation_source_object_verified"])
        with self.assertRaises(ValueError):
            subject.corroborate_source_bytes(
                v1, register(), manifest, LICENSE, accuracy_check_raw=VALIDATION)
        pins["accuracy_check_path"] = "other-source"
        with self.assertRaises(ValueError):
            subject.validate_pins(pins)

    def test_cli_optional_validation_file_needs_matching_sha256_and_blob(self):
        manifest = fixture().encode()
        with tempfile.TemporaryDirectory(prefix="private-validation-") as d:
            root = Path(d)
            mp, lp, vp = (root / name for name in
                          ("model.yml", "LICENSE", "accuracy-check.yml"))
            mp.write_bytes(manifest)
            lp.write_bytes(LICENSE)
            vp.write_bytes(VALIDATION)
            pins = synthetic_pins(manifest, LICENSE)
            pins["accuracy_check_git_blob_sha1"] = subject.git_blob_sha1(VALIDATION)
            pins_file, artifacts_file = root / "pins.json", root / "artifacts.json"
            pins_file.write_text(json.dumps(pins), encoding="utf-8")
            artifacts_file.write_text(json.dumps(register()), encoding="utf-8")
            base = ["--manifest", str(mp), "--manifest-sha256", sha(manifest),
                    "--license", str(lp), "--license-sha256", sha(LICENSE)]
            flags = base + ["--accuracy-check", str(vp),
                            "--accuracy-check-sha256", sha(VALIDATION)]
            with (patch.object(subject, "PINS_FILE", pins_file),
                  patch.object(subject, "ARTIFACTS_FILE", artifacts_file)):
                rc, output, error = call(flags)
                self.assertEqual(rc, 0, error)
                self.assertTrue(json.loads(output)["validation_source_object_verified"])
                self.assertNotIn(d, output)
                for missing in (base + ["--accuracy-check", str(vp)],
                                base + ["--accuracy-check-sha256", sha(VALIDATION)]):
                    rc, output, error = call(missing)
                    self.assertEqual(rc, 2)
                    self.assertEqual(output, "")
                    self.assertNotIn(d, error)
                vp.write_bytes(VALIDATION + b"modified")
                rc, output, error = call(
                    base + ["--accuracy-check", str(vp),
                            "--accuracy-check-sha256", sha(vp.read_bytes())])
                self.assertEqual(rc, 2)
                self.assertNotIn(d, error)

    def test_changed_manifest_or_license_fails_even_when_sha256_is_recomputed(self):
        manifest = fixture().encode()
        pins = synthetic_pins(manifest, LICENSE)
        for changed_m, changed_l in (
            (manifest + b"\n# after revision\n", LICENSE),
            (manifest, LICENSE + b"\nnew notice\n"),
            (manifest.replace(b"3571437", b"3571438"), LICENSE),
            (manifest, b"                                 Apache License\n"
                       b"                           Version 3.0, January 2004\n"),
        ):
            with self.subTest(manifest_len=len(changed_m), license_len=len(changed_l)):
                with self.assertRaises(ValueError):
                    subject.corroborate_source_bytes(
                        pins, register(), changed_m, changed_l)

    def test_malicious_or_relabelled_pins_are_refused(self):
        original = json.loads(subject.PINS_FILE.read_text("utf-8"))
        for key, value in (
            ("schema_version", True),
            ("source_repository", "evil/evil"),
            ("source_commit_sha1", "garbage"),
            ("manifest_git_blob_sha1", "A" * 40),
            ("license_git_blob_sha1", "a" * 39),
            ("manifest_path", "../secrets"),
            ("license_path", "other/LICENSE"),
            ("production_approved", True),
            ("checkpoint_redistribution_rights_status", "approved"),
            ("training_data_rights_status", "approved"),
            ("source_verification_status", "cryptographically_authenticated"),
        ):
            with self.subTest(field=key), self.assertRaises(ValueError):
                subject.validate_pins(dict(original, **{key: value}))
        with self.assertRaises(ValueError):
            subject.validate_pins(dict(original, private_user_path="/sdcard"))
        with self.assertRaises(ValueError):
            subject.git_blob_sha1(b"")

    def test_cli_fails_closed_on_symlink_or_stale_sha_with_no_path_leak(self):
        manifest = fixture().encode()
        with tempfile.TemporaryDirectory(prefix="secret-local-upstream-") as root:
            tmp = Path(root)
            mp, lp = tmp / "model.yml", tmp / "LICENSE"
            mp.write_bytes(manifest)
            lp.write_bytes(LICENSE)
            register_path, pins_path = tmp / "artifacts.json", tmp / "pins.json"
            register_path.write_text(json.dumps(register()), encoding="utf-8")
            pins_path.write_text(json.dumps(synthetic_pins(manifest, LICENSE)), encoding="utf-8")
            argv = ["--manifest", str(mp), "--manifest-sha256", sha(manifest),
                    "--license", str(lp), "--license-sha256", sha(LICENSE)]
            with (patch.object(subject, "PINS_FILE", pins_path),
                  patch.object(subject, "ARTIFACTS_FILE", register_path)):
                status, stdout, err = call(argv)
                self.assertEqual(status, 0, err)
                report = json.loads(stdout)
                self.assertTrue(report["source_sha256_pins_checked_by_cli"])
                self.assertNotIn(root, stdout)
                self.assertFalse(report["production_approved"])
                lp.write_bytes(LICENSE + b"changed")
                status, stdout, err = call(argv)
                self.assertEqual(status, 2)
                self.assertEqual(stdout, "")
                self.assertNotIn(root, err)
                lp.write_bytes(LICENSE)
                alias = tmp / "symlink.yml"
                alias.symlink_to(mp)
                argv[1] = str(alias)
                status, stdout, err = call(argv)
                self.assertEqual(status, 2)
                self.assertEqual(stdout, "")
                self.assertNotIn(root, err)

    def test_unsupported_file_sizes_and_unicode_refused(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "manifest"
            p.write_bytes(b"x" * (subject.MAX_MANIFEST_BYTES + 1))
            with self.assertRaises(ValueError):
                subject.read_regular_pinned(p, sha(p.read_bytes()), subject.MAX_MANIFEST_BYTES)
            p.write_bytes(b"abcd")
            with self.assertRaises(ValueError):
                subject.read_regular_pinned(p, "bad-sha", subject.MAX_MANIFEST_BYTES)
            with self.assertRaises(ValueError):
                subject.read_regular_pinned(p, sha(b"oops"), subject.MAX_MANIFEST_BYTES)
        raw = b"\xff\xfe"
        with self.assertRaises(ValueError):
            subject.corroborate_source_bytes(synthetic_pins(raw, LICENSE), register(),
                                             raw, LICENSE)


    def test_official_v3_strong_source_fields_cannot_be_replaced(self):
        official = json.loads(subject.PINS_FILE.read_text("utf-8"))
        self.assertEqual(official["schema_version"], 3)
        self.assertEqual(set(subject.OFFICIAL_SOURCE_SHA256),
                         {"manifest", "license", "accuracy_check"})
        for key, digest in subject.OFFICIAL_SOURCE_SHA256.items():
            with self.subTest(key=key):
                self.assertEqual(official[key + "_sha256"], digest)
                self.assertEqual(len(digest), 64)
                forged = dict(official, **{key + "_sha256": "f" * 64})
                with self.assertRaisesRegex(ValueError, "immutable upstream"):
                    subject.validate_pins(forged)
        for missing in subject.V3_EXTRA_FIELDS:
            incomplete = dict(official)
            del incomplete[missing]
            with self.assertRaises(ValueError):
                subject.validate_pins(incomplete)

    def test_fictional_sha1_collision_cannot_bypass_official_v3_sha256(self):
        official = json.loads(subject.PINS_FILE.read_text("utf-8"))
        fake_manifest = fixture().encode("utf-8")
        # Pretend that invented bytes have the historical fixed SHA1 names:
        # frozen SHA256 detects the forgery before the Git-blob checks run.
        with patch.object(subject, "git_blob_sha1", side_effect=AssertionError(
                "Strong SHA256 should refuse before legacy SHA1 evaluation")):
            with self.assertRaisesRegex(ValueError, "immutable SHA256"):
                subject.corroborate_source_bytes(
                    official, register(), fake_manifest, LICENSE,
                    accuracy_check_raw=VALIDATION,
                )
        with self.assertRaisesRegex(ValueError, "requires"):
            subject.corroborate_source_bytes(
                official, register(), fake_manifest, LICENSE)

    def test_official_v3_cli_does_not_read_caller_rehashed_staged_docs(self):
        official = json.loads(subject.PINS_FILE.read_text("utf-8"))
        fake_manifest = fixture().encode("utf-8")
        with tempfile.TemporaryDirectory(prefix="fictional-intel-v3-") as td:
            root = Path(td)
            pin_file = root / "pins.json"
            reg_file = root / "artifacts.json"
            pin_file.write_text(json.dumps(official))
            reg_file.write_text(json.dumps(register()))
            argv = ["--manifest", str(root / "never-read.yml"),
                    "--manifest-sha256", sha(fake_manifest),
                    "--license", str(root / "never-read-LICENSE"),
                    "--license-sha256", sha(LICENSE),
                    "--accuracy-check", str(root / "never-read-validation"),
                    "--accuracy-check-sha256", sha(VALIDATION)]
            with (patch.object(subject, "PINS_FILE", pin_file),
                  patch.object(subject, "ARTIFACTS_FILE", reg_file),
                  patch.object(subject, "read_regular_pinned") as reader):
                rc, out, err = call(argv)
                self.assertEqual(rc, 2)
                reader.assert_not_called()
                self.assertEqual(out, "")
                self.assertNotIn(td, err)
                rc, out, err = call(argv[:-4])
                self.assertEqual(rc, 2)
                self.assertEqual(out, "")
                reader.assert_not_called()

    def test_legacy_synthetic_schema_v2_reports_no_frozen_strong_source(self):
        manifest = fixture().encode()
        pins = synthetic_pins(manifest, LICENSE)
        self.assertEqual(pins["schema_version"], 2)
        report = subject.corroborate_source_bytes(
            pins, register(), manifest, LICENSE)
        self.assertFalse(report["strong_three_source_sha256_pins_verified"])
        self.assertFalse(report["source_sha256_fixed_to_upstream_revision"])
        self.assertFalse(report["source_sha256_is_a_signed_publisher_attestation"])


if __name__ == "__main__":
    unittest.main()
