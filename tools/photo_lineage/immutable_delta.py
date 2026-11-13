"""Immutable, independently pinned numeric Photo DNA delta segments.

A segment contains complete descriptor replacements or explicit cache tombstones.
Segments form a verified linear hash chain anchored to a separately trusted
base receipt. No active-generation pointer, image access, or destructive action.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import stat
import tempfile

from .delta_overlay import combine_layers, normalize_updates
from .snapshot_receipt import _name, _parent, _pin, _unique_object_pairs

MAX_SEGMENT_BYTES = 1_048_576
MAX_SEGMENT_DOCS = 128
MAX_SEGMENT_WORDS = 8192
MAX_CHAIN_LENGTH = 32
FIELDS = frozenset(('schema', 'format', 'sequence', 'parent_sha256', 'updates'))


def _canonical(data: dict) -> bytes:
    return (json.dumps(data, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n').encode('ascii')


def _validated_updates(raw: object) -> dict[int, frozenset[int] | None]:
    """Reject duplicate/unsorted IDs and descriptor words, with strict quotas."""
    if not isinstance(raw, list) or not 1 <= len(raw) <= MAX_SEGMENT_DOCS:
        raise ValueError('Delta must include 1..128 changed document IDs')
    result = {}
    previous = -1
    total_words = 0
    for entry in raw:
        if not isinstance(entry, list) or len(entry) != 2:
            raise ValueError('Delta entry must be [document_id, replacement_or_null]')
        doc, words = entry
        if type(doc) is not int or doc <= previous or doc > (1 << 32) - 1 or doc < 0:
            raise ValueError('Delta document IDs must be unique, increasing uint32')
        previous = doc
        if words is None:
            result[doc] = None
            continue
        if not isinstance(words, list):
            raise ValueError('Replacement words must be a sorted integer array')
        if any(type(word) is not int or not 0 <= word < (1 << 26) for word in words):
            raise ValueError('Invalid 26-bit replacement word')
        if words != sorted(set(words)):
            raise ValueError('Replacement words must be unique and increasing')
        total_words += len(words)
        if total_words > MAX_SEGMENT_WORDS:
            raise ValueError('Delta exceeds total descriptor budget')
        result[doc] = frozenset(words)
    return result


def _payload(updates, *, parent_sha256: str, sequence: int) -> bytes:
    _pin(parent_sha256)
    if type(sequence) is not int or not 1 <= sequence <= 10_000:
        raise ValueError('Sequence must be a positive bounded integer')
    normalized = normalize_updates(updates, max_documents=MAX_SEGMENT_DOCS)
    if not normalized:
        raise ValueError('Delta must not be empty')
    rows = [[doc, None if words is None else sorted(words)] for doc, words in sorted(normalized.items())]
    _validated_updates(rows)
    raw = _canonical({'schema': 1, 'format': 'atlas-orb-delta-v1',
                      'sequence': sequence, 'parent_sha256': parent_sha256, 'updates': rows})
    if len(raw) > MAX_SEGMENT_BYTES:
        raise ValueError('Delta exceeds one-MiB serialization budget')
    return raw


def publish_delta(path: str | Path, updates, *, parent_sha256: str, sequence: int) -> dict:
    """Create one private immutable segment, never overwrite an existing file."""
    path = Path(path)
    _name(path.name, suffix='.delta.json')
    _parent(path)
    if path.exists() or path.is_symlink():
        raise FileExistsError('Refusing to overwrite an existing delta segment')
    raw = _payload(updates, parent_sha256=parent_sha256, sequence=sequence)
    digest = hashlib.sha256(raw).hexdigest()
    fd, pending = tempfile.mkstemp(prefix='.atlas-delta-', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(pending, path, follow_symlinks=False)
        try:
            directory = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        except OSError:
            pass  # Directory durability depends on filesystem.
    finally:
        os.unlink(pending)
    return {
        'segment_sha256': digest,
        'parent_sha256': parent_sha256,
        'sequence': sequence,
        'changed_documents': len(updates),
        'bytes': len(raw),
        'policy': 'immutable numeric cache delta; no photos or active-generation changes',
    }


def verify_delta(path: str | Path, expected_sha256: str, *,
                 expected_parent_sha256: str, expected_sequence: int) -> dict:
    """Read and authenticate a bounded segment against an independent pin."""
    _pin(expected_sha256)
    _pin(expected_parent_sha256)
    if type(expected_sequence) is not int or not 1 <= expected_sequence <= 10_000:
        raise ValueError('Invalid expected sequence')
    path = Path(path)
    _name(path.name, suffix='.delta.json')
    _parent(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError('Regular non-symlink delta file required')
    flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(path, flags)
    with os.fdopen(fd, 'rb') as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_SEGMENT_BYTES:
            raise ValueError('Delta is not a bounded regular file')
        raw = stream.read(MAX_SEGMENT_BYTES + 1)
        after = os.fstat(stream.fileno())
        if (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
            after.st_size, after.st_mtime_ns, after.st_ctime_ns
        ) or len(raw) != before.st_size:
            raise ValueError('Delta changed during verification')
        if len(raw) > MAX_SEGMENT_BYTES:
            raise ValueError('Delta exceeds size budget')
    actual = hashlib.sha256(raw).hexdigest()
    if not hmac.compare_digest(actual, expected_sha256):
        raise ValueError('Delta digest mismatch against independent pin')
    try:
        data = json.loads(raw.decode('ascii'), object_pairs_hook=_unique_object_pairs)
    except (ValueError, UnicodeError) as exc:
        raise ValueError('Malformed delta encoding') from exc
    if not isinstance(data, dict) or frozenset(data) != FIELDS:
        raise ValueError('Delta schema fields mismatch')
    if type(data['schema']) is not int or data['schema'] != 1 or data['format'] != 'atlas-orb-delta-v1':
        raise ValueError('Unknown delta schema or format')
    if type(data['sequence']) is not int or data['sequence'] != expected_sequence:
        raise ValueError('Delta sequence mismatch')
    if data['parent_sha256'] != expected_parent_sha256:
        raise ValueError('Delta parent hash does not match trusted chain')
    if raw != _canonical(data):
        raise ValueError('Noncanonical delta encoding refused')
    changes = _validated_updates(data['updates'])
    return {'verified': True, 'sha256': actual, 'sequence': expected_sequence,
            'changed_documents': len(changes), 'updates': changes}


def read_delta_chain(segments, *, base_receipt_sha256: str) -> dict:
    """Verify 1..32 ordered segments, returning only the fully checked overlay.

    segments is a caller-controlled list of (path, independent_sha256) pairs.
    Its completeness/order MUST be grounded by a trusted external manifest:
    absent trailing segments cannot be detected from an incomplete list alone.
    """
    _pin(base_receipt_sha256)
    if not isinstance(segments, (list, tuple)) or not 1 <= len(segments) <= MAX_CHAIN_LENGTH:
        raise ValueError('Delta chain must have 1..32 explicitly pinned segments')
    parent = base_receipt_sha256
    layers = []
    hashes = set()
    for sequence, item in enumerate(segments, start=1):
        if not isinstance(item, (list, tuple)) or len(item) != 2:
            raise ValueError('Chain entry must contain path and independent SHA-256')
        path, digest = item
        if digest in hashes:
            raise ValueError('Repeated delta digest refused')
        checked = verify_delta(path, digest, expected_parent_sha256=parent,
                               expected_sequence=sequence)
        layers.append(checked['updates'])
        hashes.add(digest)
        parent = digest
    combined = combine_layers(*layers)
    return {
        'verified': True,
        'segments': len(segments),
        'base_receipt_sha256': base_receipt_sha256,
        'head_segment_sha256': parent,
        'changed_documents': len(combined),
        'updates': combined,
        'policy': 'read-only validated delta chain, not an active index selection',
    }
