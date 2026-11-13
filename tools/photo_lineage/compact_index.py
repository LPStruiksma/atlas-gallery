"""Copy-on-write compaction of a verified Photo DNA snapshot + pinned deltas.

Research-only numeric posting transformation. No image decoding or original
media access. Never replaces/deletes an existing snapshot, catalog or receipt.
"""
from __future__ import annotations

import heapq
import os
from pathlib import Path
import tempfile

from .external_index import (
    DEFAULT_RESERVE_FREE_BYTES, DOC_MASK, _digest, _read_records,
    _require_disk_headroom, _write_records, build_snapshot, verify_snapshot,
)
from .generation_catalog import verify_catalog
from .snapshot_receipt import _name, _parent, _pin


def compact_verified_generation(
    snapshot: str | Path,
    catalog: str | Path,
    output: str | Path,
    *,
    trusted_catalog_sha256: str,
    max_output_bytes: int = 1_000_000_000,
    reserve_free_bytes: int = DEFAULT_RESERVE_FREE_BYTES,
) -> dict:
    """Publish a NEW immutable numeric index, or fail leaving old files intact.

    The catalog SHA must be retained separately from the catalog. This function
    does not switch the active generation or trust a self-selected directory
    listing. All three paths must be siblings in one protected cache folder.
    """
    _pin(trusted_catalog_sha256)
    if type(max_output_bytes) is not int or not 8 <= max_output_bytes <= 10_000_000_000:
        raise ValueError("max_output_bytes must be an integer from 8 to 10000000000")
    if type(reserve_free_bytes) is not int or not 0 <= reserve_free_bytes <= (1 << 40):
        raise ValueError("Invalid free-space reserve")
    snapshot, catalog, output = Path(snapshot), Path(catalog), Path(output)
    _name(snapshot.name, suffix=".u64")
    _name(catalog.name, suffix=".catalog.json")
    _name(output.name, suffix=".u64")
    parent = _parent(output)
    if _parent(snapshot).resolve() != parent.resolve() or _parent(catalog).resolve() != parent.resolve():
        raise ValueError("All compaction files must share a protected parent directory")
    if output.exists() or output.is_symlink():
        raise FileExistsError("Never overwrite an existing compact snapshot")
    if not snapshot.is_file() or snapshot.is_symlink():
        raise ValueError("Original snapshot must be a regular non-symlink file")

    trusted = verify_catalog(catalog, trusted_catalog_sha256)
    changes = trusted["updates"]
    if not changes:
        raise ValueError("No deltas to compact; preserving existing snapshot")
    source_sha = trusted["snapshot_sha256"]
    source_proof = verify_snapshot(snapshot, source_sha, max_bytes=max_output_bytes)
    added_records = sum(len(words) for words in changes.values() if words is not None)
    upper_output_bytes = source_proof["bytes"] + added_records * 8
    if upper_output_bytes > max_output_bytes:
        raise ValueError("Compact output exceeds explicit byte budget")
    # Old source bytes are already allocated and never removed. Account for
    # one full new output and generous extra replacement run/merge scratch.
    # This does NOT reserve disk; recheck before publishing the final run.
    projected_growth = (source_proof["bytes"] + added_records * 8 * 6
                        + ((added_records + 8191) // 8192) * 4096 + 2 * 1024 * 1024)
    _require_disk_headroom(parent, projected_growth, reserve_free_bytes)
    with tempfile.TemporaryDirectory(prefix=".atlas-compact-", dir=parent) as dirname:
        scratch = Path(dirname)
        replacement_path = scratch / "replacement.u64"
        build_snapshot(
            ((doc, words) for doc, words in sorted(changes.items()) if words is not None),
            replacement_path,
            max_postings=max(1, added_records),
            reserve_free_bytes=reserve_free_bytes,
        )
        final = scratch / "compacted.u64"
        _require_disk_headroom(parent, upper_output_bytes + 4096, reserve_free_bytes)

        def still_current_records():
            for packed in _read_records(snapshot):
                if (packed & DOC_MASK) not in changes:
                    yield packed

        count = _write_records(
            final,
            heapq.merge(still_current_records(), _read_records(replacement_path)),
        )
        # Ensure old evidence has not changed while we derived the new index.
        verify_snapshot(snapshot, source_sha, max_bytes=max_output_bytes)
        checksum = _digest(final)
        proof = verify_snapshot(final, checksum, max_bytes=max_output_bytes)
        if count != proof["postings"]:
            raise ValueError("Compacted record count mismatch")
        if output.exists() or output.is_symlink():
            raise FileExistsError("Never overwrite a concurrently published index")
        os.link(final, output, follow_symlinks=False)
        try:
            directory = os.open(parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        except OSError:
            pass
    return {
        "published": True,
        "previous_snapshot_sha256": source_sha,
        "new_snapshot_sha256": checksum,
        "source_postings": source_proof["postings"],
        "new_postings": count,
        "changed_document_ids": len(changes),
        "output_bytes": proof["bytes"],
        "reserved_free_bytes": reserve_free_bytes,
        "policy": "new immutable numeric cache only; no original media or activation changes",
    }
