#!/usr/bin/env python3
"""No-model, invented-text regression for immutable FastReID source audit."""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from attest_fastreid_sources import (
    PINS, SOURCE_SHA256, RELEASE_URLS, attest_sources, git_blob_sha1, main, read_pinned_text,
)


def fake_documents():
    zoo = (
        "# FastReID Model Zoo and Baselines\n"
        "### Market1501 Baselines\n"
        "**BoT**:\n"
        "| [BoT(R50)](https://github.com/JDAI-CV/fast-reid/blob/master/configs/Market1501/bagtricks_R50.yml) | ImageNet | 94.4% | 86.1% | 59.4% | [model]("
        + RELEASE_URLS["bot_r50_market1501"] + ") |\n"
        "**AGW**:\n"
        "| [AGW(R50)](https://github.com/JDAI-CV/fast-reid/blob/master/configs/Market1501/AGW_R50.yml) | ImageNet | 95.3% | 88.2% | 66.3% | [model]("
        + RELEASE_URLS["agw_r50_market1501"] + ") |\n"
        "### DukeMTMC Baseline\n"
        "| [BoT(R50)](DukeMTMC) | ImageNet | 88.0% | 70.0% | 1.0% | [model](duke.pth) |\n"
    )
    docs = {
        "zoo": zoo,
        "license": "Apache License\nVersion 2.0, January 2004\n",
        "bot": '_BASE_: ../Base-bagtricks.yml\n  NAMES: ("Market1501",)\n  TESTS: ("Market1501",)\n',
        "agw": '_BASE_: ../Base-AGW.yml\n  NAMES: ("Market1501",)\n  TESTS: ("Market1501",)\n',
        "base_bot": "    PRETRAIN: True\n  SIZE_TEST: [ 256, 128 ]\n"
                    "    DEPTH: 50x\n    FEAT_DIM: 2048\n",
        "base_agw": "_BASE_: Base-bagtricks.yml\n"
                    "    WITH_NL: True\n    POOL_LAYER: GeneralizedMeanPooling\n",
    }
    bytes_docs = {k: v.encode("utf-8") for k, v in docs.items()}
    pins = {k: (PINS[k][0], git_blob_sha1(raw))
            for k, raw in bytes_docs.items()}
    return bytes_docs, pins


