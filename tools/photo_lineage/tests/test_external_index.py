"""All files are disposable temp posting streams. Never touches real photos."""
from __future__ import annotations

from pathlib import Path
import os
import struct
import tempfile
import unittest

from tools.photo_lineage.external_index import build_snapshot, verify_snapshot


class StreamingIndexTest(unittest.TestCase):
    def _read(self, path):
        content = path.read_bytes()
        self.assertEqual(len(content) % 8, 0)
        return [value for (value,) in struct.iter_unpack('<Q', content)]

    def test_external_merge_matches_reference_many_passes(self):
        with tempfile.TemporaryDirectory() as folder:
            dst = Path(folder) / 'snapshot.u64'
            source = [(n, set((n * 191 + i * 37) % 1031 for i in range(40)))
                      for n in range(100)]
            report = build_snapshot(iter(source), dst, chunk_records=512, fan_in=2)
            reference = sorted((word << 32) | doc_id
                               for doc_id, words in source for word in words)
            self.assertEqual(self._read(dst), reference)
            self.assertGreater(report['bounded_merge_passes'], 2)
            self.assertEqual(report['postings'], len(reference))
            self.assertTrue(report['sha256'])
            self.assertEqual(list(Path(folder).iterdir()), [dst])
            if os.name == 'posix':
                self.assertEqual(dst.stat().st_mode & 0o777, 0o600)

    def test_packed_query_abi_with_numpy_mmap(self):
        """The on-disk format must be directly readable by the packed query ABI."""
        import numpy as np
        with tempfile.TemporaryDirectory() as folder:
            dst = Path(folder) / 'queryable.u64'
            build_snapshot([(0, [7, 8, 9]), (1, [7, 8, 10]), (2, [7, 11])], dst)
            mapped = np.memmap(dst, dtype='<u8', mode='r')
            start = np.searchsorted(mapped, np.uint64(7 << 32), side='left')
            end = np.searchsorted(mapped, np.uint64((7 << 32) | ((1 << 32) - 1)), side='right')
            self.assertEqual([int(v & np.uint64((1 << 32) - 1)) for v in mapped[start:end]], [0, 1, 2])
            del mapped

    def test_duplicate_terms_across_chunk_boundary(self):
        with tempfile.TemporaryDirectory() as folder:
            dst = Path(folder) / 'output.u64'
            docs = [(0, [4] * 900), (1, [7] * 1000), (2, [8] * 2)]
            out = build_snapshot(docs, dst, chunk_records=512, fan_in=2)
            self.assertEqual(self._read(dst), [4 << 32, (7 << 32) | 1, (8 << 32) | 2])
            self.assertEqual(out['duplicate_records_removed'], 1899)

    def test_refuse_existing_output_with_byte_preservation(self):
        with tempfile.TemporaryDirectory() as folder:
            dst = Path(folder) / 'index.u64'
            dst.write_bytes(b'preexisting private content')
            with self.assertRaises(FileExistsError):
                build_snapshot([(0, [7])], dst)
            self.assertEqual(dst.read_bytes(), b'preexisting private content')

    def test_invalid_stream_cleans_temp_files_without_publication(self):
        with tempfile.TemporaryDirectory() as folder:
            dst = Path(folder) / 'index.u64'
            with self.assertRaisesRegex(ValueError, 'strictly increasing'):
                build_snapshot([(0, [7] * 800), (0, [9])], dst, chunk_records=512)
            self.assertEqual(list(Path(folder).iterdir()), [])
            with self.assertRaisesRegex(ValueError, 'budget'):
                build_snapshot([(0, range(2000))], dst, max_postings=700, chunk_records=512)
            self.assertEqual(list(Path(folder).iterdir()), [])
            with self.assertRaisesRegex(ValueError, '26-bit'):
                build_snapshot([(0, [2**26])], dst)
            self.assertEqual(list(Path(folder).iterdir()), [])

    def test_empty_stream_and_symlink_output(self):
        with tempfile.TemporaryDirectory() as folder:
            dst = Path(folder) / 'empty.u64'
            report = build_snapshot([], dst)
            self.assertEqual(report['bytes'], 0)
            self.assertEqual(dst.read_bytes(), b'')
            alias = Path(folder) / 'link.u64'
            alias.symlink_to(dst)
            with self.assertRaises(FileExistsError):
                build_snapshot([], alias)
            self.assertTrue(alias.is_symlink())

    def test_snapshot_verification_and_digest_tamper(self):
        with tempfile.TemporaryDirectory() as folder:
            dst = Path(folder) / 'snapshot.u64'
            report = build_snapshot([(0, [1, 4, 9]), (1, [1, 4, 8])], dst)
            proof = verify_snapshot(dst, report['sha256'])
            self.assertEqual(proof['postings'], 6)
            self.assertEqual(proof['bytes'], 48)
            changed = bytearray(dst.read_bytes())
            changed[0] ^= 1
            dst.write_bytes(changed)
            with self.assertRaisesRegex(ValueError, 'digest|sorted'):
                verify_snapshot(dst, report['sha256'])

    def test_snapshot_refuses_corruption_and_symlinks(self):
        import hashlib
        with tempfile.TemporaryDirectory() as folder:
            dst = Path(folder) / 'bad.u64'
            for contents, reason in (
                (b'1234567', 'aligned'),
                (struct.pack('<QQ', 9, 3), 'sorted'),
                (struct.pack('<Q', 1 << 61), 'word range'),
            ):
                dst.write_bytes(contents)
                sha = hashlib.sha256(contents).hexdigest()
                with self.subTest(reason=reason), self.assertRaisesRegex(ValueError, reason):
                    verify_snapshot(dst, sha)
            src = Path(folder) / 'ok.u64'
            built = build_snapshot([(0, [5])], src)
            link = Path(folder) / 'alias.u64'
            link.symlink_to(src)
            with self.assertRaisesRegex(ValueError, 'non-symlink'):
                verify_snapshot(link, built['sha256'])

    def test_snapshot_file_budget_and_missing_pinned_digest(self):
        with tempfile.TemporaryDirectory() as folder:
            dst = Path(folder) / 'index.u64'
            report = build_snapshot([(0, range(20))], dst)
            with self.assertRaisesRegex(ValueError, 'budget'):
                verify_snapshot(dst, report['sha256'], max_bytes=16)
            with self.assertRaisesRegex(ValueError, 'independently pinned'):
                verify_snapshot(dst, 'untrusted')
            self.assertTrue(verify_snapshot(dst, report['sha256'])['verified'])

    def test_no_input_files_or_network_and_one_pass_generator(self):
        with tempfile.TemporaryDirectory() as folder:
            dst = Path(folder) / 'stream.u64'
            source = ((doc_id, (word for word in (1, 2, 3))) for doc_id in range(5))
            report = build_snapshot(source, dst, chunk_records=512, fan_in=2)
            self.assertEqual(report['photos'], 5)
            self.assertEqual(report['postings'], 15)
            self.assertEqual(len(self._read(dst)), 15)


if __name__ == '__main__':
    unittest.main()
