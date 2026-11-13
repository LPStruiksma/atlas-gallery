"""Immutable delta chain failure injection; pure numeric synthetic fixtures."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import os
import tempfile
import unittest
from unittest.mock import patch

from tools.photo_lineage.benchmark_packed_index import packed_postings
from tools.photo_lineage.delta_overlay import shortlist_with_updates
from tools.photo_lineage.immutable_delta import (
    publish_delta, verify_delta, read_delta_chain, MAX_SEGMENT_BYTES,
)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class ImmutableDeltaTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)
        self.parent = 'a' * 64

    def publish(self, filename='change-1.delta.json', updates=None, *,
                sequence=1, parent=None):
        if updates is None:
            updates = {0: [7, 8], 1: None}
        return publish_delta(self.folder / filename, updates,
                             parent_sha256=parent or self.parent, sequence=sequence)

    def test_single_segment_roundtrip_and_private_file(self):
        published = self.publish()
        path = self.folder / 'change-1.delta.json'
        checked = verify_delta(path, published['segment_sha256'],
                               expected_parent_sha256=self.parent, expected_sequence=1)
        self.assertEqual(checked['updates'], {0: frozenset({7, 8}), 1: None})
        self.assertEqual(checked['changed_documents'], 2)
        self.assertEqual(published['parent_sha256'], self.parent)
        self.assertEqual(set(self.folder.iterdir()), {path})
        if os.name == 'posix':
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_two_segments_replay_and_exact_shortlist_parity(self):
        original = packed_postings([{1, 2, 3}, {1, 2, 4}, {7, 8}])
        original_bytes = original.tobytes()
        first = self.publish(updates={0: [7, 8], 1: None})
        second = self.publish('change-2.delta.json',
                              updates={0: None, 1: [7, 8], 3: [1, 2]},
                              sequence=2, parent=first['segment_sha256'])
        chain = read_delta_chain([
            (self.folder / 'change-1.delta.json', first['segment_sha256']),
            (self.folder / 'change-2.delta.json', second['segment_sha256'])
        ], base_receipt_sha256=self.parent)
        self.assertTrue(chain['verified'])
        self.assertEqual(chain['changed_documents'], 3)
        self.assertEqual(chain['updates'], {0: None, 1: frozenset({7, 8}), 3: frozenset({1, 2})})
        self.assertEqual(shortlist_with_updates(original, {7, 8}, chain['updates']), [(1, 2), (2, 2)])
        self.assertEqual(shortlist_with_updates(original, {1, 2}, chain['updates']), [(3, 2)])
        self.assertEqual(original.tobytes(), original_bytes)

    def test_wrong_parent_and_reordering_refused(self):
        first = self.publish()
        second = self.publish('change-2.delta.json', updates={0: None},
                              sequence=2, parent=first['segment_sha256'])
        with self.assertRaisesRegex(ValueError, 'parent'):
            verify_delta(self.folder / 'change-1.delta.json', first['segment_sha256'],
                         expected_parent_sha256='b'*64, expected_sequence=1)
        with self.assertRaisesRegex(ValueError, 'sequence|parent'):
            read_delta_chain([
                (self.folder/'change-2.delta.json', second['segment_sha256']),
                (self.folder/'change-1.delta.json', first['segment_sha256']),
            ], base_receipt_sha256=self.parent)
        with self.assertRaisesRegex(ValueError, 'sequence'):
            verify_delta(self.folder/'change-2.delta.json', second['segment_sha256'],
                         expected_parent_sha256=first['segment_sha256'], expected_sequence=1)

    def test_tampered_segment_or_changed_pin_cannot_replay(self):
        first = self.publish()
        path = self.folder/'change-1.delta.json'
        path.write_bytes(path.read_bytes() + b'x')
        with self.assertRaisesRegex(ValueError, 'digest'):
            verify_delta(path, first['segment_sha256'],
                         expected_parent_sha256=self.parent, expected_sequence=1)
        with self.assertRaisesRegex(ValueError, 'digest'):
            read_delta_chain([(path, first['segment_sha256'])],
                             base_receipt_sha256=self.parent)

    def test_duplicate_json_entries_and_unsorted_words_rejected_even_if_pinned(self):
        first = self.publish()
        path = self.folder/'change-1.delta.json'
        original = json.loads(path.read_text())
        for edited in (
            dict(original, updates=[[0, [8, 7]], [1, None]]),
            dict(original, updates=[[0, [7]], [0, None]]),
            dict(original, updates=[[0, [7, 7]], [1, None]]),
            dict(original, updates=[[0, [1 << 26]]]),
        ):
            raw = (json.dumps(edited, sort_keys=True, separators=(',', ':')) + '\n').encode()
            path.write_bytes(raw)
            with self.subTest(edited=edited['updates']), self.assertRaises(ValueError):
                verify_delta(path, digest(raw), expected_parent_sha256=self.parent,
                             expected_sequence=1)
        duplicate_field = path.read_bytes().replace(b'"schema":1', b'"schema":1,"schema":1')
        path.write_bytes(duplicate_field)
        with self.assertRaisesRegex(ValueError, 'Malformed delta'):
            verify_delta(path, digest(duplicate_field),
                         expected_parent_sha256=self.parent, expected_sequence=1)

    def test_existing_segment_and_symlink_preserved(self):
        old = self.folder/'change-1.delta.json'
        old.write_bytes(b'private preexisting record')
        with self.assertRaises(FileExistsError):
            self.publish()
        self.assertEqual(old.read_bytes(), b'private preexisting record')
        alias = self.folder/'alias.delta.json'
        alias.symlink_to(old)
        with self.assertRaises(FileExistsError):
            self.publish('alias.delta.json')
        self.assertEqual(set(x.name for x in self.folder.iterdir()),
                         {'change-1.delta.json', 'alias.delta.json'})

    def test_bad_inputs_and_total_size_limit_leave_no_files(self):
        for updates in ({}, {0: list(range(4097))},
                        {i: [1, 2] for i in range(129)},
                        {0: [True]}, {-1: [7]}):
            with self.subTest(kind=len(updates)), self.assertRaises(ValueError):
                self.publish(updates=updates)
        with self.assertRaises(ValueError):
            self.publish(parent='not-a-digest')
        with self.assertRaises(ValueError):
            self.publish(sequence=0)
        self.assertFalse(list(self.folder.iterdir()))

    def test_size_and_path_guards(self):
        first = self.publish()
        path = self.folder/'change-1.delta.json'
        alias = self.folder/'alias.delta.json'
        alias.symlink_to(path)
        with self.assertRaisesRegex(ValueError, 'non-symlink'):
            verify_delta(alias, first['segment_sha256'],
                         expected_parent_sha256=self.parent, expected_sequence=1)
        path.write_bytes(b'{' + b' ' * MAX_SEGMENT_BYTES + b'}')
        with self.assertRaisesRegex(ValueError, 'bounded'):
            verify_delta(path, digest(path.read_bytes()),
                         expected_parent_sha256=self.parent, expected_sequence=1)

    def test_competing_publisher_does_not_replace_or_leave_scratch(self):
        def winner(source, dest, *, follow_symlinks):
            Path(dest).write_bytes(b'winner data')
            raise FileExistsError('concurrent segment')
        with patch('tools.photo_lineage.immutable_delta.os.link', side_effect=winner):
            with self.assertRaises(FileExistsError):
                self.publish()
        self.assertEqual((self.folder/'change-1.delta.json').read_bytes(), b'winner data')
        self.assertFalse(list(self.folder.glob('.atlas-delta-*')))


if __name__ == '__main__':
    unittest.main()
