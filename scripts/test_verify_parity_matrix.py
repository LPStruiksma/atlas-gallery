"""Offline, synthetic-byte checks for parity manifest completeness and fail-closed IO."""
import csv
import hashlib
import struct
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest.mock import patch

import verify_parity_matrix as matrix


def chunk(name, payload):
    return (struct.pack(">I", len(payload)) + name + payload +
            struct.pack(">I", zlib.crc32(name + payload) & 0xFFFFFFFF))


def fixture_png():
    """A structurally valid disposable black image; not exported as UI evidence."""
    header = struct.pack(">IIBBBBB", 1080, 2340, 8, 6, 0, 0, 0)
    row = b"\x00" + b"\x00" * (1080 * 4)
    pixels = zlib.compress(row * 2340, 9)
    return matrix.PNG_SIGNATURE + chunk(b"IHDR", header) + chunk(b"IDAT", pixels) + chunk(b"IEND", b"")


class ParityMatrixContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.evidence = self.root / "ui-evidence"
        self.evidence.mkdir()
        self.contract = self.root / "contract.tsv"
        self.contract.write_bytes(matrix.CONTRACT.read_bytes())
        p = patch.object(matrix, "CONTRACT", self.contract)
        p.start()
        self.addCleanup(p.stop)
        p = patch.object(matrix.subprocess, "check_output", return_value="a" * 40 + "\n")
        p.start()
        self.addCleanup(p.stop)

    def rows(self):
        with self.contract.open(newline="", encoding="utf-8") as f:
            return list(csv.DictReader(f, delimiter="\t"))

    def save_rows(self, rows):
        with self.contract.open("w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=matrix.COLUMNS, delimiter="\t")
            writer.writeheader()
            writer.writerows(rows)

    def populate(self):
        data = fixture_png()
        self.assertGreater(len(data), 1024)
        for row in self.rows():
            if row["source_png"]:
                (self.evidence / row["source_png"]).write_bytes(data)
        return data

    def test_real_matrix_rows_match_20_surfaces_and_both_themes(self):
        rows = matrix.read_contract()
        self.assertEqual(40, len(rows))
        self.assertEqual(26, sum(bool(r["source_png"]) for r in rows))
        self.assertEqual(14, sum(not r["source_png"] for r in rows))

    def test_missing_row_or_duplicate_pair_is_refused(self):
        rows = self.rows()
        self.save_rows(rows[:-1])
        with self.assertRaisesRegex(ValueError, "Missing surface/theme"):
            matrix.read_contract()
        self.save_rows(rows + [rows[0]])
        with self.assertRaisesRegex(ValueError, "duplicated"):
            matrix.read_contract()

    def test_mandatory_missing_png_fails_instead_of_claiming_a_capture(self):
        with self.assertRaisesRegex(ValueError, "Missing required source PNG"):
            matrix.build(self.evidence)

    def test_valid_synthetic_pngs_produce_exact_manifest_and_verbatim_copies(self):
        raw = self.populate()
        matrix.build(self.evidence)
        dest = self.evidence / "parity-matrix"
        with (dest / "manifest.tsv").open(newline="", encoding="utf-8") as f:
            manifest = list(csv.DictReader(f, delimiter="\t"))
        self.assertEqual(40, len(manifest))
        self.assertEqual(26, sum(r["status"] == "CAPTURED" for r in manifest))
        self.assertEqual(14, sum(r["status"] == "NOT_CAPTURED" for r in manifest))
        self.assertEqual("a" * 40, manifest[0]["commit_sha"])
        for row in manifest:
            self.assertEqual("1080", row["width"])
            self.assertEqual("2340", row["height"])
            if row["status"] == "CAPTURED":
                self.assertEqual(hashlib.sha256(raw).hexdigest(), row["sha256"])
                self.assertEqual(raw, (self.evidence / row["expected_png"]).read_bytes())
                self.assertFalse(row["reason"])
            else:
                self.assertFalse(row["expected_png"])
                self.assertTrue(row["reason"])

    def test_corrupt_png_fails_even_when_header_and_dimensions_match(self):
        data = bytearray(self.populate())
        name = next(r["source_png"] for r in self.rows() if r["source_png"])
        data[40] ^= 0x01
        (self.evidence / name).write_bytes(data)
        with self.assertRaisesRegex(ValueError, "Bad PNG CRC"):
            matrix.build(self.evidence)

    def test_required_unsourced_row_refused(self):
        rows = self.rows()
        row = next(r for r in rows if not r["source_png"])
        row["required"] = "yes"
        self.save_rows(rows)
        with self.assertRaisesRegex(ValueError, "Mandatory surface"):
            matrix.read_contract()


if __name__ == "__main__":
    unittest.main()
