"""Immutable Photo DNA generation selection with independently trusted head pin.

This is *not* a mutable CURRENT pointer or native app integration. A caller
selects a generation by providing the externally trusted SHA-256 of its ticket.
Both catalogs and all ancestor ticket bytes remain private and immutable.
No original images or live app databases are touched.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
from pathlib import Path
import stat
import tempfile

from .generation_catalog import verify_catalog
from .snapshot_receipt import (
    _canonical, _name, _parent, _pin, _unique_object_pairs,
)

SCHEMA_FIELDS = frozenset(("schema", "format", "epoch", "parent", "primary", "fallback"))
MAX_TICKET_BYTES = 4096
MAX_EPOCH = 32


def _catalog_pair(value: object) -> tuple[str, str]:
    if not isinstance(value, list) or len(value) != 2:
        raise ValueError("Catalog reference must be [basename, pinned_sha256]")
    name, digest = value
    _name(name, suffix=".catalog.json")
    _pin(digest)
    return name, digest


def _ticket_pair(value: object) -> tuple[str, str]:
    if not isinstance(value, list) or len(value) != 2:
        raise ValueError("Parent selection must be [basename, pinned_sha256]")
    name, digest = value
    _name(name, suffix=".selection.json")
    _pin(digest)
    return name, digest


def _validate(data: object) -> dict:
    if not isinstance(data, dict) or frozenset(data) != SCHEMA_FIELDS:
        raise ValueError("Selection ticket has unexpected schema fields")
    if type(data["schema"]) is not int or data["schema"] != 1:
        raise ValueError("Unsupported selection schema")
    if data["format"] != "atlas-photo-dna-selection-v1":
        raise ValueError("Unsupported selection format")
    epoch = data["epoch"]
    if type(epoch) is not int or not 1 <= epoch <= MAX_EPOCH:
        raise ValueError("Selection epoch outside bounded range")
    primary = _catalog_pair(data["primary"])
    fallback = data["fallback"]
    parent = data["parent"]
    if epoch == 1:
        if parent is not None or fallback is not None:
            raise ValueError("Initial selection cannot have parent or fallback")
    else:
        _ticket_pair(parent)
        if _catalog_pair(fallback) == primary:
            raise ValueError("Fallback must be distinct from primary catalog")
    return data


def _read_pinned_ticket(path: Path, expected_sha256: str) -> dict:
    _pin(expected_sha256)
    _name(path.name, suffix=".selection.json")
    _parent(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError("Existing regular non-symlink selection ticket required")
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(fd, "rb") as stream:
        start = os.fstat(stream.fileno())
        if not stat.S_ISREG(start.st_mode) or start.st_size > MAX_TICKET_BYTES:
            raise ValueError("Unbounded or nonregular selection ticket")
        raw = stream.read(MAX_TICKET_BYTES + 1)
        end = os.fstat(stream.fileno())
        if ((start.st_size, start.st_mtime_ns, start.st_ctime_ns) !=
                (end.st_size, end.st_mtime_ns, end.st_ctime_ns)
                or len(raw) != start.st_size):
            raise ValueError("Selection ticket changed during read")
        if len(raw) > MAX_TICKET_BYTES:
            raise ValueError("Selection ticket exceeds byte budget")
    if not hmac.compare_digest(hashlib.sha256(raw).hexdigest(), expected_sha256):
        raise ValueError("Selection digest mismatches separately trusted SHA-256")
    try:
        data = json.loads(raw.decode("ascii"), object_pairs_hook=_unique_object_pairs)
    except (ValueError, UnicodeError) as exc:
        raise ValueError("Malformed selection ticket") from exc
    _validate(data)
    if raw != _canonical(data):
        raise ValueError("Noncanonical selection ticket")
    return data


def _audit_history(path: Path, sha256: str) -> dict:
    """Read *all* immutable parent ticket bytes; do not accept missing history."""
    seen = set()
    expected_epoch = None
    newest = None
    for _ in range(MAX_EPOCH):
        if path.name in seen:
            raise ValueError("Selection ancestry cycle or repeated name")
        seen.add(path.name)
        current = _read_pinned_ticket(path, sha256)
        if newest is None:
            newest = current
        if expected_epoch is not None and current["epoch"] != expected_epoch:
            raise ValueError("Selection ancestry epoch gap")
        if current["parent"] is None:
            if current["epoch"] != 1:
                raise ValueError("Selection ancestry lacks initial epoch")
            return newest
        expected_epoch = current["epoch"] - 1
        parent_name, sha256 = _ticket_pair(current["parent"])
        path = path.parent / parent_name
    raise ValueError("Selection ancestry exceeds bounded depth")


def _select_verified_catalog(parent: Path, data: dict) -> tuple[dict, list, bool]:
    """Choose a verified candidate, exposing fallback use rather than hiding it."""
    primary = data["primary"]
    try:
        result = verify_catalog(parent / primary[0], primary[1])
    except (ValueError, OSError):
        if data["fallback"] is None:
            raise ValueError("Selected catalog is not verifiable and no fallback exists") from None
        fallback = data["fallback"]
        try:
            result = verify_catalog(parent / fallback[0], fallback[1])
        except (ValueError, OSError) as exc:
            raise ValueError("Neither selected catalog nor its fallback is verifiable") from exc
        return result, fallback, True
    return result, primary, False


def verify_selection(path: str | Path, trusted_ticket_sha256: str) -> dict:
    """Resolve a whole verified generation from an *independently pinned* ticket.

    An old, correctly pinned ticket yields an old but internally valid view;
    detecting rollback requires the caller to guard the newest authoritative pin.
    The returned descriptor overlay is read-only, not an Android activation.
    """
    path = Path(path)
    latest = _audit_history(path, trusted_ticket_sha256)
    result, selected_pair, fell_back = _select_verified_catalog(path.parent, latest)
    return {
        "verified": True,
        "epoch": latest["epoch"],
        "selected_generation": result["generation"],
        "selected_catalog_sha256": selected_pair[1],
        "ticket_sha256": trusted_ticket_sha256,
        "fallback_used": fell_back,
        "primary_status": "unusable" if fell_back else "verified",
        "segments": result["segments"],
        "updated_documents": result["updated_documents"],
        "updates": result["updates"],
        "policy": "verified research selection; no mutable activation or media operations",
    }


def publish_selection(
    path: str | Path,
    *,
    primary_catalog_name: str,
    primary_catalog_sha256: str,
    previous_ticket_name: str | None = None,
    trusted_previous_ticket_sha256: str | None = None,
) -> dict:
    """Publish only when a *new* catalog and previous effective state verify.

    The trusted caller is responsible for independently retaining the returned
    SHA-256 as its authoritative tip. We never overwrite old selection tickets.
    """
    path = Path(path)
    _name(path.name, suffix=".selection.json")
    parent = _parent(path)
    if path.exists() or path.is_symlink():
        raise FileExistsError("Refusing to overwrite a selection ticket")
    primary = [*_catalog_pair([primary_catalog_name, primary_catalog_sha256])]
    # Validate requested catalog *before* writing any scratch file.
    primary_evidence = verify_catalog(parent / primary[0], primary[1])
    if previous_ticket_name is None and trusted_previous_ticket_sha256 is None:
        epoch = 1
        predecessor = None
        fallback = None
    else:
        if previous_ticket_name is None or trusted_previous_ticket_sha256 is None:
            raise ValueError("Both previous ticket basename and trusted pin are required")
        prev_name, prev_sha = _ticket_pair([previous_ticket_name, trusted_previous_ticket_sha256])
        if prev_name == path.name:
            raise ValueError("New selection cannot name itself as parent")
        previous_path = parent / prev_name
        previous = _audit_history(previous_path, prev_sha)
        old_result, old_pair, _ = _select_verified_catalog(parent, previous)
        if primary == old_pair:
            raise ValueError("New primary catalog must differ from previous effective catalog")
        epoch = previous["epoch"] + 1
        if epoch > MAX_EPOCH:
            raise ValueError("Selection history full; explicit audited rollover required")
        predecessor = [prev_name, prev_sha]
        fallback = old_pair
    data = dict(
        schema=1, format="atlas-photo-dna-selection-v1", epoch=epoch,
        parent=predecessor, primary=primary, fallback=fallback,
    )
    _validate(data)
    raw = _canonical(data)
    if len(raw) > MAX_TICKET_BYTES:
        raise ValueError("Selection ticket exceeds byte budget")
    fd, pending = tempfile.mkstemp(prefix=".atlas-selection-", suffix=".tmp", dir=parent)
    try:
        with os.fdopen(fd, "wb") as stream:
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
    return {
        "epoch": epoch,
        "ticket_sha256": hashlib.sha256(raw).hexdigest(),
        "primary_generation": primary_evidence["generation"],
        "fallback_retained": fallback is not None,
        "policy": "immutable selection ticket only; trusted tip update is external",
    }
