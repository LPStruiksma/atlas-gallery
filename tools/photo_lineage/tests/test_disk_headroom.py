"""Synthetic disk exhaustion and recovery guards; never touch actual media."""
from __future__ import annotations

import errno
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

from tools.photo_lineage.external_index import (
    DEFAULT_RESERVE_FREE_BYTES, DiskBudgetError,
    _maximum_scratch_reservation, build_snapshot, verify_snapshot,
)


class DiskBudgetTests(unittest.TestCase):
    def test_preflight_low_space_refuses_before_scratch_or_writes(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            existing = folder / 'previous-generation.u64'
            existing.write_bytes(b'previous immutable snapshot')
            new = folder / 'new-generation.u64'
            with patch('tools.photo_lineage.external_index.shutil.disk_usage',
                       return_value=SimpleNamespace(free=DEFAULT_RESERVE_FREE_BYTES)):
                with self.assertRaisesRegex(DiskBudgetError, 'Insufficient free disk'):
                    build_snapshot([(0, [1, 2])], new)
            self.assertFalse(new.exists())
            self.assertEqual(existing.read_bytes(), b'previous immutable snapshot')
            self.assertEqual(set(folder.iterdir()), {existing})

    def test_bound_is_monotone_and_exact_preflight_threshold_admitted(self):
        self.assertGreater(_maximum_scratch_reservation(1001, 512),
                           _maximum_scratch_reservation(1000, 512))
        with tempfile.TemporaryDirectory() as tmp:
            dst = Path(tmp) / 'index.u64'
            max_postings, chunk_size, reserve = 1000, 512, 0
            allowance = _maximum_scratch_reservation(max_postings, chunk_size)
            with patch('tools.photo_lineage.external_index.shutil.disk_usage',
                       return_value=SimpleNamespace(free=allowance)):
                proof = build_snapshot([(0, range(900))], dst, max_postings=max_postings,
                                       chunk_records=chunk_size,
                                       reserve_free_bytes=reserve)
            self.assertEqual(proof['postings'], 900)
            self.assertTrue(verify_snapshot(dst, proof['sha256'])['verified'])

    def test_loss_of_headroom_during_initial_chunks_preserves_old(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            old = folder / 'old.u64'
            old.write_bytes(b'keep old index')
            out = folder / 'pending.u64'
            huge = SimpleNamespace(free=10**12)
            exhausted = SimpleNamespace(free=0)
            with patch('tools.photo_lineage.external_index.shutil.disk_usage',
                       side_effect=[huge, huge, exhausted]) as usage:
                with self.assertRaises(DiskBudgetError):
                    build_snapshot([(0, range(900))], out, max_postings=1000,
                                   chunk_records=512, reserve_free_bytes=0)
            self.assertEqual(usage.call_count, 3)
            self.assertEqual(set(folder.iterdir()), {old})
            self.assertEqual(old.read_bytes(), b'keep old index')

    def test_loss_of_headroom_before_merge_preserves_old(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            old = folder / 'old.u64'
            old.write_bytes(b'older generation still valid')
            out = folder / 'new.u64'
            huge = SimpleNamespace(free=10**12)
            exhausted = SimpleNamespace(free=0)
            with patch('tools.photo_lineage.external_index.shutil.disk_usage',
                       side_effect=[huge, huge, huge, exhausted]) as usage:
                with self.assertRaisesRegex(DiskBudgetError, 'Insufficient free'):
                    build_snapshot([(0, range(900))], out, max_postings=1000,
                                   chunk_records=512, fan_in=2,
                                   reserve_free_bytes=0)
            self.assertEqual(usage.call_count, 4)
            self.assertEqual(set(folder.iterdir()), {old})
            self.assertEqual(old.read_bytes(), b'older generation still valid')

    def test_unexpected_enospc_is_not_silenced_and_removes_scratch(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            old = folder / 'old.u64'
            old.write_bytes(b'old intact')
            with patch('tools.photo_lineage.external_index._write_records',
                       side_effect=OSError(errno.ENOSPC, 'No space left on device')):
                with self.assertRaises(OSError) as error:
                    build_snapshot([(0, [1, 2])], folder / 'new.u64',
                                   max_postings=3, reserve_free_bytes=0)
            self.assertEqual(error.exception.errno, errno.ENOSPC)
            self.assertEqual(set(folder.iterdir()), {old})
            self.assertEqual(old.read_bytes(), b'old intact')

    def test_budget_arguments_strict_and_existing_output_stays_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'existing.u64'
            path.write_bytes(b'keep')
            with patch('tools.photo_lineage.external_index.shutil.disk_usage',
                       side_effect=AssertionError('must refuse before checking disk')):
                with self.assertRaises(FileExistsError):
                    build_snapshot([(0, [1])], path)
            for bad in (-1, True, 1.5, 1 << 40 | 1, None):
                with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, 'reserve_free_bytes'):
                    build_snapshot([], Path(tmp) / 'new.u64', reserve_free_bytes=bad)
            self.assertEqual(path.read_bytes(), b'keep')
            self.assertEqual(set(Path(tmp).iterdir()), {path})


if __name__ == '__main__':
    unittest.main()
