"""Experimental two-slot local checkpoint of pinned Photo DNA selection tickets.

Only this module's tiny metadata slots are replaceable. Indexes, catalogs,
selection tickets, images and other user files are never modified or removed.
A local two-slot checkpoint detects incidental corruption; it is NOT a signed
or hardware-backed anti-rollback authority.
"""
from __future__ import annotations

from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import tempfile

from .generation_selection import _read_pinned_ticket, verify_selection
from .snapshot_receipt import _canonical, _name, _pin, _unique_object_pairs

SLOTS = ("tip-a.json", "tip-b.json")
FIELDS = frozenset(("schema", "format", "epoch", "ticket", "parent_tip_sha256"))
MAX_TIP_BYTES = 1024
DEFAULT_FREE_RESERVE = 64 * 1024
LOCK_FILENAME = ".atlas-tip.lock"


class DamagedTipSlotError(ValueError):
    """A pre-existing, unverifiable checkpoint must be preserved for review."""



def _directory(value: str | Path) -> Path:
    path = Path(value)
    if path.is_symlink() or not path.is_dir():
        raise ValueError("Protected tip authority directory must already exist")
    if stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise ValueError("Tip authority directory must be owner-private (0700)")
    return path.resolve()


def _roots(authority: str | Path, cache: str | Path):
    authority, cache = _directory(authority), _directory(cache)
    if (authority == cache or authority.is_relative_to(cache)
            or cache.is_relative_to(authority)):
        raise ValueError("Protected tip authority must be separate from index cache")
    return authority, cache


@contextmanager
def _lock(directory: Path, *, exclusive: bool, create: bool):
    flags = os.O_RDWR | getattr(os, "O_NOFOLLOW", 0)
    if create:
        flags |= os.O_CREAT
    fd = os.open(directory / LOCK_FILENAME, flags, 0o600)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode):
            raise ValueError("Tip lock must be a regular file")
        fcntl.flock(fd, fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH)
        yield
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)


def _validate_ticket(data: object) -> dict:
    if not isinstance(data, dict) or frozenset(data) != FIELDS:
        raise ValueError("Invalid tip record schema fields")
    if type(data["schema"]) is not int or data["schema"] != 1:
        raise ValueError("Unsupported tip schema")
    if data["format"] != "atlas-photo-dna-tip-v1":
        raise ValueError("Unsupported tip format")
    epoch = data["epoch"]
    if type(epoch) is not int or not 1 <= epoch <= 32:
        raise ValueError("Tip epoch outside supported bound")
    ticket = data["ticket"]
    if not isinstance(ticket, list) or len(ticket) != 2:
        raise ValueError("Tip requires one ticket basename and SHA")
    _name(ticket[0], suffix=".selection.json")
    _pin(ticket[1])
    parent = data["parent_tip_sha256"]
    if epoch == 1:
        if parent is not None:
            raise ValueError("Initial tip may not have a predecessor")
    else:
        _pin(parent)
    return data


def _read_slot(path: Path) -> tuple[str, dict]:
    if path.is_symlink() or not path.is_file():
        raise ValueError("Tip slot is missing or is not a regular file")
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(fd, "rb") as source:
        first = os.fstat(source.fileno())
        if not stat.S_ISREG(first.st_mode) or first.st_size > MAX_TIP_BYTES:
            raise ValueError("Tip slot exceeds byte budget")
        raw = source.read(MAX_TIP_BYTES + 1)
        last = os.fstat(source.fileno())
        if ((first.st_size, first.st_ctime_ns, first.st_mtime_ns) !=
                (last.st_size, last.st_ctime_ns, last.st_mtime_ns)
                or len(raw) != first.st_size or len(raw) > MAX_TIP_BYTES):
            raise ValueError("Tip slot changed during verification")
    try:
        data = json.loads(raw.decode("ascii"), object_pairs_hook=_unique_object_pairs)
    except (ValueError, UnicodeError) as exc:
        raise ValueError("Malformed tip slot") from exc
    _validate_ticket(data)
    if raw != _canonical(data):
        raise ValueError("Tip slot is not canonically encoded")
    return hashlib.sha256(raw).hexdigest(), data


def _inspect_slots(authority: Path, cache: Path):
    good, bad = [], []
    for name in SLOTS:
        path = authority / name
        if not path.exists() and not path.is_symlink():
            continue
        try:
            digest, data = _read_slot(path)
            evidence = verify_selection(cache / data["ticket"][0], data["ticket"][1])
            if data["epoch"] != evidence["epoch"]:
                raise ValueError("Tip epoch disagrees with pinned selection")
            good.append((name, digest, data, evidence))
        except (ValueError, OSError):
            bad.append(name)
    if not good:
        if bad:
            raise ValueError("No verifiable tip slot; explicit owner recovery required")
        return None, (), tuple()
    good.sort(key=lambda item: item[2]["epoch"])
    if len(good) > 1:
        low, high = good
        if (high[2]["epoch"] != low[2]["epoch"] + 1 or
                high[2]["parent_tip_sha256"] != low[1]):
            raise ValueError("Tip slots forked, replayed or not contiguous")
    return good[-1], tuple(good), tuple(bad)


def _resolved(authority: Path, cache: Path) -> dict:
    winner, good, bad = _inspect_slots(authority, cache)
    if winner is None:
        raise ValueError("No published tip selection")
    slot, digest, data, evidence = winner
    return {
        "verified": True,
        "epoch": data["epoch"],
        "local_tip_sha256": digest,
        "ticket_sha256": data["ticket"][1],
        "selected_generation": evidence["selected_generation"],
        "selected_catalog_sha256": evidence["selected_catalog_sha256"],
        "catalog_fallback_used": evidence["fallback_used"],
        "degraded_tip_redundancy": bool(bad),
        "valid_tip_slots": len(good),
        "policy": "local two-slot consistency only; no independent anti-rollback proof",
    }


