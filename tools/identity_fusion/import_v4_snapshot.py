#!/usr/bin/env python3
"""Read-only V4 confirmed-face snapshot -> ephemeral V5 evidence-store adapter.

Only accepts a CONSISTENT, OFFLINE standalone snapshot. No live V4/Native DB
writes, filesystem photo reads, network, guesses, or person confirmations.
"""
from __future__ import annotations

from contextlib import closing
from pathlib import Path
import math
import os
import sqlite3
import stat

from verify_candidate_artifact import _open_regular_no_alias

from evidence_store import initialize, add_asset, add_observation, add_candidate_evidence

MAX_CONFIRMED = 100_000
MAX_OFFLINE_SNAPSHOT_BYTES = 512 * 1024 * 1024
SQLITE_HEADER = b"SQLite format 3\x00"


def _read_fixed_offline_snapshot(source_path: Path) -> bytes:
    """Read one bounded, unaliased stable SQLite inode by descriptor only.

    Never let SQLite open a caller-controlled pathname or follow a
    symlink to a possibly live V4/Native People database. Sidecars and
    concurrently mutating input fail closed; immutable copy is queried
    in a separate in-memory SQLite instance.
    """
    if not isinstance(source_path, Path):
        raise ValueError("Expected a local standalone snapshot Path")
    if any(Path(str(source_path) + suffix).exists()
           for suffix in ("-wal", "-shm", "-journal")):
        raise ValueError("SQLite sidecar present; require offline backup")
    descriptor = _open_regular_no_alias(source_path)
    with os.fdopen(descriptor, "rb") as handle:
        first = os.fstat(handle.fileno())
        if (not stat.S_ISREG(first.st_mode) or first.st_nlink != 1
                or not 100 <= first.st_size <= MAX_OFFLINE_SNAPSHOT_BYTES):
            raise ValueError("Missing, aliased or oversized offline V4 snapshot")
        raw = handle.read(MAX_OFFLINE_SNAPSHOT_BYTES + 1)
        second = os.fstat(handle.fileno())
        if (len(raw) != first.st_size
                or (first.st_dev, first.st_ino, first.st_size,
                    first.st_nlink, first.st_mtime_ns, first.st_ctime_ns)
                != (second.st_dev, second.st_ino, second.st_size,
                    second.st_nlink, second.st_mtime_ns, second.st_ctime_ns)):
            raise ValueError("Offline V4 snapshot changed during read")
        if not raw.startswith(SQLITE_HEADER):
            raise ValueError("Not a standalone SQLite snapshot")
    if any(Path(str(source_path) + suffix).exists()
           for suffix in ("-wal", "-shm", "-journal")):
        raise ValueError("SQLite sidecar appeared during snapshot read")
    return raw


REQUIRED = {
    "images": {"id", "path", "width", "height", "state"},
    "faces": {"id", "image_id", "x", "y", "w", "h",
              "person_id", "review", "ignored"},
    "people": {"id"},
}


