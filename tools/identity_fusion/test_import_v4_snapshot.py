"""Offline V4 snapshots only; this cannot read a live phone or upload media."""
from pathlib import Path
import hashlib
import os
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from import_v4_snapshot import load_confirmed_v4_snapshot
from evidence_store import review_candidates


def make_snapshot(path):
    with sqlite3.connect(path) as c:
        c.executescript("""
            CREATE TABLE people(id INTEGER PRIMARY KEY,name TEXT);
            CREATE TABLE images(id INTEGER PRIMARY KEY,path TEXT,
                width INTEGER,height INTEGER,state TEXT);
            CREATE TABLE faces(id INTEGER PRIMARY KEY,image_id INTEGER,
                x REAL,y REAL,w REAL,h REAL,person_id INTEGER,
                review TEXT,ignored INTEGER);
            INSERT INTO people VALUES(7,'Alex Fictional');
            INSERT INTO people VALUES(8,'Alex Fictional');
            INSERT INTO images VALUES
              (1,'/storage/emulated/0/DCIM/Synthetic/one.png',100,100,'done'),
              (2,'/storage/emulated/0/DCIM/Synthetic/error.png',100,100,'error'),
              (3,'/unexpected/inaccessible/three.png',100,100,'done'),
              (4,'/storage/emulated/0/DCIM/Synthetic/outside.png',100,100,'done');
            INSERT INTO faces VALUES
              (101,1,10,10,40,60,7,'confirmed',0),
              (102,1,5,5,20,20,8,'confirmed',0),
              (103,2,1,1,10,10,7,'confirmed',0),
              (104,1,10,10,40,60,7,'auto',0),
              (105,1,10,10,40,60,7,'confirmed',1),
              (106,3,10,10,40,60,7,'confirmed',0),
              (107,4,95,95,30,30,7,'confirmed',0);
        """)


