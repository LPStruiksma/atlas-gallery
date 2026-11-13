"""Synthetic numeric-only retrieval tests. No original images or external data."""
from __future__ import annotations

import hashlib
from pathlib import Path
import struct
import tempfile
import unittest

from tools.photo_lineage.bounded_posting_query import (
    PostingBudgetExceeded, open_pinned_queries,
)
from tools.photo_lineage.external_index import build_snapshot


def _make(documents, folder, *, max_postings=500000, chunk_records=512):
    path = Path(folder) / "index.u64"
    report = build_snapshot(documents, path, chunk_records=chunk_records,
                            max_postings=max_postings, reserve_free_bytes=0)
    return path, report["sha256"]


class BoundedQueryTests(unittest.TestCase):
    def test_parity_with_reference_and_stable_ties(self):
        with tempfile.TemporaryDirectory() as folder:
            docs = [(0, (1, 2, 3, 3)), (1, (1, 2, 4)), (2, (1, 2, 5)),
                    (3, (1, 3, 4)), (4, (4, 5, 8)), (5, (50,))]
            path, sha = _make(docs, folder)
            originals = path.read_bytes()
            with open_pinned_queries(path, sha) as index:
                result = index.query([4, 1, 2, 2, 3], top_k=4)
                self.assertEqual(result["distinct_query_words"], 4)
                self.assertEqual(result["matches"], [
                    {"photo_id": 0, "shared_visual_words": 3},
                    {"photo_id": 1, "shared_visual_words": 3},
                    {"photo_id": 3, "shared_visual_words": 3},
                    {"photo_id": 2, "shared_visual_words": 2},
                ])
                excluded = index.query((word for word in (1, 2, 3)), exclude_doc_id=0)
                self.assertEqual(excluded["matches"], [
                    {"photo_id": 1, "shared_visual_words": 2},
                    {"photo_id": 2, "shared_visual_words": 2},
                    {"photo_id": 3, "shared_visual_words": 2},
                ])
                self.assertTrue(result["complete"])
                self.assertEqual(index.query([])["matches"], [])
            self.assertEqual(originals, path.read_bytes())

    def test_huge_common_word_abstains_before_materializing_scores(self):
        with tempfile.TemporaryDirectory() as folder:
            # Real 150k numeric document IDs, only two words/doc to remain a
            # modest CI fixture. NOT 150k decoded photos nor 384 words/photo.
            path, sha = _make(((i, (7, 1000 + (i % 65000))) for i in range(150_000)),
                              folder, max_postings=300_000, chunk_records=8192)
            with open_pinned_queries(path, sha) as index:
                self.assertEqual(index.count, 300_000)
                with self.assertRaisesRegex(PostingBudgetExceeded, "abstain"):
                    index.query([7], max_posting_visits=10000)
                with self.assertRaisesRegex(PostingBudgetExceeded, "Candidate-document budget"):
                    index.query([7, 1001], max_posting_visits=200_000,
                                max_distinct_documents=10)
                known = index.query([7, 1001], max_posting_visits=200_000,
                                    max_distinct_documents=150_000)
                self.assertTrue(known["complete"])
                self.assertEqual(known["matches"][0]["photo_id"], 1)
                self.assertEqual(known["matches"][0]["shared_visual_words"], 2)

    def test_pinned_digest_corruption_and_unsorted_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            path, sha = _make([(0, (1, 4)), (1, (3, 4))], folder)
            with self.assertRaisesRegex(ValueError, "digest"):
                with open_pinned_queries(path, "0" * 64):
                    pass
            path.write_bytes(struct.pack("<QQ", (4 << 32) | 1, 2))
            new_digest = hashlib.sha256(path.read_bytes()).hexdigest()
            with self.assertRaisesRegex(ValueError, "sorted"):
                with open_pinned_queries(path, new_digest):
                    pass

    def test_symlink_and_unaligned_snapshot_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            path, sha = _make([(1, (7, 9))], folder)
            link = Path(folder) / "alias.u64"
            link.symlink_to(path)
            with self.assertRaisesRegex(ValueError, "non-symlink"):
                with open_pinned_queries(link, sha):
                    pass
            path.write_bytes(b"bad")
            with self.assertRaisesRegex(ValueError, "Unaligned"):
                with open_pinned_queries(path, hashlib.sha256(b"bad").hexdigest()):
                    pass

    def test_empty_snapshot_is_usable_but_no_match(self):
        with tempfile.TemporaryDirectory() as folder:
            path, sha = _make([], folder)
            with open_pinned_queries(path, sha) as index:
                self.assertEqual(index.count, 0)
                self.assertEqual(index.query([7, 8])["matches"], [])

    def test_invalid_query_parameter_types_and_bounds(self):
        with tempfile.TemporaryDirectory() as folder:
            path, sha = _make([(0, (1, 2))], folder)
            with open_pinned_queries(path, sha) as index:
                for terms in ([True], [-1], [1 << 26], [1.0]):
                    with self.subTest(terms=terms), self.assertRaisesRegex(ValueError, "26-bit"):
                        index.query(terms)
                with self.assertRaisesRegex(ValueError, "Too many"):
                    index.query(range(513))
                for options in ({"top_k": True}, {"top_k": 0},
                                {"max_posting_visits": True},
                                {"max_distinct_documents": 0},
                                {"exclude_doc_id": -1},
                                {"exclude_doc_id": True}):
                    with self.subTest(options=options), self.assertRaises(ValueError):
                        index.query([1], **options)
            with self.assertRaisesRegex(ValueError, "pinned"):
                with open_pinned_queries(path, "not-a-digest"):
                    pass
            with self.assertRaisesRegex(ValueError, "budget"):
                with open_pinned_queries(path, sha, max_bytes=1):
                    pass

    def test_reused_terms_do_not_inflate_score(self):
        with tempfile.TemporaryDirectory() as folder:
            path, sha = _make([(0, (1, 2)), (1, (1, 2))], folder)
            with open_pinned_queries(path, sha) as index:
                self.assertEqual(index.query([1, 1, 1, 2])["matches"][0]["shared_visual_words"], 2)

    def test_raw_repeated_token_generator_is_bounded(self):
        with tempfile.TemporaryDirectory() as folder:
            path, sha = _make([(0, (1, 2))], folder)
            with open_pinned_queries(path, sha) as index:
                with self.assertRaisesRegex(ValueError, "raw query terms"):
                    index.query((1 for _ in range(2500)))

    def test_benchmark_micro_smoke(self):
        from tools.photo_lineage.benchmark_bounded_query import run
        sample = run(photos=501, queries=4)
        self.assertEqual(sample["synthetic_photo_ids"], 501)
        self.assertEqual(sample["top1_numeric_query_checks"], 4)
        self.assertTrue(sample["hot_word_abstained_explicitly"])
        self.assertEqual(sample["postings"], 1503)

    def test_read_only_load_inspector_agrees_with_exact_score_window_cost(self):
        with tempfile.TemporaryDirectory() as folder:
            path, sha = _make([(0, (1, 2, 9)), (1, (1, 2)),
                               (2, (1, 7)), (3, (2, 7))], folder)
            original = path.read_bytes()
            with open_pinned_queries(path, sha) as index:
                inspection = index.inspect_query_load([1, 2, 2, 42], max_posting_visits=6)
                self.assertEqual(inspection["distinct_query_words"], 3)
                self.assertEqual(inspection["postings_preflight"], 6)
                self.assertEqual(inspection["largest_single_word_posting_list"], 3)
                self.assertEqual(inspection["words_with_nonempty_postings"], 2)
                self.assertEqual(inspection["posting_budget_status"], "within_posting_budget_only")
                self.assertFalse(inspection["query_ranking_performed"])
                self.assertNotIn("matches", inspection)
                query = index.query([1, 2, 2, 42], max_posting_visits=6)
                self.assertEqual(query["postings_preflight"], inspection["postings_preflight"])
                self.assertEqual(query["matches"][0],
                                 {"photo_id": 0, "shared_visual_words": 2})
                blocked = index.inspect_query_load([1, 2], max_posting_visits=5)
                self.assertEqual(blocked["posting_budget_status"], "abstain_incomplete")
                with self.assertRaises(PostingBudgetExceeded):
                    index.query([1, 2], max_posting_visits=5)
            self.assertEqual(original, path.read_bytes())

    def test_384_distinct_queries_are_inspected_without_candidate_materialization(self):
        with tempfile.TemporaryDirectory() as folder:
            path, sha = _make([(0, range(384)), (1, range(384)),
                               (2, range(100, 384))], folder)
            with open_pinned_queries(path, sha) as index:
                result = index.inspect_query_load(range(384), max_posting_visits=1000)
                self.assertEqual(result["distinct_query_words"], 384)
                self.assertEqual(result["postings_preflight"], 1052)
                self.assertEqual(result["posting_budget_status"], "abstain_incomplete")
                with self.assertRaises(PostingBudgetExceeded):
                    index.query(range(384), max_posting_visits=1000)
                self.assertEqual(index.query(range(384), max_posting_visits=1100)["matches"][0],
                                 {"photo_id": 0, "shared_visual_words": 384})

    def test_load_inspector_rejects_same_invalid_tokens_as_query(self):
        with tempfile.TemporaryDirectory() as folder:
            path, sha = _make([(0, (1, 2))], folder)
            with open_pinned_queries(path, sha) as index:
                with self.assertRaisesRegex(ValueError, "raw query terms"):
                    index.inspect_query_load((1 for _ in range(2500)))
                with self.assertRaisesRegex(ValueError, "26-bit"):
                    index.inspect_query_load([True])
                with self.assertRaisesRegex(ValueError, "budget"):
                    index.inspect_query_load([1], max_posting_visits=True)

    def test_single_valid_match_is_below_two_word_gate(self):
        with tempfile.TemporaryDirectory() as folder:
            path, sha = _make([(0, (1, 7)), (1, (1, 9))], folder)
            with open_pinned_queries(path, sha) as index:
                self.assertEqual(index.query([1])["matches"], [])

    def test_write_after_open_changes_stat_and_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            path, sha = _make([(0, (1, 2)), (1, (1, 2))], folder)
            with open_pinned_queries(path, sha) as index:
                with path.open("ab") as fd:
                    fd.write(struct.pack("<Q", (20 << 32) | 4))
                with self.assertRaisesRegex(ValueError, "changed"):
                    index.query([1, 2])


if __name__ == "__main__":
    unittest.main()
