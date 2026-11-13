"""Pure numeric end-to-end immutable catalog safety tests; no photographs."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

from tools.photo_lineage.delta_overlay import shortlist_with_updates
from tools.photo_lineage.external_index import build_snapshot
from tools.photo_lineage.generation_catalog import publish_catalog, verify_catalog
from tools.photo_lineage.immutable_delta import publish_delta
from tools.photo_lineage.snapshot_receipt import publish_receipt


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.base = self.folder/'snapshot.u64'
        built = build_snapshot([(0, [1, 2, 3]), (1, [1, 2, 4]), (2, [7, 8])],
                               self.base, max_postings=8)
        self.receipt = self.folder/'snapshot.json'
        self.receipt_info = publish_receipt(self.base, self.receipt,
                                            generation='base1',
                                            expected_snapshot_sha256=built['sha256'])
        self.parent = self.receipt_info['receipt_sha256']
        self.catalog = self.folder/'version-1.catalog.json'

    def segments(self):
        first = publish_delta(self.folder/'first.delta.json', {0: [7, 8], 1: None},
                              parent_sha256=self.parent, sequence=1)
        second = publish_delta(self.folder/'second.delta.json', {0: None, 1: [7, 8], 3: [1, 2]},
                               parent_sha256=first['segment_sha256'], sequence=2)
        return [['first.delta.json', first['segment_sha256']],
                ['second.delta.json', second['segment_sha256']]]

    def publish(self, segments=None):
        return publish_catalog(self.catalog, generation='index1',
                               base_receipt_name=self.receipt.name,
                               base_receipt_sha256=self.parent,
                               segments=[] if segments is None else segments)

    def test_full_catalog_verified_and_numeric_query_parity(self):
        roster = self.segments()
        pub = self.publish(roster)
        verified = verify_catalog(self.catalog, pub['catalog_sha256'])
        self.assertTrue(verified['verified'])
        self.assertEqual(verified['segments'], 2)
        self.assertEqual(verified['updated_documents'], 3)
        self.assertEqual(verified['updates'][0], None)
        self.assertEqual(verified['updates'][1], frozenset({7, 8}))
        before = self.base.read_bytes()
        mapped = np.memmap(self.base, dtype='<u8', mode='r')
        self.assertEqual(shortlist_with_updates(mapped, {1, 2}, verified['updates']), [(3, 2)])
        self.assertEqual(shortlist_with_updates(mapped, {7, 8}, verified['updates']), [(1, 2), (2, 2)])
        del mapped
        self.assertEqual(self.base.read_bytes(), before)
        if os.name == 'posix':
            self.assertEqual(self.catalog.stat().st_mode & 0o777, 0o600)

    def test_base_only_catalog_valid_and_nonactivating(self):
        pub = self.publish()
        report = verify_catalog(self.catalog, pub['catalog_sha256'])
        self.assertEqual(report['segments'], 0)
        self.assertEqual(report['updates'], {})
        self.assertEqual(report['generation'], 'index1')
        self.assertEqual(set(x.name for x in self.folder.iterdir()),
                         {'snapshot.u64', 'snapshot.json', 'version-1.catalog.json'})

    def test_missing_or_corrupted_tail_refuses_complete_catalog(self):
        roster = self.segments()
        pub = self.publish(roster)
        (self.folder/'second.delta.json').unlink()  # Disposable numeric fixture only.
        with self.assertRaises((ValueError, OSError)):
            verify_catalog(self.catalog, pub['catalog_sha256'])
        self.assertEqual(self.base.stat().st_size, 8*8)
        self.assertTrue(self.receipt.exists())

    def test_altered_segment_roster_cannot_match_pinned_catalog(self):
        roster = self.segments()
        pub = self.publish(roster)
        obj = json.loads(self.catalog.read_text())
        obj['segments'] = obj['segments'][:1]  # Deliberately omit an existing segment.
        raw = (json.dumps(obj, sort_keys=True, separators=(',', ':')) + '\n').encode()
        self.catalog.write_bytes(raw)
        with self.assertRaisesRegex(ValueError, 'trusted digest'):
            verify_catalog(self.catalog, pub['catalog_sha256'])

    def test_wrong_parent_and_wrong_sequence_fail_before_publication(self):
        roster = self.segments()
        bad_roster = [['second.delta.json', roster[1][1]]]
        with self.assertRaisesRegex(ValueError, 'sequence|parent'):
            self.publish(bad_roster)
        with self.assertRaisesRegex(ValueError, 'checksum|digest'):
            publish_catalog(self.catalog, generation='index1',
                            base_receipt_name='snapshot.json',
                            base_receipt_sha256='f'*64, segments=roster)
        self.assertFalse(self.catalog.exists())

    def test_tampered_baseline_receipt_fails_even_if_catalog_still_pinned(self):
        pub = self.publish()
        self.receipt.write_bytes(self.receipt.read_bytes() + b'x')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            verify_catalog(self.catalog, pub['catalog_sha256'])

    def test_duplicate_names_and_unsafe_roster_paths_refused(self):
        roster = self.segments()
        for bad in (
            roster + [roster[0]],
            [['../elsewhere.delta.json', roster[0][1]]],
            [['snapshot.json', roster[0][1]]],
            [['something.delta.json', 'not-a-pin']],
        ):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                self.publish(bad)
        self.assertFalse(self.catalog.exists())

    def test_existing_catalog_never_overwritten_and_old_remains_usable(self):
        base_only = self.publish()
        raw = self.catalog.read_bytes()
        with self.assertRaises(FileExistsError):
            self.publish(self.segments())
        self.assertEqual(self.catalog.read_bytes(), raw)
        self.assertTrue(verify_catalog(self.catalog, base_only['catalog_sha256'])['verified'])

    def test_competing_publication_leaves_no_own_scratch(self):
        def concurrent_writer(source, destination, *, follow_symlinks):
            Path(destination).write_bytes(b'competing protected catalog')
            raise FileExistsError('another writer won')
        with patch('tools.photo_lineage.generation_catalog.os.link',
                   side_effect=concurrent_writer):
            with self.assertRaises(FileExistsError):
                self.publish()
        self.assertEqual(self.catalog.read_bytes(), b'competing protected catalog')
        self.assertFalse(list(self.folder.glob('.atlas-catalog-*')))

    def test_symlink_and_duplicate_json_fields_refused(self):
        pub = self.publish()
        alias = self.folder/'alias.catalog.json'
        alias.symlink_to(self.catalog)
        with self.assertRaisesRegex(ValueError, 'non-symlink'):
            verify_catalog(alias, pub['catalog_sha256'])
        corrupted = self.catalog.read_bytes().replace(b'"schema":1',
                                                      b'"schema":1,"schema":1')
        self.catalog.write_bytes(corrupted)
        with self.assertRaisesRegex(ValueError, 'Malformed generation catalog'):
            verify_catalog(self.catalog, digest(corrupted))


if __name__ == '__main__':
    unittest.main()
