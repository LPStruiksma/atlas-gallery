"""Fictional bytes and locally authored pins only, never real photographs."""
from hashlib import sha256
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from tools.photo_lineage.hdrplus_burst_admission import inspect_burst
from tools.photo_lineage.hdrplus_sha256_manifest import load_sha256_pins


class HDRPlusPinsTests(unittest.TestCase):
    def setup_frames(self, root: Path) -> dict[int, bytes]:
        result = {0: b"synthetic-frame-zero" * 19, 1: b"synthetic-frame-one" * 23}
        for index, data in result.items():
            (root / f"payload_N{index:03d}.dng").write_bytes(data)
        return result

    def manifest(self, path: Path, photos: dict[int, bytes]) -> None:
        path.write_text(json.dumps({
            "version": 1,
            "frame_sha256": {str(i): sha256(data).hexdigest() for i, data in photos.items()},
        }))

    def test_predeclared_matching_pins_preserve_all_source_bytes(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            photos = self.setup_frames(root)
            pins_path = root / "source-pins.json"
            self.manifest(pins_path, photos)
            before = {p.name: p.read_bytes() for p in root.iterdir()}
            pins = load_sha256_pins(pins_path)
            self.assertEqual(set(pins), {0, 1})
            report = inspect_burst(root, check_byte_distinctness=True,
                                   expected_frame_sha256=pins)
            self.assertTrue(report["local_bytes_match_user_supplied_sha256_pins"])
            self.assertFalse(report["original_publisher_hashes_authenticated"])
            self.assertFalse(report["physical_shutter_independence_verified"])
            self.assertFalse(report["source_bytes_verified"])
            self.assertNotIn(str(root), json.dumps(report))
            for digest in (sha256(data).hexdigest() for data in photos.values()):
                self.assertNotIn(digest, json.dumps(report))
            self.assertEqual(before, {p.name: p.read_bytes() for p in root.iterdir()})

    def test_predeclared_checksum_mismatch_rejects_nonidentical_staged_bytes(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            photos = self.setup_frames(root)
            pin_path = root / "pins.json"
            self.manifest(pin_path, photos)
            pins = load_sha256_pins(pin_path)
            target = root / "payload_N001.dng"
            target.write_bytes(b"not-original" * 26)
            with self.assertRaisesRegex(ValueError, "predeclared"):
                inspect_burst(root, check_byte_distinctness=True, expected_frame_sha256=pins)

    def test_manifest_input_validation_and_boolean_pin_requirement(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            frames = self.setup_frames(root)
            path = root / "pins.json"
            self.manifest(path, frames)
            pins = load_sha256_pins(path)
            for invalid in (
                {0: pins[0]}, {0: pins[0], 2: pins[1]},
                {0: pins[0], 1: pins[0]}, {False: pins[0], 1: pins[1]},
                {"0": pins[0], 1: pins[1]}, {0: pins[0], 1: "invalid"},
            ):
                with self.subTest(invalid=repr(invalid)[:50]):
                    with self.assertRaises(ValueError):
                        inspect_burst(root, check_byte_distinctness=True,
                                      expected_frame_sha256=invalid)
            with self.assertRaisesRegex(ValueError, "verification"):
                inspect_burst(root, expected_frame_sha256=pins)

    def test_bad_or_duplicate_manifest_json_fails_closed(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            photos = self.setup_frames(root)
            path = root / "pins.json"
            self.manifest(path, photos)
            valid = json.loads(path.read_text())
            cases = (
                {"version": True, "frame_sha256": valid["frame_sha256"]},
                {"version": 2, "frame_sha256": valid["frame_sha256"]},
                {"version": 1, "frame_sha256": {"00": "a" * 64, "1": "b" * 64}},
                {"version": 1, "frame_sha256": {"0": "A" * 64, "1": "b" * 64}},
                {"version": 1, "frame_sha256": {"0": "a" * 64, "1": "a" * 64}},
                {"version": 1, "frame_sha256": {"0": "a" * 64}},
                {"version": 1, "frame_sha256": valid["frame_sha256"], "extra": 1},
            )
            for case in cases:
                with self.subTest(case=case):
                    path.write_text(json.dumps(case))
                    with self.assertRaises(ValueError):
                        load_sha256_pins(path)
            path.write_text('{"version":1,"version":1,"frame_sha256":{"0":"a","1":"b"}}')
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                load_sha256_pins(path)
            path.write_bytes(b"x" * 4097)
            with self.assertRaisesRegex(ValueError, "oversized"):
                load_sha256_pins(path)

    def test_pin_manifest_symlink_and_hardlink_are_refused(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            photos = self.setup_frames(root)
            actual = root / "real-pins.json"
            self.manifest(actual, photos)
            alias = root / "alias.json"
            alias.symlink_to(actual)
            with self.assertRaisesRegex(ValueError, "non-symlink"):
                load_sha256_pins(alias)
            alias.unlink()
            os.link(actual, alias)
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                load_sha256_pins(actual)


if __name__ == "__main__":
    unittest.main()
