"""Bounded inspection of ONE explicitly staged HDR+ burst.

Read-only metadata inspection by default. Optional bounded streaming SHA-256
catches byte-identical frame aliases, never certifies shutter independence.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import stat

MAX_DIRECTORY_ENTRIES = 128
MAX_FRAMES = 10
MAX_FRAME_BYTES = 128 * 1024 * 1024
MAX_TOTAL_BYTES = 1024 * 1024 * 1024
_FRAME = re.compile(r"payload_N([0-9]{1,3})\.dng\Z")



def _stat_signature(info: os.stat_result) -> tuple:
    """Verify the pinned object did not change during a bounded read."""
    return (info.st_dev, info.st_ino, info.st_mode, info.st_size,
            info.st_mtime_ns, info.st_ctime_ns)


def _stream_digest(directory_fd: int, name: str, listed: os.stat_result) -> bytes:
    """Hash through a no-follow descriptor relative to the pinned directory.

    The name was allowlisted during nonrecursive directory enumeration. Do not
    expose this name or any digest in the public report.
    """
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
    try:
        fd = os.open(name, flags, dir_fd=directory_fd)
    except OSError:
        raise ValueError("Cannot safely open staged raw frame") from None
    try:
        before = os.fstat(fd)
        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                or _stat_signature(before) != _stat_signature(listed)):
            raise ValueError("Staged raw frame changed or has external hard links")
        digest = hashlib.sha256()
        count = 0
        while True:
            chunk = os.read(fd, 1024 * 1024)
            if not chunk:
                break
            count += len(chunk)
            if count > listed.st_size:
                raise ValueError("Staged raw frame grew during verification")
            digest.update(chunk)
        after = os.fstat(fd)
        if count != listed.st_size or _stat_signature(after) != _stat_signature(before):
            raise ValueError("Staged raw frame changed during byte verification")
        return digest.digest()
    except OSError:
        raise ValueError("Cannot safely read staged raw frame") from None
    finally:
        os.close(fd)

def inspect_burst(directory: str | Path, *, check_byte_distinctness: bool = False,
                  expected_frame_sha256: dict[int, bytes] | None = None,
                  check_dng_headers: bool = False) -> dict:
    """First validate bounded structure; optionally check exact-byte aliases.

    This is *not* a DNG parser, cryptographic source attestation, or evidence
    that distinct bytes came from separate exposures. Checks never modify data.
    """
    if type(check_byte_distinctness) is not bool or type(check_dng_headers) is not bool:
        raise ValueError("check_byte_distinctness must be boolean")
    if expected_frame_sha256 is not None:
        pins = expected_frame_sha256
        if (not check_byte_distinctness or type(pins) is not dict
                or not 2 <= len(pins) <= MAX_FRAMES
                or any(type(k) is not int or not 0 <= k < MAX_FRAMES
                       or type(value) is not bytes or len(value) != 32
                       for k, value in pins.items())
                or len(set(pins.values())) != len(pins)):
            raise ValueError("Invalid predeclared SHA-256 pins or byte verification not enabled")
    root = Path(directory).absolute()
    if any(parent.is_symlink() for parent in (root, *root.parents)):
        raise ValueError("Symlinked directory root or parent refused")
    if not root.is_dir():
        raise ValueError("Expected one existing burst directory")
    root_before = root.stat()
    frames = []
    inodes = set()
    total_bytes = 0
    with os.scandir(root) as directory_entries:
        for count, entry in enumerate(directory_entries, 1):
            if count > MAX_DIRECTORY_ENTRIES:
                raise ValueError("Burst directory entry quota exceeded")
            if entry.is_symlink():
                raise ValueError("Symlink inside staged burst refused")
            if entry.name.lower().endswith(".dng"):
                match = _FRAME.fullmatch(entry.name)
                if match is None:
                    raise ValueError("Unrecognized DNG input name")
                info = entry.stat(follow_symlinks=False)
                if (not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= MAX_FRAME_BYTES):
                    raise ValueError("Nonregular, empty or oversized frame")
                key = (info.st_dev, info.st_ino)
                if key in inodes:
                    raise ValueError("Identical inode attached as distinct frames")
                inodes.add(key)
                index = int(match.group(1))
                frames.append((index, entry.name, info))
                if len(frames) > MAX_FRAMES:
                    raise ValueError("Too many raw frames in a burst")
                total_bytes += info.st_size
                if total_bytes > MAX_TOTAL_BYTES:
                    raise ValueError("Burst exceeds metadata size quota")
    if not 2 <= len(frames) <= MAX_FRAMES:
        raise ValueError("Expected 2..10 separately numbered raw frames")
    if sorted(index for index, _, _ in frames) != list(range(len(frames))):
        raise ValueError("Duplicate, missing or nonzero-start frame indices")
    if expected_frame_sha256 is not None and set(expected_frame_sha256) != {
            index for index, _, _ in frames}:
        raise ValueError("Predeclared SHA-256 pins must cover exactly the admitted frames")

    header_bytes = 0
    if check_byte_distinctness or check_dng_headers:
        # Fail closed when an OS cannot pin directories/files without following
        # symlinks. No fallback to check-then-open by a path string.
        if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
            raise ValueError("Safe descriptor-relative byte verification unavailable")
        try:
            directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        except OSError:
            raise ValueError("Cannot pin staged burst directory") from None
        try:
            if _stat_signature(os.fstat(directory_fd)) != _stat_signature(root_before):
                raise ValueError("Staged burst directory changed before byte verification")
            if check_dng_headers:
                from .dng_header_probe import inspect_frame_header
            digests = set()
            for index, name, info in sorted(frames):
                if check_dng_headers:
                    header_bytes += inspect_frame_header(directory_fd, name, info)
                if check_byte_distinctness:
                    digest = _stream_digest(directory_fd, name, info)
                    if digest in digests:
                        raise ValueError("Byte-identical frame aliases are not independent samples")
                    if (expected_frame_sha256 is not None and
                            not hmac.compare_digest(digest, expected_frame_sha256[index])):
                        raise ValueError("Staged bytes do not match predeclared SHA-256 pin")
                    digests.add(digest)
            if _stat_signature(os.fstat(directory_fd)) != _stat_signature(root_before):
                raise ValueError("Staged burst directory changed during byte verification")
        finally:
            os.close(directory_fd)
    return {
        "policy": "One explicitly staged directory; read-only, no downloads or DNG decode",
        "structural_burst_candidate": True,
        "reported_frame_count": len(frames),
        "sum_of_frame_stat_bytes": total_bytes,
        "data_bytes_read": check_byte_distinctness or check_dng_headers,
        "bounded_header_bytes_inspected": header_bytes,
        "dng_ifd0_header_checked": check_dng_headers,
        "frame_bytes_hashed": check_byte_distinctness,
        "bytes_hashed": total_bytes if check_byte_distinctness else 0,
        "byte_distinctness_checked": check_byte_distinctness,
        "bytewise_distinct_frames_observed": True if check_byte_distinctness else None,
        "local_bytes_match_user_supplied_sha256_pins": True if expected_frame_sha256 is not None else None,
        "original_publisher_hashes_authenticated": False,
        "source_bytes_verified": False,
        "image_format_validated": False,
        "dataset_origin_and_license_verified": False,
        "physical_shutter_independence_verified": False,
        "scene_heldout_eligibility_verified": False,
        "requires_manual_rights_privacy_source_and_scene_review": True,
        "no_photos_modified_or_uploaded": True,
        "limitations": (
            "Different file SHA-256 hashes exclude only exact byte aliases, not "
            "reencoded pixel aliases or separately captured exposure proof. "
            "A local pin file does not authenticate publisher identity. No "
            "DNG parse, source license, physical shutter or image-level matcher verified."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--check-byte-distinctness", action="store_true",
                        help="Opt-in bounded local SHA-256 reading; no hashes output")
    parser.add_argument("--expected-hashes", type=Path,
                        help="Optional strictly predeclared, read-only SHA-256 pin manifest (implies byte check)")
    parser.add_argument("--check-dng-headers", action="store_true",
                        help="Check <=16 KiB/frame classic TIFF IFD0 marker, no RAW decode")
    args = parser.parse_args()
    from .hdrplus_sha256_manifest import load_sha256_pins
    pins = load_sha256_pins(args.expected_hashes) if args.expected_hashes else None
    print(json.dumps(inspect_burst(
        args.directory, check_byte_distinctness=args.check_byte_distinctness or pins is not None,
        expected_frame_sha256=pins, check_dng_headers=args.check_dng_headers), indent=2))


if __name__ == "__main__":
    main()
