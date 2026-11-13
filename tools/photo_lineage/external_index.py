"""Bounded-memory, *research-only* immutable uint64 posting-stream builder.

No image decoding, filename manifests, or live-cache mutation. Input is a
single-pass stream of (monotonically increasing photo_id, visual-word iterable).
The output is an immutable, private, numeric-only index published atomically
*if absent*. Every temporary file is confined to the output directory.

Not a replacement for a crash-safe *incremental* on-device index. Caller owns
space/quota approval, the private ID-to-photo mapping, and external validation.
"""
from __future__ import annotations

import hashlib
import heapq
import os
from pathlib import Path
import shutil
import stat
import struct
import tempfile
import time
from typing import Iterable

DOC_MASK = (1 << 32) - 1
WORD_LIMIT = 1 << 26
READ_BATCH = 1024
DEFAULT_RESERVE_FREE_BYTES = 64 * 1024 * 1024
MAX_RESERVE_FREE_BYTES = 1 << 40


class DiskBudgetError(OSError):
    """Available scratch volume is insufficient for the requested index budget."""


def _require_disk_headroom(directory: Path, growth_bytes: int, reserve_bytes: int) -> None:
    """Conservative advisory free-space gate; never a filesystem reservation.

    A concurrent writer may exhaust the filesystem after any successful check.
    The caller must still handle ENOSPC and preserve older immutable snapshots.
    """
    available = shutil.disk_usage(directory).free
    if available < growth_bytes + reserve_bytes:
        raise DiskBudgetError(
            'Insufficient free disk space for scratch index and retained reserve; '
            'reduce max_postings or free space before retrying'
        )


def _maximum_scratch_reservation(max_postings: int, chunk_records: int) -> int:
    """Bound peak scratch for a completed multi-pass merge.

    Every merge retains the old runs until their replacement is durable.
    Triple the maximum posting bytes, then add generous per-run filesystem
    overhead and one MiB for fixed scratch metadata.
    """
    number_of_runs = (max_postings + chunk_records - 1) // chunk_records
    return max_postings * 8 * 3 + number_of_runs * 4096 + 1024 * 1024



def _write_records(path: Path, values: Iterable[int]) -> int:
    """Write sorted u64 values with bounded output buffering and private mode."""
    count = 0
    last = None
    buf: list[int] = []
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'wb', buffering=65536) as dest:
        for value in values:
            if last is not None and value < last:
                raise ValueError('Internal posting order violated')
            if value != last:
                buf.append(value)
                count += 1
                if len(buf) >= READ_BATCH:
                    dest.write(struct.pack(f'<{len(buf)}Q', *buf))
                    buf.clear()
            last = value
        if buf:
            dest.write(struct.pack(f'<{len(buf)}Q', *buf))
        dest.flush()
        os.fsync(dest.fileno())
    return count


def _read_records(path: Path):
    with path.open('rb', buffering=65536) as source:
        while True:
            block = source.read(8 * READ_BATCH)
            if not block:
                return
            if len(block) % 8:
                raise ValueError('Corrupted posting-run byte length')
            for (value,) in struct.iter_unpack('<Q', block):
                yield value


