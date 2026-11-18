import csv
import hashlib
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

import compare_parity_renders as comparator


def chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)


def png(pixel, filter_mode=0):
    header = struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)
    rgba = bytes(pixel)
    encoded = rgba if filter_mode == 0 else bytes((rgba[0], rgba[1], rgba[2], rgba[3]))
    return comparator.SIGNATURE + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes((filter_mode,)) + encoded)) + chunk(b"IEND", b"")


class ComparatorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.base = self.root / "base"; self.candidate = self.root / "candidate"
        self.base.mkdir(); self.candidate.mkdir(); self.base_sha = "a" * 40; self.candidate_sha = "b" * 40

    def write(self, directory, sha, image=None, status="CAPTURED", reason=""):
        digest = ""; name = ""
        if image is not None:
            name = "photos-light.png"; (directory / name).write_bytes(image); digest = hashlib.sha256(image).hexdigest()
        with (directory / "manifest.tsv").open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=sorted(comparator.MANIFEST_FIELDS), delimiter="\t")
            writer.writeheader(); writer.writerow({"surface": "photos", "theme": "light", "status": status,
                "commit_sha": sha, "fixture": "synthetic", "png": name, "sha256": digest, "reason": reason})

    def compare(self):
        return comparator.compare(self.base, self.candidate, self.base_sha, self.candidate_sha, self.root / "report.tsv")

    def test_pixel_identical_pngs_pass_and_emit_report(self):
        image = png((1, 2, 3, 255)); self.write(self.base, self.base_sha, image); self.write(self.candidate, self.candidate_sha, image)
        self.assertEqual("IDENTICAL", self.compare()[0][2]); self.assertIn("changed_pixels", (self.root / "report.tsv").read_text())

    def test_pixel_change_fails(self):
        self.write(self.base, self.base_sha, png((1, 2, 3, 255))); self.write(self.candidate, self.candidate_sha, png((2, 2, 3, 255)))
        with self.assertRaisesRegex(ValueError, "1 changed pixels"): self.compare()

    def test_manifest_sha_and_image_digest_tampering_fail(self):
        image = png((1, 2, 3, 255)); self.write(self.base, "c" * 40, image); self.write(self.candidate, self.candidate_sha, image)
        with self.assertRaisesRegex(ValueError, "commit SHA"): self.compare()
        self.write(self.base, self.base_sha, image); (self.base / "photos-light.png").write_bytes(image + b"x")
        with self.assertRaisesRegex(ValueError, "SHA-256"): self.compare()

    def test_explicit_matching_gaps_are_preserved(self):
        self.write(self.base, self.base_sha, status="NOT_CAPTURED", reason="route unavailable")
        self.write(self.candidate, self.candidate_sha, status="NOT_CAPTURED", reason="route unavailable")
        self.assertEqual("NOT_CAPTURED", self.compare()[0][2])

    def test_asymmetric_gap_fails_closed(self):
        self.write(self.base, self.base_sha, status="NOT_CAPTURED", reason="route unavailable")
        self.write(self.candidate, self.candidate_sha, png((1, 2, 3, 255)))
        with self.assertRaisesRegex(ValueError, "asymmetric"): self.compare()


if __name__ == "__main__":
    unittest.main()
