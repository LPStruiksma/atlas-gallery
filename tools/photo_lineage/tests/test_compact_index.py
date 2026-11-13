"""Copy-on-write compaction tests on disposable numeric index generations only."""
from __future__ import annotations

import errno
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

from tools.photo_lineage.compact_index import compact_verified_generation
from tools.photo_lineage.external_index import build_snapshot, verify_snapshot
from tools.photo_lineage.generation_catalog import publish_catalog, verify_catalog
from tools.photo_lineage.immutable_delta import publish_delta
from tools.photo_lineage.snapshot_receipt import publish_receipt


class CompactIndexTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'old.u64'
        self.base = [(0, [1, 2, 3]), (1, [1, 2, 4]), (2, [7, 8])]
        self.base_result = build_snapshot(self.base, self.source, max_postings=8)
        self.receipt = self.root / 'old.json'
        self.base_receipt = publish_receipt(
            self.source, self.receipt, generation='old',
            expected_snapshot_sha256=self.base_result['sha256'],
        )
        self.catalog = self.root / 'old.catalog.json'
        self.destination = self.root / 'new.u64'

    def stage(self, layers=None):
        if layers is None:
            layers = [{0: [7, 8], 1: None, 3: [1, 2]},
                      {0: None, 1: [7, 8], 3: [1, 2]}]
        prev = self.base_receipt['receipt_sha256']
        roster = []
        for n, changes in enumerate(layers, start=1):
            path = self.root / f'delta-{n}.delta.json'
            receipt = publish_delta(path, changes, parent_sha256=prev, sequence=n)
            prev = receipt['segment_sha256']
            roster.append((path.name, prev))
        info = publish_catalog(
            self.catalog, generation='old_with_deltas',
            base_receipt_name=self.receipt.name,
            base_receipt_sha256=self.base_receipt['receipt_sha256'],
            segments=roster,
        )
        return info['catalog_sha256']

    def compact(self, pin, **kwargs):
        return compact_verified_generation(
            self.source, self.catalog, self.destination,
            trusted_catalog_sha256=pin, **kwargs,
        )

    def postings(self, path):
        data = path.read_bytes()
        self.assertEqual(len(data) % 8, 0)
        return [x[0] for x in struct.iter_unpack('<Q', data)]

    def test_full_compaction_matches_exhaustive_reference(self):
        pin = self.stage()
        immutable_before = self.source.read_bytes()
        result = self.compact(pin)
        self.assertTrue(result['published'])
        self.assertEqual(result['changed_document_ids'], 3)
        self.assertEqual(result['new_postings'], 6)
        expected = sorted([(7 << 32) | 1, (8 << 32) | 1,
                           (7 << 32) | 2, (8 << 32) | 2,
                           (1 << 32) | 3, (2 << 32) | 3])
        self.assertEqual(self.postings(self.destination), expected)
        self.assertTrue(verify_snapshot(self.destination, result['new_snapshot_sha256'])['verified'])
        self.assertEqual(self.source.read_bytes(), immutable_before)
        self.assertTrue(verify_catalog(self.catalog, pin)['verified'])

    def test_replace_and_delete_all_old_postings(self):
        pin = self.stage([{0: None, 1: [], 2: None}])
        result = self.compact(pin)
        self.assertEqual(result['new_postings'], 0)
        self.assertEqual(self.destination.read_bytes(), b'')
        self.assertTrue(verify_snapshot(self.destination, result['new_snapshot_sha256'])['verified'])

    def test_unchanged_baseline_and_added_document(self):
        pin = self.stage([{0: [9], 3: [7, 8]}])
        result = self.compact(pin)
        actual = self.postings(self.destination)
        expected = sorted([(9 << 32),
                           (1 << 32) | 1, (2 << 32) | 1, (4 << 32) | 1,
                           (7 << 32) | 2, (8 << 32) | 2,
                           (7 << 32) | 3, (8 << 32) | 3])
        self.assertEqual(actual, expected)
        self.assertEqual(result['new_postings'], 8)

    def test_existing_output_never_overwritten(self):
        pin = self.stage()
        self.destination.write_bytes(b'preexisting private data')
        before = self.destination.read_bytes()
        with self.assertRaises(FileExistsError):
            self.compact(pin)
        self.assertEqual(self.destination.read_bytes(), before)
        self.assertFalse(list(self.root.glob('.atlas-compact-*')))

    def test_untrusted_catalog_digest_rejected_before_writing(self):
        pin = self.stage()
        with self.assertRaisesRegex(ValueError, 'trusted digest'):
            self.compact('f' * 64)
        self.assertFalse(self.destination.exists())
        self.assertTrue(verify_catalog(self.catalog, pin)['verified'])

    def test_missing_delta_tail_rejected_without_new_index(self):
        pin = self.stage()
        (self.root / 'delta-2.delta.json').unlink()  # Synthetic-only fixture.
        with self.assertRaises((ValueError, OSError)):
            self.compact(pin)
        self.assertFalse(self.destination.exists())
        self.assertTrue(verify_snapshot(self.source, self.base_result['sha256'])['verified'])

    def test_low_space_admission_preserves_old_files(self):
        pin = self.stage()
        original = self.source.read_bytes()
        with patch('tools.photo_lineage.compact_index._require_disk_headroom',
                   side_effect=OSError(errno.ENOSPC, 'disk budget exhausted')):
            with self.assertRaises(OSError):
                self.compact(pin)
        self.assertFalse(self.destination.exists())
        self.assertEqual(self.source.read_bytes(), original)
        self.assertFalse(list(self.root.glob('.atlas-compact-*')))

    def test_write_failure_does_not_publish_or_delete_old(self):
        pin = self.stage()
        old_bytes = self.source.read_bytes()
        with patch('tools.photo_lineage.compact_index._write_records',
                   side_effect=OSError(errno.ENOSPC, 'No space left on device')):
            with self.assertRaises(OSError) as error:
                self.compact(pin)
        self.assertEqual(error.exception.errno, errno.ENOSPC)
        self.assertFalse(self.destination.exists())
        self.assertFalse(list(self.root.glob('.atlas-compact-*')))
        self.assertEqual(self.source.read_bytes(), old_bytes)

    def test_changed_source_during_compaction_refuses_publish(self):
        pin = self.stage()
        from tools.photo_lineage import compact_index
        original_reader = compact_index._read_records
        def modified_reader(path):
            for row in original_reader(path):
                yield row
            if Path(path) == self.source:
                self.source.write_bytes(self.source.read_bytes() + b'tamper')
        with patch('tools.photo_lineage.compact_index._read_records', side_effect=modified_reader):
            with self.assertRaisesRegex(ValueError, 'aligned|digest'):
                self.compact(pin)
        self.assertFalse(self.destination.exists())
        self.assertFalse(list(self.root.glob('.atlas-compact-*')))

    def test_budget_bounds_fail_without_partial_publication(self):
        pin = self.stage()
        with self.assertRaisesRegex(ValueError, 'budget'):
            self.compact(pin, max_output_bytes=8)
        for bad in (-1, True, 2.4, None):
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, 'free-space reserve'):
                self.compact(pin, reserve_free_bytes=bad)
        self.assertFalse(self.destination.exists())

    def test_base_only_catalog_requires_no_compaction(self):
        pin = self.stage([])
        with self.assertRaisesRegex(ValueError, 'No deltas'):
            self.compact(pin)
        self.assertFalse(self.destination.exists())


if __name__ == '__main__':
    unittest.main()
