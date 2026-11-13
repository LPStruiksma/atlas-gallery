"""Immutable, independently pinned receipt for a private numeric Photo DNA index.

Research-only: neither creates an active-generation pointer nor deletes originals,
postings, or older generations. No network, image decoding, or database writes.
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

from .external_index import verify_snapshot

SCHEMA_VERSION = 1
MAX_RECEIPT_BYTES = 4096
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
NAME_PATTERN = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}\Z")
GENERATION_PATTERN = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}\Z")
RECEIPT_FIELDS = frozenset(("schema", "generation", "format", "file", "sha256", "bytes", "postings"))


def _pin(value: str) -> str:
    if not isinstance(value, str) or SHA256_PATTERN.fullmatch(value) is None:
        raise ValueError("An independently pinned lowercase SHA-256 is required")
    return value


def _name(value: str, *, suffix: str) -> str:
    if (not isinstance(value, str) or NAME_PATTERN.fullmatch(value) is None
            or value in (".", "..") or not value.endswith(suffix)):
        raise ValueError("Invalid basename or file format")
    return value


def _parent(path: Path) -> Path:
    if not path.parent.is_dir() or path.parent.is_symlink():
        raise ValueError("Existing non-symlink output directory required")
    return path.parent


def _canonical(data: dict) -> bytes:
    return (json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=True) + "\n").encode("ascii")


def _read_pinned(path: Path, expected_sha256: str) -> bytes:
    _pin(expected_sha256)
    if path.is_symlink() or not path.is_file():
        raise ValueError("Existing regular non-symlink receipt required")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    with os.fdopen(fd, "rb") as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_RECEIPT_BYTES:
            raise ValueError("Receipt is not a bounded regular file")
        payload = stream.read(MAX_RECEIPT_BYTES + 1)
        after = os.fstat(stream.fileno())
        if (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
                after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise ValueError("Receipt changed during verification")
        if len(payload) > MAX_RECEIPT_BYTES or len(payload) != before.st_size:
            raise ValueError("Receipt length exceeds limit or changed")
    if not hmac.compare_digest(hashlib.sha256(payload).hexdigest(), expected_sha256):
        raise ValueError("Receipt checksum mismatches independently pinned SHA-256")
    return payload


def _unique_object_pairs(pairs):
    data = {}
    for key, value in pairs:
        if key in data:
            raise ValueError("Duplicate JSON key in receipt")
        data[key] = value
    return data


def publish_receipt(
    snapshot: str | Path,
    receipt: str | Path,
    *,
    generation: str,
    expected_snapshot_sha256: str,
    max_index_bytes: int = 1_000_000_000,
) -> dict:
    """Publish a new receipt only if complete and destination does not exist.

    Expected snapshot digest must be pinned outside the snapshot itself.
    All output is created as private mode 0600; no pre-existing path is changed.
    """
    _pin(expected_snapshot_sha256)
    if not isinstance(generation, str) or GENERATION_PATTERN.fullmatch(generation) is None:
        raise ValueError("Invalid generation identifier")
    snapshot, receipt = Path(snapshot), Path(receipt)
    _name(snapshot.name, suffix=".u64")
    _name(receipt.name, suffix=".json")
    if _parent(snapshot).resolve() != _parent(receipt).resolve():
        raise ValueError("Snapshot and receipt must have the same private parent")
    if receipt.exists() or receipt.is_symlink():
        raise FileExistsError("Refusing to overwrite an existing receipt")
    proof = verify_snapshot(snapshot, expected_snapshot_sha256, max_bytes=max_index_bytes)
    obj = {
        "schema": SCHEMA_VERSION,
        "generation": generation,
        "format": "atlas-orb-u64-v1",
        "file": snapshot.name,
        "sha256": expected_snapshot_sha256,
        "bytes": proof["bytes"],
        "postings": proof["postings"],
    }
    raw = _canonical(obj)
    if len(raw) > MAX_RECEIPT_BYTES:
        raise ValueError("Receipt exceeds byte budget")
    # File fsync then no-overwrite hard-link publication. Old generations
    # are NEVER replaced, even if another writer races to publish this name.
    fd, pending = tempfile.mkstemp(prefix=".atlas-receipt-", suffix=".tmp", dir=receipt.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(pending, receipt, follow_symlinks=False)
        try:
            directory_fd = os.open(receipt.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except OSError:
            pass  # Filesystems without directory fsync may have weaker durability.
    finally:
        os.unlink(pending)
    return {
        "generation": generation,
        "receipt_sha256": hashlib.sha256(raw).hexdigest(),
        "snapshot_sha256": expected_snapshot_sha256,
        "bytes": proof["bytes"],
        "postings": proof["postings"],
        "policy": "immutable, nonactivating receipt; no originals or older files changed",
    }


def verify_receipt(
    receipt: str | Path,
    expected_receipt_sha256: str,
    *,
    max_index_bytes: int = 1_000_000_000,
) -> dict:
    """Verify the trusted receipt and its referenced stream without mutations.

    An independently pinned receipt SHA-256 is mandatory: a self-hashed JSON
    file cannot authenticate itself. Never infer photo identity from this proof.
    """
    path = Path(receipt)
    _name(path.name, suffix=".json")
    _parent(path)
    raw = _read_pinned(path, expected_receipt_sha256)
    try:
        data = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object_pairs)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError("Invalid receipt JSON") from exc
    if not isinstance(data, dict) or frozenset(data) != RECEIPT_FIELDS:
        raise ValueError("Receipt schema fields mismatch")
    if type(data["schema"]) is not int or data["schema"] != SCHEMA_VERSION:
        raise ValueError("Unsupported receipt schema version")
    if data["format"] != "atlas-orb-u64-v1":
        raise ValueError("Unsupported packed index format")
    if not isinstance(data["generation"], str) or GENERATION_PATTERN.fullmatch(data["generation"]) is None:
        raise ValueError("Invalid generation identifier")
    _name(data["file"], suffix=".u64")
    _pin(data["sha256"])
    if (type(data["bytes"]) is not int or type(data["postings"]) is not int
            or data["bytes"] < 0 or data["postings"] < 0
            or data["bytes"] != 8 * data["postings"]):
        raise ValueError("Invalid receipt byte and posting counts")
    snapshot = path.parent / data["file"]
    evidence = verify_snapshot(snapshot, data["sha256"], max_bytes=max_index_bytes)
    if evidence["bytes"] != data["bytes"] or evidence["postings"] != data["postings"]:
        raise ValueError("Receipt and verified index counts disagree")
    return {
        "verified": True,
        "generation": data["generation"],
        "postings": evidence["postings"],
        "bytes": evidence["bytes"],
        "receipt_sha256": expected_receipt_sha256,
        "snapshot_sha256": data["sha256"],
        "policy": "read-only, pinned receipt verification; not a live activation or photo classification",
    }


def verify_with_fallback(
    primary_receipt: str | Path,
    primary_sha256: str,
    fallback_receipt: str | Path,
    fallback_sha256: str,
    *,
    max_index_bytes: int = 1_000_000_000,
) -> dict:
    """Read-only recovery decision between TWO independently pinned generations.

    Does not alter a live active-generation pointer and never suppresses the
    primary failure: a fallback response explicitly records it as unusable.
    """
    _pin(primary_sha256)
    _pin(fallback_sha256)
    primary = Path(primary_receipt)
    fallback = Path(fallback_receipt)
    if primary.absolute() == fallback.absolute():
        raise ValueError('Primary and fallback must use distinct receipt paths')
    try:
        checked = verify_receipt(primary, primary_sha256, max_index_bytes=max_index_bytes)
        return dict(checked, fallback_used=False, primary_status='verified',
                    selection='verified_candidate_only_not_activated')
    except (ValueError, OSError) as primary_error:
        failure_type = type(primary_error).__name__
    try:
        checked = verify_receipt(fallback, fallback_sha256, max_index_bytes=max_index_bytes)
    except (ValueError, OSError) as fallback_error:
        raise ValueError('Neither independently pinned index generation is verifiable') from fallback_error
    return dict(checked, fallback_used=True, primary_status='unusable',
                primary_failure_type=failure_type,
                selection='verified_fallback_candidate_only_not_activated')
