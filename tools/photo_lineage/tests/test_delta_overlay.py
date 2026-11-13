"""Pure synthetic replacement/tombstone overlay invariants; no media files."""
import random
import unittest

from tools.photo_lineage.benchmark_packed_index import packed_postings
from tools.photo_lineage.delta_overlay import (
    MAX_UPDATE_DOCS, combine_layers, normalize_updates, shortlist_with_updates,
)


class DeltaOverlayTest(unittest.TestCase):
    def test_replacement_hides_all_old_features(self):
        base = packed_postings([{7, 8, 9}, {7, 8, 10}])
        self.assertEqual(shortlist_with_updates(base, {7, 8}, {}), [(0, 2), (1, 2)])
        self.assertEqual(shortlist_with_updates(base, {7, 8}, {0: {21, 22}}), [(1, 2)])
        self.assertEqual(shortlist_with_updates(base, {21, 22}, {0: {21, 22}}), [(0, 2)])

    def test_tombstone_never_resurrects_old_features(self):
        base = packed_postings([{1, 2, 3}, {1, 2, 4}])
        self.assertEqual(shortlist_with_updates(base, {1, 2}, {0: None}), [(1, 2)])
        self.assertEqual(shortlist_with_updates(base, {1, 2}, {0: None, 1: None}), [])

    def test_new_document_and_empty_replacement(self):
        base = packed_postings([{5, 6}])
        self.assertEqual(shortlist_with_updates(base, {5, 6}, {7: [5, 6]}), [(0, 2), (7, 2)])
        self.assertEqual(shortlist_with_updates(base, {5, 6}, {0: []}), [])
        self.assertEqual(shortlist_with_updates(base, {5, 6}, {7: []}), [(0, 2)])

    def test_last_layer_wins_and_recovery_is_explicit(self):
        base = packed_postings([{5, 6}])
        updates = combine_layers({0: [7, 8]}, {0: None}, {0: [5, 6], 1: [7, 8]})
        self.assertEqual(shortlist_with_updates(base, {5, 6}, updates), [(0, 2)])
        self.assertEqual(shortlist_with_updates(base, {7, 8}, updates), [(1, 2)])
        self.assertEqual(combine_layers({0: [5]}, {0: None})[0], None)

    def test_query_duplicates_do_not_inflate_scores(self):
        base = packed_postings([{1, 2}, {1, 3}])
        self.assertEqual(shortlist_with_updates(base, [1, 1, 2, 2, 2], {}), [(0, 2)])
        self.assertEqual(shortlist_with_updates(base, [], {3: [1, 2]}), [])

    def test_large_common_posting_budget_fails_closed(self):
        base = packed_postings([{1, 2} for _ in range(50)])
        with self.assertRaisesRegex(ValueError, 'Posting scan budget'):
            shortlist_with_updates(base, {1, 2}, {}, max_posting_visits=20)
        self.assertEqual(len(shortlist_with_updates(base, {1, 2}, {}, max_posting_visits=100)), 20)

    def test_refuse_malformed_ids_words_and_oversized_updates(self):
        for bad in (True, -1, 2**32, 2.0):
            with self.subTest(bad_id=bad), self.assertRaisesRegex(ValueError, 'Document ID'):
                normalize_updates({bad: {1}})
        for bad in (True, -1, 2**26, 2.0):
            with self.subTest(bad_word=bad), self.assertRaisesRegex(ValueError, 'Visual word'):
                normalize_updates({1: [bad]})
        with self.assertRaisesRegex(ValueError, 'document budget'):
            normalize_updates({i: [1] for i in range(MAX_UPDATE_DOCS + 1)})
        with self.assertRaisesRegex(ValueError, 'visual-word budget'):
            normalize_updates({1: range(4097)})

    def test_query_budget_and_tie_breaks(self):
        base = packed_postings([{4, 5} for _ in range(4)])
        self.assertEqual(shortlist_with_updates(base, {4, 5}, {}, top_k=2), [(0, 2), (1, 2)])
        with self.assertRaisesRegex(ValueError, 'Too many query words'):
            shortlist_with_updates(base, range(513), {})
        with self.assertRaisesRegex(ValueError, 'top_k'):
            shortlist_with_updates(base, {4}, {}, top_k=0)

    def test_single_pass_replacement_generator(self):
        base = packed_postings([{1, 2}])
        replacements = {0: (i for i in (3, 4, 4))}
        self.assertEqual(shortlist_with_updates(base, {3, 4}, replacements), [(0, 2)])

    def test_exact_equivalence_to_exhaustive_replacement_reference(self):
        rng = random.Random(707)
        originals = [set(rng.sample(range(1, 120), 16)) for _ in range(80)]
        base = packed_postings(originals)
        original_bytes = base.tobytes()
        updates = {}
        for doc in range(0, 80, 5):
            updates[doc] = None if doc % 2 else set(rng.sample(range(1, 120), 16))
        updates[100] = set(rng.sample(range(1, 120), 16))
        merged = {i: set(words) for i, words in enumerate(originals)}
        for doc, words in updates.items():
            if words is None:
                merged.pop(doc, None)
            else:
                merged[doc] = set(words)
        for trial in range(20):
            query = set(rng.sample(range(1, 120), 20))
            expected = sorted(
                ((doc, len(words & query)) for doc, words in merged.items() if len(words & query) >= 2),
                key=lambda item: (-item[1], item[0]),
            )[:10]
            with self.subTest(trial=trial):
                self.assertEqual(shortlist_with_updates(base, query, updates, top_k=10), expected)
        self.assertEqual(base.tobytes(), original_bytes)


if __name__ == '__main__':
    unittest.main()
