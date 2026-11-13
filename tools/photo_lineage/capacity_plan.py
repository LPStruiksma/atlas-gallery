"""No-execution numeric space forecast for 150k-scale Photo DNA posting indexes.

The real builder already checks scratch headroom before publication; this
pure-stdlib calculator exposes its same conservative arithmetic up front.
No photos, databases, filesystem storage inspection, allocation or writes.
"""
from __future__ import annotations

import argparse
import json

from .external_index import (
    DEFAULT_RESERVE_FREE_BYTES, MAX_RESERVE_FREE_BYTES,
    _maximum_scratch_reservation,
)

BUILDER_MAX_POSTINGS = 100_000_000
MIB = 1024 * 1024


def plan_build(
    *, photos: int = 150_000, max_words_per_photo: int = 384,
    chunk_records: int = 8192,
    reserve_free_bytes: int = DEFAULT_RESERVE_FREE_BYTES,
    available_free_bytes: int | None = None,
) -> dict:
    """Show maximum reserved scratch/free-space needed *before* building.

    'Available free' must mean actual free bytes on the SAME destination
    volume, measured after preserving all existing generations/caches. An
    advisory positive result is never permission to launch on private photos.
    """
    if type(photos) is not int or not 1 <= photos <= 1_000_000:
        raise ValueError("photos must be integer 1..1,000,000")
    if type(max_words_per_photo) is not int or not 1 <= max_words_per_photo <= 4096:
        raise ValueError("max_words_per_photo must be integer 1..4096")
    postings = photos * max_words_per_photo
    if postings > BUILDER_MAX_POSTINGS:
        raise ValueError("Posting total exceeds existing builder's 100 million record ceiling")
    if type(chunk_records) is not int or not 512 <= chunk_records <= 1_000_000:
        raise ValueError("chunk_records must be integer 512..1,000,000")
    if type(reserve_free_bytes) is not int or not 0 <= reserve_free_bytes <= MAX_RESERVE_FREE_BYTES:
        raise ValueError("Invalid protected free-space reserve")
    if available_free_bytes is not None and (
        type(available_free_bytes) is not int or not 0 <= available_free_bytes <= (1 << 60)
    ):
        raise ValueError("available_free_bytes must be explicit nonnegative integer")
    worst_final = postings * 8
    runs = (postings + chunk_records - 1) // chunk_records
    scratch = _maximum_scratch_reservation(postings, chunk_records)
    required = scratch + reserve_free_bytes
    if available_free_bytes is None:
        status = "unknown_free_space_do_not_start"
        shortfall = None
        surplus = None
    elif available_free_bytes < required:
        status = "no_go_insufficient_reported_space"
        shortfall = required - available_free_bytes
        surplus = 0
    else:
        status = "advisory_headroom_only_not_deployment_go"
        shortfall = 0
        surplus = available_free_bytes - required
    return {
        "policy": "Arithmetic research planning only; never starts indexing or accesses private media",
        "photo_count_assumed": photos,
        "max_descriptor_words_per_photo_assumed": max_words_per_photo,
        "posting_upper_count": postings,
        "packed_snapshot_upper_bytes": worst_final,
        "packed_snapshot_upper_mib": round(worst_final / MIB, 2),
        "initial_runs_upper_count": runs,
        "scratch_advisory_upper_bytes": scratch,
        "scratch_advisory_upper_mib": round(scratch / MIB, 2),
        "protected_reserve_bytes": reserve_free_bytes,
        "free_bytes_needed_before_build": required,
        "free_mib_needed_before_build": round(required / MIB, 2),
        "reported_free_bytes": available_free_bytes,
        "conservative_admission": status,
        "reported_shortfall_bytes": shortfall,
        "reported_surplus_bytes": surplus,
        "assumptions": (
            "Existing immutable generations and manifests remain retained. If free space is supplied, "
            "it MUST have been measured on the actual destination volume AFTER accounting for them. "
            "The 3x posting scratch plus run overhead is advisory and not an atomic reservation; "
            "simultaneous writers, crashes, fs metadata, delta compaction, manifests, file IDs, "
            "photo decoding/ORB CPU/RAM, battery and thermal costs are excluded. "
            "A favorable forecast is NEVER permission to index a private phone gallery."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--photos", type=int, default=150_000)
    parser.add_argument("--max-words-per-photo", type=int, default=384)
    parser.add_argument("--chunk-records", type=int, default=8192)
    parser.add_argument("--reserve-free-bytes", type=int,
                        default=DEFAULT_RESERVE_FREE_BYTES)
    parser.add_argument("--available-free-bytes", type=int, default=None,
                        help="EXPLICIT hypothetical or trusted same-volume free byte count; does not inspect disk")
    args = parser.parse_args()
    result = plan_build(
        photos=args.photos, max_words_per_photo=args.max_words_per_photo,
        chunk_records=args.chunk_records,
        reserve_free_bytes=args.reserve_free_bytes,
        available_free_bytes=args.available_free_bytes,
    )
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
