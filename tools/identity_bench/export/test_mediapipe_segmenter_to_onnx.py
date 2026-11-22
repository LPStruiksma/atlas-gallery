"""Network-free validation of source pin and layout (not model parity)."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from tools.identity_bench.export import mediapipe_segmenter_to_onnx as mp


class PinsAndLayout(unittest.TestCase):
    def test_pins_agree_with_existing_bench(self):
        self.assertEqual(mp.SOURCE_SIZE, 16_371_837)
        self.assertEqual(mp.SOURCE_SHA256, "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0")
        self.assertEqual((mp.CONVERTER, mp.CONVERTER_VERSION), ("tf2onnx", "1.17.0"))
        self.assertEqual((mp.TENSORFLOW_VERSION, mp.OPSET), ("2.21.0", 17))

    def test_bytes_are_verified_before_converting(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "synthetic.tflite"
            path.write_bytes(b"dummy" * 8)
            mp.verify_source(path, size=40, digest=hashlib.sha256(path.read_bytes()).hexdigest())
            with self.assertRaises(ValueError):
                mp.verify_source(path)
            with self.assertRaises(ValueError):
                mp.verify_source(path, size=40, digest="0" * 64)

    def test_rejects_wrong_layout_or_class_count(self):
        mp.validate_shapes([mp.INPUT_SHAPE], [mp.OUTPUT_SHAPE])
        for inputs, outputs in [
            ([(1, 3, 256, 256)], [mp.OUTPUT_SHAPE]),
            ([mp.INPUT_SHAPE], [(1, 6, 256, 256)]),
            ([mp.INPUT_SHAPE], [(1, 256, 256, 1)]),
            ([], [mp.OUTPUT_SHAPE]),
        ]:
            with self.subTest(inputs=inputs, outputs=outputs):
                with self.assertRaises(ValueError):
                    mp.validate_shapes(inputs, outputs)


if __name__ == "__main__":
    unittest.main()
