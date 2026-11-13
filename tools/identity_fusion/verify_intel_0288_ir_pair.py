#!/usr/bin/env python3
"""Offline byte-only attestation for Intel ReID 0288 OpenVINO IR XML+BIN.

The publisher's exact pinned model.yml has NO ONNX asset for 0288.
An IR checkpoint is TWO distinct files; one trusted hash is insufficient.
This module never parses XML, loads graphs, imports OpenVINO or executes ML.
"""
from __future__ import annotations

import argparse
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys

from verify_candidate_artifact import _open_regular_no_alias

SOURCE_COMMIT = "af810de42bcf460a18610481f639dcdc86249e69"
SOURCE_MANIFEST_BLOB = "4f8409e88de4399b2f9471b1962ea18a3242a1e2"
SOURCE_MANIFEST_PATH = (
    "models/intel/person-reidentification-retail-0288/model.yml"
)
MAX_IR_FILE_BYTES = 8 * 1024 * 1024
DIGEST = re.compile(r"[a-f0-9]{96}\Z")
MODEL_NAME = "person-reidentification-retail-0288"
FILE_NAMES = {"xml": MODEL_NAME + ".xml", "bin": MODEL_NAME + ".bin"}
# Immutable SHA384+exact byte size from Intel's public source manifest,
# verified at SOURCE_COMMIT. These are manifest assertions, not proof of
# third-party weight authorship, redistribution rights, or graph semantics.
IR_PINS = {
    "FP32": {
        "xml": (450664,
                "25082c8e783ad358396039d6c3969f9d862512de0cd3dd87b23c2e5790fabc64e288336e5d42369fcc4c347be15c19f2"),
        "bin": (728052,
                "ffc5956253b60f9fac52189976a2ceba2b69288715b35caeff05238840b6e87dcc314d777b612c54d55d976988d6b9d9"),
    },
    "FP16": {
        "xml": (614533,
                "18814c6445b35224987bc44bcbdb47da6c3826e5da0b6aa00a88c06bf71491a8c4f42e1f62424c077cce46e76a41f7a2"),
        "bin": (364098,
                "0a5ca9d000ce63b078149a3aa3993cd6e92d52fdecf1ff58fbe717481c2043db348938c32fed964846817dc321825a4c"),
    },
}


def _pins_for_precision(precision, synthetic_pins):
    if type(precision) is not str or precision not in IR_PINS:
        raise ValueError("Unsupported pinned IR model precision")
    if synthetic_pins is not None:
        if type(synthetic_pins) is not dict:
            raise ValueError("Invalid fictional-only checkpoint fixture")
        pins = synthetic_pins
    else:
        pins = IR_PINS[precision]
    if type(pins) is not dict or set(pins) != {"xml", "bin"}:
        raise ValueError("Complete two-part IR pin required")
    for key in FILE_NAMES:
        item = pins[key]
        if (type(item) is not tuple or len(item) != 2
                or type(item[0]) is not int
                or not 1 <= item[0] <= MAX_IR_FILE_BYTES
                or type(item[1]) is not str
                or DIGEST.fullmatch(item[1]) is None):
            raise ValueError("Malformed immutable IR source checksum and size")
    return pins


def _digest_open_descriptor(handle, expected_size, expected_sha384):
    before = os.fstat(handle.fileno())
    if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
            or before.st_size != expected_size
            or not 0 < before.st_size <= MAX_IR_FILE_BYTES):
        raise ValueError("Untrusted IR member is aliased or wrong size")
    digest = hashlib.sha384()
    length = 0
    while length <= MAX_IR_FILE_BYTES:
        chunk = handle.read(min(1024 * 1024, MAX_IR_FILE_BYTES + 1 - length))
        if not chunk:
            break
        length += len(chunk)
        digest.update(chunk)
    if (length != before.st_size or length > MAX_IR_FILE_BYTES
            or digest.hexdigest() != expected_sha384):
        raise ValueError("IR source member not the pinned publisher bytes")
    return before


def verify_ir_pair(directory: Path, precision: str, *,
                   _synthetic_pins=None):
    """Verify BOTH pinned source members by stable open descriptors.

    The private fictional-pins injection exists only for synthetic tests;
    such a result is explicitly nonauthoritative and cannot be converted
    into official checkpoint or production evidence.
    """
    pins = _pins_for_precision(precision, _synthetic_pins)
    if not isinstance(directory, Path):
        raise ValueError("IR directory must be a local Path")
    official = _synthetic_pins is None
    descriptors = {}
    with ExitStack() as stack:
        for extension in ("xml", "bin"):
            file_path = directory / FILE_NAMES[extension]
            descriptor = _open_regular_no_alias(file_path)
            try:
                descriptors[extension] = stack.enter_context(
                    os.fdopen(descriptor, "rb"))
            except BaseException:
                os.close(descriptor)
                raise
        first_stats = {
            ext: _digest_open_descriptor(
                descriptors[ext], pins[ext][0], pins[ext][1])
            for ext in ("xml", "bin")
        }
        # A split checkpoint must consist of TWO distinct regular inodes;
        # a bind/hardlinked substitute is not a valid pair.
        if (first_stats["xml"].st_dev, first_stats["xml"].st_ino) == (
                first_stats["bin"].st_dev, first_stats["bin"].st_ino):
            raise ValueError("IR XML and BIN unexpectedly alias")
        # Check both open descriptors after BOTH reads, not just after
        # each separate hash. An observed in-place rewrite is refused.
        for ext, first in first_stats.items():
            last = os.fstat(descriptors[ext].fileno())
            if (first.st_dev, first.st_ino, first.st_nlink, first.st_size,
                first.st_mtime_ns, first.st_ctime_ns) != (
                last.st_dev, last.st_ino, last.st_nlink, last.st_size,
                last.st_mtime_ns, last.st_ctime_ns):
                raise ValueError("IR source mutated during paired verification")
    return {
        "model": MODEL_NAME,
        "format": "openvino_ir_xml_plus_bin_NOT_ONNX",
        "precision": precision,
        "verification": ("publisher_manifest_paired_sha384_integrity_only"
                         if official else "fictional_synthetic_pair_only"),
        "source_commit_recorded": SOURCE_COMMIT,
        "source_manifest_git_blob_recorded": SOURCE_MANIFEST_BLOB,
        "publisher_manifest_pair_checksums_checked": official,
        "both_members_required_and_byte_integrity_checked": True,
        "xml_bytes": pins["xml"][0],
        "bin_bytes": pins["bin"][0],
        "actual_graph_execution_or_semantics_checked": False,
        "native_onnx_runtime_loadable_without_conversion": False,
        "conversion_parity_or_android_backend_verified": False,
        "publisher_and_model_training_rights_verified": False,
        "human_review_required": True,
        "auto_confirm_allowed": False,
        "production_approved": False,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("precision", choices=sorted(IR_PINS))
    parser.add_argument("staged_ir_directory", type=Path)
    args = parser.parse_args(argv)
    try:
        result = verify_ir_pair(args.staged_ir_directory, args.precision)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (OSError, UnicodeError, ValueError, TypeError):
        print("Refused unsafe or unverified paired Intel IR source",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
