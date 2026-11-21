#!/usr/bin/env python3
"""Rebuild #833 gesture evidence files from an emulator job log.

Viewer142GestureNavigationEvidenceTest writes each file to logcat (tag
ATLAS_TESTKIT) as:
    G142 begin <name> <size> <sha256> <chunks>
    G142 chunk <name> <i> <base64>
    G142 end <name>
The stress lane prints that logcat into the job log. This script finds those
lines (whatever timestamp/prefix precedes them), rebuilds every file, checks
chunk count, size and SHA-256, and writes it to OUT_DIR. It refuses
incomplete or mismatched files instead of writing them.

Usage: decode_log.py JOB_LOG OUT_DIR
"""
import base64
import hashlib
import re
import sys
from pathlib import Path

BEGIN = re.compile(r"G142 begin (\S+) (\d+) ([0-9a-f]{64}) (\d+)\s*$")
CHUNK = re.compile(r"G142 chunk (\S+) (\d+) ([A-Za-z0-9+/=]+)\s*$")
END = re.compile(r"G142 end (\S+)\s*$")
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")


def decode(lines):
    meta, parts, done = {}, {}, set()
    for line in lines:
        if m := BEGIN.search(line):
            name, size, digest, count = m.group(1), int(m.group(2)), m.group(3), int(m.group(4))
            meta[name] = (size, digest, count)
            parts[name] = {}
            done.discard(name)
        elif m := CHUNK.search(line):
            name = m.group(1)
            if name in parts:
                parts[name][int(m.group(2))] = m.group(3)
        elif m := END.search(line):
            done.add(m.group(1))
    files, errors = {}, []
    for name, (size, digest, count) in meta.items():
        if not SAFE_NAME.match(name):
            errors.append(f"{name}: unsafe name")
            continue
        if name not in done or sorted(parts[name]) != list(range(count)):
            errors.append(f"{name}: {len(parts[name])}/{count} chunks, end={'yes' if name in done else 'no'}")
            continue
        data = base64.b64decode("".join(parts[name][i] for i in range(count)))
        if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
            errors.append(f"{name}: size/hash mismatch")
            continue
        files[name] = data
    return files, errors


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    text = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace").splitlines()
    files, errors = decode(text)
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for name, data in sorted(files.items()):
        (out / name).write_bytes(data)
        print(f"OK {name} {len(data)} bytes sha256 {hashlib.sha256(data).hexdigest()}")
    for e in errors:
        print(f"REFUSED {e}", file=sys.stderr)
    sys.exit(1 if errors or not files else 0)


if __name__ == "__main__":
    main()
