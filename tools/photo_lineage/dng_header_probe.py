"""Read-only bounded TIFF/DNG IFD0 marker probe; NOT RAW image decoding."""
from __future__ import annotations

import os
import stat

MAX_HEADER_BYTES = 16 * 1024
MAX_IFD0_OFFSET = 8 * 1024
MAX_IFD0_ENTRIES = 256
DNG_VERSION_TAG = 50706


def validate_dng_prefix(prefix: bytes, file_size: int) -> None:
    """Parse classic TIFF IFD0 only, refusing unbounded offset traversal."""
    if (type(prefix) is not bytes or type(file_size) is not int
            or file_size < 8 or not 8 <= len(prefix) <= min(file_size, MAX_HEADER_BYTES)):
        raise ValueError("Invalid bounded DNG prefix input")
    if prefix[:2] == b"II":
        endian = "little"
    elif prefix[:2] == b"MM":
        endian = "big"
    else:
        raise ValueError("Missing TIFF byte-order marker")
    u16 = lambda i: int.from_bytes(prefix[i:i + 2], endian)
    u32 = lambda i: int.from_bytes(prefix[i:i + 4], endian)
    if u16(2) != 42:
        raise ValueError("Unsupported TIFF magic; no BigTIFF or RAW decoding")
    offset = u32(4)
    if not 8 <= offset <= MAX_IFD0_OFFSET or offset + 2 > len(prefix):
        raise ValueError("Unsafe TIFF IFD0 offset")
    count = u16(offset)
    if not 1 <= count <= MAX_IFD0_ENTRIES:
        raise ValueError("Invalid TIFF IFD0 entry quota")
    stop = offset + 2 + 12 * count
    if stop + 4 > len(prefix) or stop + 4 > file_size:
        raise ValueError("Truncated TIFF IFD0")
    found = 0
    for index in range(count):
        entry = offset + 2 + 12 * index
        if u16(entry) == DNG_VERSION_TAG:
            version = prefix[entry + 8:entry + 12]
            if (u16(entry + 2) != 1 or u32(entry + 4) != 4
                    or version[0] != 1 or version[1] > 9):
                raise ValueError("Malformed or unsupported DNGVersion")
            found += 1
    if found != 1:
        raise ValueError("Exactly one DNGVersion required in TIFF IFD0")
    next_ifd = u32(stop)
    if next_ifd and (next_ifd < 8 or next_ifd >= file_size):
        raise ValueError("Invalid next TIFF IFD offset")
    # No EXIF, GPS, SubIFD, image data or downstream offsets are followed.


def inspect_frame_header(directory_fd: int, name: str,
                         listed: os.stat_result) -> int:
    """Read max 16 KiB from a no-follow FD relative to the pinned burst root."""
    try:
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                     dir_fd=directory_fd)
    except OSError:
        raise ValueError("Unsafe raw-frame header open") from None
    sig = lambda s: (s.st_dev, s.st_ino, s.st_mode, s.st_size,
                     s.st_mtime_ns, s.st_ctime_ns)
    try:
        initial = os.fstat(fd)
        if (not stat.S_ISREG(initial.st_mode) or initial.st_nlink != 1
                or sig(initial) != sig(listed)):
            raise ValueError("Raw-frame header changed before reading")
        required = min(initial.st_size, MAX_HEADER_BYTES)
        data = bytearray()
        while len(data) < required:
            chunk = os.read(fd, required - len(data))
            if not chunk:
                raise ValueError("Truncated raw-frame header during reading")
            data.extend(chunk)
        if sig(os.fstat(fd)) != sig(initial):
            raise ValueError("Raw-frame header changed during reading")
        validate_dng_prefix(bytes(data), initial.st_size)
        return len(data)
    except OSError:
        raise ValueError("Unsafe raw-frame header read") from None
    finally:
        os.close(fd)
