#!/usr/bin/env python3
"""Only source-pinned synthetic Pillow 12.3.0 geometry, no actual images."""
import hashlib
import importlib.util
import unittest

from audit_intel_resize_goldens import REFERENCE_SHA256
from benchmark_resize_parity import PROBES, TARGET, artificial_rgb
from synthetic_pillow_bilinear_reference import synthetic_pillow_bilinear


class StdlibPillowSyntheticGoldenTests(unittest.TestCase):
    def test_four_stdlib_synthetic_reference_hashes_equal_existing_pins(self):
        for name, w, h in PROBES:
            with self.subTest(name=name):
                result = synthetic_pillow_bilinear(name, w, h)
                self.assertEqual(len(result), TARGET[0] * TARGET[1] * 3)
                self.assertEqual(hashlib.sha256(result).hexdigest(),
                                 REFERENCE_SHA256[name]["resized_rgb"])

    def test_arbitrary_data_and_fake_geometry_cannot_be_used_as_input(self):
        for args in (("gradient", 341, 174), ("checker", 341, 173),
                     ("colorbars", True, 256), ("impulse", 65, 82),
                     ("secret.jpg", 65, 83), ("gradient", 10**10, 500),
                     (None, 341, 173)):
            with self.subTest(args=args), self.assertRaises(ValueError):
                synthetic_pillow_bilinear(*args)

    @unittest.skipUnless(importlib.util.find_spec("PIL"),
                         "Optional Pillow comparison unavailable")
    def test_independent_pillow_algorithm_matches_stdlib_on_four_probes(self):
        from PIL import Image
        for name, w, h in PROBES:
            pixels = artificial_rgb(name, w, h)
            expected = Image.frombytes("RGB", (w, h), pixels).resize(
                TARGET, Image.Resampling.BILINEAR).tobytes()
            self.assertEqual(synthetic_pillow_bilinear(name, w, h), expected)


if __name__ == "__main__":
    unittest.main(verbosity=2)
