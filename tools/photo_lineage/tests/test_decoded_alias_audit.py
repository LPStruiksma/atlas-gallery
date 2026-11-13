"""Synthetic photo-source collision fixtures; no external or private images."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from PIL import Image
import numpy as np

from tools.photo_lineage.decoded_alias_audit import audit_staged_sources, _decoded_pixel_sha256
from tools.photo_lineage.heldout_eval import evaluate


class DecodedAliasAuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.media = self.root / "images"
        self.media.mkdir()
        self.manifest = self.root / "curated.json"
        yy, xx = np.indices((128, 128))
        pixels = np.stack(((xx * 5 + yy * 3) % 256, (xx * 7 + yy * 11) % 256,
                           (xx * 13 + yy * 17) % 256), axis=-1).astype(np.uint8)
        self.pixels = pixels
        self.data = {"version": 2, "scenes": []}
        for scene, split in (("alpha", "development"), ("beta", "holdout")):
            captures = []
            for i in range(2):
                name = f"{scene}_{i}.png"
                variant = pixels.copy()
                variant[i*11 + (0 if scene == "alpha" else 40), 12] = [100+i, 30+i, 250-i]
                Image.fromarray(variant, "RGB").save(self.media / name)
                captures.append(self._entry(scene, i, name))
            self.data["scenes"].append({
                "id": scene, "scene_group": f"curated_{scene}", "split": split,
                "captures": captures,
            })
        self._save()

    def _entry(self, scene, i, name):
        return {
            "id": f"{scene}_exposure{i}",
            "file": name,
            "sha256": hashlib.sha256((self.media / name).read_bytes()).hexdigest(),
            "provenance": "independent_shutter",
            "provenance_note": "Completely fictional synthetically created camera exposure.",
            "author": "Synthetic fixture",
            "license": "CC0-1.0",
            "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
            "source_url": f"https://commons.wikimedia.org/wiki/File:synthetic_{scene}_{i}.png",
        }

    def _save(self):
        self.manifest.write_text(json.dumps(self.data))

    def test_different_source_images_pass_but_cannot_prove_independence(self):
        originals = {p.name: p.read_bytes() for p in self.media.iterdir()}
        report = audit_staged_sources(self.manifest, self.media)
        self.assertEqual(report["captures"], 4)
        self.assertTrue(report["no_exact_pixel_collisions_observed"])
        self.assertFalse(report["source_exposure_independence_proven"])
        self.assertEqual(originals, {p.name: p.read_bytes() for p in self.media.iterdir()})

    def test_png_bmp_lossless_recode_with_distinct_sha_and_url_detected(self):
        original = self.media / "alpha_0.png"
        new_name = "beta_0.bmp"
        Image.open(original).save(self.media / new_name)
        self.data["scenes"][1]["captures"][0] = self._entry("beta", 0, new_name)
        self._save()
        self.assertNotEqual(hashlib.sha256(original.read_bytes()).hexdigest(),
                            hashlib.sha256((self.media / new_name).read_bytes()).hexdigest())
        report = audit_staged_sources(self.manifest, self.media)
        self.assertEqual(report["decoded_pixel_collision_groups"], 1)
        self.assertEqual(report["captures_with_identical_decoded_pixels"], 2)
        self.assertNotIn(str(original), json.dumps(report))
        self.assertNotIn(new_name, json.dumps(report))
        with self.assertRaisesRegex(ValueError, "pixel aliases"):
            evaluate(self.manifest, self.media, split="holdout", pixel_audit=True)

    def test_exact_pixels_with_different_metadata_and_alpha_equivalence(self):
        a = self.media / "x.png"
        b = self.media / "y.bmp"
        rgba = Image.fromarray(self.pixels, "RGB")
        rgba.save(a, pnginfo=None)
        rgba.save(b)
        self.assertEqual(_decoded_pixel_sha256(a), _decoded_pixel_sha256(b))
        different = Image.fromarray(self.pixels.copy(), "RGB")
        changed = np.array(different)
        changed[6, 9, 0] ^= 1
        Image.fromarray(changed, "RGB").save(b)
        self.assertNotEqual(_decoded_pixel_sha256(a), _decoded_pixel_sha256(b))

    def test_lossy_jpeg_cannot_be_assumed_identical(self):
        source = self.media / "alpha_0.png"
        lossy = self.media / "q30.jpg"
        with Image.open(source) as image:
            image.save(lossy, quality=30)
        self.assertNotEqual(_decoded_pixel_sha256(source), _decoded_pixel_sha256(lossy))
        # This is NOT a negative proof; the lossy file IS derived from source.

    def test_corrupt_other_partition_fails_before_selected_split_scoring(self):
        other = self.media / "alpha_0.png"
        other.write_bytes(other.read_bytes() + b"altered")
        with self.assertRaisesRegex(ValueError, "integrity mismatch"):
            evaluate(self.manifest, self.media, split="holdout", pixel_audit=True)
        no_audit = evaluate(self.manifest, self.media, split="holdout")
        self.assertEqual(no_audit["all_splits_pixel_audit"], "not_run")

    def test_invalid_pixel_audit_argument_and_symlink_refused(self):
        with self.assertRaisesRegex(ValueError, "boolean"):
            evaluate(self.manifest, self.media, pixel_audit=1)
        path = self.media / "alpha_0.png"
        path.unlink()
        path.symlink_to(self.media / "alpha_1.png")
        with self.assertRaisesRegex(ValueError, "Symlink"):
            audit_staged_sources(self.manifest, self.media)

    def test_integrated_uncontaminated_preflight_and_privacy(self):
        report = evaluate(self.manifest, self.media, split="holdout", pixel_audit=True)
        self.assertEqual(report["all_splits_pixel_audit"], "passed_exact_only")
        self.assertTrue(report["unsafe_to_certify_exposure_identity"])
        self.assertNotIn(str(self.media), json.dumps(report))
        self.assertEqual(report["captures"], 2)


    def test_decoded_pixel_reader_rejects_symlinked_parent_and_leaf(self):
        target = self.media / "alpha_0.png"
        parent_alias = self.root / "alias-media"
        parent_alias.symlink_to(self.media, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "safely decoded"):
            _decoded_pixel_sha256(parent_alias / target.name)
        leaf_alias = self.media / "alias.png"
        leaf_alias.symlink_to(target)
        with self.assertRaisesRegex(ValueError, "safely decoded"):
            _decoded_pixel_sha256(leaf_alias)
        # A precheck that admits a real path does not authorize a later
        # symlink substitution before pixel decoding begins.
        self.assertIsInstance(_decoded_pixel_sha256(target), str)

    def test_in_place_write_during_decoding_fails_and_never_trusts_digest(self):
        source = self.media / "alpha_0.png"
        real_open = Image.open
        def mutation_after_open(stream, *args, **kwargs):
            image = real_open(stream, *args, **kwargs)
            # Disposable synthetic fixture only: append a byte to change
            # source metadata after the descriptor has been opened.
            with source.open("ab") as sink:
                sink.write(b"x")
            return image
        with mock.patch("tools.photo_lineage.decoded_alias_audit.Image.open",
                        side_effect=mutation_after_open):
            with self.assertRaisesRegex(ValueError, "changed during decoded-pixel"):
                _decoded_pixel_sha256(source)



if __name__ == "__main__":
    unittest.main()
