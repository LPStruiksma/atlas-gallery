import json, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
from tools.ocr_bench.ocr0b import benchmark, percentile, project_150k, select_threshold, slice_report

class Ocr0BTest(unittest.TestCase):
    def test_threshold_uses_positive_scores(self):
        rows = [{"score": x, "text_positive": True} for x in (1., 2., 3., 4.)] + [{"score": 100., "text_positive": False}]
        self.assertEqual(select_threshold(rows, .75), 2.)
        self.assertEqual(select_threshold(rows, .5), 3.)
    def test_threshold_keeps_tied_scores_within_recall(self):
        rows = [{"score": x, "text_positive": True} for x in (1., 1., 1., 2., 3.)]
        threshold = select_threshold(rows, .8)
        self.assertEqual(threshold, 1.)
        self.assertGreaterEqual(sum(r["score"] >= threshold for r in rows) / len(rows), .8)
    def test_report_separates_events(self):
        rows = [{"score": 2., "text_positive": True, "language": "en", "kind": "sign"},
                {"score": 0., "text_positive": True, "language": "nl", "kind": "receipt"},
                {"score": 0., "text_positive": False, "language": "unspecified", "kind": "no_text"},
                {"score": 3., "text_positive": False, "language": "unspecified", "kind": "no_text"}]
        report = slice_report(rows, 1.)["all"]
        self.assertEqual((report["false_skips"], report["no_text_pass"], report["no_text_skip"]), (1, 1, 1))
    def test_projection_and_percentile(self):
        result = project_150k(1., .5, 10., 20., 100.)
        self.assertAlmostEqual(result["times"]["1.0"]["hours"], 2400 / 3600)
        self.assertEqual(result["peak_ram_bound_mb"], 104.)
        self.assertEqual(percentile([4, 1, 3, 2], .95), 3)
    def test_benchmark_output_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            corpus = Path(tmp); (corpus / "manifest.jsonl").write_text(json.dumps({"path": "x.png"}) + "\n")
            runner = lambda path: ([{"text": "x"}], 2., 3., 6.)
            with patch("tools.ocr_bench.ocr0b._rss_mb", side_effect=(10., 12., 12.)):
                result = benchmark("ppocr", corpus, Path("models"), warmup=0,
                                   runner_builder=lambda models, threads: runner, decoder=lambda path: None)
        self.assertEqual(result["status"], "MEASURED")
        self.assertEqual(set(result["warm"]), {"decode_ms", "detector_ms", "recognizer_ms", "total_ms"})
        self.assertEqual(result["warm"]["detector_ms"]["median_ms"], 2.)
        self.assertEqual(result["rss"]["delta_mb"], 2.)
if __name__ == "__main__": unittest.main()
