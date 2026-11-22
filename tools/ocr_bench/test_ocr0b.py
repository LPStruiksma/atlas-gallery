import unittest
from tools.ocr_bench.ocr0b import percentile, project_150k, select_threshold, slice_report

class Ocr0BTest(unittest.TestCase):
    def test_threshold_uses_positive_scores(self):
        rows = [{"score": x, "text_positive": True} for x in (1., 2., 3., 4.)] + [{"score": 100., "text_positive": False}]
        self.assertGreater(select_threshold(rows, .75), 1.)
        self.assertGreater(select_threshold(rows, .5), 2.)
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
if __name__ == "__main__": unittest.main()
