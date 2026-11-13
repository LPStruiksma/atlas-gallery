#!/usr/bin/env python3
"""Verify locally stored upstream candidate weight bytes, never load them.

Only author-pinned, inspectable third-party metadata is accepted. A matching
hash establishes byte identity, NOT legal rights or recognition quality.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

MAX_BYTES = 64 * 1024 * 1024
HEX = {"sha256": re.compile(r"[a-f0-9]{64}\Z"),
       "sha384": re.compile(r"[a-f0-9]{96}\Z")}
FIELDS = {"id", "file_name", "upstream_page", "source_purpose", "format",
          "bytes", "checksum_algorithm", "checksum", "repository_license_label",
          "weight_rights_status", "production_approved", "caution"}


def validate_register(document: dict) -> dict[str, dict]:
    if (not isinstance(document, dict) or set(document) != {"schema_version", "artifacts"}
            or type(document["schema_version"]) is not int or document["schema_version"] != 1
            or not isinstance(document["artifacts"], list)
            or not 1 <= len(document["artifacts"]) <= 20):
        raise ValueError("Malformed offline candidate artifact register")
    result = {}
    for item in document["artifacts"]:
        if not isinstance(item, dict) or set(item) != FIELDS:
            raise ValueError("Malformed candidate entry")
        name = item["id"]
        if not isinstance(name, str) or not re.fullmatch(r"[a-z][a-z0-9_]{2,79}", name) or name in result:
            raise ValueError("Duplicate or malformed model identifier")
        filename = item["file_name"]
        if not isinstance(filename, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,160}", filename) or filename.startswith("."):
            raise ValueError("Invalid upstream file name")
        page = item["upstream_page"]
        if not isinstance(page, str):
            raise ValueError("Malformed public provenance link")
        url = urlsplit(page)
        if (url.scheme != "https" or url.hostname not in {"github.com", "huggingface.co"}
                or url.username or url.password or url.query or url.fragment):
            raise ValueError("Malformed public provenance link")
        algo = item["checksum_algorithm"]
        digest = item["checksum"]
        if (algo not in HEX or not isinstance(digest, str)
                or not HEX[algo].fullmatch(digest)):
            raise ValueError("Missing or invalid immutable upstream checksum")
        size = item["bytes"]
        if size is not None and (type(size) is not int or not 1 <= size <= MAX_BYTES):
            raise ValueError("Unsafe stated artifact size")
        if (item["source_purpose"] not in {"reid_trained", "imagenet_only"}
                or item["format"] not in {"onnx", "pytorch_pickle"}
                or item["repository_license_label"] not in {"MIT", "Apache-2.0"}
                or item["weight_rights_status"] != "unverified"
                or item["production_approved"] is not False
                or not isinstance(item["caution"], str)
                or not 12 <= len(item["caution"]) <= 240):
            raise ValueError("Candidate register must not declare deployment clearance")
        result[name] = item
    return result


def _open_regular_no_alias(file: Path) -> int:
    """Open a file through pinned directory FDs: no symlink/race traversal.

    O_NOFOLLOW only on the final path is insufficient: an intermediate
    directory can be a symlink, or be replaced between lstat and open.
    Each path component is instead opened relative to an already-open
    directory descriptor with O_DIRECTORY|O_NOFOLLOW.
    """
    if not isinstance(file, Path) or ".." in file.parts:
        raise ValueError("Unsafe local artifact path")
    full = file.absolute()
    components = full.parts
    if len(components) < 2 or components[0] != "/":
        raise ValueError("Unsafe absolute local artifact path")
    directory_flags = (os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
                       | getattr(os, "O_NOFOLLOW", 0)
                       | getattr(os, "O_CLOEXEC", 0))
    file_flags = (os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
                  | getattr(os, "O_NONBLOCK", 0)
                  | getattr(os, "O_CLOEXEC", 0))
    directory_fd = None
    try:
        directory_fd = os.open("/", directory_flags)
        for component in components[1:-1]:
            if component in ("", ".", ".."):
                raise ValueError("Unsafe artifact path component")
            next_fd = os.open(component, directory_flags, dir_fd=directory_fd)
            os.close(directory_fd)
            directory_fd = next_fd
        if components[-1] in ("", ".", ".."):
            raise ValueError("Unsafe artifact leaf name")
        leaf_fd = os.open(components[-1], file_flags, dir_fd=directory_fd)
        try:
            if not stat.S_ISREG(os.fstat(leaf_fd).st_mode):
                raise ValueError("Nonregular or unaliased candidate artifact")
        except BaseException:
            os.close(leaf_fd)
            raise
        return leaf_fd
    except OSError:
        raise ValueError("Unable to open unaliased local artifact") from None
    finally:
        if directory_fd is not None:
            os.close(directory_fd)


def verify_local_file(item: dict, file: Path) -> dict:
    """Hash pinned regular inode without executing or writing model bytes.

    FDs, not caller paths, anchor all observations; hardlinks/renames/symlink
    escapes and changed source stats are refused. This is byte-integrity
    evidence only, NOT file authorship, safe model loading or legal clearance.
    """
    if not isinstance(item, dict) or not isinstance(file, Path):
        raise ValueError("Expected local candidate and Path")
    declared = item["bytes"]
    algorithm = item["checksum_algorithm"]
    expected = item["checksum"]
    if (algorithm not in HEX or type(expected) is not str
            or HEX[algorithm].fullmatch(expected) is None
            or (declared is not None and
                (type(declared) is not int or not 1 <= declared <= MAX_BYTES))):
        raise ValueError("Missing immutable candidate digest or bounded size")

    descriptor = _open_regular_no_alias(file)
    with os.fdopen(descriptor, "rb") as handle:
        first = os.fstat(handle.fileno())
        if (not stat.S_ISREG(first.st_mode) or first.st_nlink != 1
                or not 0 < first.st_size <= MAX_BYTES
                or (declared is not None and declared != first.st_size)):
            raise ValueError("Candidate must be one unaliased bounded file")
        digest = hashlib.new(algorithm)
        total = 0
        while True:
            chunk = handle.read(min(1024 * 1024, MAX_BYTES + 1 - total))
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_BYTES:
                raise ValueError("Artifact exceeds maximum size")
            digest.update(chunk)
        last = os.fstat(handle.fileno())
        if (total != first.st_size or last.st_size != first.st_size
                or (first.st_dev, first.st_ino) != (last.st_dev, last.st_ino)
                or (first.st_mtime_ns, first.st_ctime_ns)
                   != (last.st_mtime_ns, last.st_ctime_ns)
                or digest.hexdigest() != expected):
            raise ValueError("Artifact changed or checksum mismatch")
    return {
        "artifact_id": item["id"],
        "status": "integrity_only_no_execution",
        "checksum_algorithm": algorithm,
        "checksum_matches_upstream_pin": True,
        "source_purpose": item["source_purpose"],
        "format": item["format"],
        "bytes": total,
        "weight_rights_verified": False,
        "production_approved": False,
        "accuracy_benchmarked": False,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact_id", help="Identifier in the versioned register")
    parser.add_argument("local_artifact", type=Path, help="A local file; never executed")
    args = parser.parse_args(argv)
    try:
        manifest = json.loads(Path(__file__).with_name("candidate_model_artifacts.json").read_text(encoding="utf-8"))
        entries = validate_register(manifest)
        if args.artifact_id not in entries:
            raise ValueError("Unknown pinned artifact")
        print(json.dumps(verify_local_file(entries[args.artifact_id], args.local_artifact),
                         sort_keys=True, indent=2))
        return 0
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        print("Candidate integrity verification refused; no bytes executed", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
