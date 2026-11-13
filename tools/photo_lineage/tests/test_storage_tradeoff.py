"""Deterministic synthetic ablation: recall versus inverted-index storage."""
import unittest
from tools.photo_lineage.benchmark_storage_tradeoff import run


class StorageTradeoffTest(unittest.TestCase):
    def test_all_configurations_expose_recall_and_posting_cost(self):
        result = run(distractors=8, anchors=2)
        self.assertEqual(result['photos'], 10)
        self.assertEqual(result['queries'], 14)
        self.assertEqual(len(result['configurations']), 12)
        combinations = {(row['keypoints'], row['bands']): row
                        for row in result['configurations']}
        self.assertEqual(combinations[(96,4)]['relative_postings_vs_96x4'], 1.0)
        self.assertLess(combinations[(32,2)]['postings'], combinations[(96,4)]['postings'])
        for row in result['configurations']:
            self.assertTrue(0 <= row['recall_at_5'] <= 1)
            self.assertLessEqual(row['recall_at_1'], row['recall_at_5'])
            self.assertLessEqual(row['recall_at_5'], row['recall_at_20'])


if __name__ == '__main__':
    unittest.main()
