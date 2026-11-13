"""Synthetic-only read-only packed posting index invariants."""
import unittest
import numpy as np
from tools.photo_lineage.benchmark_packed_index import packed_postings, packed_query, run


class PackedIndexTest(unittest.TestCase):
    def test_tie_breaks_and_shared_words(self):
        records = packed_postings([{7, 8, 9}, {7, 8, 10}, {7, 11}])
        self.assertEqual(records.dtype, np.dtype('<u8'))
        self.assertEqual(packed_query(records, {7, 8}, top_k=2), [(0, 2), (1, 2)])
        self.assertEqual(packed_query(records, {11}, top_k=2), [])
        self.assertEqual(len(records) * 8, records.nbytes)

    def test_invalid_visual_word_is_refused(self):
        with self.assertRaises(ValueError):
            packed_postings([{2**26}])

    def test_synthetic_crops_retrievable(self):
        result = run(distractors=8, anchors=2)
        self.assertEqual(result['photos'], 10)
        self.assertEqual(result['queries'], 14)
        self.assertEqual(result['recall_at_20'], 1.0)
        self.assertAlmostEqual(result['postings'] * 8 / 1048576, result['packed_mib'], delta=.001)


if __name__ == '__main__':
    unittest.main()