class SourceAdapterTests(unittest.TestCase):
    def test_offline_confirmed_only_and_zero_source_mutation(self):
        with tempfile.TemporaryDirectory(prefix='atlas-v5-v4-import-') as d:
            path = Path(d)/'not-live-v4.sqlite3'
            make_snapshot(path)
            digest = hashlib.sha256(path.read_bytes()).digest()
            store, report = load_confirmed_v4_snapshot(path)
            try:
                self.assertEqual(report['source_confirmed_faces'], 5)
                self.assertEqual(report['imported_review_references'], 2)
                self.assertEqual(report['distinct_local_assets'], 1)
                self.assertEqual(report['skipped_invalid_face_region'], 1)
                self.assertEqual(report['skipped_unsupported_source_path'], 1)
                self.assertTrue(report['read_only_source'])
                self.assertTrue(report['review_only_target'])
                self.assertEqual(
                    store.execute('SELECT COUNT(*) FROM observations').fetchone()[0], 2
                )
                self.assertEqual(
                    store.execute('SELECT COUNT(*) FROM candidate_evidence').fetchone()[0], 2
                )
                # Two identically named humans remain different person IDs.
                self.assertEqual(
                    review_candidates(store,'v4-face:101')['proposals'][0]
                      ['candidate_person_id'],7
                )
                self.assertEqual(
                    review_candidates(store,'v4-face:102')['proposals'][0]
                      ['candidate_person_id'],8
                )
                self.assertEqual(
                    store.execute("SELECT region_w,region_h,occlusion_known "
                                  "FROM observations "
                                  "WHERE observation_uid='v4-face:101'").fetchone(),
                    (.4,.6,0)
                )
                self.assertFalse(
                    store.execute("SELECT COUNT(*) FROM candidate_evidence "
                                  "WHERE observation_uid='v4-face:104'").fetchone()[0]
                )
                self.assertTrue(review_candidates(store,'v4-face:101')
                                ['unknown_always_allowed'])
                self.assertNotIn('Alex Fictional',str(report))
                self.assertNotIn('/storage/emulated/0/',str(report))
            finally:
                store.close()
            self.assertEqual(hashlib.sha256(path.read_bytes()).digest(),digest)
            self.assertFalse(Path(str(path)+'-wal').exists())
            with sqlite3.connect(path) as old:
                self.assertEqual(old.execute('SELECT COUNT(*) FROM faces')
                                 .fetchone()[0],7)

    def test_fractional_edge_drift_skips_only_bad_faces_not_the_snapshot(self):
        # V4's historic +0.01-pixel edge tolerance accepted 100.005
        # on a 100-pixel image, but the V5 evidence store rejects the
        # normalized value 1.00005. An otherwise legitimate snapshot
        # must not abort halfway through, losing unrelated good reviews.
        with tempfile.TemporaryDirectory(prefix='atlas-v5-edge-cases-') as d:
            path = Path(d)/'fictional-v4.sqlite3'
            make_snapshot(path)
            with sqlite3.connect(path) as source:
                source.execute(
                    "INSERT INTO images VALUES(5,?,100,100,'done')",
                    ('/storage/emulated/0/DCIM/Synthetic/invalid-only.jpg',))
                source.executemany(
                    "INSERT INTO faces VALUES(?,?,?,?,?,?,?,?,?)",
                    [
                        # Valid exact full edge: should be imported.
                        (97,1,99.0,99.0,1.0,1.0,7,'confirmed',0),
                        # Formerly passed V4's .01-px permissive check,
                        # then raised from add_observation's actual bounds.
                        (98,1,99.0,40.0,1.005,20.0,7,'confirmed',0),
                        (99,1,1.0,99.0,20.0,1.005,7,'confirmed',0),
                        # Face on another asset: must not leave a blank
                        # orphaned evidence asset when rejected.
                        (100,5,99.0,99.0,1.005,1.0,7,'confirmed',0),
                    ])
            before = hashlib.sha256(path.read_bytes()).digest()
            store, report = load_confirmed_v4_snapshot(path)
            try:
                self.assertEqual(report['source_confirmed_faces'], 9)
                self.assertEqual(report['imported_review_references'], 3)
                self.assertEqual(report['skipped_invalid_face_region'], 4)
                self.assertEqual(report['skipped_unsupported_source_path'], 1)
                self.assertEqual(report['distinct_local_assets'], 1)
                self.assertEqual(store.execute(
                    'SELECT COUNT(*) FROM assets').fetchone()[0], 1)
                self.assertEqual(store.execute(
                    'SELECT COUNT(*) FROM observations').fetchone()[0], 3)
                self.assertEqual(store.execute(
                    'SELECT COUNT(*) FROM candidate_evidence').fetchone()[0], 3)
                self.assertEqual(store.execute(
                    "SELECT region_x,region_y,region_w,region_h "
                    "FROM observations WHERE observation_uid='v4-face:97'"
                ).fetchone(), (.99, .99, .01, .01))
                for face in (98,99,100):
                    self.assertEqual(store.execute(
                        "SELECT COUNT(*) FROM observations WHERE observation_uid=?",
                        ('v4-face:'+str(face),)
                    ).fetchone()[0], 0)
                self.assertTrue(
                    review_candidates(store,'v4-face:97')['unknown_always_allowed'])
                self.assertFalse(
                    review_candidates(store,'v4-face:101')['auto_confirm_allowed'])
                self.assertNotIn('invalid-only.jpg', str(report))
            finally:
                store.close()
            self.assertEqual(hashlib.sha256(path.read_bytes()).digest(), before)
            self.assertFalse(Path(str(path)+'-wal').exists())

    def test_snapshot_aliases_nonregular_and_oversized_source_fail_closed(self):
        # In particular a symlink to a live V4 DB must never bypass the
        # no-WAL-sidecar check by presenting an innocent alternate name.
        from import_v4_snapshot import MAX_OFFLINE_SNAPSHOT_BYTES
        with tempfile.TemporaryDirectory(prefix='synthetic-v4-alias-') as d:
            root = Path(d)
            realdir = root/'snapshot'
            realdir.mkdir()
            source = realdir/'fictional-v4.sqlite3'
            make_snapshot(source)
            expected = source.read_bytes()
            alias = root/'innocent-copy.sqlite3'
            alias.symlink_to(source)
            with self.assertRaises(ValueError):
                load_confirmed_v4_snapshot(alias)
            alias.unlink()
            parent_alias = root/'disguised-folder'
            parent_alias.symlink_to(realdir, target_is_directory=True)
            with self.assertRaises(ValueError):
                load_confirmed_v4_snapshot(
                    parent_alias/'fictional-v4.sqlite3')
            parent_alias.unlink()
            os.link(source, alias)
            with self.assertRaises(ValueError):
                load_confirmed_v4_snapshot(source)
            alias.unlink()
            oversized = root/'oversized.sqlite3'
            with oversized.open('wb') as handle:
                handle.write(b'SQLite format 3\x00')
                # Sparse; metadata only. Refused before reading even a
                # single 512-MiB artificial file body into Python RAM.
                handle.truncate(MAX_OFFLINE_SNAPSHOT_BYTES + 1)
            with self.assertRaises(ValueError):
                load_confirmed_v4_snapshot(oversized)
            malformed = root/'bad-header.sqlite3'
            malformed.write_bytes(b'not a SQLite DB' + b'0' * 128)
            with self.assertRaises(ValueError):
                load_confirmed_v4_snapshot(malformed)
            self.assertEqual(source.read_bytes(), expected)

    def test_source_path_replaced_after_pinned_read_does_not_change_import(self):
        # The old mode=ro&immutable=1 URI would reopen a pathname AFTER
        # its is_file/stat checks. Deserialize must query only the pinned
        # original bytes, never a different DB swapped in at that path.
        from import_v4_snapshot import _read_fixed_offline_snapshot
        with tempfile.TemporaryDirectory(prefix='synthetic-v4-race-') as d:
            root=Path(d)
            source=root/'snapshot.sqlite3'
            make_snapshot(source)
            original=source.read_bytes()
            archival=root/'archived-original.sqlite3'
            real_reader=_read_fixed_offline_snapshot

            def substitute_after_read(path):
                payload=real_reader(path)
                source.rename(archival)
                make_snapshot(source)
                with sqlite3.connect(source) as changed:
                    changed.execute("DELETE FROM faces")
                return payload

            with patch('import_v4_snapshot._read_fixed_offline_snapshot',
                       side_effect=substitute_after_read):
                store, report=load_confirmed_v4_snapshot(source)
            try:
                self.assertEqual(report['source_confirmed_faces'],5)
                self.assertEqual(report['imported_review_references'],2)
                self.assertEqual(store.execute(
                    'SELECT COUNT(*) FROM candidate_evidence').fetchone()[0],2)
                self.assertFalse(review_candidates(
                    store,'v4-face:101')['auto_confirm_allowed'])
            finally:
                store.close()
            self.assertEqual(source.stat().st_size > 100, True)
            self.assertEqual(archival.read_bytes(),original)
            with sqlite3.connect(source) as altered:
                self.assertEqual(altered.execute(
                    'SELECT COUNT(*) FROM faces').fetchone()[0],0)

    def test_refuse_inconsistent_live_wal_and_wrong_schema(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'v4.sqlite3'
            make_snapshot(path)
            wal=Path(str(path)+'-wal')
            wal.write_bytes(b'pretend in-progress WAL')
            with self.assertRaises(ValueError):
                load_confirmed_v4_snapshot(path)
            wal.unlink()
            with self.assertRaises(ValueError):
                load_confirmed_v4_snapshot(path,max_faces=2)
            path.unlink()
            with sqlite3.connect(path) as c:
                c.execute('CREATE TABLE assignments(face_id INTEGER PRIMARY KEY)')
            with self.assertRaises(ValueError):
                load_confirmed_v4_snapshot(path)


if __name__=='__main__':
    unittest.main(verbosity=2)
