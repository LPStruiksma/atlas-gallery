"""End-to-end, numeric-only generation selection and recovery tests."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools.photo_lineage.external_index import build_snapshot
from tools.photo_lineage.generation_catalog import publish_catalog
from tools.photo_lineage.generation_selection import publish_selection, verify_selection
from tools.photo_lineage.immutable_delta import publish_delta
from tools.photo_lineage.snapshot_receipt import publish_receipt


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


class GenerationSelectionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)

    def catalog(self, name, documents, delta=None):
        snapshot = self.folder / (name + '.u64')
        postings = sum(len(words) for _, words in documents)
        built = build_snapshot(documents, snapshot, max_postings=max(1, postings))
        receipt = self.folder / (name + '.json')
        saved = publish_receipt(snapshot, receipt, generation=name,
                                expected_snapshot_sha256=built['sha256'])
        segments = []
        if delta is not None:
            segment = self.folder / (name + '.delta.json')
            delta_saved = publish_delta(segment, delta,
                                        parent_sha256=saved['receipt_sha256'], sequence=1)
            segments = [(segment.name, delta_saved['segment_sha256'])]
        catalog = self.folder / (name + '.catalog.json')
        published = publish_catalog(catalog, generation=name,
                                    base_receipt_name=receipt.name,
                                    base_receipt_sha256=saved['receipt_sha256'],
                                    segments=segments)
        return catalog, published['catalog_sha256']

    def initial(self):
        c, pin = self.catalog('first', [(0, [1, 2]), (1, [3, 4])])
        ticket = self.folder/'epoch-1.selection.json'
        data = publish_selection(ticket, primary_catalog_name=c.name,
                                 primary_catalog_sha256=pin)
        return ticket, data['ticket_sha256']

    def second(self):
        first_path, first_pin = self.initial()
        c, pin = self.catalog('second', [(0, [5, 6]), (1, [7, 8])], delta={0: [9, 10]})
        ticket = self.folder/'epoch-2.selection.json'
        saved = publish_selection(ticket, primary_catalog_name=c.name,
                                  primary_catalog_sha256=pin,
                                  previous_ticket_name=first_path.name,
                                  trusted_previous_ticket_sha256=first_pin)
        return (first_path, first_pin), (ticket, saved['ticket_sha256'])

    def test_initial_pinned_selection_and_no_fallback(self):
        ticket, pin = self.initial()
        before = {p.name: p.read_bytes() for p in self.folder.iterdir()}
        verified = verify_selection(ticket, pin)
        self.assertTrue(verified['verified'])
        self.assertEqual(verified['epoch'], 1)
        self.assertEqual(verified['selected_generation'], 'first')
        self.assertFalse(verified['fallback_used'])
        self.assertEqual(verified['updated_documents'], 0)
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.folder.iterdir()})
        if os.name == 'posix':
            self.assertEqual(ticket.stat().st_mode & 0o777, 0o600)

    def test_new_verified_catalog_and_previous_fallback(self):
        (_, _), (new_path, new_pin) = self.second()
        result = verify_selection(new_path, new_pin)
        self.assertEqual(result['epoch'], 2)
        self.assertEqual(result['selected_generation'], 'second')
        self.assertEqual(result['updates'], {0: frozenset({9, 10})})
        self.assertEqual(result['segments'], 1)
        self.assertFalse(result['fallback_used'])

    def test_corrupted_latest_catalog_uses_previous_verified_generation(self):
        (old_ticket, old_pin), (new_ticket, new_pin) = self.second()
        newest_catalog = self.folder/'second.catalog.json'
        newest_catalog.write_bytes(newest_catalog.read_bytes() + b'tampered')
        original_old = old_ticket.read_bytes()
        result = verify_selection(new_ticket, new_pin)
        self.assertTrue(result['fallback_used'])
        self.assertEqual(result['primary_status'], 'unusable')
        self.assertEqual(result['selected_generation'], 'first')
        self.assertEqual(result['updated_documents'], 0)
        self.assertEqual(old_ticket.read_bytes(), original_old)
        self.assertTrue(verify_selection(old_ticket, old_pin)['verified'])

    def test_both_latest_and_fallback_corrupt_refused(self):
        (_, _), (new_ticket, new_pin) = self.second()
        for name in ('first.catalog.json', 'second.catalog.json'):
            path = self.folder / name
            path.write_bytes(path.read_bytes() + b'corrupt')
        with self.assertRaisesRegex(ValueError, 'Neither selected catalog'):
            verify_selection(new_ticket, new_pin)

    def test_missing_parent_ticket_fails_even_when_primary_valid(self):
        (old_ticket, _), (new_ticket, new_pin) = self.second()
        old_ticket.unlink()  # Only our disposable synthetic ticket fixture.
        with self.assertRaises(ValueError):
            verify_selection(new_ticket, new_pin)
        self.assertTrue((self.folder/'second.catalog.json').is_file())

    def test_ticket_tamper_and_independent_head_pin(self):
        ticket, pin = self.initial()
        raw = ticket.read_bytes()
        data = json.loads(raw)
        data['epoch'] = 2
        forged = (json.dumps(data, sort_keys=True, separators=(',', ':')) + '\n').encode()
        ticket.write_bytes(forged)
        with self.assertRaisesRegex(ValueError, 'separately trusted'):
            verify_selection(ticket, pin)
        with self.assertRaises(ValueError):
            verify_selection(ticket, sha(forged))

    def test_duplicate_json_fields_and_noncanonical_encoding(self):
        ticket, _ = self.initial()
        raw = ticket.read_bytes()
        duplicated = raw.replace(b'"schema":1', b'"schema":1,"schema":1')
        ticket.write_bytes(duplicated)
        with self.assertRaisesRegex(ValueError, 'Malformed selection'):
            verify_selection(ticket, sha(duplicated))
        clean = json.loads(raw)
        noncanonical = json.dumps(clean).encode()
        ticket.write_bytes(noncanonical)
        with self.assertRaisesRegex(ValueError, 'Noncanonical'):
            verify_selection(ticket, sha(noncanonical))

    def test_symlink_and_existing_destination_fail_closed(self):
        ticket, pin = self.initial()
        existing = ticket.read_bytes()
        with self.assertRaises(FileExistsError):
            publish_selection(ticket, primary_catalog_name='first.catalog.json',
                              primary_catalog_sha256='f'*64)
        self.assertEqual(ticket.read_bytes(), existing)
        link = self.folder/'alias.selection.json'
        link.symlink_to(ticket)
        with self.assertRaisesRegex(ValueError, 'non-symlink'):
            verify_selection(link, pin)
        self.assertEqual(ticket.read_bytes(), existing)

    def test_publish_rejects_invalid_previous_or_same_primary(self):
        first, pin = self.initial()
        same_new = self.folder/'epoch-2.selection.json'
        with self.assertRaisesRegex(ValueError, 'must differ'):
            publish_selection(same_new, primary_catalog_name='first.catalog.json',
                              primary_catalog_sha256=verify_selection(first, pin)['selected_catalog_sha256'],
                              previous_ticket_name=first.name,
                              trusted_previous_ticket_sha256=pin)
        with self.assertRaisesRegex(ValueError, 'digest|trusted'):
            publish_selection(same_new, primary_catalog_name='first.catalog.json',
                              primary_catalog_sha256='0'*64,
                              previous_ticket_name=first.name,
                              trusted_previous_ticket_sha256=pin)
        self.assertFalse(same_new.exists())

    def test_competing_publisher_preserves_existing_and_cleans_temp(self):
        self.catalog('first', [(0, [1, 2])])
        old_pin = hashlib.sha256((self.folder/'first.catalog.json').read_bytes()).hexdigest()
        target = self.folder/'epoch-1.selection.json'
        def concurrent_win(source, destination, *, follow_symlinks):
            Path(destination).write_bytes(b'competing selection')
            raise FileExistsError('another writer completed publication')
        with patch('tools.photo_lineage.generation_selection.os.link', side_effect=concurrent_win):
            with self.assertRaises(FileExistsError):
                publish_selection(target, primary_catalog_name='first.catalog.json',
                                  primary_catalog_sha256=old_pin)
        self.assertEqual(target.read_bytes(), b'competing selection')
        self.assertFalse(list(self.folder.glob('.atlas-selection-*')))

    def test_corrupt_primary_then_publish_new_from_effective_fallback(self):
        (_, _), (middle, middle_pin) = self.second()
        old_catalog = self.folder/'second.catalog.json'
        old_catalog.write_bytes(old_catalog.read_bytes() + b'corrupt')
        next_cat, next_pin = self.catalog('third', [(0, [15, 16])])
        final = self.folder/'epoch-3.selection.json'
        saved = publish_selection(final, primary_catalog_name=next_cat.name,
                                  primary_catalog_sha256=next_pin,
                                  previous_ticket_name=middle.name,
                                  trusted_previous_ticket_sha256=middle_pin)
        result = verify_selection(final, saved['ticket_sha256'])
        self.assertEqual(result['selected_generation'], 'third')
        next_cat.write_bytes(next_cat.read_bytes() + b'corrupt')
        recovered = verify_selection(final, saved['ticket_sha256'])
        self.assertTrue(recovered['fallback_used'])
        self.assertEqual(recovered['selected_generation'], 'first')

    def test_old_pinned_head_remains_valid_but_not_necessarily_fresh(self):
        (old_ticket, old_pin), (new_ticket, new_pin) = self.second()
        old_view = verify_selection(old_ticket, old_pin)
        new_view = verify_selection(new_ticket, new_pin)
        self.assertEqual(old_view['selected_generation'], 'first')
        self.assertEqual(new_view['selected_generation'], 'second')
        self.assertNotEqual(old_view['ticket_sha256'], new_view['ticket_sha256'])
        # Only the caller's independently trusted *latest tip* can detect rollback.


if __name__ == '__main__':
    unittest.main()
