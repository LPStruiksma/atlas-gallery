"""No-download public-sample ingestion tests using ONLY generated fixture images.

Do not rely on scikit-image being installed in the repository CI.
"""
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import cv2
import numpy as np
from PIL import Image

from tools.photo_lineage import public_domain_smoke as smoke
from tools.photo_lineage.benchmark import textured


class PublicDomainSmokeTests(unittest.TestCase):
    def test_known_photo_names_have_independently_pinned_lowercase_digests(self):
        self.assertEqual(set(smoke.PINNED_PHOTOS),
                         {"astronaut", "camera", "chelsea", "clock", "coffee"})
        self.assertTrue(all(len(item) == 3 for item in smoke.PINNED_PHOTOS.values()))
        self.assertTrue(all(len(item[1]) == 64 for item in smoke.PINNED_PHOTOS.values()))

    def test_byte_pin_and_decoding_refuse_corruption_and_symlinks(self):
        with TemporaryDirectory() as scratch:
            root = Path(scratch)
            file = root / "fictional.png"
            Image.fromarray(textured(9, size=256)).save(file)
            before = file.read_bytes()
            sha = hashlib.sha256(before).hexdigest()
            pixels = smoke._read_pinned_photo(file, sha)
            self.assertEqual(pixels.shape, (256, 256))
            self.assertTrue(np.array_equal(pixels, textured(9, size=256)))
            self.assertEqual(file.read_bytes(), before)
            link = root / "link.png"
            link.symlink_to(file)
            with self.assertRaisesRegex(ValueError, "symlinked"):
                smoke._read_pinned_photo(link, sha)
            with self.assertRaisesRegex(ValueError, "byte mismatch"):
                smoke._read_pinned_photo(file, "0" * 64)
            with self.assertRaisesRegex(ValueError, "SHA256"):
                smoke._read_pinned_photo(file, "wrong")
            file.write_bytes(before + b"\0")
            with self.assertRaisesRegex(ValueError, "byte mismatch"):
                smoke._read_pinned_photo(file, sha)

    def test_jpeg_without_png_format_refused_even_if_digest_matches(self):
        with TemporaryDirectory() as scratch:
            path = Path(scratch) / "false.png"
            cv2.imwrite(str(path), textured(13, size=256),
                        [cv2.IMWRITE_JPEG_QUALITY, 90])
            # cv2 codec is extension-driven, so explicitly write JPEG bytes.
            success, jpeg = cv2.imencode(".jpg", textured(13, size=256))
            self.assertTrue(success)
            path.write_bytes(jpeg.tobytes())
            with self.assertRaisesRegex(ValueError, "format"):
                smoke._read_pinned_photo(path, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_runner_with_generated_photos_never_makes_real_accuracy_claim(self):
        with TemporaryDirectory() as scratch:
            root = Path(scratch)
            records = {}
            before = {}
            for label, seed in (("camera", 301), ("chelsea", 602)):
                filename = label + ".png"
                path = root / filename
                Image.fromarray(textured(seed, size=300)).save(path)
                before[label] = path.read_bytes()
                records[label] = (filename, hashlib.sha256(before[label]).hexdigest(),
                                  "Fictional test image / no photographic license assertion")
            with patch.object(smoke, "PINNED_PHOTOS", records), patch.object(
                    smoke, "_bundled_root", return_value=root):
                outcome = smoke.run(algorithm="orb", sample_names=("camera", "chelsea"))
            self.assertEqual(outcome["sample_count"], 2)
            self.assertEqual(outcome["derived_edit_total"], 10)
            self.assertEqual(outcome["unrelated_different_scene_pair_count"], 1)
            self.assertEqual(sum(outcome["unrelated_different_scene_verdicts"].values()), 1)
            for row in outcome["edit_verdicts"].values():
                self.assertEqual(sum(row.values()), 2)
            self.assertEqual(outcome["genuine_same_scene_independent_shutters_tested"], 0)
            self.assertFalse(outcome["real_camera_same_scene_accuracy_proven"])
            self.assertFalse(outcome["device_performance_measured"])
            self.assertFalse(outcome["originals_or_derivatives_exported"])
            self.assertNotIn(str(root), json.dumps(outcome))
            for label in before:
                self.assertEqual((root / (label + ".png")).read_bytes(), before[label])

    def test_invalid_algorithm_names_and_selection_boundaries_refused(self):
        for kw in (
            {"algorithm": "unverified"},
            {"sample_names": ()}, {"sample_names": ("camera",)},
            {"sample_names": ("camera", "camera")},
            {"sample_names": ["camera", "chelsea"]},
            {"sample_names": ("camera", "made_up")},
            {"sample_names": ("camera", True)},
        ):
            with self.subTest(kw=kw), self.assertRaises(ValueError):
                smoke.run(**kw)


if __name__ == "__main__":
    unittest.main()
