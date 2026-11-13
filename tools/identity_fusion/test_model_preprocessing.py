#!/usr/bin/env python3
"""Pure stdlib no-photo synthetic preprocessing contract safety regressions."""
from array import array
from dataclasses import FrozenInstanceError
import math
import unittest

from model_preprocessing import (
    CONTRACTS, HEIGHT, WIDTH, SHAPE,
    get_contract, synthetic_rgb_stripes, audited_nchw_tensor,
    synthetic_contract_tensor,
    INTEL_PUBLISHED_EVAL_SOURCE_COMMIT,
    synthetic_intel_0287_published_eval_tensor,
    synthetic_intel_0287_eval_sentinel_report,
)


class SyntheticPreprocessingContractTests(unittest.TestCase):
    def test_verified_contracts_fail_closed_when_numeric_scale_is_unknown(self):
        torch = get_contract("torchreid_osnet_eval")
        intel = get_contract("intel_omz_0287")
        self.assertEqual(torch.shape, (1, 3, 256, 128))
        self.assertEqual(intel.shape, torch.shape)
        self.assertEqual(torch.channel_order, "RGB")
        self.assertEqual(intel.channel_order, "BGR")
        self.assertTrue(torch.ready_for_synthetic_tensor)
        self.assertFalse(intel.ready_for_synthetic_tensor)
        self.assertFalse(torch.spatial_resize_parity_proven)
        self.assertFalse(intel.spatial_resize_parity_proven)
        with self.assertRaisesRegex(ValueError, "unresolved"):
            synthetic_contract_tensor("intel_omz_0287")
        with self.assertRaises(ValueError):
            get_contract("imaginary_model")
        with self.assertRaises(FrozenInstanceError):
            torch.pixel_scale = "raw_u8"

    def test_rgb_stripe_planes_are_normalized_in_nchw_not_bgr_order(self):
        probe = synthetic_rgb_stripes()
        self.assertEqual(len(probe), HEIGHT * WIDTH * 3)
        self.assertEqual(probe[:3], bytes((255, 0, 0)))
        self.assertEqual(probe[(WIDTH // 4) * 3:(WIDTH // 4) * 3 + 3], bytes((0, 255, 0)))
        self.assertEqual(probe[3 * WIDTH // 2:3 * WIDTH // 2 + 3], bytes((0, 0, 255)))
        flattened = audited_nchw_tensor("torchreid_osnet_eval", probe)
        self.assertIsInstance(flattened, array)
        self.assertEqual(flattened.typecode, "f")
        self.assertEqual(len(flattened), 3 * HEIGHT * WIDTH)
        plane = HEIGHT * WIDTH
        examples = [
            (0, (255, 0, 0)), (WIDTH // 4, (0, 255, 0)),
            (WIDTH // 2, (0, 0, 255)), (3 * WIDTH // 4, (127, 127, 127)),
        ]
        contract = get_contract("torchreid_osnet_eval")
        for pixel, rgb in examples:
            for ch, val in enumerate(rgb):
                target = (val / 255 - contract.mean[ch]) / contract.std[ch]
                self.assertAlmostEqual(flattened[ch * plane + pixel], target, places=5)
        self.assertEqual(flattened, synthetic_contract_tensor("torchreid_osnet_eval"))

    def test_published_intel_eval_bgr_raw_fp32_is_distinct_from_torchreid(self):
        """Evidence for an upstream *evaluation* step, not ONNX deployment."""
        self.assertEqual(INTEL_PUBLISHED_EVAL_SOURCE_COMMIT,
                         "af810de42bcf460a18610481f639dcdc86249e69")
        sample = synthetic_intel_0287_published_eval_tensor()
        self.assertIsInstance(sample, array)
        self.assertEqual(sample.typecode, "f")
        plane = HEIGHT * WIDTH
        self.assertEqual(len(sample), 3 * plane)
        # Artificial RGB red should yield B,G,R = 0,0,255 in NCHW.
        cases = (
            (0, (0.0, 0.0, 255.0)),
            (WIDTH // 4, (0.0, 255.0, 0.0)),
            (WIDTH // 2, (255.0, 0.0, 0.0)),
            (3 * WIDTH // 4, (127.0, 127.0, 127.0)),
        )
        for pixel, bgr in cases:
            with self.subTest(pixel=pixel):
                self.assertEqual(tuple(sample[ch * plane + pixel]
                                       for ch in range(3)), bgr)
        self.assertEqual(sample, synthetic_intel_0287_published_eval_tensor())
        torch = synthetic_contract_tensor("torchreid_osnet_eval")
        self.assertNotEqual(sample[2 * plane + 0], torch[2 * plane + 0])
        self.assertNotEqual(sample[WIDTH // 2], torch[WIDTH // 2])
        self.assertFalse(get_contract("intel_omz_0287").ready_for_synthetic_tensor)

    def test_published_intel_eval_report_is_public_sentinel_not_deployment_gate(self):
        import json
        data = synthetic_intel_0287_eval_sentinel_report()
        self.assertEqual(data["status"],
                         "published_openvino_market1501_evaluation_step_only")
        self.assertEqual(data["shape_nchw"], [1, 3, 256, 128])
        self.assertEqual(data["artificial_red_rgb_pixel_after_bgr_cast"],
                         [0.0, 0.0, 255.0])
        self.assertEqual(data["artificial_blue_rgb_pixel_after_bgr_cast"],
                         [255.0, 0.0, 0.0])
        self.assertTrue(data["evaluation_step_uses_raw_0_to_255_float32"])
        for flag in (
            "original_onnx_graph_input_range_verified",
            "published_evaluation_resize_byte_parity_tested",
            "native_android_preprocessing_equivalent",
            "model_rights_or_identity_accuracy_verified",
            "actual_model_weight_bytes_or_execution_verified",
            "production_approved", "auto_confirm_allowed",
        ):
            self.assertIs(data[flag], False, flag)
        serialized = json.dumps(data)
        for prohibited in ("/sdcard", "subject_uid", "filename", "embedding"):
            self.assertNotIn(prohibited, serialized)

    def test_nonstandard_shapes_or_malformed_bytes_rejected(self):
        for width, height in ((128, 255), (127, 256), (True, 256), (128, "256")):
            with self.subTest(width=width, height=height):
                with self.assertRaises(ValueError):
                    synthetic_rgb_stripes(width=width, height=height)
        probe = synthetic_rgb_stripes()
        for item in (probe[:-1], probe + b"x", bytearray(probe), None, b""):
            with self.subTest(length=len(item) if item else None):
                with self.assertRaises(ValueError):
                    audited_nchw_tensor("torchreid_osnet_eval", item)

    def test_provenance_and_artificial_only_contract_remain_explicit(self):
        self.assertEqual(set(CONTRACTS), {"intel_omz_0287", "torchreid_osnet_eval"})
        for c in CONTRACTS.values():
            self.assertTrue(c.source.startswith("https://github.com/"))
            self.assertFalse(c.spatial_resize_parity_proven)
            self.assertEqual(c.shape, SHAPE)
        with self.assertRaises(ValueError):
            get_contract(None)


if __name__ == "__main__":
    unittest.main(verbosity=2)
