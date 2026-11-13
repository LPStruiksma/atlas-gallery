"""Decoder-shaped fakes only; no LibRaw, actual raw files or image reads."""
from types import SimpleNamespace
import unittest

import numpy as np

from tools.photo_lineage.rawpy_metadata_bridge import visible_bayer_calibration


class FakeRaw:
    def __init__(self, top=0, left=0):
        self.sizes = SimpleNamespace(top_margin=top, left_margin=left, width=256, height=256)
        self.raw_pattern = np.array([[0, 1], [3, 2]], dtype=np.uint8)
        self.color_desc = b"RGBG"
        self.black_level_per_channel = [100, 200, 300, 400]
        self.white_level = 4095
        self.camera_white_level_per_channel = None
        self.calls = []

    def raw_color(self, row, col):
        self.calls.append((row, col))
        return int(self.raw_pattern[row % 2, col % 2])

    @property
    def raw_image_visible(self):
        raise AssertionError("sensor pixel payload must not be touched")


class BridgeTests(unittest.TestCase):
    def test_visible_origin_uses_full_raw_margin_parity_without_pixel_access(self):
        expectations = {
            (0, 0): ("RGGB", (100, 200, 400, 300)),
            (1, 0): ("GBRG", (400, 300, 100, 200)),
            (0, 1): ("GRBG", (200, 100, 300, 400)),
            (1, 1): ("BGGR", (300, 400, 200, 100)),
        }
        for (top, left), (pattern, black) in expectations.items():
            with self.subTest(margins=(top, left)):
                decoder = FakeRaw(top, left)
                result = visible_bayer_calibration(decoder)
                self.assertEqual(result, {"pattern": pattern,
                                          "black_levels": black,
                                          "white_level": 4095})
                self.assertEqual(len(decoder.calls), 4)

    def test_rejects_non_bayer_color_and_unsupported_cfa(self):
        wrong = FakeRaw()
        wrong.color_desc = b"RGBE"
        with self.assertRaises(ValueError):
            visible_bayer_calibration(wrong)
        wrong = FakeRaw()
        wrong.raw_pattern = np.zeros((6, 6), dtype=np.uint8)
        with self.assertRaises(ValueError):
            visible_bayer_calibration(wrong)
        wrong = FakeRaw()
        wrong.raw_pattern = None
        with self.assertRaises(ValueError):
            visible_bayer_calibration(wrong)

    def test_rejects_mismatched_phase_and_unsupported_levels(self):
        raw = FakeRaw()
        raw.raw_color = lambda row, col: 0
        with self.assertRaises(ValueError):
            visible_bayer_calibration(raw)
        raw = FakeRaw()
        raw.camera_white_level_per_channel = [4095, 4095, 4095, 3999]
        with self.assertRaises(ValueError):
            visible_bayer_calibration(raw)
        raw = FakeRaw()
        raw.black_level_per_channel[1] = 4095
        with self.assertRaises(ValueError):
            visible_bayer_calibration(raw)

    def test_bounded_visible_dimensions_fail_closed(self):
        for width, height in ((0, 256), (95, 256), (256, 95), (12001, 256),
                              (8192, 8192), (True, 256)):
            with self.subTest(width=width, height=height):
                raw = FakeRaw()
                raw.sizes.width = width
                raw.sizes.height = height
                with self.assertRaises(ValueError):
                    visible_bayer_calibration(raw)

    def test_missing_metadata_and_invalid_offsets_fail_closed(self):
        for top, left in ((-1, 0), (0, 100000), (True, 0)):
            with self.subTest(top=top, left=left):
                with self.assertRaises(ValueError):
                    visible_bayer_calibration(FakeRaw(top, left))
        with self.assertRaises(ValueError):
            visible_bayer_calibration(SimpleNamespace())


if __name__ == "__main__":
    unittest.main()
