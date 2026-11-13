"""Synthetic local image fixtures only; source and cache always read-only."""
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from PIL import Image

from tools.photo_lineage.benchmark import textured
from tools.photo_lineage.fingerprint_consistency_audit import audit_fingerprints
from tools.photo_lineage.retrieval import audit_cache, index_folder


class FingerprintConsistencyTests(unittest.TestCase):
    def test_same_length_and_restored_mtime_cannot_hide_changed_visual_content(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            source_dir = root / "synthetic"
            source_dir.mkdir()
            source = source_dir / "photo.bmp"
            Image.fromarray(textured(510, 256)).save(source)
            original = source.read_bytes()
            stamp = source.stat()
            database = root / "cache.sqlite"
            indexed = index_folder(source_dir, database, limit=1)
            self.assertEqual(indexed["new_or_updated"], 1)
            before = audit_fingerprints(database)
            self.assertEqual(before["verified_cached_fingerprints"], 1)
            self.assertEqual(before["fingerprint_mismatches"], 0)

            # Windows/BMP-style fixed-length content; simulate external
            # replacement, NOT an ATLAS source-modifying operation.
            other = root / "different.bmp"
            Image.fromarray(textured(511, 256)).save(other)
            self.assertEqual(other.stat().st_size, stamp.st_size)
            os.replace(other, source)
            os.utime(source, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
            self.assertNotEqual(source.read_bytes(), original)
            self.assertEqual(source.stat().st_size, stamp.st_size)
            self.assertEqual(source.stat().st_mtime_ns, stamp.st_mtime_ns)

            # Current cheap cache freshness check cannot detect this
            # size+mtime-preserving swap, but explicit ORB re-extraction does.
            self.assertEqual(audit_cache(database)["stale"], 0)
            result = audit_fingerprints(database)
            self.assertEqual(result["fingerprint_mismatches"], 1)
            self.assertEqual(result["verified_cached_fingerprints"], 0)
            self.assertEqual(result["metadata_stale_or_missing"], 0)
            self.assertTrue(result["page_fully_accounted_for"])
            self.assertFalse(result["source_or_cache_modified"])
            self.assertEqual(source.stat().st_size, stamp.st_size)
            self.assertNotIn(str(source_dir), json.dumps(result))
            self.assertEqual(audit_cache(database)["stale"], 0)

    def test_explicit_pagination_does_not_conflate_partial_scan_with_full_audit(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            images = root / "synthetic"
            images.mkdir()
            for i in range(3):
                Image.fromarray(textured(900 + i, 256)).save(images / f"{i}.png")
            db = root / "cache.sqlite"
            self.assertEqual(index_folder(images, db, limit=3)["total_cached"], 3)
            first = audit_fingerprints(db, page_size=2)
            self.assertEqual(first["rows_examined"], 2)
            self.assertTrue(first["has_more_after_this_page"])
            self.assertFalse(first["all_records_in_gallery_verified"])
            self.assertEqual(first["verified_cached_fingerprints"], 2)
            second = audit_fingerprints(db, after_id=first["next_after_id"], page_size=2)
            self.assertEqual(second["rows_examined"], 1)
            self.assertFalse(second["has_more_after_this_page"])
            self.assertEqual(second["verified_cached_fingerprints"], 1)
            self.assertGreater(second["next_after_id"], first["next_after_id"])

    def test_missing_or_symlinked_cached_paths_are_unknown_or_stale_not_valid(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            sources = root / "synthetic"
            sources.mkdir()
            for name, seed in (("a.png", 81), ("b.png", 82)):
                Image.fromarray(textured(seed, 256)).save(sources / name)
            db = root / "cache.sqlite"
            index_folder(sources, db, limit=2)
            (sources / "a.png").unlink()
            (sources / "b.png").rename(root / "moved.png")
            (sources / "b.png").symlink_to(root / "moved.png")
            result = audit_fingerprints(db, page_size=2)
            self.assertEqual(result["metadata_stale_or_missing"], 2)
            self.assertEqual(result["verified_cached_fingerprints"], 0)
            self.assertTrue(result["page_fully_accounted_for"])
            self.assertNotIn(str(root), json.dumps(result))

    def test_pagination_inputs_must_be_strictly_bounded_before_open(self):
        for wrong in (True, -1, None, 1.5, 2**63):
            with self.subTest(wrong=wrong), self.assertRaises(ValueError):
                audit_fingerprints("not-created.sqlite", after_id=wrong)
        for wrong in (True, 0, -2, 65, 1.5, None):
            with self.subTest(wrong=wrong), self.assertRaises(ValueError):
                audit_fingerprints("not-created.sqlite", page_size=wrong)


if __name__ == "__main__":
    unittest.main()
