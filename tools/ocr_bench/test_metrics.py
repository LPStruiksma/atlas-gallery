import json
import tempfile
import unittest
from pathlib import Path

from tools.ocr_bench.evaluate import evaluate
from tools.ocr_bench.metrics import error_rate, normalize


class MetricsTest(unittest.TestCase):
    def test_normalizes_dutch_unicode_and_whitespace(self):
        self.assertEqual(normalize("  CAFÉ\nIJssel  "), "café ijssel")

    def test_known_rates(self):
        self.assertEqual(error_rate("abc", "axc"), 1 / 3)
        self.assertEqual(error_rate("een twee", "een drie", words=True), 1 / 2)

    def test_negative_and_missed_text_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.jsonl"; predictions = root / "predictions.jsonl"
            manifest.write_text('\n'.join((json.dumps({"id":"a","kind":"no_text","text":""}),
                                             json.dumps({"id":"b","kind":"sign","text":"STOP"}))), encoding="utf-8")
            predictions.write_text('\n'.join((json.dumps({"id":"a","text":"noise"}),
                                                json.dumps({"id":"b","text":""}))), encoding="utf-8")
            result = evaluate(manifest, predictions)
            self.assertEqual((result["false_text"], result["false_skips"]), (1, 1))


if __name__ == "__main__":
    unittest.main()