def load_confirmed_v4_snapshot(source_path: Path, max_faces: int = MAX_CONFIRMED):
    """Return (memory-only evidence SQLite, aggregate-only report).

    The caller owns and must close the returned in-memory connection.
    No output SQLite file is written. This does NOT infer missing identities.
    """
    if not 1 <= max_faces <= MAX_CONFIRMED:
        raise ValueError("Unsafe confirmed-face import budget")
    snapshot_bytes = _read_fixed_offline_snapshot(source_path)
    # In-memory deserialization is critical: after reading a stable inode,
    # SQLite must NEVER re-open the original path, which an attacker or an
    # accidental live WAL writer might replace during the SQL query.
    # If Python/SQLite lacks deserialize support, refuse instead of falling
    # back to unsafe file-path based database opening.
    try:
        with closing(sqlite3.connect(":memory:", timeout=2)) as source:
            if not hasattr(source, "deserialize"):
                raise ValueError("SQLite deserialize support is required")
            source.deserialize(snapshot_bytes)
            source.row_factory = sqlite3.Row
            source.execute("PRAGMA query_only=ON")
            # The connection uses sqlite3.Row: compare values, not Row
            # objects against tuples (those are never equal in Python).
            integrity = [tuple(row) for row in
                         source.execute("PRAGMA integrity_check").fetchall()]
            if integrity != [("ok",)]:
                raise ValueError("Offline V4 snapshot integrity check failed")
            for table, columns in REQUIRED.items():
                actual = {row[1] for row in source.execute(
                    f"PRAGMA table_info({table})"
                ).fetchall()}
                if not columns.issubset(actual):
                    raise ValueError("Snapshot is not the expected V4 schema")
            count = source.execute("""SELECT COUNT(*) FROM faces
                WHERE person_id IS NOT NULL AND review='confirmed'
                  AND ignored=0""").fetchone()[0]
            if count > max_faces:
                raise ValueError("V4 confirmed-face import exceeds the explicit budget")
            rows = source.execute("""SELECT f.id AS face_id,f.person_id,i.id AS image_id,
                    i.path,i.width,i.height,f.x,f.y,f.w,f.h
                FROM faces f JOIN images i ON i.id=f.image_id
                JOIN people p ON p.id=f.person_id
                WHERE f.person_id IS NOT NULL AND f.review='confirmed'
                  AND f.ignored=0 AND i.state='done'
                ORDER BY f.id""").fetchall()
            # Materialize offline rows before leaving the read-only source.
            snapshot = [tuple(row) for row in rows]
    except sqlite3.DatabaseError:
        raise ValueError("Unreadable or incompatible offline V4 SQLite snapshot") from None

    target = sqlite3.connect(":memory:")
    initialize(target)
    seen_assets = set()
    imported = 0
    skipped_bad_regions = 0
    skipped_unsupported_paths = 0
    for fid, pid, image_id, path, width, height, x, y, w, h in snapshot:
        # Detector boxes might extend outside crops or old scans may lack
        # source dimensions. Skip rather than invent an exact region.
        if any(not isinstance(a, (int, float)) or not math.isfinite(a)
               for a in (width, height, x, y, w, h)):
            skipped_bad_regions += 1
            continue
        if width <= 0 or height <= 0 or w <= 0 or h <= 0 or x < 0 or y < 0:
            skipped_bad_regions += 1
            continue
        # V4's pixel-space +0.01 tolerance is *not* equivalent to
        # evidence_store.add_observation's normalized [0,1] bounds.
        # A box ending just 0.005 px outside a 100-px photo could pass
        # here, then fail the evidence-store validation and abort the
        # ENTIRE snapshot import. Compute the exact region ONCE and
        # validate against the actual downstream contract before adding
        # even the asset; malformed single faces must be counted/skipped.
        region = (x / width, y / height, w / width, h / height)
        nx, ny, nw, nh = region
        if (any(not math.isfinite(v) for v in region)
                or nx < 0 or ny < 0 or nx > 1 or ny > 1
                or not (0 < nw <= 1) or not (0 < nh <= 1)
                or nx + nw > 1.00000001 or ny + nh > 1.00000001):
            skipped_bad_regions += 1
            continue
        if not isinstance(path, str) or not path.startswith("/storage/emulated/0/"):
            skipped_unsupported_paths += 1
            continue
        asset_uid = f"v4-image:{image_id}"
        if image_id not in seen_assets:
            try:
                add_asset(target, uid=asset_uid, exact_path=path)
            except ValueError:
                skipped_unsupported_paths += 1
                continue
            seen_assets.add(image_id)
        observation_uid = f"v4-face:{fid}"
        add_observation(target, uid=observation_uid, asset_uid=asset_uid,
                        region=region,
                        visibility="unknown", occlusion_fraction=None)
        # This preserves the SOURCE provenance that the user confirmed face
        # assignment in V4, but V5 still cannot write/confirm any Person.
        add_candidate_evidence(
            target, uid=f"v4-reviewed:{fid}", observation_uid=observation_uid,
            person_id=int(pid), family="face", polarity="supports",
            correlation_group=f"v4-photo:{image_id}",
            method_version="v4-human-confirmed-face-reference-v1",
            source_reference=f"v4:face:{fid}"
        )
        imported += 1
    return target, {
        "source_confirmed_faces": int(count),
        "imported_review_references": imported,
        "distinct_local_assets": len(seen_assets),
        "skipped_invalid_face_region": skipped_bad_regions,
        "skipped_unsupported_source_path": skipped_unsupported_paths,
        "read_only_source": True,
        "review_only_target": True,
        "photos_or_embeddings_uploaded": False,
        "note": "This is only a reference adapter, not person re-identification"
    }
