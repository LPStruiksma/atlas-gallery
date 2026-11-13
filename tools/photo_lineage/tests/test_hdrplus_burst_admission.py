"""Fabricated byte fixtures ONLY. No real DNGs or downloads."""
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
from tools.photo_lineage import hdrplus_burst_admission as admission
from tools.photo_lineage.hdrplus_burst_admission import inspect_burst


class HDRPlusAdmissionTests(unittest.TestCase):
    def frames(self, root: Path, *indices: int):
        for index in indices:
            (root / f"payload_N{index:03d}.dng").write_bytes((b"synthetic" + bytes([index])) * 200)

    def test_two_frames_metadata_only_and_media_unchanged(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            self.frames(root, 0, 1)
            (root / "rgb2rgb.txt").write_text("fictional")
            before = {p.name: p.read_bytes() for p in root.iterdir()}
            result = inspect_burst(root)
            self.assertEqual(result["reported_frame_count"], 2)
            self.assertEqual(result["sum_of_frame_stat_bytes"], 4000)
            self.assertFalse(result["physical_shutter_independence_verified"])
            self.assertFalse(result["dataset_origin_and_license_verified"])
            self.assertFalse(result["data_bytes_read"])
            self.assertNotIn(td, json.dumps(result))
            self.assertEqual(before, {p.name: p.read_bytes() for p in root.iterdir()})

    def test_rejects_missing_indices_and_fake_aliases(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            self.frames(root, 0, 2)
            with self.assertRaisesRegex(ValueError, "indices"):
                inspect_burst(root)
            (root / "payload_N002.dng").rename(root / "payload_N1.dng")
            self.assertEqual(inspect_burst(root)["reported_frame_count"], 2)
            self.frames(root, 1)
            with self.assertRaisesRegex(ValueError, "indices"):
                inspect_burst(root)

    def test_rejects_symlinks_hardlinks_and_bad_names(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            self.frames(root, 0, 1)
            (root / "misc.dng").write_bytes(b"x")
            with self.assertRaisesRegex(ValueError, "Unrecognized"):
                inspect_burst(root)
            (root / "misc.dng").unlink()
            (root / "marker").symlink_to("payload_N000.dng")
            with self.assertRaisesRegex(ValueError, "Symlink"):
                inspect_burst(root)
            (root / "marker").unlink()
            (root / "payload_N001.dng").unlink()
            os.link(root / "payload_N000.dng", root / "payload_N001.dng")
            with self.assertRaisesRegex(ValueError, "inode"):
                inspect_burst(root)

    def test_rejects_extra_frames_empty_frames_and_excess_entries(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            self.frames(root, *range(11))
            with self.assertRaisesRegex(ValueError, "Too many"):
                inspect_burst(root)
        with TemporaryDirectory() as td:
            root = Path(td)
            self.frames(root, 0, 1)
            (root / "payload_N000.dng").write_bytes(b"")
            with self.assertRaisesRegex(ValueError, "empty"):
                inspect_burst(root)
        with TemporaryDirectory() as td:
            root = Path(td)
            self.frames(root, 0, 1)
            for i in range(128):
                (root / f"aux-{i:03d}.txt").touch()
            with self.assertRaisesRegex(ValueError, "entry quota"):
                inspect_burst(root)

    def test_refuses_symlinked_root(self):
        with TemporaryDirectory() as td:
            root = Path(td) / "real"
            root.mkdir()
            self.frames(root, 0, 1)
            alias = Path(td) / "alias"
            alias.symlink_to(root, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "Symlinked"):
                inspect_burst(alias)


    def test_optional_byte_distinctness_reads_only_curated_frame_bytes(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            self.frames(root, 0, 1)
            (root / "rgb2rgb.txt").write_text("fictional")
            before = {p.name: p.read_bytes() for p in root.iterdir()}
            report = inspect_burst(root, check_byte_distinctness=True)
            self.assertTrue(report["frame_bytes_hashed"])
            self.assertTrue(report["byte_distinctness_checked"])
            self.assertTrue(report["bytewise_distinct_frames_observed"])
            self.assertTrue(report["data_bytes_read"])
            self.assertEqual(report["bytes_hashed"], 4000)
            self.assertFalse(report["source_bytes_verified"])
            self.assertFalse(report["image_format_validated"])
            self.assertFalse(report["physical_shutter_independence_verified"])
            self.assertNotIn(td, json.dumps(report))
            self.assertNotRegex(json.dumps(report), r"\b[0-9a-f]{64}\b")
            self.assertEqual(before, {p.name: p.read_bytes() for p in root.iterdir()})

    def test_optional_hash_rejects_distinct_inodes_with_identical_bytes(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            self.frames(root, 0, 1)
            (root / "payload_N001.dng").write_bytes(
                (root / "payload_N000.dng").read_bytes())
            self.assertNotEqual(
                (root / "payload_N000.dng").stat().st_ino,
                (root / "payload_N001.dng").stat().st_ino)
            self.assertEqual(inspect_burst(root)["reported_frame_count"], 2)
            with self.assertRaisesRegex(ValueError, "Byte-identical"):
                inspect_burst(root, check_byte_distinctness=True)

    def test_byte_hash_rejects_in_place_mutation_since_listing(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            self.frames(root, 0, 1)
            original = admission._stream_digest

            def mutate_then_hash(fd, name, expected):
                if name == "payload_N000.dng":
                    (root / name).write_bytes(b"x" * expected.st_size)
                return original(fd, name, expected)

            with patch.object(admission, "_stream_digest", side_effect=mutate_then_hash):
                with self.assertRaisesRegex(ValueError, "changed"):
                    inspect_burst(root, check_byte_distinctness=True)

    def test_byte_hash_refuses_external_hardlink_and_wrong_boolean(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            self.frames(root, 0, 1)
            os.link(root / "payload_N000.dng", root / "external-backup")
            with self.assertRaisesRegex(ValueError, "external hard links"):
                inspect_burst(root, check_byte_distinctness=True)
            self.assertEqual(inspect_burst(root)["reported_frame_count"], 2)
            for invalid in (1, "yes", None):
                with self.subTest(invalid=invalid):
                    with self.assertRaisesRegex(ValueError, "boolean"):
                        inspect_burst(root, check_byte_distinctness=invalid)

    def test_byte_hash_rejects_symlink_swap_after_listing(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            self.frames(root, 0, 1)
            original = admission._stream_digest

            def swap_then_hash(fd, name, expected):
                if name == "payload_N000.dng":
                    (root / name).rename(root / "replacement.bin")
                    (root / name).symlink_to("replacement.bin")
                return original(fd, name, expected)

            with patch.object(admission, "_stream_digest", side_effect=swap_then_hash):
                with self.assertRaises(ValueError):
                    inspect_burst(root, check_byte_distinctness=True)



if __name__ == "__main__":
    unittest.main()
