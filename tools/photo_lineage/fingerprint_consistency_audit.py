"""Read-only, bounded audit of cached Photo DNA visual-word fingerprints.

Never changes source photos or SQLite; never claims cryptographic byte
integrity or independent photographic exposure. Caller pages explicitly.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import stat

import cv2

from .matcher import read_grayscale
from .retrieval import KEYPOINTS, BANDS, MAX_SIDE, open_db, words_for_image

MAX_PAGE = 64
MAX_INDEX_WORDS = KEYPOINTS * len(BANDS)


def _signature(record) -> tuple:
    return (record.st_dev, record.st_ino, record.st_size,
            record.st_mtime_ns, record.st_ctime_ns)


def audit_fingerprints(db_path: str | Path, *, after_id: int = 0,
                       page_size: int = 16) -> dict:
    """Audit ONE full numeric-ID page, never silently claim whole-index coverage.

    Verify paths and fresh ORB words within a fixed read transaction.
    One mismatch means stale cache evidence, NOT different physical
    exposure or permission to rewrite/delete user photos.
    """
    if type(after_id) is not int or not 0 <= after_id <= 2**63 - 1:
        raise ValueError("after_id must be a nonnegative SQLite row cursor")
    if type(page_size) is not int or not 1 <= page_size <= MAX_PAGE:
        raise ValueError("page_size must be integer 1..64")
    checked = mismatches = metadata_stale = unreadable = 0
    with closing(open_db(db_path)) as conn:
        conn.execute("BEGIN")
        found = conn.execute(
            "SELECT id,path,size,mtime_ns,word_count FROM photos "
            "WHERE id>? ORDER BY id LIMIT ?",
            (after_id, page_size + 1),
        ).fetchall()
        has_more = len(found) > page_size
        page = found[:page_size]
        for photo_id, source, expected_size, expected_time, expected_count in page:
            path = Path(source)
            try:
                before = path.lstat()
            except (OSError, ValueError):
                metadata_stale += 1
                continue
            if (not stat.S_ISREG(before.st_mode) or
                    (before.st_size, before.st_mtime_ns) !=
                    (expected_size, expected_time)):
                metadata_stale += 1
                continue
            try:
                gray = read_grayscale(path, MAX_SIDE)
                after = path.lstat()
                if not stat.S_ISREG(after.st_mode) or _signature(before) != _signature(after):
                    metadata_stale += 1
                    continue
                fresh = words_for_image(gray)
            except (OSError, ValueError, cv2.error):
                # Inaccessible/undecodable != definitely missing or valid.
                unreadable += 1
                continue
            # LIMIT keeps a corrupted/crafted row from turning the audit
            # into a potentially unbounded SQLite posting enumeration.
            stored_rows = conn.execute(
                "SELECT word FROM words WHERE photo_id=? LIMIT ?",
                (photo_id, MAX_INDEX_WORDS + 1),
            ).fetchall()
            stored = {word for (word,) in stored_rows}
            if (len(stored_rows) > MAX_INDEX_WORDS
                    or expected_count != len(stored)
                    or any(type(word) is not int or not 0 <= word < 1 << 26
                           for word in stored)
                    or stored != fresh):
                mismatches += 1
            else:
                checked += 1

    next_id = page[-1][0] if page else after_id
    return {
        "policy": "Read-only bounded cache visual-word audit, NOT source-photo proof or automatic cache repair",
        "page_after_id": after_id,
        "next_after_id": next_id,
        "page_size_limit": page_size,
        "rows_examined": len(page),
        "verified_cached_fingerprints": checked,
        "fingerprint_mismatches": mismatches,
        "metadata_stale_or_missing": metadata_stale,
        "unreadable_or_undecodable": unreadable,
        "has_more_after_this_page": has_more,
        "page_fully_accounted_for": (
            checked + mismatches + metadata_stale + unreadable == len(page)
        ),
        "all_records_in_gallery_verified": False,
        "cryptographic_source_byte_integrity_proven": False,
        "common_physical_shutter_proven": False,
        "source_or_cache_modified": False,
        "private_paths_or_pixels_in_report": False,
        "limitations": (
            "This checks current decoded ORB words against cached postings, not "
            "cryptographic byte equality. Different images can collide in feature "
            "space and concurrent cache writes can invalidate a read snapshot. "
            "Pages are independent bounded snapshots; a caller must iterate to "
            "completion and coordinate any concurrent index writes. Unreadable "
            "files remain unknown. No automatic deletion, grouping or repair."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True, type=Path)
    parser.add_argument("--after-id", type=int, default=0)
    parser.add_argument("--page-size", type=int, default=16)
    args = parser.parse_args()
    print(json.dumps(audit_fingerprints(args.db, after_id=args.after_id,
                                        page_size=args.page_size), indent=2))


if __name__ == "__main__":
    main()