def resolve_tip(authority: str | Path, cache: str | Path) -> dict:
    """Read current or surviving older slot under shared flock; never mutate."""
    authority, cache = _roots(authority, cache)
    with _lock(authority, exclusive=False, create=False):
        return _resolved(authority, cache)


def publish_tip(
    authority: str | Path,
    cache: str | Path,
    *,
    ticket_name: str,
    trusted_ticket_sha256: str,
    expected_previous_tip_sha256: str | None = None,
    reserve_free_bytes: int = DEFAULT_FREE_RESERVE,
) -> dict:
    """Atomically replace only the oldest local tip slot, preserving the newer.

    The externally pinned ticket and selected generation are verified before
    touching any tip slot. Any existing invalid slot blocks further writes;
    read-only degraded fallback remains available for owner-led recovery.
    The expected previous *local* tip digest is CAS, but not a cryptographic
    anti-rollback authority.
    """
    authority, cache = _roots(authority, cache)
    _name(ticket_name, suffix=".selection.json")
    _pin(trusted_ticket_sha256)
    if type(reserve_free_bytes) is not int or not 0 <= reserve_free_bytes <= (1 << 40):
        raise ValueError("Invalid tip disk reserve")
    if expected_previous_tip_sha256 is not None:
        _pin(expected_previous_tip_sha256)
    with _lock(authority, exclusive=True, create=True):
        current, good, bad = _inspect_slots(authority, cache)
        if bad:
            # Recovery reads may still return an older verified slot. Writes
            # are different: do not erase an unknown, torn, or substituted
            # standby slot (including a regular file, not only symlinks).
            # The unavailable generation may be newer than the surviving
            # slot; automatically advancing from it risks a silent rollback.
            raise DamagedTipSlotError(
                "Damaged tip slot requires explicit owner review before any "
                "new checkpoint can be published; existing bytes preserved"
            )
        latest = verify_selection(cache / ticket_name, trusted_ticket_sha256)
        if latest["fallback_used"]:
            raise ValueError("Refusing to publish a degraded new selection ticket")
        ticket = _read_pinned_ticket(cache / ticket_name, trusted_ticket_sha256)
        if current is None:
            if expected_previous_tip_sha256 is not None or latest["epoch"] != 1:
                raise ValueError("Initial tip must select the first verified ticket")
            parent_digest = None
            target_name = SLOTS[0]
        else:
            _, digest, previous, _ = current
            if expected_previous_tip_sha256 != digest:
                raise ValueError("Stale expected tip; refusing competing update")
            if latest["epoch"] != previous["epoch"] + 1:
                raise ValueError("Tip epoch must advance exactly one generation")
            if ticket["parent"] != previous["ticket"]:
                raise ValueError("New selection ticket is not a direct child of prior tip")
            parent_digest = digest
            if len(good) == 1:
                target_name = next(name for name in SLOTS if name != current[0])
            else:
                target_name = good[0][0]  # Replace only older slot.
        data = {"schema": 1, "format": "atlas-photo-dna-tip-v1",
                "epoch": latest["epoch"], "ticket": [ticket_name, trusted_ticket_sha256],
                "parent_tip_sha256": parent_digest}
        _validate_ticket(data)
        raw = _canonical(data)
        if len(raw) > MAX_TIP_BYTES:
            raise ValueError("Tip would exceed private slot byte budget")
        if shutil.disk_usage(authority).free < reserve_free_bytes + len(raw) + 4096:
            raise OSError("Insufficient protected tip volume free space")
        # Atomic filesystem replace changes only the oldest tiny tip metadata,
        # never the previously selected slot or any cache index/receipt.
        fd, temporary = tempfile.mkstemp(prefix=".atlas-tip-", suffix=".tmp", dir=authority)
        try:
            with os.fdopen(fd, "wb") as out:
                os.fchmod(out.fileno(), 0o600)
                out.write(raw)
                out.flush()
                os.fsync(out.fileno())
            os.replace(temporary, authority / target_name)
            try:
                dirfd = os.open(authority, os.O_RDONLY)
                try:
                    os.fsync(dirfd)
                finally:
                    os.close(dirfd)
            except OSError:
                pass
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        # Atomic replacement can succeed while the caller loses its response
        # (process interruption, IO error or a partial durability failure).
        # Re-read the entire selected generation under the SAME exclusive
        # lock before claiming success. Never roll back or erase a suspicious
        # new slot automatically; the old known-good slot is still retained.
        expected_digest = hashlib.sha256(raw).hexdigest()
        try:
            observed = _resolved(authority, cache)
        except (OSError, ValueError) as exc:
            raise RuntimeError(
                "Checkpoint commit outcome unverified; read-only recovery required"
            ) from exc
        if (observed["epoch"] != latest["epoch"] or
                observed["local_tip_sha256"] != expected_digest or
                observed["ticket_sha256"] != trusted_ticket_sha256 or
                observed["degraded_tip_redundancy"] or
                observed["catalog_fallback_used"]):
            raise RuntimeError(
                "Checkpoint publication differs from intended generation; "
                "read-only recovery required"
            )
        return {
            "epoch": latest["epoch"],
            "local_tip_sha256": expected_digest,
            "ticket_sha256": trusted_ticket_sha256,
            "fallback_slot_preserved": current is not None,
            "postpublication_verified": True,
            "policy": "verified local metadata slot switch; no original media or index mutation",
        }