def _digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def build_snapshot(
    documents: Iterable[tuple[int, Iterable[int]]],
    output: str | Path,
    *,
    chunk_records: int = 8192,
    fan_in: int = 16,
    max_postings: int = 500_000,
    reserve_free_bytes: int = DEFAULT_RESERVE_FREE_BYTES,
) -> dict:
    """Return measured build report. No overwrite, no source-file operations.

    Worst-case in-process working set is bounded by chunk_records postings plus
    fan_in read batches and fixed-size merge buffers, independent of library
    size. Scratch space is preflighted against max_postings with a generous
    upper bound, then checked before every run/merge write. This is never
    a filesystem reservation or an Android device capacity guarantee.
    """
    if not 512 <= chunk_records <= 1_000_000:
        raise ValueError('chunk_records must be 512..1000000')
    if not 2 <= fan_in <= 32:
        raise ValueError('fan_in must be 2..32')
    if type(max_postings) is not int or not 1 <= max_postings <= 100_000_000:
        raise ValueError('max_postings must be 1..100000000')
    if (type(reserve_free_bytes) is not int or
            not 0 <= reserve_free_bytes <= MAX_RESERVE_FREE_BYTES):
        raise ValueError('reserve_free_bytes must be an integer between 0 and 1 TiB')
    path = Path(output)
    if path.exists() or path.is_symlink():
        raise FileExistsError('Refusing to overwrite an existing index or symlink')
    if not path.name or path.parent.is_symlink() or not path.parent.is_dir():
        raise ValueError('Output must be within an existing real directory')
    if not stat.S_ISDIR(path.parent.stat().st_mode):
        raise ValueError('Output parent is not a directory')
    _require_disk_headroom(path.parent,
                           _maximum_scratch_reservation(max_postings, chunk_records),
                           reserve_free_bytes)
    start = time.perf_counter()
    emitted = 0
    docs = 0
    run_count = 0
    passes = 0
    prev_id = -1
    with tempfile.TemporaryDirectory(prefix='.atlas-postings-', dir=path.parent) as dirname:
        scratch = Path(dirname)
        chunk: list[int] = []
        paths: list[Path] = []

        def flush():
            nonlocal run_count
            if not chunk:
                return
            run = scratch / f'run-{run_count:08d}.u64'
            _require_disk_headroom(path.parent, len(chunk) * 8 + 4096, reserve_free_bytes)
            _write_records(run, sorted(chunk))
            paths.append(run)
            run_count += 1
            chunk.clear()

        for doc_id, words in documents:
            if not isinstance(doc_id, int) or isinstance(doc_id, bool) or not prev_id < doc_id <= DOC_MASK:
                raise ValueError('Photo IDs must be strictly increasing uint32 integers')
            prev_id = doc_id
            docs += 1
            per_doc = 0
            for word in words:
                if not isinstance(word, int) or isinstance(word, bool) or not 0 <= word < WORD_LIMIT:
                    raise ValueError('Visual word outside supported 26-bit range')
                per_doc += 1
                if per_doc > 4096:
                    raise ValueError('Excessive visual words per document')
                emitted += 1
                if emitted > max_postings:
                    raise ValueError('Maximum posting budget exceeded before publication')
                chunk.append((word << 32) | doc_id)
                if len(chunk) >= chunk_records:
                    flush()
        flush()
        if not paths:
            empty = scratch / 'empty.u64'
            _require_disk_headroom(path.parent, 4096, reserve_free_bytes)
            _write_records(empty, ())
            paths = [empty]
        while len(paths) > 1:
            next_paths = []
            for offset in range(0, len(paths), fan_in):
                group = paths[offset:offset + fan_in]
                if len(group) == 1:
                    next_paths.append(group[0])
                    continue
                merged = scratch / f'merge-{passes:04d}-{offset:08d}.u64'
                projected_bytes = sum(source.stat().st_size for source in group)
                _require_disk_headroom(path.parent, projected_bytes + 4096,
                                       reserve_free_bytes)
                _write_records(merged, heapq.merge(*(_read_records(src) for src in group)))
                for src in group:
                    src.unlink()
                next_paths.append(merged)
            paths = next_paths
            passes += 1
        final = paths[0]
        byte_count = final.stat().st_size
        if byte_count % 8:
            raise ValueError('Unaligned final posting stream')
        # Data was fsynced before publication. Atomic hard-link creation
        # fails if output exists, unlike os.replace which may overwrite.
        checksum = _digest(final)
        os.link(final, path, follow_symlinks=False)
        # Best-effort directory metadata durability (POSIX only).
        try:
            directory_fd = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except OSError:
            pass
    return {
        'policy': 'immutable private numeric cache; no photo reads, moves, or deletion',
        'photos': docs, 'raw_postings': emitted, 'postings': byte_count // 8,
        'duplicate_records_removed': emitted - byte_count // 8,
        'initial_sorted_runs': run_count, 'bounded_merge_passes': passes,
        'bytes': byte_count, 'sha256': checksum,
        'build_seconds': round(time.perf_counter() - start, 3),
        'output_exists': path.is_file(),
        'reserved_free_bytes': reserve_free_bytes,
        'disk_policy': 'conservative preflight and per-write checks, not a disk reservation',
    }


def verify_snapshot(path: str | Path, expected_sha256: str, *, max_bytes: int = 1_000_000_000) -> dict:
    """Verify a private immutable posting snapshot without writing anything.

    A digest supplied by an independent trusted manifest is essential: a digest
    computed solely from an untrusted file proves nothing about its identity.
    Detects a changed file during verification where stat metadata permits.
    """
    import hmac
    import re

    if not isinstance(expected_sha256, str) or re.fullmatch(r'[a-f0-9]{64}', expected_sha256) is None:
        raise ValueError('Expected an independently pinned lowercase SHA-256 digest')
    if not isinstance(max_bytes, int) or isinstance(max_bytes, bool) or max_bytes < 1:
        raise ValueError('Positive byte budget required')
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError('Existing regular non-symlink snapshot required')
    flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(path, flags)
    with os.fdopen(fd, 'rb', buffering=65536) as f:
        initial = os.fstat(f.fileno())
        if not stat.S_ISREG(initial.st_mode) or initial.st_size > max_bytes:
            raise ValueError('Invalid or over-budget snapshot file')
        if initial.st_size % 8:
            raise ValueError('Posting file is not uint64-aligned')
        digest = hashlib.sha256()
        previous = -1
        records = 0
        while True:
            block = f.read(8 * READ_BATCH)
            if not block:
                break
            if len(block) % 8:
                raise ValueError('Truncated uint64 record')
            digest.update(block)
            for (value,) in struct.iter_unpack('<Q', block):
                if value <= previous:
                    raise ValueError('Postings not strictly sorted and unique')
                if value >> 32 >= WORD_LIMIT:
                    raise ValueError('Invalid packed word range')
                previous = value
                records += 1
        end = os.fstat(f.fileno())
        if (initial.st_size, initial.st_mtime_ns, initial.st_ctime_ns) != (end.st_size, end.st_mtime_ns, end.st_ctime_ns):
            raise ValueError('Snapshot changed while being verified')
        if records * 8 != initial.st_size:
            raise ValueError('Snapshot length mismatch')
        observed = digest.hexdigest()
        if not hmac.compare_digest(observed, expected_sha256):
            raise ValueError('Snapshot digest does not match trusted manifest')
    return {'verified': True, 'sha256': observed, 'bytes': initial.st_size,
            'postings': records, 'policy': 'read-only private index verification; no images'}
