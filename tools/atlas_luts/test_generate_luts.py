import hashlib
import json
import tempfile
import zlib
import unittest
from pathlib import Path

from generate_luts import LOOKS, SIZE, lut_bytes, generate

class GenerateLutsTest(unittest.TestCase):
    def test_catalogue_is_original_named_and_bounded(self):
        self.assertEqual(14, len(LOOKS))
        self.assertEqual(len(LOOKS), len({x.id for x in LOOKS}))
        self.assertTrue(all(x.id.startswith("atlas-") and x.name for x in LOOKS))
        self.assertLess(sum(len(lut_bytes(x)) for x in LOOKS), 2_000_000)

    def test_generation_is_byte_identical_and_hashes_cover_files(self):
        with tempfile.TemporaryDirectory() as root:
            a, b = Path(root) / "a", Path(root) / "b"
            first, second = generate(a), generate(b)
            self.assertEqual(first, second)
            self.assertEqual((a / "catalogue.json").read_bytes(), (b / "catalogue.json").read_bytes())
            parsed = json.loads((a / "catalogue.json").read_text())
            for entry in parsed["looks"]:
                data = (a / f'{entry["id"]}.lut').read_bytes()
                self.assertEqual(entry["sha256"], hashlib.sha256(data).hexdigest())
                self.assertEqual(b"ATLASLUT1", data[:9])
                self.assertEqual(SIZE, data[9])
                self.assertEqual(SIZE ** 3 * 3, len(zlib.decompress(data[10:])))

    def test_binary_axis_order_and_endpoints_are_stable(self):
        raw = zlib.decompress(lut_bytes(LOOKS[0])[10:])
        values = raw
        self.assertEqual([0, 0, 0], list(values[:3]))
        self.assertEqual([255, 255, 255], list(values[-3:]))
        self.assertNotEqual(values[3:6], values[SIZE * 3:SIZE * 3 + 3])

if __name__ == "__main__": unittest.main()
