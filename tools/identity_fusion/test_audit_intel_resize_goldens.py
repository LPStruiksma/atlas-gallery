#!/usr/bin/env python3
"""No-photo/artificial-only Intel published resize & BGR tensor regression."""
from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import struct
import unittest
from unittest.mock import patch

from audit_intel_resize_goldens import (
    PATTERN_DIMENSIONS, PLANE, REFERENCE_GENERATOR_VERSION,
    REFERENCE_SHA256, ACCURACY_CHECK_GIT_BLOBS, TARGET, audit_published_intel_resize,
    main, opencv_linear_resize, pillow_resize, rgb_to_raw_bgr_nchw_float32le,
)
from benchmark_resize_parity import artificial_rgb


class SyntheticIntelResizeGoldensTests(unittest.TestCase):
    def test_frozen_source_rgb_hashes_and_no_user_images(self):
        self.assertEqual(REFERENCE_GENERATOR_VERSION,
                         "Pillow 12.3.0 Image.Resampling.BILINEAR")
        self.assertEqual(set(ACCURACY_CHECK_GIT_BLOBS),
                         {"intel_omz_0287", "intel_omz_0288"})
        self.assertEqual(ACCURACY_CHECK_GIT_BLOBS["intel_omz_0288"],
                         "28d6981f6d9e0f055e1c7f6107e5b1b0208516d0")
        self.assertEqual(set(REFERENCE_SHA256),
                         {"gradient", "checker", "colorbars", "impulse"})
        self.assertEqual(TARGET, (128, 256))
        for label, (width, height) in PATTERN_DIMENSIONS.items():
            raw = artificial_rgb(label, width, height)
            self.assertEqual(hashlib.sha256(raw).hexdigest(),
                             REFERENCE_SHA256[label]["input_rgb"])
            for digest in REFERENCE_SHA256[label].values():
                self.assertEqual(len(digest), 64)
                self.assertTrue(all(ch in "0123456789abcdef" for ch in digest))

    def test_reference_rgb_to_raw_bgr_nchw_is_lossless_channel_order_and_range(self):
        red = bytes((255, 0, 0)) * PLANE
        tensor = rgb_to_raw_bgr_nchw_float32le(red)
        self.assertEqual(len(tensor), 3 * PLANE * 4)
        self.assertEqual(struct.unpack_from("<f", tensor, 0)[0], 0.)
        self.assertEqual(struct.unpack_from("<f", tensor, PLANE * 4)[0], 0.)
        self.assertEqual(struct.unpack_from("<f", tensor, PLANE * 8)[0], 255.)
        self.assertEqual(struct.unpack_from("<f", tensor, 4 * (PLANE - 1))[0], 0.)
        self.assertEqual(struct.unpack_from("<f", tensor, 4 * (2 * PLANE + PLANE - 1))[0], 255.)
        for invalid in (red[:-1], red + b"X", bytearray(red), None):
            with self.subTest(invalid=type(invalid).__name__), self.assertRaises(
                    ValueError):
                rgb_to_raw_bgr_nchw_float32le(invalid)

    @unittest.skipUnless(importlib.util.find_spec("PIL"), "optional Pillow unavailable")
    def test_pillow_generated_all_four_reference_vectors_exact(self):
        for model in ACCURACY_CHECK_GIT_BLOBS:
            report = audit_published_intel_resize(
                pillow_resize, model_id=model)
            self.assertTrue(report["all_four_synthetic_probes_exact"])
            self.assertEqual(len(report["results"]), 4)
            self.assertEqual(report["target_nchw"], [1, 3, 256, 128])
            self.assertEqual(report["channel_order"], "BGR")
            for row in report["results"]:
                self.assertTrue(row["pillow_12_3_rgb_byte_parity"])
                self.assertTrue(row["raw_bgr_nchw_float32le_tensor_byte_parity"])
                self.assertEqual(
                    row["candidate_bgr_tensor_sha256"],
                    REFERENCE_SHA256[row["pattern"]]["tensor_bgr_f32le"])
            for flag in (
                "independent_real_image_decoder_or_exif_verified",
                "actual_0288_ir_or_0287_onnx_graph_input_verified",
                "mobile_android_pixel_or_model_parity_verified",
                "real_person_accuracy_or_training_rights_verified",
                "real_model_inference_executed",
                "auto_confirm_allowed", "production_approved",
            ):
                self.assertFalse(report[flag], flag)
            printed = json.dumps(report)
            for forbidden in ("/storage", "person_uid", "filename",
                              "source_photo", "embedding", "owner"):
                self.assertNotIn(forbidden, printed)

    @unittest.skipUnless(
        importlib.util.find_spec("cv2") and importlib.util.find_spec("numpy"),
        "optional OpenCV/NumPy unavailable")
    def test_cv2_inter_linear_falsely_passes_identity_shape_but_fails_resampling(self):
        result = audit_published_intel_resize(
            opencv_linear_resize, model_id="intel_omz_0288")
        self.assertFalse(result["all_four_synthetic_probes_exact"])
        rows = {row["pattern"]: row for row in result["results"]}
        self.assertFalse(rows["gradient"]["pillow_12_3_rgb_byte_parity"])
        self.assertFalse(rows["checker"]["pillow_12_3_rgb_byte_parity"])
        self.assertTrue(rows["colorbars"]["pillow_12_3_rgb_byte_parity"])
        self.assertTrue(rows["colorbars"]["raw_bgr_nchw_float32le_tensor_byte_parity"])
        self.assertFalse(result["production_approved"])

    @unittest.skipUnless(importlib.util.find_spec("PIL"), "optional Pillow unavailable")
    def test_one_bit_flip_of_gradient_refuses_whole_pipeline(self):
        def corrupted(width, height, rgb):
            output = bytearray(pillow_resize(width, height, rgb))
            if width == 341:
                output[33] ^= 1
            return bytes(output)
        result = audit_published_intel_resize(
            corrupted, model_id="intel_omz_0288")
        self.assertFalse(result["all_four_synthetic_probes_exact"])
        self.assertEqual([x["pattern"] for x in result["results"]
                          if not x["pillow_12_3_rgb_byte_parity"]],
                         ["gradient"])
        self.assertFalse(result["auto_confirm_allowed"])

    def test_malformed_callbacks_and_fixture_drift_fail_closed(self):
        with self.assertRaises(ValueError):
            audit_published_intel_resize(lambda w, h, rgb: b"",
                                         model_id="intel_omz_0287")
        with self.assertRaises(ValueError):
            audit_published_intel_resize(
                lambda w, h, rgb: bytearray(PLANE * 3),
                model_id="intel_omz_0287")
        with self.assertRaises(ValueError):
            audit_published_intel_resize(
                lambda w, h, rgb: b"0" * (PLANE * 3),
                model_id="intel_omz_0286")
        with self.assertRaises(ValueError):
            audit_published_intel_resize(None, model_id="intel_omz_0288")
        original = artificial_rgb("gradient", 341, 173)
        with patch("audit_intel_resize_goldens.artificial_rgb",
                   return_value=original[:-1] + b"Z"):
            with self.assertRaisesRegex(ValueError, "drifted"):
                audit_published_intel_resize(
                    lambda w, h, rgb: bytes(PLANE * 3),
                    model_id="intel_omz_0287")

    @unittest.skipUnless(importlib.util.find_spec("PIL"), "optional Pillow unavailable")
    def test_cli_reports_only_synthetic_reference_and_error_on_opencv_mismatch(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(main(["--model-id", "intel_omz_0288",
                                   "--candidate", "pillow"]), 0)
        report = json.loads(out.getvalue())
        self.assertTrue(report["all_four_synthetic_probes_exact"])
        self.assertFalse(report["production_approved"])
        self.assertEqual(report["candidate"], "pillow")
        if importlib.util.find_spec("cv2") and importlib.util.find_spec("numpy"):
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(main(["--model-id", "intel_omz_0287",
                                       "--candidate", "opencv_linear"]), 2)
            self.assertFalse(json.loads(out.getvalue())[
                "all_four_synthetic_probes_exact"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
