"""Two-slot trusted-tip prototype tests; every asset is fictional numeric data."""
from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace
import json
import os
import tempfile
import unittest
from unittest.mock import patch

from tools.photo_lineage.external_index import build_snapshot
from tools.photo_lineage.generation_catalog import publish_catalog
from tools.photo_lineage.generation_selection import publish_selection
from tools.photo_lineage.snapshot_receipt import publish_receipt
from tools.photo_lineage.tip_store import publish_tip, resolve_tip, SLOTS


class TipStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        base = Path(self.temp.name)
        self.cache, self.authority = base / "cache", base / "authority"
        self.cache.mkdir(mode=0o700)
        self.authority.mkdir(mode=0o700)
        self.tickets = []

    def create_ticket(self, n, *, previous=None):
        name = f"sample-{n}"
        snapshot = self.cache / (name + ".u64")
        built = build_snapshot([(0, [n * 4 + 1, n * 4 + 2])],
                               snapshot, max_postings=2)
        receipt = self.cache / (name + ".json")
        issued = publish_receipt(snapshot, receipt, generation=name,
                                 expected_snapshot_sha256=built['sha256'])
        cat_path = self.cache / (name + ".catalog.json")
        cat = publish_catalog(cat_path, generation=name, base_receipt_name=receipt.name,
                              base_receipt_sha256=issued['receipt_sha256'])
        ticket = self.cache / (f"epoch-{n}.selection.json")
        kwargs = {}
        if previous is not None:
            kwargs = dict(previous_ticket_name=previous[0].name,
                          trusted_previous_ticket_sha256=previous[1])
        created = publish_selection(ticket, primary_catalog_name=cat_path.name,
                                    primary_catalog_sha256=cat['catalog_sha256'], **kwargs)
        return ticket, created['ticket_sha256']

    def advance(self, n, previous_ticket=None, old_tip=None):
        ticket = self.create_ticket(n, previous=previous_ticket)
        kwargs = {}
        if old_tip is not None:
            kwargs['expected_previous_tip_sha256'] = old_tip['local_tip_sha256']
        data = publish_tip(self.authority, self.cache,
                           ticket_name=ticket[0].name,
                           trusted_ticket_sha256=ticket[1], **kwargs)
        self.tickets.append(ticket)
        return ticket, data

    def test_first_epoch_has_one_private_slot_and_keeps_cache_readonly(self):
        ticket, first = self.advance(1)
        selected = resolve_tip(self.authority, self.cache)
        self.assertEqual(selected['epoch'], 1)
        self.assertEqual(selected['selected_generation'], 'sample-1')
        self.assertEqual(selected['valid_tip_slots'], 1)
        self.assertFalse(selected['degraded_tip_redundancy'])
        self.assertEqual(selected['ticket_sha256'], ticket[1])
        self.assertTrue((self.cache / 'sample-1.u64').exists())
        self.assertTrue((self.cache / 'sample-1.catalog.json').exists())
        self.assertTrue((self.authority / 'tip-a.json').exists())
        self.assertFalse((self.authority / 'tip-b.json').exists())
        self.assertTrue((self.authority / '.atlas-tip.lock').exists())
        if os.name == 'posix':
            self.assertEqual((self.authority/'tip-a.json').stat().st_mode & 0o777, 0o600)

    def test_three_epochs_alternate_only_tip_slots_and_preserve_previous(self):
        t1, p1 = self.advance(1)
        t2, p2 = self.advance(2, t1, p1)
        before = (self.authority/'tip-b.json').read_bytes()
        t3, p3 = self.advance(3, t2, p2)
        report = resolve_tip(self.authority, self.cache)
        self.assertEqual(report['epoch'], 3)
        self.assertEqual(report['selected_generation'], 'sample-3')
        self.assertEqual(report['valid_tip_slots'], 2)
        self.assertEqual((self.authority/'tip-b.json').read_bytes(), before)
        self.assertEqual(json.loads((self.authority/'tip-a.json').read_text())['epoch'], 3)
        self.assertTrue(all(t[0].exists() for t in (t1, t2, t3)))

    def test_latest_slot_corruption_recovers_prior_explicitly(self):
        t1, p1 = self.advance(1)
        _, p2 = self.advance(2, t1, p1)
        newer = self.authority/'tip-b.json'
        newer.write_bytes(b'torn selector')
        report = resolve_tip(self.authority, self.cache)
        self.assertEqual(report['epoch'], 1)
        self.assertEqual(report['selected_generation'], 'sample-1')
        self.assertTrue(report['degraded_tip_redundancy'])
        self.assertEqual(report['valid_tip_slots'], 1)

    def test_both_slots_broken_refuse_arbitrary_directory_selection(self):
        t1, p1 = self.advance(1)
        self.advance(2, t1, p1)
        for name in SLOTS:
            (self.authority/name).write_bytes(b'invalid')
        with self.assertRaisesRegex(ValueError, 'No verifiable'):
            resolve_tip(self.authority, self.cache)

    def test_stale_compare_and_swap_prevents_competing_writer(self):
        t1, p1 = self.advance(1)
        t2, p2 = self.advance(2, t1, p1)
        third = self.create_ticket(3, previous=t2)
        snapshots = {n:(self.authority/n).read_bytes() for n in SLOTS}
        with self.assertRaisesRegex(ValueError, 'Stale expected tip'):
            publish_tip(self.authority, self.cache,
                        ticket_name=third[0].name, trusted_ticket_sha256=third[1],
                        expected_previous_tip_sha256=p1['local_tip_sha256'])
        self.assertEqual(snapshots, {n:(self.authority/n).read_bytes() for n in SLOTS})
        self.assertEqual(resolve_tip(self.authority, self.cache)['epoch'], 2)

    def test_failed_replace_preserves_last_good_slot(self):
        t1, p1 = self.advance(1)
        second = self.create_ticket(2, previous=t1)
        with patch('tools.photo_lineage.tip_store.os.replace',
                   side_effect=OSError('injected kill-before-replace')):
            with self.assertRaisesRegex(OSError, 'kill-before'):
                publish_tip(self.authority, self.cache,
                            ticket_name=second[0].name,
                            trusted_ticket_sha256=second[1],
                            expected_previous_tip_sha256=p1['local_tip_sha256'])
        self.assertEqual(resolve_tip(self.authority, self.cache)['epoch'], 1)
        self.assertFalse(list(self.authority.glob('.atlas-tip-*tmp')))

    def test_low_disk_tip_budget_preserves_last_good(self):
        t1, p1 = self.advance(1)
        second = self.create_ticket(2, previous=t1)
        with patch('tools.photo_lineage.tip_store.shutil.disk_usage',
                   return_value=SimpleNamespace(free=10)):
            with self.assertRaisesRegex(OSError, 'Insufficient protected'):
                publish_tip(self.authority, self.cache,
                            ticket_name=second[0].name,
                            trusted_ticket_sha256=second[1],
                            expected_previous_tip_sha256=p1['local_tip_sha256'])
        self.assertEqual(resolve_tip(self.authority, self.cache)['epoch'], 1)
        self.assertFalse((self.authority/'tip-b.json').exists())

    def test_unsafe_symlink_tip_slot_blocks_overwrite(self):
        t1, p1 = self.advance(1)
        alternate = self.authority/'tip-b.json'
        alternate.symlink_to(self.authority/'tip-a.json')
        second = self.create_ticket(2, previous=t1)
        with self.assertRaisesRegex(ValueError, 'explicit owner review'):
            publish_tip(self.authority, self.cache,
                        ticket_name=second[0].name,
                        trusted_ticket_sha256=second[1],
                        expected_previous_tip_sha256=p1['local_tip_sha256'])
        self.assertTrue(alternate.is_symlink())
        self.assertEqual(resolve_tip(self.authority, self.cache)['epoch'], 1)


    def test_torn_newest_slot_is_readable_as_fallback_but_not_replaced(self):
        t1, p1 = self.advance(1)
        t2, p2 = self.advance(2, t1, p1)
        damaged = self.authority / 'tip-b.json'
        damaged.write_bytes(b'torn or unrelated private record')
        original = damaged.read_bytes()
        report = resolve_tip(self.authority, self.cache)
        self.assertEqual(report['selected_generation'], 'sample-1')
        self.assertTrue(report['degraded_tip_redundancy'])

        # A different valid epoch-2 ticket may exist, but the damaged bytes
        # might represent an already-committed later generation. Never erase.
        other = self.cache / 'alternate-2.u64'
        built = build_snapshot([(0, [91, 92])], other, max_postings=2)
        receipt = self.cache / 'alternate-2.json'
        saved = publish_receipt(other, receipt, generation='alternate2',
                                expected_snapshot_sha256=built['sha256'])
        cat_path = self.cache / 'alternate-2.catalog.json'
        cat = publish_catalog(cat_path, generation='alternate2',
                              base_receipt_name=receipt.name,
                              base_receipt_sha256=saved['receipt_sha256'])
        replacement = self.cache / 'epoch-2-alternative.selection.json'
        replacement_pin = publish_selection(
            replacement, primary_catalog_name=cat_path.name,
            primary_catalog_sha256=cat['catalog_sha256'],
            previous_ticket_name=t1[0].name,
            trusted_previous_ticket_sha256=t1[1])['ticket_sha256']
        with self.assertRaisesRegex(ValueError, 'explicit owner review'):
            publish_tip(self.authority, self.cache, ticket_name=replacement.name,
                        trusted_ticket_sha256=replacement_pin,
                        expected_previous_tip_sha256=p1['local_tip_sha256'])
        self.assertEqual(damaged.read_bytes(), original)
        self.assertEqual(resolve_tip(self.authority, self.cache)['epoch'], 1)
        self.assertFalse(list(self.authority.glob('.atlas-tip-*.tmp')))

    def test_damaged_older_slot_cannot_be_overwritten_when_newer_is_valid(self):
        t1, p1 = self.advance(1)
        t2, p2 = self.advance(2, t1, p1)
        third = self.create_ticket(3, previous=t2)
        older = self.authority / 'tip-a.json'
        older.write_bytes(b'unknown original slot data; preserve for inspection')
        original = older.read_bytes()
        report = resolve_tip(self.authority, self.cache)
        self.assertEqual(report['epoch'], 2)
        self.assertTrue(report['degraded_tip_redundancy'])
        with self.assertRaisesRegex(ValueError, 'explicit owner review'):
            publish_tip(self.authority, self.cache,
                        ticket_name=third[0].name, trusted_ticket_sha256=third[1],
                        expected_previous_tip_sha256=p2['local_tip_sha256'])
        self.assertEqual(older.read_bytes(), original)
        self.assertEqual(resolve_tip(self.authority, self.cache)['epoch'], 2)

    def test_unexpected_standby_regular_file_is_preserved(self):
        t1, p1 = self.advance(1)
        standby = self.authority / 'tip-b.json'
        standby.write_bytes(b'an unrelated file coincidentally occupying this name')
        old = standby.read_bytes()
        ticket = self.create_ticket(2, previous=t1)
        with self.assertRaisesRegex(ValueError, 'explicit owner review'):
            publish_tip(self.authority, self.cache,
                        ticket_name=ticket[0].name, trusted_ticket_sha256=ticket[1],
                        expected_previous_tip_sha256=p1['local_tip_sha256'])
        self.assertEqual(standby.read_bytes(), old)
        self.assertEqual(resolve_tip(self.authority, self.cache)['epoch'], 1)

    def test_abandoned_scratch_is_never_cleaned_up_automatically(self):
        t1, p1 = self.advance(1)
        artifact = self.authority / '.atlas-tip-private-old.tmp'
        artifact.write_bytes(b'forensic evidence of a previous interrupted write')
        second = self.create_ticket(2, previous=t1)
        saved = publish_tip(self.authority, self.cache,
                            ticket_name=second[0].name,
                            trusted_ticket_sha256=second[1],
                            expected_previous_tip_sha256=p1['local_tip_sha256'])
        self.assertEqual(saved['epoch'], 2)
        self.assertEqual(artifact.read_bytes(),
                         b'forensic evidence of a previous interrupted write')


    def test_successful_publish_reports_full_postpublication_verification(self):
        first_ticket, first = self.advance(1)
        self.assertTrue(first['postpublication_verified'])
        second_ticket, second = self.advance(2, first_ticket, first)
        self.assertTrue(second['postpublication_verified'])
        third_ticket, third = self.advance(3, second_ticket, second)
        self.assertTrue(third['postpublication_verified'])
        observed = resolve_tip(self.authority, self.cache)
        self.assertEqual(observed['epoch'], 3)
        self.assertEqual(observed['local_tip_sha256'], third['local_tip_sha256'])

    def test_replaced_slot_corrupted_before_success_acknowledgement(self):
        first_ticket, first = self.advance(1)
        second_ticket = self.create_ticket(2, previous=first_ticket)
        actual_replace = os.replace

        def corrupt_after_replace(source, destination):
            actual_replace(source, destination)
            Path(destination).write_bytes(b'unreadable bytes after successful replace')

        previous = (self.authority / 'tip-a.json').read_bytes()
        with patch('tools.photo_lineage.tip_store.os.replace',
                   side_effect=corrupt_after_replace):
            with self.assertRaisesRegex(RuntimeError, 'read-only recovery required'):
                publish_tip(self.authority, self.cache,
                            ticket_name=second_ticket[0].name,
                            trusted_ticket_sha256=second_ticket[1],
                            expected_previous_tip_sha256=first['local_tip_sha256'])
        self.assertEqual((self.authority / 'tip-a.json').read_bytes(), previous)
        self.assertEqual((self.authority / 'tip-b.json').read_bytes(),
                         b'unreadable bytes after successful replace')
        recovered = resolve_tip(self.authority, self.cache)
        self.assertEqual(recovered['epoch'], 1)
        self.assertTrue(recovered['degraded_tip_redundancy'])

    def test_successful_replace_but_lost_response_requires_reconciliation(self):
        first_ticket, first = self.advance(1)
        second_ticket = self.create_ticket(2, previous=first_ticket)
        original_replace = os.replace

        def commit_then_lose_response(source, destination):
            original_replace(source, destination)
            raise OSError('simulated failure after successful atomic replacement')

        old_slot = (self.authority / 'tip-a.json').read_bytes()
        with patch('tools.photo_lineage.tip_store.os.replace',
                   side_effect=commit_then_lose_response):
            with self.assertRaisesRegex(OSError, 'failure after successful'):
                publish_tip(self.authority, self.cache,
                            ticket_name=second_ticket[0].name,
                            trusted_ticket_sha256=second_ticket[1],
                            expected_previous_tip_sha256=first['local_tip_sha256'])
        # The caller must INSPECT; retrying blindly could fork or overwrite.
        observed = resolve_tip(self.authority, self.cache)
        self.assertEqual(observed['epoch'], 2)
        self.assertEqual(observed['selected_generation'], 'sample-2')
        self.assertEqual((self.authority / 'tip-a.json').read_bytes(), old_slot)
        self.assertFalse(list(self.authority.glob('.atlas-tip-*.tmp')))
        with self.assertRaisesRegex(ValueError, 'Stale expected tip'):
            publish_tip(self.authority, self.cache,
                        ticket_name=second_ticket[0].name,
                        trusted_ticket_sha256=second_ticket[1],
                        expected_previous_tip_sha256=first['local_tip_sha256'])

    def test_failed_postcommit_read_does_not_report_publication_success(self):
        first_ticket, first = self.advance(1)
        second_ticket = self.create_ticket(2, previous=first_ticket)
        with patch('tools.photo_lineage.tip_store._resolved',
                   side_effect=ValueError('injected verification interruption')):
            with self.assertRaisesRegex(RuntimeError, 'outcome unverified'):
                publish_tip(self.authority, self.cache,
                            ticket_name=second_ticket[0].name,
                            trusted_ticket_sha256=second_ticket[1],
                            expected_previous_tip_sha256=first['local_tip_sha256'])
        # The ticket really did land; once validation is available,
        # only a new read can disambiguate that prior failed call.
        self.assertEqual(resolve_tip(self.authority, self.cache)['epoch'], 2)

    def test_degraded_new_selection_not_promoted_even_with_older_fallback(self):
        t1, p1 = self.advance(1)
        second = self.create_ticket(2, previous=t1)
        selected_catalog = self.cache/'sample-2.catalog.json'
        selected_catalog.write_bytes(selected_catalog.read_bytes() + b'broken')
        with self.assertRaisesRegex(ValueError, 'degraded'):
            publish_tip(self.authority, self.cache,
                        ticket_name=second[0].name,
                        trusted_ticket_sha256=second[1],
                        expected_previous_tip_sha256=p1['local_tip_sha256'])
        self.assertEqual(resolve_tip(self.authority, self.cache)['epoch'], 1)

    def test_replay_of_sibling_selection_refused(self):
        t1, p1 = self.advance(1)
        t2, p2 = self.advance(2, t1, p1)
        other = self.cache/'sample-extra.u64'
        built = build_snapshot([(0, [17, 18])], other, max_postings=2)
        receipt = self.cache/'sample-extra.json'
        saved = publish_receipt(other, receipt, generation='other',
                                expected_snapshot_sha256=built['sha256'])
        catalog = self.cache/'sample-extra.catalog.json'
        cat = publish_catalog(catalog, generation='other',
                              base_receipt_name=receipt.name,
                              base_receipt_sha256=saved['receipt_sha256'])
        sibling = self.cache/'epoch-2-sibling.selection.json'
        alt = publish_selection(sibling,
                                primary_catalog_name=catalog.name,
                                primary_catalog_sha256=cat['catalog_sha256'],
                                previous_ticket_name=t1[0].name,
                                trusted_previous_ticket_sha256=t1[1])
        with self.assertRaisesRegex(ValueError, 'advance exactly one'):
            publish_tip(self.authority, self.cache,
                        ticket_name=sibling.name,
                        trusted_ticket_sha256=alt['ticket_sha256'],
                        expected_previous_tip_sha256=p2['local_tip_sha256'])
        self.assertEqual(resolve_tip(self.authority, self.cache)['epoch'], 2)

    def test_protected_authority_must_not_equal_cache_root(self):
        ticket = self.create_ticket(1)
        with self.assertRaisesRegex(ValueError, 'separate'):
            publish_tip(self.cache, self.cache, ticket_name=ticket[0].name,
                        trusted_ticket_sha256=ticket[1])
        self.assertFalse((self.cache/'tip-a.json').exists())


if __name__ == '__main__':
    unittest.main()
