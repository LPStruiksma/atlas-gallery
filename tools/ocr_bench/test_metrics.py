import json
import tempfile
import unittest
from pathlib import Path

from tools.ocr_bench.evaluate import evaluate
from tools.ocr_bench.metrics import error_rate, normalize


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")


class MetricsTest(unittest.TestCase):
    def test_normalizes_dutch_unicode_and_whitespace(self):
        self.assertEqual(normalize("  CAFÉ\nIJssel  "), "café ijssel")

    def test_known_rates(self):
        self.assertEqual(error_rate("abc", "axc"), 1 / 3)
        self.assertEqual(error_rate("een twee", "een drie", words=True), 1 / 2)

    def test_negative_and_missed_text_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.jsonl"
            predictions = root / "predictions.jsonl"
            write_jsonl(manifest, [{"id": "a", "kind": "no_text", "text": ""},
                                   {"id": "b", "kind": "sign", "text": "STOP"}])
            write_jsonl(predictions, [{"id": "a", "text": "noise"},
                                      {"id": "b", "text": ""}])
            result = evaluate(manifest, predictions)
            self.assertEqual((result["false_text"], result["false_skips"]), (1, 1))
            self.assertEqual(result["no_text_samples"], 1)
            self.assertEqual(result["text_samples"], 1)
            self.assertEqual(result["false_text_rate"], 1)
            self.assertEqual(result["false_skip_rate"], 1)
            self.assertIn("unspecified", result["by_language"])

    def test_language_kind_slices_and_denominators(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.jsonl"
            predictions = root / "predictions.jsonl"
            write_jsonl(manifest, [
                {"id": "en-screen", "kind": "screenshot", "language": "en", "text": "CAT"},
                {"id": "nl-sign", "kind": "sign", "language": "nl", "text": "IJssel"},
                {"id": "en-receipt", "kind": "receipt", "language": "en", "text": "Paid"},
                {"id": "en-empty", "kind": "no_text", "language": "en", "text": ""},
                {"id": "nl-empty", "kind": "no_text", "language": "nl", "text": ""},
            ])
            write_jsonl(predictions, [
                {"id": "en-screen", "text": "CAR"},
                {"id": "nl-sign", "text": ""},
                {"id": "en-receipt", "text": "Paid"},
                {"id": "en-empty", "text": "noise"},
                {"id": "nl-empty", "text": ""},
            ])
            result = evaluate(manifest, predictions)
            self.assertEqual(result["samples"], 5)
            self.assertAlmostEqual(result["cer"], 7 / 13)
            self.assertAlmostEqual(result["wer"], 2 / 3)
            self.assertEqual(result["by_kind"]["receipt"]["cer"], 0)
            self.assertEqual(result["by_kind"]["sign"]["wer"], 1)
            self.assertAlmostEqual(result["by_language"]["en"]["cer"], 1 / 7)
            self.assertEqual(result["by_language"]["nl"]["cer"], 1)
            self.assertAlmostEqual(result["by_language_kind"]["en"]["screenshot"]["cer"], 1 / 3)
            self.assertEqual(result["by_language_kind"]["nl"]["no_text"]["cer"], None)
            self.assertEqual(result["by_kind"]["no_text"]["no_text_samples"], 2)
            self.assertEqual(result["by_kind"]["no_text"]["false_text_rate"], 1 / 2)
            self.assertEqual(result["by_language"]["en"]["false_text_rate"], 1)
            self.assertEqual(result["by_language"]["nl"]["false_text_rate"], 0)
            self.assertEqual(result["by_language"]["nl"]["false_skip_rate"], 1)

    def test_duplicate_manifest_and_prediction_ids_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.jsonl"
            predictions = root / "predictions.jsonl"
            entry = {"id": "a", "kind": "sign", "language": "en", "text": "STOP"}
            prediction = {"id": "a", "text": "STOP"}
            for duplicate_source in ("manifest", "predictions"):
                with self.subTest(duplicate_source=duplicate_source):
                    write_jsonl(manifest, [entry] * (2 if duplicate_source == "manifest" else 1))
                    write_jsonl(predictions, [prediction] * (2 if duplicate_source == "predictions" else 1))
                    with self.assertRaisesRegex(ValueError, "duplicate id 'a'"):
                        evaluate(manifest, predictions)

    def test_missing_and_extra_ids_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.jsonl"
            predictions = root / "predictions.jsonl"
            write_jsonl(manifest, [{"id": "one", "kind": "sign", "text": "ONE"}])
            write_jsonl(predictions, [{"id": "two", "text": "TWO"}])
            with self.assertRaisesRegex(ValueError, "ID mismatch"):
                evaluate(manifest, predictions)


if __name__ == "__main__":
    unittest.main()
