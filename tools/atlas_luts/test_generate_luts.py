import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from generate_luts import LOOKS, SIZE, MAGIC, lut_bytes, generate


class GenerateLutsTest(unittest.TestCase):
    def test_original_named_and_bounded(self):
        self.assertEqual(14, len(LOOKS))
        self.assertEqual(len(LOOKS), len({x.id for x in LOOKS}))
        self.assertTrue(all(x.id.startswith("atlas-") and x.name for x in LOOKS))
        self.assertTrue(all(0.0 < x.preview_strength <= 0.68 for x in LOOKS))
        self.assertLess(sum(len(lut_bytes(x)) for x in LOOKS), 2_000_000)

    def test_byte_identical_regeneration_and_hashes(self):
        with tempfile.TemporaryDirectory() as root:
            first_dir, second_dir = Path(root) / "a", Path(root) / "b"
            first, second = generate(first_dir), generate(second_dir)
            self.assertEqual(first, second)
            self.assertEqual((first_dir / "catalogue.json").read_bytes(),
                             (second_dir / "catalogue.json").read_bytes())
            for entry in first["looks"]:
                data = (first_dir / (entry["id"] + ".lut")).read_bytes()
                self.assertEqual(entry["sha256"], hashlib.sha256(data).hexdigest())
                self.assertEqual(MAGIC, data[:9])
                self.assertEqual(SIZE, data[9])
                self.assertEqual(10 + SIZE ** 3 * 3, len(data))

    def test_checked_in_bundle_matches_generated_source(self):
        committed = Path(__file__).resolve().parents[2] / "app/src/main/assets/editor/luts"
        self.assertTrue(committed.is_dir())
        with tempfile.TemporaryDirectory() as root:
            generated = Path(root)
            expected = generate(generated)
            self.assertEqual({p.name for p in generated.iterdir()},
                             {p.name for p in committed.iterdir()})
            # Compare the serialized catalogue shape: dataclass tuples become JSON lists,
            # and JSON does not preserve the distinction between 0 and 0.0.
            expected_json = json.loads(json.dumps(expected))
            self.assertEqual(expected_json, json.loads((committed / "catalogue.json").read_text()))
            for look in LOOKS:
                filename = look.id + ".lut"
                self.assertEqual((generated / filename).read_bytes(),
                                 (committed / filename).read_bytes())

    def test_rgb_major_axis_order_and_endpoints(self):
        raw = lut_bytes(LOOKS[0])[10:]
        self.assertEqual([0, 0, 0], list(raw[:3]))
        self.assertEqual([255, 255, 255], list(raw[-3:]))
        self.assertNotEqual(raw[3:6], raw[SIZE * 3:SIZE * 3 + 3])


if __name__ == "__main__":
    unittest.main()
