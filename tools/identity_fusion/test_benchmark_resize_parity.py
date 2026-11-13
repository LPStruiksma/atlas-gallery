#!/usr/bin/env python3
"""Pure stdlib regression; actual Pillow/OpenCV run is optional locally."""
import json
import unittest

from benchmark_resize_parity import (
    TARGET, PROBES, artificial_rgb, compare_bytes, audit_resize_pair
)


class ResizeParitySyntheticTests(unittest.TestCase):
    def test_synthetic_patterns_are_deterministic_and_have_no_input_files(self):
        for pattern, w, h in PROBES:
            with self.subTest(pattern=pattern):
                sample = artificial_rgb(pattern, w, h)
                self.assertIs(type(sample), bytes)
                self.assertEqual(len(sample), w * h * 3)
                self.assertEqual(sample, artificial_rgb(pattern, w, h))
        with self.assertRaises(ValueError):
            artificial_rgb("private-photo.jpg", 128, 256)
        for w, h in ((0, 10), (513, 100), (True, 256), ("128", 256)):
            with self.assertRaises(ValueError):
                artificial_rgb("gradient", w, h)

    def test_exact_parity_vs_color_order_swap_is_not_conflated(self):
        n = TARGET[0] * TARGET[1]
        rgb = bytes((255, 0, 0)) * n
        bgr = bytes((0, 0, 255)) * n
        exact = compare_bytes(rgb, rgb, pixel_count=n)
        self.assertTrue(exact["exact_byte_parity"])
        self.assertEqual(exact["mean_abs_channel_delta"], 0.0)
        self.assertEqual(exact["p95_abs_channel_delta"], 0)
        wrong = compare_bytes(rgb, bgr, pixel_count=n)
        self.assertFalse(wrong["exact_byte_parity"])
        self.assertEqual(wrong["mean_abs_channel_delta"], 170.0)
        self.assertEqual(wrong["max_abs_channel_delta"], 255)
        self.assertEqual(wrong["different_channel_fraction"], round(2 / 3, 6))

    def test_injected_deterministic_resizers_report_only_aggregates(self):
        n = TARGET[0] * TARGET[1]
        def same(w, h, source):
            return bytes((10, 20, 30)) * n
        def changed(w, h, source):
            return bytes((11, 20, 30)) * n
        report = audit_resize_pair(same, same)
        self.assertEqual(len(report["reports"]), len(PROBES))
        self.assertTrue(all(x["exact_byte_parity"] for x in report["reports"]))
        drift = audit_resize_pair(same, changed, probes=(("gradient", 341, 173),))
        stats = drift["reports"][0]
        self.assertEqual(stats["mean_abs_channel_delta"], round(1 / 3, 4))
        self.assertEqual(stats["p95_abs_channel_delta"], 1)
        self.assertFalse(stats["exact_byte_parity"])
        self.assertTrue(drift["no_real_media_processed"])
        self.assertTrue(drift["no_model_accuracy_claim"])
        output = json.dumps(drift)
        self.assertNotIn("source_bytes", output)
        self.assertNotIn("embedding", output)

    def test_reject_bad_pixel_buffers_or_unbounded_geometry(self):
        n = TARGET[0] * TARGET[1]
        rgb = bytes((5, 7, 9)) * n
        for candidate in (rgb[:-1], rgb + b"x", bytearray(rgb), None):
            with self.subTest(length=len(candidate) if candidate else None):
                with self.assertRaises(ValueError):
                    compare_bytes(rgb, candidate, pixel_count=n)
        for count in (0, -1, True, 1.5):
            with self.assertRaises(ValueError):
                compare_bytes(rgb, rgb, pixel_count=count)
        with self.assertRaises(ValueError):
            audit_resize_pair(lambda w,h,s: rgb[:-1], lambda w,h,s: rgb)
        with self.assertRaises(ValueError):
            audit_resize_pair(None, lambda w,h,s: rgb)
        with self.assertRaises(ValueError):
            audit_resize_pair(lambda w,h,s: rgb, lambda w,h,s: rgb, probes=[])


if __name__ == "__main__":
    unittest.main(verbosity=2)
