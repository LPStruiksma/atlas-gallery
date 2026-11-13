"""Synthetic, no-media tests. Run 'python -m unittest discover -s tools/photo_lineage/tests'."""
import tempfile
import unittest
from unittest.mock import patch
import os
import errno
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from tools.photo_lineage.benchmark import cases, textured
from tools.photo_lineage.matcher import cardinal_hash_distance, compare_arrays, compare_files, read_grayscale


class PhotoDnaTest(unittest.TestCase):
    def test_crops_rotation_edits(self):
        cases_by_name = {name: (a, b) for name, a, b, _ in cases()}
        for name in ("resized", "crop_68pct", "arbitrary_rotation_and_crop", "brightness_gamma_edit"):
            with self.subTest(name=name):
                result = compare_arrays(*cases_by_name[name])
                self.assertIn(result.verdict, ("candidate", "review"), result.json())
                self.assertGreaterEqual(result.inliers, 10)

    def test_unrelated_and_shared_text_dont_autoconfirm(self):
        for name, a, b, expected in cases():
            if not expected:
                with self.subTest(name=name):
                    result = compare_arrays(a, b)
                    self.assertNotEqual(result.verdict, "candidate", result.json())

    def test_blank_abstention(self):
        gray = np.full((300, 300), 180, np.uint8)
        self.assertEqual(compare_arrays(gray, gray).verdict, "unknown")

    def test_hash_rotation_not_confirmation(self):
        gray = textured(5, 256)
        self.assertEqual(cardinal_hash_distance(gray, np.rot90(gray)), 0)
        self.assertEqual(compare_arrays(np.full((300, 300), 10, np.uint8),
                                        np.full((300, 300), 10, np.uint8)).verdict, "unknown")

    def test_original_unchanged_and_exif_aware(self):
        with tempfile.TemporaryDirectory() as directory:
            file_a = Path(directory) / "a.png"
            file_b = Path(directory) / "b.png"
            pixels = textured(9, 250)
            Image.fromarray(pixels).save(file_a)
            Image.fromarray(cv2.resize(pixels, (170, 170))).save(file_b)
            before_a, before_b = file_a.read_bytes(), file_b.read_bytes()
            report = compare_files(file_a, file_b)
            self.assertIn(report.verdict, ("candidate", "review"))
            self.assertEqual(before_a, file_a.read_bytes())
            self.assertEqual(before_b, file_b.read_bytes())
            self.assertEqual(read_grayscale(file_a).shape, pixels.shape)

    def test_changed_foreground_is_not_strong_same_exposure(self):
        base = textured(509)
        other_exposure = base.copy()
        cv2.rectangle(other_exposure, (190, 190), (550, 550), 210, -1)
        result = compare_arrays(base, other_exposure)
        self.assertNotEqual(result.verdict, "candidate", result.json())
        self.assertLess(result.local_consistency, 0.88)

    def test_colorized_rgb_jpeg_enhancement_and_tight_crop(self):
        source = textured(31)
        smooth = cv2.GaussianBlur(source, (0, 0), 2.2)
        sharpened = cv2.addWeighted(source, 1.65, smooth, -0.65, 10)
        with tempfile.TemporaryDirectory() as folder:
            original = Path(folder) / "original.png"
            colorized = Path(folder) / "colorized.png"
            enhanced = Path(folder) / "enhanced.jpg"
            Image.fromarray(source).save(original)
            red = np.clip(0.95 * source.astype(np.float32) + 25, 0, 255)
            green = np.clip(0.68 * np.sqrt(source.astype(np.float32) / 255) * 255 + 10, 0, 255)
            blue = np.clip(0.43 * source.astype(np.float32) + 45, 0, 255)
            Image.fromarray(np.stack((red, green, blue), axis=2).astype(np.uint8)).save(colorized)
            Image.fromarray(sharpened).save(enhanced, quality=45)
            for variant in (colorized, enhanced):
                self.assertEqual(compare_files(original, variant).verdict, "candidate")
        self.assertIn(compare_arrays(source, source[275:465, 280:470]).verdict,
                      ("candidate", "review"))

    def test_arbitrary_rotation_sweep(self):
        from tools.photo_lineage.benchmark import rotate
        source = textured(129, 640)
        for angle in (-157, -63, 49, 143):
            with self.subTest(angle=angle):
                cropped = rotate(source, angle)[75:570, 100:550]
                self.assertIn(compare_arrays(source, cropped).verdict,
                              ("candidate", "review"))

    def test_direct_matcher_rejects_unbounded_arrays_before_opencv(self):
        small = np.full((128, 128), 100, dtype=np.uint8)
        huge_view = np.broadcast_to(np.zeros((1, 1), dtype=np.uint8), (4097, 96))
        invalids = (
            huge_view,
            np.zeros((32, 300), dtype=np.uint8),
            np.zeros((300, 32), dtype=np.uint8),
            np.zeros((120, 120), dtype=np.uint16),
            np.zeros((120, 120, 3), dtype=np.uint8),
            np.ma.array(small),
            "not pixels",
            None,
        )
        with patch("tools.photo_lineage.matcher.cardinal_hash_distance",
                   side_effect=AssertionError("feature computation started")) as called:
            for invalid in invalids:
                with self.subTest(kind=type(invalid).__name__):
                    with self.assertRaises(ValueError):
                        compare_arrays(invalid, small)
                    with self.assertRaises(ValueError):
                        compare_arrays(small, invalid)
            called.assert_not_called()

    def test_bounded_negative_stride_cardinal_rotation_still_supported(self):
        source = textured(31, 256)
        rotated_view = np.rot90(source)
        self.assertFalse(rotated_view.flags.c_contiguous)
        before = source.copy()
        result = compare_arrays(source, rotated_view, algorithm="orb")
        self.assertIn(result.verdict, ("candidate", "review"))
        self.assertTrue(np.array_equal(source, before))

    def test_symlink_swap_after_precheck_is_refused_at_descriptor_open(self):
        """Artificially swap a fictional path at the native open boundary."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "visible.png"
            other = root / "other-synthetic.png"
            Image.fromarray(textured(991, 256)).save(source)
            Image.fromarray(textured(992, 256)).save(other)
            ordinary_open = os.open
            attempted = []

            def swap_then_open(path, flags, *args, **kwargs):
                if path == source.name and kwargs.get("dir_fd") is not None:
                    attempted.append(flags)
                    source.unlink()
                    source.symlink_to(other)
                return ordinary_open(path, flags, *args, **kwargs)

            with patch("tools.photo_lineage.matcher.os.open",
                       side_effect=swap_then_open), \
                    patch("tools.photo_lineage.matcher.Image.open",
                          side_effect=AssertionError("Symlink destination decoded")) as pillow:
                with self.assertRaisesRegex(ValueError, "unsafe image"):
                    read_grayscale(source)
                pillow.assert_not_called()
            self.assertEqual(len(attempted), 1)
            self.assertTrue(attempted[0] & os.O_NOFOLLOW)

    def test_fd_open_is_anchored_if_path_replaced_during_decode(self):
        """The original opened handle, not replacement pathname, feeds Pillow."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "visible.png"
            substitute = root / "replacement.png"
            before = textured(777, 256)
            after = textured(778, 256)
            Image.fromarray(before).save(source)
            Image.fromarray(after).save(substitute)
            genuine_open = Image.open
            replaced = []

            def replace_after_opened_stream(file, *args, **kwargs):
                self.assertFalse(isinstance(file, (str, Path)))
                # The file descriptor is already open. Its inode remains
                # available even after the visible path is atomically replaced.
                if not replaced:
                    os.replace(substitute, source)
                    replaced.append(True)
                return genuine_open(file, *args, **kwargs)

            with patch("tools.photo_lineage.matcher.Image.open",
                       side_effect=replace_after_opened_stream):
                decoded = read_grayscale(source)
            self.assertTrue(np.array_equal(decoded, before))
            self.assertFalse(np.array_equal(decoded, after))
            self.assertTrue(np.array_equal(read_grayscale(source), after))

    def test_parent_directory_symlink_refused_before_pillow(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            actual = root / "actual"
            actual.mkdir()
            Image.fromarray(textured(971, 256)).save(actual / "photo.png")
            alias = root / "symlinked-parent"
            alias.symlink_to(actual, target_is_directory=True)
            with patch("tools.photo_lineage.matcher.Image.open",
                       side_effect=AssertionError("Symlinked parent decoded")) as opener:
                with self.assertRaisesRegex(ValueError, "unsafe image"):
                    read_grayscale(alias / "photo.png")
                opener.assert_not_called()

    def test_parent_symlink_swap_at_directory_open_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scene = root / "scene"
            other = root / "other"
            scene.mkdir()
            other.mkdir()
            Image.fromarray(textured(972, 256)).save(scene / "photo.png")
            Image.fromarray(textured(973, 256)).save(other / "photo.png")
            genuine_open = os.open
            swapped = []

            def swap_at_parent_open(component, flags, *args, **kwargs):
                if component == "scene" and not swapped:
                    scene.rename(root / "scene-original")
                    scene.symlink_to(other, target_is_directory=True)
                    swapped.append(True)
                return genuine_open(component, flags, *args, **kwargs)

            with patch("tools.photo_lineage.matcher.os.open",
                       side_effect=swap_at_parent_open), \
                    patch("tools.photo_lineage.matcher.Image.open",
                          side_effect=AssertionError("Other source decoded")) as opener:
                with self.assertRaisesRegex(ValueError, "unsafe image"):
                    read_grayscale(scene / "photo.png")
                opener.assert_not_called()
            self.assertEqual(len(swapped), 1)

    def test_parent_descriptor_survives_atomic_swap_after_directory_open(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scene = root / "scene"
            other = root / "other"
            scene.mkdir()
            other.mkdir()
            original = textured(974, 256)
            replacement = textured(975, 256)
            Image.fromarray(original).save(scene / "photo.png")
            Image.fromarray(replacement).save(other / "photo.png")
            genuine_open = os.open
            swapped = []

            def swap_before_leaf_open(component, flags, *args, **kwargs):
                if component == "photo.png" and kwargs.get("dir_fd") is not None and not swapped:
                    scene.rename(root / "scene-original")
                    scene.symlink_to(other, target_is_directory=True)
                    swapped.append(True)
                return genuine_open(component, flags, *args, **kwargs)

            with patch("tools.photo_lineage.matcher.os.open",
                       side_effect=swap_before_leaf_open):
                decoded = read_grayscale(scene / "photo.png")
            self.assertEqual(len(swapped), 1)
            self.assertTrue(np.array_equal(decoded, original))
            self.assertFalse(np.array_equal(decoded, replacement))
            with self.assertRaises(ValueError):
                read_grayscale(scene / "photo.png")

    def test_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            img = Path(directory) / "a.png"
            Image.fromarray(textured(1, 256)).save(img)
            link = Path(directory) / "link.png"
            link.symlink_to(img)
            with self.assertRaises(ValueError):
                read_grayscale(link)


if __name__ == "__main__":
    unittest.main()
