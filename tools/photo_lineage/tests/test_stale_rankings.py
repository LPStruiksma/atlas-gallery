"""Synthetic cache-integrity and stale-retrieval regressions, no personal media."""
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from tools.photo_lineage.benchmark import textured
from tools.photo_lineage.retrieval import audit_cache, index_folder, open_db, shortlist


class StaleRankingTest(unittest.TestCase):
    def test_stale_high_ranked_hits_cannot_hide_existing_original(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            images = base / "synthetic"
            images.mkdir()
            source = textured(989, 300)
            for i in range(12):
                Image.fromarray(source).save(images / f"same_{i:02d}.png")
            query = base / "query.png"
            Image.fromarray(source).save(query)
            cache = base / "index.sqlite"
            self.assertEqual(index_folder(images, cache, limit=20)["total_cached"], 12)
            initial_ranking = shortlist(query, cache, top_k=12)
            self.assertEqual(len(initial_ranking), 12)
            # Mimic external deletion of ten high-ranked files; leave two
            # valid copies hidden behind stale top-ranked postings.
            for hit in initial_ranking[:10]:
                Path(hit.path).unlink()
            remaining = {hit.path for hit in initial_ranking[10:]}
            valid = shortlist(query, cache, top_k=1)
            self.assertEqual(len(valid), 1)
            self.assertIn(valid[0].path, remaining)


class DatabaseSafetyTest(unittest.TestCase):
    def test_existing_unrelated_database_is_not_modified(self):
        import sqlite3
        from contextlib import closing
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'other.sqlite'
            with closing(sqlite3.connect(path)) as conn, conn:
                conn.execute('CREATE TABLE sensitive_values (value TEXT)')
                conn.execute('INSERT INTO sensitive_values VALUES (?)', ('preserved',))
            original = path.read_bytes()
            with self.assertRaisesRegex(ValueError, 'nonempty'):
                open_db(path, write=True)
            self.assertEqual(path.read_bytes(), original)

    def test_fingerprint_mismatch_refuses_query_and_update(self):
        import sqlite3
        from contextlib import closing
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            images = base / 'photos'
            images.mkdir()
            source = images / 'a.png'
            Image.fromarray(textured(1000, 300)).save(source)
            query = base / 'query.png'
            Image.fromarray(textured(1000, 300)).save(query)
            db = base / 'cache.sqlite'
            index_folder(images, db)
            with closing(sqlite3.connect(db)) as conn, conn:
                conn.execute("UPDATE metadata SET value = 'other-algorithm' WHERE key='fingerprint'")
            with self.assertRaisesRegex(ValueError, 'Fingerprint version mismatch'):
                shortlist(query, db)
            with self.assertRaisesRegex(ValueError, 'Fingerprint version mismatch'):
                index_folder(images, db)


class CacheAuditTest(unittest.TestCase):
    def test_dry_run_apply_and_image_preservation(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            media = base / 'media'
            media.mkdir()
            unchanged = media / 'unchanged.png'
            edited = media / 'externally_edited.png'
            removed = media / 'externally_removed.png'
            for p, seed in ((unchanged, 1800), (edited, 1801), (removed, 1802)):
                Image.fromarray(textured(seed, 300)).save(p)
            db = base / 'cache.sqlite'
            index_folder(media, db)
            original_bytes = unchanged.read_bytes()
            Image.fromarray(textured(1901, 310)).save(edited)
            edited_bytes = edited.read_bytes()
            removed.unlink()
            dry = audit_cache(db)
            self.assertEqual(dry['stale'], 2)
            self.assertEqual(dry['removed_from_cache'], 0)
            self.assertEqual(audit_cache(db, apply=True)['removed_from_cache'], 2)
            self.assertEqual(audit_cache(db)['stale'], 0)
            self.assertEqual(unchanged.read_bytes(), original_bytes)
            self.assertEqual(edited.read_bytes(), edited_bytes)
            self.assertEqual(index_folder(media, db)['new_or_updated'], 1)

    def test_guard_prevents_partial_audit_on_too_many_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            media = base / 'media'
            media.mkdir()
            for i in range(3):
                Image.fromarray(textured(3000 + i, 256)).save(media / f'{i}.png')
            db = base / 'cache.sqlite'
            index_folder(media, db)
            with self.assertRaisesRegex(ValueError, 'exceeds audit limit'):
                audit_cache(db, apply=True, max_entries=2)
            self.assertEqual(audit_cache(db)['examined'], 3)


if __name__ == '__main__':
    unittest.main()


class CacheNoncreationTest(unittest.TestCase):
    def test_audit_apply_refuses_missing_database_without_creating_it(self):
        with tempfile.TemporaryDirectory() as d:
            db = Path(d) / 'missing.sqlite'
            with self.assertRaisesRegex(ValueError, 'Existing local index'):
                audit_cache(db, apply=True)
            self.assertFalse(db.exists())
