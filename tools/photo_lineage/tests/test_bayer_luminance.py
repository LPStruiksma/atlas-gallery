"""Entirely fictional Bayer sensor matrices; no RAW decoder/photo downloads."""
import json
import unittest

import numpy as np

from tools.photo_lineage.bayer_luminance import (
    audit_grayscale_byte_aliases, bayer_to_grayscale,
)


class BayerLuminanceTests(unittest.TestCase):
    def frame(self, pattern="RGGB", size=256):
        raw = np.zeros((size, size), dtype=np.uint16)
        for index, color in enumerate(pattern):
            raw[index // 2::2, index % 2::2] = {"R": 1400, "G": 2100, "B": 900}[color]
        return raw

    def test_four_bayer_layouts_equivalent_under_constant_rgb_planes(self):
        results = []
        for pattern in ("RGGB", "BGGR", "GRBG", "GBRG"):
            raw = self.frame(pattern=pattern)
            before = raw.copy()
            gray = bayer_to_grayscale(raw, pattern=pattern,
                                      black_levels=(100, 100, 100, 100),
                                      white_level=4000)
            self.assertEqual(gray.dtype, np.uint8)
            self.assertEqual(gray.shape, (128, 128))
            self.assertTrue(np.array_equal(raw, before))
            results.append(gray)
        for item in results[1:]:
            self.assertTrue(np.array_equal(item, results[0]))

    def test_position_specific_black_levels_normalize_equal_signal(self):
        raw = np.zeros((256, 256), dtype=np.uint16)
        blacks = (100, 200, 300, 400)
        for i, b in enumerate(blacks):
            raw[i // 2::2, i % 2::2] = b + 1000
        gray = bayer_to_grayscale(raw, pattern="RGGB", black_levels=blacks,
                                  white_level=2100)
        self.assertGreater(gray.mean(), 0)
        self.assertLess(gray.mean(), 255)

    def test_decimation_is_area_averaged_not_nearest_cfa_sample(self):
        raw = np.zeros((512, 512), dtype=np.uint16)
        raw[:, :256] = 4095
        expected = bayer_to_grayscale(raw, pattern="RGGB",
                                      black_levels=(0, 0, 0, 0),
                                      white_level=4095, max_side=128)
        self.assertEqual(expected.shape, (128, 128))
        self.assertGreater(float(expected[:, :64].mean()), 250)
        self.assertLess(float(expected[:, 64:].mean()), 5)

    def test_abstains_on_unknown_cfa_missing_calibration_and_memory_limits(self):
        raw = self.frame()
        base = {"pattern": "RGGB", "black_levels": (0, 0, 0, 0),
                "white_level": 4095}
        variants = (
            {"pattern": "RGBG"}, {"pattern": "XTRANS"},
            {"black_levels": [0, 0, 0, 0]},
            {"black_levels": (0, 0, 0, 4095)},
            {"white_level": True}, {"max_side": 10000},
        )
        for kwargs in variants:
            with self.subTest(kwargs=kwargs):
                with self.assertRaises(ValueError):
                    bayer_to_grayscale(raw, **(base | kwargs))
        for illegal in (raw.astype(np.uint8), raw[None],
                        np.zeros((20, 20), np.uint16),
                        np.zeros((8193, 8193), np.uint16)):
            with self.assertRaises(ValueError):
                bayer_to_grayscale(illegal, **base)

    def test_alias_audit_fails_closed_on_equal_normalized_pixels(self):
        a = bayer_to_grayscale(self.frame("RGGB"), pattern="RGGB",
                               black_levels=(0, 0, 0, 0), white_level=4095)
        b = bayer_to_grayscale(self.frame("BGGR"), pattern="BGGR",
                               black_levels=(0, 0, 0, 0), white_level=4095)
        with self.assertRaisesRegex(ValueError, "alias"):
            audit_grayscale_byte_aliases([a, b])
        diff = a.copy()
        diff[0, 0] = (int(diff[0, 0]) + 1) % 256
        result = audit_grayscale_byte_aliases(iter((a, diff)))
        self.assertEqual(result["preview_count"], 2)
        self.assertFalse(result["physical_shutter_independence_verified"])
        self.assertNotIn("hash", json.dumps(result))
        for invalid in ([a], [a] * 11, [a, a.astype(np.uint16)],
                        [a, a[:, ::2]]):
            with self.assertRaises(ValueError):
                audit_grayscale_byte_aliases(invalid)


if __name__ == "__main__":
    unittest.main()
