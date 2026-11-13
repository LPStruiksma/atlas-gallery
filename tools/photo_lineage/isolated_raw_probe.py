"""Opt-in Linux host-only bounded RAW research worker, not a security sandbox."""
from __future__ import annotations
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import resource
import stat
import sys

MAX_BYTES = 128 * 1024 * 1024
_SHA = re.compile(r"[0-9a-f]{64}\Z")
_NAME = re.compile(r"payload_N[0-9]{1,3}\.dng\Z")


def install_limits(*, memory_mib: int, cpu_seconds: int) -> None:
    if (type(memory_mib) is not int or not 512 <= memory_mib <= 4096 or
            type(cpu_seconds) is not int or not 3 <= cpu_seconds <= 90):
        raise ValueError("Invalid worker resource limits")
    if (sys.platform != "linux" or os.environ.get("ANDROID_ROOT") or
            os.environ.get("TERMUX_VERSION") or Path("/system/build.prop").exists()):
        raise ValueError("Linux host-only worker; Android/Termux refused")
    for kind, value in ((resource.RLIMIT_CORE, 0), (resource.RLIMIT_FSIZE, 0),
                        (resource.RLIMIT_CPU, cpu_seconds),
                        (resource.RLIMIT_AS, memory_mib * 1024 * 1024)):
        resource.setrlimit(kind, (value, value))


def _source_fd(path: Path) -> int:
    if not _NAME.fullmatch(path.name):
        raise ValueError("Expected one numbered staged DNG")
    path = path.absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError("Symlinked staged media path refused")
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except OSError:
        raise ValueError("Could not safely open staged RAW") from None
    info = os.fstat(fd)
    if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or
            not 8 <= info.st_size <= MAX_BYTES):
        os.close(fd)
        raise ValueError("Nonregular, hardlinked or oversized staged RAW")
    return fd


def _identity(info: os.stat_result) -> tuple:
    return (info.st_dev, info.st_ino, info.st_mode, info.st_size,
            info.st_mtime_ns, info.st_ctime_ns)


def _digest_fd(fd: int, size: int) -> bytes:
    os.lseek(fd, 0, os.SEEK_SET)
    digest = hashlib.sha256()
    seen = 0
    while seen < size:
        chunk = os.read(fd, min(1024 * 1024, size - seen))
        if not chunk:
            raise ValueError("Staged RAW truncated")
        digest.update(chunk)
        seen += len(chunk)
    if os.read(fd, 1):
        raise ValueError("Staged RAW grew during digest")
    return digest.digest()


def _check_dng_header(fd: int, file_size: int) -> None:
    """Reject obvious non-DNG content BEFORE importing native RAW code.

    Read at most 16 KiB from the same checked source descriptor; never chase
    EXIF, SubIFD, GPS or pixel-data offsets. This is not format validation.
    """
    from .dng_header_probe import MAX_HEADER_BYTES, validate_dng_prefix
    os.lseek(fd, 0, os.SEEK_SET)
    required = min(file_size, MAX_HEADER_BYTES)
    prefix = bytearray()
    while len(prefix) < required:
        chunk = os.read(fd, required - len(prefix))
        if not chunk:
            raise ValueError("Truncated staged DNG prefix")
        prefix.extend(chunk)
    validate_dng_prefix(bytes(prefix), file_size)


def probe_source(path: Path, expected_sha256: str, *, decoder_module=None) -> dict:
    """Call from already-limited process; decoder injection only for CI fakes."""
    if type(expected_sha256) is not str or not _SHA.fullmatch(expected_sha256):
        raise ValueError("Predeclared lowercase SHA-256 pin required")
    fd = _source_fd(path)
    try:
        initial = os.fstat(fd)
        pin = bytes.fromhex(expected_sha256)
        if not hmac.compare_digest(_digest_fd(fd, initial.st_size), pin):
            raise ValueError("Staged source differs from predeclared byte pin")
        if _identity(os.fstat(fd)) != _identity(initial):
            raise ValueError("Source changed before header admission")
        _check_dng_header(fd, initial.st_size)
        if _identity(os.fstat(fd)) != _identity(initial):
            raise ValueError("Source changed during DNG header admission")
        if decoder_module is None:
            import rawpy as decoder_module
        from .rawpy_metadata_bridge import visible_bayer_calibration
        from .bayer_luminance import bayer_to_grayscale
        os.lseek(fd, 0, os.SEEK_SET)
        # Hold original inode, not a fresh pathname vulnerable to replacement.
        with os.fdopen(os.dup(fd), "rb") as source:
            with decoder_module.imread(source) as raw:
                calib = visible_bayer_calibration(raw)
                gray = bayer_to_grayscale(raw.raw_image_visible, **calib)
                height, width = gray.shape
        if _identity(os.fstat(fd)) != _identity(initial):
            raise ValueError("Source changed during decode")
        if not hmac.compare_digest(_digest_fd(fd, initial.st_size), pin):
            raise ValueError("Staged source changed after decode")
        if _identity(os.fstat(fd)) != _identity(initial):
            raise ValueError("Staged source changed during verification")
        return {
            "source_sha256_pin_matched_before_and_after": True,
            "basic_dng_header_passed": True,
            "full_dng_format_validated": False,
            "grayscale_width": width,
            "grayscale_height": height,
            "grayscale_bytes_generated_in_memory": int(gray.size),
            "original_publisher_identity_authenticated": False,
            "separate_shutter_identity_verified": False,
            "camera_color_fidelity_validated": False,
            "images_exported_or_uploaded": False,
        }
    finally:
        os.close(fd)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--memory-mib", type=int, default=2048)
    parser.add_argument("--cpu-seconds", type=int, default=30)
    args = parser.parse_args()
    try:
        # Limits precede importing NumPy, OpenCV, rawpy or local modules.
        install_limits(memory_mib=args.memory_mib, cpu_seconds=args.cpu_seconds)
        # Python -I omits checkout from sys.path; add only trusted code root.
        sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
        from tools.photo_lineage.isolated_raw_probe import probe_source as packaged_probe
        print(json.dumps(packaged_probe(args.source, args.sha256), sort_keys=True))
        return 0
    except Exception:
        # Native rawpy errors have multiple subclasses; do not print paths or
        # source-derived exception strings to the worker output.
        print(json.dumps({"passed": False, "reason": "RAW probe refused or unavailable"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