class FakeFastReidSourceTests(unittest.TestCase):
    def test_public_registered_exact_commit_and_six_blob_ids(self):
        self.assertEqual(set(PINS), {
            "zoo", "license", "bot", "agw", "base_bot", "base_agw"
        })
        for name, (path, sha) in PINS.items():
            with self.subTest(name=name):
                self.assertEqual(len(sha), 40)
                self.assertNotIn("..", path)
        self.assertEqual(set(RELEASE_URLS),
                         {"bot_r50_market1501", "agw_r50_market1501"})
        self.assertTrue(all(v.endswith(".pth") for v in RELEASE_URLS.values()))

    def test_fictional_evidence_is_successful_but_untrusted(self):
        docs, pins = fake_documents()
        report = attest_sources(docs, _synthetic_pins=pins)
        self.assertEqual(report["status"], "synthetic_source_validation_only")
        self.assertFalse(report["official_git_blob_pins_checked"])
        self.assertEqual(report["published_benchmark_metadata_only"][
                         "published_height_px"], 256)
        self.assertEqual(report["published_benchmark_metadata_only"][
                         "upstream_market1501_bot_r50_rank1_pct"], 94.4)
        self.assertFalse(report["checkpoint_bytes_downloaded_or_deserialized"])
        self.assertFalse(report["production_approved"])
        self.assertFalse(report["auto_person_confirmation_allowed"])
        for item in report["publisher_reported_reid_models"].values():
            self.assertEqual(item["backbone_pretraining_column"], "ImageNet")
            self.assertEqual(item["published_checkpoint_training_task"],
                             "Market1501_person_reid")
            self.assertFalse(item["asset_bytes_verified"])
            self.assertFalse(item["model_inference_permitted"])
        self.assertEqual(report, attest_sources(
            dict(reversed(list(docs.items()))), _synthetic_pins=pins))

    def test_mock_source_never_validates_real_publisher_blobs(self):
        docs, pins = fake_documents()
        with self.assertRaisesRegex(ValueError, "Git SHA1/SHA256"):
            attest_sources(docs)
        corrupted = dict(docs)
        corrupted["zoo"] += b"\nchanged"
        with self.assertRaisesRegex(ValueError, "Git SHA1/SHA256"):
            attest_sources(corrupted, _synthetic_pins=pins)

    def test_mutated_model_row_requires_repin_and_still_fails_semantically(self):
        docs, _ = fake_documents()
        variants = [
            ("ImageNet", "MSMT17"), ("94.4%", "99.9%"),
            ("market_bot_R50.pth", "duke_bot_R50.pth"),
            ("bagtricks_R50.yml", "AGW_R50.yml"),
            ("### DukeMTMC Baseline", "### Unknown baseline"),
        ]
        for before, after in variants:
            altered = dict(docs)
            altered["zoo"] = altered["zoo"].replace(
                before.encode(), after.encode(), 1)
            new_pins = {k: (PINS[k][0], git_blob_sha1(raw))
                        for k, raw in altered.items()}
            with self.subTest(edit=before), self.assertRaises(ValueError):
                attest_sources(altered, _synthetic_pins=new_pins)

    def test_duplicate_market_row_rejected_even_when_rehashed(self):
        docs, _ = fake_documents()
        row = [x for x in docs["zoo"].splitlines()
               if x.startswith(b"| [BoT(R50)]")][0]
        clone = dict(docs)
        clone["zoo"] = clone["zoo"].replace(b"**AGW**:", row + b"\n**AGW**:")
        pins = {k: (PINS[k][0], git_blob_sha1(raw))
                for k, raw in clone.items()}
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            attest_sources(clone, _synthetic_pins=pins)

    def test_config_pretraining_and_shape_drift_rejected(self):
        docs, _ = fake_documents()
        for key, original, changed in (
            ("base_bot", b"    PRETRAIN: True", b"    PRETRAIN: False"),
            ("base_bot", b"[ 256, 128 ]", b"[ 128, 256 ]"),
            ("base_agw", b"    WITH_NL: True", b"    WITH_NL: False"),
            ("bot", b"Market1501", b"DukeMTMC"),
        ):
            altered = dict(docs)
            altered[key] = altered[key].replace(original, changed, 1)
            pins = {k: (PINS[k][0], git_blob_sha1(raw))
                    for k, raw in altered.items()}
            with self.subTest(key=key), self.assertRaises(ValueError):
                attest_sources(altered, _synthetic_pins=pins)

    def test_unreviewed_or_missing_source_never_looks_ok(self):
        docs, pins = fake_documents()
        for key in PINS:
            incomplete = dict(docs)
            del incomplete[key]
            with self.subTest(key=key), self.assertRaises(ValueError):
                attest_sources(incomplete, _synthetic_pins=pins)
        docs["license"] = b"MIT License\n"
        with self.assertRaises(ValueError):
            attest_sources(docs, _synthetic_pins={
                k: (PINS[k][0], git_blob_sha1(raw))
                for k, raw in docs.items()
            })

    def test_source_file_is_read_only_and_sha256_verified(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "fictional-source.txt"
            raw = b"fictional source material, never a real model"
            path.write_bytes(raw)
            before = path.read_bytes()
            self.assertEqual(read_pinned_text(path,
                              hashlib.sha256(raw).hexdigest()), raw)
            self.assertEqual(path.read_bytes(), before)
            with self.assertRaisesRegex(ValueError, "SHA256"):
                read_pinned_text(path, "0" * 64)
            for bad in (True, b"not-a-digest", "", "z" * 64):
                with self.subTest(bad=bad), self.assertRaises(ValueError):
                    read_pinned_text(path, bad)

    def test_symlinks_and_hardlink_aliases_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            primary = root / "primary.txt"
            primary.write_text("synthetic")
            digest = hashlib.sha256(primary.read_bytes()).hexdigest()
            link = root / "alias.txt"
            link.symlink_to(primary)
            with self.assertRaises(ValueError):
                read_pinned_text(link, digest)
            dir_link = root / "alias-directory"
            dir_link.symlink_to(root, target_is_directory=True)
            with self.assertRaises(ValueError):
                read_pinned_text(dir_link / primary.name, digest)
            second = root / "same-inode.txt"
            os.link(primary, second)
            with self.assertRaises(ValueError):
                read_pinned_text(primary, digest)

    def test_oversized_and_nonfile_rejected(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            too_big = root / "huge.txt"
            with too_big.open("wb") as f:
                f.truncate(32769)
            with self.assertRaises(ValueError):
                read_pinned_text(too_big, "0" * 64)
            with self.assertRaises(ValueError):
                read_pinned_text(root, "0" * 64)
            with self.assertRaises(ValueError):
                read_pinned_text(root / "missing", "0" * 64)

    def test_cli_refusal_masks_private_staged_file_paths(self):
        with tempfile.TemporaryDirectory() as home:
            private = Path(home) / "SENTINEL_person_123.txt"
            private.write_text("fictional")
            args = []
            for key in PINS:
                flag = key.replace("_", "-")
                args += ["--" + flag, str(private),
                         "--" + flag + "-sha256", "0" * 64]
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = main(args)
            self.assertEqual(code, 2)
            self.assertEqual(stdout.getvalue(), "")
            self.assertNotIn("SENTINEL", stderr.getvalue())
            self.assertNotIn(home, stderr.getvalue())


    def test_official_sha256_pins_cover_same_six_exact_source_objects(self):
        self.assertEqual(set(PINS), set(SOURCE_SHA256))
        for key, sha in SOURCE_SHA256.items():
            with self.subTest(source=key):
                self.assertEqual(len(sha), 64)
                self.assertTrue(all(ch in "0123456789abcdef" for ch in sha))
        self.assertEqual(
            SOURCE_SHA256["zoo"],
            "72c4f5c6bba0e44d1f11f5badee651df592b60c3b6a8de6a0650068f2494d632",
        )
        self.assertEqual(
            SOURCE_SHA256["bot"],
            "1c8de024f01fdc559cb1925e9ea617bf7c6b30942e587f398f65e68f97c09d08",
        )

    def test_forged_legacy_git_sha1_cannot_bypass_fixed_source_sha256(self):
        # Simulate a SHA-1 blob collision with fabricated source bytes:
        # legacy Git-object verification appears to pass EVERY file,
        # but the separately frozen SHA-256 pins must still reject them.
        docs, _ = fake_documents()
        by_raw = {raw: PINS[name][1] for name, raw in docs.items()}
        with patch("attest_fastreid_sources.git_blob_sha1",
                   side_effect=lambda raw: by_raw[raw]):
            with self.assertRaisesRegex(ValueError, "SHA1/SHA256"):
                attest_sources(docs)

    def test_cli_refuses_recalculated_unregistered_digests_before_reading(self):
        # Wrong but well-formed caller-supplied SHA256 must not be treated
        # as a replacement for the GitHub-observed frozen fingerprint.
        args = []
        for key in PINS:
            flag = key.replace("_", "-")
            args.extend(["--" + flag, "/never/read/a/source/" + key,
                         "--" + flag + "-sha256", "0" * 64])
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch("attest_fastreid_sources.read_pinned_text") as reader:
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(main(args), 2)
            reader.assert_not_called()
        self.assertEqual(stdout.getvalue(), "")
        self.assertNotIn("/never/read", stderr.getvalue())

    def test_mocked_synthetic_pins_cannot_claim_official_strong_source(self):
        docs, synthetic_pins = fake_documents()
        report = attest_sources(docs, _synthetic_pins=synthetic_pins)
        self.assertFalse(report["official_git_blob_pins_checked"])
        self.assertFalse(report["official_git_source_sha256_pins_checked"])
        self.assertFalse(report["git_source_sha256_is_publisher_signature"])
        self.assertFalse(report["production_approved"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
