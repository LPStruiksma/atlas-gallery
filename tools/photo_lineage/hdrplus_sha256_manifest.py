"""Strict small user-predeclared HDR+ frame byte SHA-256 pins.

No network calls, no write, no photo read. This validates LOCAL curator-provided
pins, never asserts that the manifest was authored or authenticated by Google.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import stat

MAX_MANIFEST_BYTES = 4096
_DIGEST = re.compile(r"[0-9a-f]{64}\Z")


def _no_duplicate_keys(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key in pinned frame manifest")
        result[key] = value
    return result


def load_sha256_pins(path: str | Path) -> dict[int, bytes]:
    """Read only a tiny exact-schema JSON pin file through a no-follow FD."""
    name = Path(path).absolute()
    if (any(p.is_symlink() for p in (name, *name.parents))
            or not name.is_file()):
        raise ValueError("Expected an existing non-symlink pin manifest")
    if not hasattr(os, "O_NOFOLLOW"):
        raise ValueError("Secure pin-manifest reading unavailable")
    try:
        before = name.stat()
        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                or not 0 < before.st_size <= MAX_MANIFEST_BYTES):
            raise ValueError("Unsafe or oversized pin manifest")
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except OSError:
        raise ValueError("Unable to safely open pin manifest") from None
    try:
        opened = os.fstat(fd)
        fields = lambda s: (
            s.st_dev, s.st_ino, s.st_mode, s.st_size, s.st_mtime_ns, s.st_ctime_ns
        )
        if fields(before) != fields(opened):
            raise ValueError("Pin manifest changed before reading")
        raw = os.read(fd, MAX_MANIFEST_BYTES + 1)
        after = os.fstat(fd)
        if (len(raw) != before.st_size or len(raw) > MAX_MANIFEST_BYTES
                or fields(after) != fields(opened)):
            raise ValueError("Pin manifest changed while reading")
    except OSError:
        raise ValueError("Unable to safely read pin manifest") from None
    finally:
        os.close(fd)
    try:
        data = json.loads(raw.decode("utf-8"), object_pairs_hook=_no_duplicate_keys)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError("Malformed pin manifest JSON") from None
    if (type(data) is not dict or set(data) != {"version", "frame_sha256"}
            or type(data["version"]) is not int or data["version"] != 1
            or type(data["frame_sha256"]) is not dict
            or not 2 <= len(data["frame_sha256"]) <= 10):
        raise ValueError("Expected strict pin manifest version 1 with 2..10 frames")
    pins = {}
    for key, value in data["frame_sha256"].items():
        if (type(key) is not str or re.fullmatch(r"[0-9]", key) is None
                or type(value) is not str or _DIGEST.fullmatch(value) is None):
            raise ValueError("Expected canonical zero-based index and lowercase SHA-256")
        pins[int(key)] = bytes.fromhex(value)
    if len(set(pins.values())) != len(pins):
        raise ValueError("Predeclared frame pins repeat the same content hash")
    if set(pins) != set(range(len(pins))):
        raise ValueError("Pin manifest must contain contiguous indices from zero")
    return pins
