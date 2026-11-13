"""Pinned, immutable Photo DNA generation catalog; never activates an index.

A catalog binds one verified snapshot receipt and the COMPLETE ordered roster
of incremental delta segments. The catalog digest must come from an independent
trusted authority, not from the catalog itself. No photos or paths are emitted.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
from pathlib import Path
import stat
import tempfile

from .immutable_delta import read_delta_chain, MAX_CHAIN_LENGTH
from .snapshot_receipt import (
    GENERATION_PATTERN, _canonical, _name, _parent, _pin, _unique_object_pairs,
    verify_receipt,
)

MAX_CATALOG_BYTES = 8192
FIELDS = frozenset(('schema', 'format', 'generation', 'base_receipt',
                    'base_receipt_sha256', 'segments'))


def _validate_roster(base_name, base_digest, segments):
    _name(base_name, suffix='.json')
    if base_name.endswith('.delta.json'):
        raise ValueError('Base receipt may not be a delta segment')
    _pin(base_digest)
    if not isinstance(segments, (list, tuple)) or len(segments) > MAX_CHAIN_LENGTH:
        raise ValueError('Generation segment roster exceeds 32 entries')
    observed = {base_name}
    validated = []
    for entry in segments:
        if not isinstance(entry, (list, tuple)) or len(entry) != 2:
            raise ValueError('Every segment needs a name and independent digest')
        name, digest = entry
        _name(name, suffix='.delta.json')
        _pin(digest)
        if name in observed:
            raise ValueError('Duplicate segment filename in trusted catalog')
        observed.add(name)
        validated.append([name, digest])
    return validated


def _validate_payload(payload):
    if not isinstance(payload, dict) or frozenset(payload) != FIELDS:
        raise ValueError('Unknown generation catalog schema fields')
    if type(payload['schema']) is not int or payload['schema'] != 1:
        raise ValueError('Unsupported catalog schema version')
    if payload['format'] != 'atlas-photo-dna-catalog-v1':
        raise ValueError('Unsupported catalog format')
    gen = payload['generation']
    if not isinstance(gen, str) or GENERATION_PATTERN.fullmatch(gen) is None:
        raise ValueError('Invalid catalog generation')
    validated = _validate_roster(payload['base_receipt'],
                                  payload['base_receipt_sha256'], payload['segments'])
    if validated != payload['segments']:
        raise ValueError('Catalog roster is not in canonical array form')
    return validated


def _verify_references(parent: Path, base_name: str, base_digest: str, roster):
    receipt = verify_receipt(parent / base_name, base_digest)
    if roster:
        chain = read_delta_chain([(parent / name, digest) for name, digest in roster],
                                 base_receipt_sha256=base_digest)
        overlay = chain['updates']
        head_digest = chain['head_segment_sha256']
    else:
        overlay = {}
        head_digest = base_digest
    return {
        'receipt_generation': receipt['generation'],
        'snapshot_sha256': receipt['snapshot_sha256'],
        'postings': receipt['postings'],
        'segments': len(roster),
        'delta_head_sha256': head_digest,
        'updated_documents': len(overlay),
        'updates': overlay,
    }


def publish_catalog(path: str | Path, *, generation: str, base_receipt_name: str,
                    base_receipt_sha256: str, segments=()) -> dict:
    """Validate full referenced state BEFORE atomically publishing new catalog."""
    path = Path(path)
    _name(path.name, suffix='.catalog.json')
    parent = _parent(path)
    if path.exists() or path.is_symlink():
        raise FileExistsError('Refusing to replace a published generation catalog')
    data = dict(schema=1, format='atlas-photo-dna-catalog-v1',
                generation=generation, base_receipt=base_receipt_name,
                base_receipt_sha256=base_receipt_sha256,
                segments=_validate_roster(base_receipt_name, base_receipt_sha256, segments))
    _validate_payload(data)
    proof = _verify_references(parent, base_receipt_name, base_receipt_sha256, data['segments'])
    raw = _canonical(data)
    if len(raw) > MAX_CATALOG_BYTES:
        raise ValueError('Generation catalog exceeds byte budget')
    fd, pending = tempfile.mkstemp(prefix='.atlas-catalog-', suffix='.tmp', dir=parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(pending, path, follow_symlinks=False)
        try:
            directory = os.open(parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        except OSError:
            pass
    finally:
        os.unlink(pending)
    return {'generation': generation, 'catalog_sha256': hashlib.sha256(raw).hexdigest(),
            'segments': len(data['segments']), 'updated_documents': proof['updated_documents'],
            'policy': 'immutable verified catalog; NOT an active generation pointer'}


def verify_catalog(path: str | Path, expected_catalog_sha256: str) -> dict:
    """Fail closed if catalog or ANY referenced receipt/segment is untrusted."""
    _pin(expected_catalog_sha256)
    path = Path(path)
    _name(path.name, suffix='.catalog.json')
    parent = _parent(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError('Existing regular non-symlink generation catalog required')
    flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(path, flags)
    with os.fdopen(fd, 'rb') as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_CATALOG_BYTES:
            raise ValueError('Unbounded or nonregular generation catalog')
        raw = stream.read(MAX_CATALOG_BYTES + 1)
        after = os.fstat(stream.fileno())
        if (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
                after.st_size, after.st_mtime_ns, after.st_ctime_ns
        ) or len(raw) != before.st_size:
            raise ValueError('Generation catalog changed during read')
        if len(raw) > MAX_CATALOG_BYTES:
            raise ValueError('Catalog exceeds byte budget')
    observed = hashlib.sha256(raw).hexdigest()
    if not hmac.compare_digest(observed, expected_catalog_sha256):
        raise ValueError('Catalog does not match independently trusted digest')
    try:
        data = json.loads(raw.decode('ascii'), object_pairs_hook=_unique_object_pairs)
    except (ValueError, UnicodeError) as exc:
        raise ValueError('Malformed generation catalog') from exc
    roster = _validate_payload(data)
    if raw != _canonical(data):
        raise ValueError('Noncanonical catalog encoding refused')
    proof = _verify_references(parent, data['base_receipt'],
                               data['base_receipt_sha256'], roster)
    return dict(proof, verified=True, generation=data['generation'],
                catalog_sha256=expected_catalog_sha256,
                policy='read-only complete pinned generation verification; NOT live activation')
