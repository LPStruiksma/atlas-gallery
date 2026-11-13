"""Synthetic numeric posting fixtures only; no personal photo or phone data."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

from tools.photo_lineage.snapshot_receipt import publish_receipt, verify_receipt, verify_with_fallback


def sha(content):
    return hashlib.sha256(content).hexdigest()


class SnapshotReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.index = self.folder / 'gen1.u64'
        self.receipt = self.folder / 'gen1.json'
        self.bytes = struct.pack('<QQQQ', 1 << 32, (1 << 32) | 3, 2 << 32, (2 << 32) | 2)
        self.index.write_bytes(self.bytes)
        self.source_digest = sha(self.bytes)

    def publish(self):
        return publish_receipt(self.index, self.receipt, generation='generation_1',
                               expected_snapshot_sha256=self.source_digest)

    def test_round_trip_private_immutable_receipt_and_no_original_mutation(self):
        before = self.index.read_bytes()
        result = self.publish()
        report = verify_receipt(self.receipt, result['receipt_sha256'])
        self.assertTrue(report['verified'])
        self.assertEqual(report['bytes'], len(before))
        self.assertEqual(report['postings'], 4)
        self.assertEqual(report['generation'], 'generation_1')
        self.assertEqual(self.index.read_bytes(), before)
        self.assertEqual(set(x.name for x in self.folder.iterdir()), {'gen1.u64', 'gen1.json'})
        if os.name == 'posix':
            self.assertEqual(self.receipt.stat().st_mode & 0o777, 0o600)

    def test_existing_receipt_preserved_without_overwrite(self):
        self.receipt.write_bytes(b'Unrelated personal file; preserve this content.')
        before = self.receipt.read_bytes()
        with self.assertRaises(FileExistsError):
            self.publish()
        self.assertEqual(self.receipt.read_bytes(), before)

    def test_unpinned_and_mismatching_snapshot_refused_without_receipt(self):
        with self.assertRaisesRegex(ValueError, 'independently pinned'):
            publish_receipt(self.index, self.receipt, generation='g1', expected_snapshot_sha256='bad')
        with self.assertRaisesRegex(ValueError, 'digest'):
            publish_receipt(self.index, self.receipt, generation='g1', expected_snapshot_sha256='0'*64)
        self.assertFalse(self.receipt.exists())

    def test_failed_verifier_produces_no_partial_receipt(self):
        with patch('tools.photo_lineage.snapshot_receipt.verify_snapshot', side_effect=ValueError('Index rejected')):
            with self.assertRaisesRegex(ValueError, 'Index rejected'):
                self.publish()
        self.assertEqual([p.name for p in self.folder.iterdir()], ['gen1.u64'])

    def test_receipt_tamper_and_snapshot_tamper_fail_closed(self):
        data = self.publish()
        self.receipt.write_bytes(self.receipt.read_bytes() + b'\n')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            verify_receipt(self.receipt, data['receipt_sha256'])
        self.assertEqual(self.index.read_bytes(), self.bytes)
        self.receipt.write_bytes(self.receipt.read_bytes()[:-1])
        self.index.write_bytes(self.bytes + struct.pack('<Q', (4 << 32) | 1))
        with self.assertRaisesRegex(ValueError, 'digest'):
            verify_receipt(self.receipt, data['receipt_sha256'])

    def test_traversal_cannot_escape_receipt_directory_even_with_matching_pin(self):
        self.publish()
        obj = json.loads(self.receipt.read_text())
        obj['file'] = '../outside.u64'
        tampered = (json.dumps(obj, sort_keys=True, separators=(',', ':')) + '\n').encode()
        self.receipt.write_bytes(tampered)
        with self.assertRaisesRegex(ValueError, 'basename'):
            verify_receipt(self.receipt, sha(tampered))

    def test_duplicate_json_keys_refused_even_with_matching_pin(self):
        self.publish()
        content = self.receipt.read_bytes()
        edited = content.replace(b'"schema":1', b'"schema":1,"schema":1')
        self.receipt.write_bytes(edited)
        with self.assertRaisesRegex(ValueError, 'Duplicate JSON'):
            verify_receipt(self.receipt, sha(edited))

    def test_symlink_and_size_limit_rejected(self):
        data = self.publish()
        symlink = self.folder / 'alias.json'
        symlink.symlink_to(self.receipt)
        with self.assertRaisesRegex(ValueError, 'non-symlink'):
            verify_receipt(symlink, data['receipt_sha256'])
        with self.assertRaisesRegex(ValueError, 'budget'):
            verify_receipt(self.receipt, data['receipt_sha256'], max_index_bytes=8)
        self.assertEqual(len(list(self.folder.glob('.atlas-receipt-*'))), 0)

    def test_cross_directory_and_invalid_generation_refused(self):
        elsewhere = self.folder / 'other'
        elsewhere.mkdir()
        with self.assertRaisesRegex(ValueError, 'same private parent'):
            publish_receipt(self.index, elsewhere/'gen1.json', generation='g1',
                            expected_snapshot_sha256=self.source_digest)
        with self.assertRaisesRegex(ValueError, 'generation'):
            publish_receipt(self.index, self.receipt, generation='../unsafe',
                            expected_snapshot_sha256=self.source_digest)
        self.assertFalse(self.receipt.exists())

    def test_link_publication_race_cleans_scratch_and_preserves_other_writer(self):
        def concurrent_writer(source, target, *, follow_symlinks):
            Path(target).write_bytes(b'concurrent immutable publication')
            raise FileExistsError('another writer claimed the destination')
        with patch('tools.photo_lineage.snapshot_receipt.os.link', side_effect=concurrent_writer):
            with self.assertRaises(FileExistsError):
                self.publish()
        self.assertEqual(self.receipt.read_bytes(), b'concurrent immutable publication')
        self.assertEqual(len(list(self.folder.glob('.atlas-receipt-*'))), 0)
        self.assertEqual(self.index.read_bytes(), self.bytes)

    def test_oversize_pinned_receipt_rejected_without_parsing(self):
        too_big = b'{' + b' ' * 5000 + b'}'
        self.receipt.write_bytes(too_big)
        with self.assertRaisesRegex(ValueError, 'bounded regular file'):
            verify_receipt(self.receipt, sha(too_big))
        self.assertEqual(self.receipt.read_bytes(), too_big)

    def test_fallback_not_used_when_primary_valid(self):
        primary = self.publish()
        result = verify_with_fallback(self.receipt, primary['receipt_sha256'],
                                      self.folder/'missing_fallback.json', 'f'*64)
        self.assertTrue(result['verified'])
        self.assertFalse(result['fallback_used'])
        self.assertEqual(result['primary_status'], 'verified')

    def test_corrupt_primary_exposes_recovery_with_pinned_fallback(self):
        original = self.publish()
        backup = self.folder/'old.json'
        old = publish_receipt(self.index, backup, generation='g_old',
                              expected_snapshot_sha256=self.source_digest)
        self.receipt.write_bytes(b'invalid new generation')
        before_backup = backup.read_bytes()
        status = verify_with_fallback(self.receipt, original['receipt_sha256'],
                                      backup, old['receipt_sha256'])
        self.assertTrue(status['fallback_used'])
        self.assertEqual(status['primary_status'], 'unusable')
        self.assertEqual(status['generation'], 'g_old')
        self.assertIn(status['primary_failure_type'], ('ValueError', 'OSError'))
        self.assertEqual(backup.read_bytes(), before_backup)
        self.assertEqual(self.index.read_bytes(), self.bytes)

    def test_both_invalid_fail_closed(self):
        fresh = self.publish()
        with self.assertRaisesRegex(ValueError, 'Neither independently pinned'):
            verify_with_fallback(self.receipt, '0'*64, self.folder/'missing.json', 'f'*64)
        with self.assertRaisesRegex(ValueError, 'distinct'):
            verify_with_fallback(self.receipt, fresh['receipt_sha256'], self.receipt, fresh['receipt_sha256'])

    def test_metadata_count_tamper_caught_even_with_matching_receipt_digest(self):
        self.publish()
        obj = json.loads(self.receipt.read_text())
        obj['postings'] = 5
        tampered = (json.dumps(obj, sort_keys=True, separators=(',', ':')) + '\n').encode()
        self.receipt.write_bytes(tampered)
        with self.assertRaisesRegex(ValueError, 'byte and posting'):
            verify_receipt(self.receipt, sha(tampered))


if __name__ == '__main__':
    unittest.main()
