"""Only manufactured TIFF-style bytes; no real DNGs."""
import struct
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from tools.photo_lineage.dng_header_probe import validate_dng_prefix, MAX_HEADER_BYTES
from tools.photo_lineage.hdrplus_burst_admission import inspect_burst


def manufactured_header(big_endian=False, version=b"\x01\x04\x00\x00", count=1):
    endian = ">" if big_endian else "<"
    return ((b"MM" if big_endian else b"II") + struct.pack(endian + "HI", 42, 8)
            + struct.pack(endian + "H", count)
            + (struct.pack(endian + "HHI", 50706, 1, 4) + version) * count
            + struct.pack(endian + "I", 0))


class DngHeaderAdmissionTests(unittest.TestCase):
    def test_little_and_big_endian_header_only_opt_in_preserves_inputs(self):
        for endian in (False, True):
            data = manufactured_header(big_endian=endian)
            validate_dng_prefix(data, len(data))
        with TemporaryDirectory() as td:
            root = Path(td)
            frames = (manufactured_header() + b"a",
                      manufactured_header(big_endian=True) + b"b")
            for i, blob in enumerate(frames):
                (root / f"payload_N{i:03d}.dng").write_bytes(blob)
            report = inspect_burst(root, check_dng_headers=True)
            self.assertTrue(report["dng_ifd0_header_checked"])
            self.assertTrue(report["data_bytes_read"])
            self.assertFalse(report["image_format_validated"])
            self.assertFalse(report["physical_shutter_independence_verified"])
            self.assertLessEqual(report["bounded_header_bytes_inspected"],
                                 len(frames) * MAX_HEADER_BYTES)
            self.assertEqual(
                frames, tuple((root / f"payload_N{i:03d}.dng").read_bytes()
                              for i in range(2)))
            self.assertFalse(inspect_burst(root)["data_bytes_read"])

    def test_rejects_fake_file_and_damaged_ifd(self):
        valid = manufactured_header()
        invalid = (
            b"JPEG masquerading as DNG",
            b"ZZ" + valid[2:],
            valid[:2] + b"\x2b\x00" + valid[4:],
            manufactured_header(version=b"\x02\x00\x00\x00"),
            manufactured_header(count=2),
            valid[:-1],
        )
        for item in invalid:
            with self.subTest(item=item[:8]):
                with self.assertRaises(ValueError):
                    validate_dng_prefix(item, len(item))
        with self.assertRaises(ValueError):
            validate_dng_prefix(valid[:12], len(valid))

    def test_bad_field_type_and_unsafe_ifd_offset(self):
        original = bytearray(manufactured_header())
        original[12:14] = b"\x03\x00"
        with self.assertRaisesRegex(ValueError, "DNGVersion"):
            validate_dng_prefix(bytes(original), len(original))
        original = bytearray(manufactured_header())
        original[4:8] = b"\xff\xff\xff\xff"
        with self.assertRaisesRegex(ValueError, "offset"):
            validate_dng_prefix(bytes(original), len(original))

    def test_boolean_validation_and_header_rejection_in_staged_burst(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            (root / "payload_N000.dng").write_bytes(manufactured_header())
            (root / "payload_N001.dng").write_bytes(b"not-DNG-but-long-enough")
            with self.assertRaisesRegex(ValueError, "boolean"):
                inspect_burst(root, check_dng_headers=1)
            with self.assertRaisesRegex(ValueError, "TIFF"):
                inspect_burst(root, check_dng_headers=True)


if __name__ == "__main__":
    unittest.main()
