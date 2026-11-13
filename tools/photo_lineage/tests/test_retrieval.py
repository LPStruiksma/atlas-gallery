"""Synthetic local inverted index checks. No private photos."""
import tempfile
import unittest
import os
from pathlib import Path
from unittest.mock import patch
import numpy as np
from PIL import Image
from tools.photo_lineage.benchmark import textured, rotate
from tools.photo_lineage.retrieval import index_folder, shortlist, review_shortlist, words_for_image, open_db, MAX_SIDE
from tools.photo_lineage.matcher import read_grayscale
from tools.photo_lineage.bounded_posting_query import PostingBudgetExceeded
from contextlib import closing


class TestRetrieval(unittest.TestCase):
    def test_crop_rotation_keeps_shared_visual_words(self):
        base = textured(115, 600)
        crop = rotate(base, 47)[90:510, 100:520]
        diff = textured(235, 600)
        shared = len(words_for_image(base) & words_for_image(crop))
        unrelated = len(words_for_image(base) & words_for_image(diff))
        self.assertGreater(shared, unrelated + 1, (shared, unrelated))

    def test_resumption_stale_file_and_no_photo_mutations(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            root = d/'source'
            root.mkdir()
            db = d/'index.sqlite'
            base = textured(121, 550)
            original, edit, negative = (root/x for x in ('original.png','edit.png','negative.png'))
            Image.fromarray(base).save(original)
            Image.fromarray(rotate(base, 33)[95:490,90:500]).save(edit)
            Image.fromarray(textured(122,550)).save(negative)
            before = {p.name:p.read_bytes() for p in (original,edit,negative)}
            self.assertEqual(index_folder(root, db, limit=1)['new_or_updated'], 1)
            self.assertEqual(index_folder(root, db, limit=2)['total_cached'], 3)
            self.assertEqual(index_folder(root, db, limit=2)['new_or_updated'], 0)
            self.assertEqual(before,{p.name:p.read_bytes() for p in (original,edit,negative)})
            self.assertIn(str(edit), [h.path for h in shortlist(original, db)])
            evidence = review_shortlist(original, db, top_k=5)
            self.assertTrue(any(edge['retrieval']['path']==str(edit) for edge in evidence['candidate_edges']))
            edit.unlink()  # Simulates an external change, not a tool action.
            self.assertNotIn(str(edit),[h.path for h in shortlist(original,db)])

    def test_sqlite_posting_budget_abstains_before_ranking_not_as_empty(self):
        with tempfile.TemporaryDirectory() as dirname:
            d = Path(dirname)
            root = d / "photos"
            root.mkdir()
            source = textured(225, 350)
            for name in ("one.png", "two.png", "three.png"):
                Image.fromarray(source).save(root / name)
            query = d / "query.png"
            Image.fromarray(source).save(query)
            db = d / "index.sqlite"
            index_folder(root, db, limit=3)
            before = {p.name: p.read_bytes() for p in root.iterdir()}
            terms = sorted(words_for_image(read_grayscale(query, MAX_SIDE)))
            self.assertTrue(terms)
            params = ",".join("?" for _ in terms)
            with closing(open_db(db)) as conn:
                actual = conn.execute(
                    f"SELECT COUNT(*) FROM words WHERE word IN ({params})",
                    terms,
                ).fetchone()[0]
            self.assertGreater(actual, 3)
            with self.assertRaises(PostingBudgetExceeded) as error:
                shortlist(query, db, max_posting_visits=actual - 1)
            self.assertEqual(error.exception.reason, "posting_visits")
            # Same 3 input documents, and visits fit. A separate candidate-
            # cardinality guard must still abstain before GROUP BY ranking.
            with self.assertRaises(PostingBudgetExceeded) as distinct:
                shortlist(query, db, max_posting_visits=actual,
                          max_distinct_documents=2)
            self.assertEqual(distinct.exception.reason, "distinct_documents")
            with self.assertRaises(PostingBudgetExceeded) as review_distinct:
                review_shortlist(query, db, max_posting_visits=actual,
                                 max_distinct_documents=2)
            self.assertEqual(review_distinct.exception.reason, "distinct_documents")
            self.assertIn("search incomplete", str(error.exception))
            with self.assertRaises(PostingBudgetExceeded) as review_error:
                review_shortlist(query, db, max_posting_visits=actual - 1)
            self.assertEqual(review_error.exception.reason, "posting_visits")
            # The boundary is inclusive. A successful shortlist still returns
            # proper human-review candidates rather than bogus empty results.
            hits = shortlist(query, db, max_posting_visits=actual,
                             max_distinct_documents=3, top_k=3)
            self.assertEqual(len(hits), 3)
            self.assertEqual({h.path for h in hits},
                             {str(root / n) for n in ("one.png", "two.png", "three.png")})
            self.assertEqual({p.name: p.read_bytes() for p in root.iterdir()}, before)

    def test_sqlite_budget_rejects_bool_float_and_invalid_ranges(self):
        with tempfile.TemporaryDirectory() as dirname:
            d = Path(dirname)
            db = d / "not-needed.sqlite"
            query = d / "not-needed.png"
            for limit in (True, False, 0, -1, 1.5, 10_000_001):
                with self.subTest(limit=limit):
                    with self.assertRaisesRegex(ValueError, "Posting budget"):
                        shortlist(query, db, max_posting_visits=limit)
                    with self.assertRaisesRegex(ValueError, "Posting budget"):
                        review_shortlist(query, db, max_posting_visits=limit)
            for limit in (True, False, 0, -1, 1.5, 1_000_001):
                with self.subTest(distinct_limit=limit):
                    with self.assertRaisesRegex(ValueError, "Candidate-document budget"):
                        shortlist(query, db, max_distinct_documents=limit)
                    with self.assertRaisesRegex(ValueError, "Candidate-document budget"):
                        review_shortlist(query, db, max_distinct_documents=limit)
            with self.assertRaisesRegex(ValueError, "top_k"):
                shortlist(query, db, top_k=True)


    def test_direct_words_refuse_large_and_invalid_arrays_before_orb(self):
        valid = textured(216, 256)
        large_side = np.broadcast_to(np.zeros((1, 1), np.uint8), (2049, 96))
        large_pixels = np.broadcast_to(np.zeros((1, 1), np.uint8), (2048, 2048))
        invalid = (
            large_side, large_pixels, np.zeros((47, 200), np.uint8),
            np.zeros((200, 47), np.uint8), valid.astype(np.uint16),
            valid[None], np.ma.array(valid), None, "not pixels",
        )
        with patch("tools.photo_lineage.retrieval.cv2.ORB_create",
                   side_effect=AssertionError("ORB called")) as orb:
            for bad in invalid:
                with self.subTest(kind=type(bad).__name__):
                    with self.assertRaisesRegex(ValueError, "bounded"):
                        words_for_image(bad)
            orb.assert_not_called()

    def test_direct_rotated_view_preserves_words_and_source(self):
        source = textured(228, 300)
        original = source.copy()
        rotated = np.rot90(source)
        self.assertFalse(rotated.flags.c_contiguous)
        terms_view = words_for_image(rotated)
        terms_copy = words_for_image(np.ascontiguousarray(rotated))
        self.assertTrue(terms_view)
        self.assertEqual(terms_view, terms_copy)
        self.assertTrue(np.array_equal(source, original))

    def test_file_replaced_during_decode_is_not_cached_with_stale_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            photo_root = folder / "photos"
            photo_root.mkdir()
            original = photo_root / "single.png"
            replacement = folder / "replacement.png"
            Image.fromarray(textured(331, 256)).save(original)
            Image.fromarray(textured(332, 256)).save(replacement)
            old_bytes = original.read_bytes()
            new_bytes = replacement.read_bytes()
            actual_reader = read_grayscale
            swapped = []

            def read_then_swap(path, max_side):
                pixels = actual_reader(path, max_side)
                if not swapped:
                    os.replace(replacement, original)
                    swapped.append(True)
                return pixels

            cache = folder / "cache.sqlite"
            with patch("tools.photo_lineage.retrieval.read_grayscale",
                       side_effect=read_then_swap):
                report = index_folder(photo_root, cache, limit=1)
            self.assertEqual(report["new_or_updated"], 0)
            self.assertEqual(report["total_cached"], 0)
            self.assertEqual(report["feature_postings"], 0)
            self.assertEqual(len(report["errors"]), 1)
            self.assertIn("changed during fingerprint", report["errors"][0]["error"])
            self.assertEqual(original.read_bytes(), new_bytes)
            self.assertNotEqual(old_bytes, original.read_bytes())
            # A subsequent explicit indexing pass processes the stable new
            # file only; the failed pass never contaminated source metadata.
            stable = index_folder(photo_root, cache, limit=1)
            self.assertEqual(stable["new_or_updated"], 1)
            self.assertEqual(stable["total_cached"], 1)

    def test_source_replaced_by_symlink_during_decoder_is_not_indexed(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            photos = folder / "photos"
            photos.mkdir()
            source = photos / "single.png"
            target = folder / "other.png"
            Image.fromarray(textured(512, 256)).save(source)
            Image.fromarray(textured(513, 256)).save(target)
            actual_reader = read_grayscale

            def read_then_alias(path, max_side):
                pixels = actual_reader(path, max_side)
                source.unlink()
                source.symlink_to(target)
                return pixels

            db = folder / "cache.sqlite"
            with patch("tools.photo_lineage.retrieval.read_grayscale",
                       side_effect=read_then_alias):
                result = index_folder(photos, db, limit=1)
            self.assertEqual(result["new_or_updated"], 0)
            self.assertEqual(result["total_cached"], 0)
            self.assertEqual(len(result["errors"]), 1)
            self.assertIn("changed", result["errors"][0]["error"])
            self.assertTrue(source.is_symlink())

    def test_scope_and_symlink_guard(self):
        with tempfile.TemporaryDirectory() as d:
            d=Path(d)
            root=d/'media'; root.mkdir()
            with self.assertRaises(ValueError): index_folder(root,root/'index.sqlite')
            alias=d/'alias'; alias.symlink_to(root,target_is_directory=True)
            with self.assertRaises(ValueError): index_folder(alias,d/'index.sqlite')


if __name__ == '__main__':
    unittest.main()
