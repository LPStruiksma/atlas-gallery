#!/usr/bin/env python3
"""Offline, read-only source-manifest corroboration for Intel OMZ 0287.

Verifies a user-supplied, byte-pinned upstream model.yml against the existing
local artifact register. Does NOT download, load, execute or license models.
Do not confuse source matching with proof of checkpoint rights or graph identity.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

from verify_candidate_artifact import validate_register

MAX_MANIFEST_BYTES = 32768
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
MODEL_ID = "intel_omz_reid_0287_onnx"
OFFICIAL_MANIFEST = (
    "https://github.com/openvinotoolkit/open_model_zoo/"
    "blob/master/models/intel/person-reidentification-retail-0287/model.yml"
)
OFFICIAL_LICENSE = (
    "https://raw.githubusercontent.com/openvinotoolkit/open_model_zoo/"
    "master/LICENSE"
)
SOURCE_PREFIX = (
    "https://storage.openvinotoolkit.org/repositories/open_model_zoo/"
    "2023.0/models_bin/1/person-reidentification-retail-0287/"
)
REGISTER = Path(__file__).with_name("candidate_model_artifacts.json")


def read_pinned_manifest(path: Path, sha256: str) -> str:
    """Strictly bounded ordinary local source document, not a web retrieval."""
    if (type(sha256) is not str or not SHA256.fullmatch(sha256)
            or path.is_symlink() or not path.is_file()):
        raise ValueError("An independently pinned ordinary source file is required")
    if not 0 < path.stat().st_size <= MAX_MANIFEST_BYTES:
        raise ValueError("Source manifest exceeds strict size cap")
    with path.open("rb") as stream:
        raw = stream.read(MAX_MANIFEST_BYTES + 1)
    if (not 0 < len(raw) <= MAX_MANIFEST_BYTES
            or hashlib.sha256(raw).hexdigest() != sha256):
        raise ValueError("Source bytes differ from the separately supplied pin")
    try:
        result = raw.decode("utf-8")
    except UnicodeError:
        raise ValueError("Source manifest is not UTF-8") from None
    if "\x00" in result or "\r" in result or "\t" in result:
        raise ValueError("Unsupported manifest control characters")
    return result


def parse_intel_manifest_subset(text: str) -> dict:
    """Parse only the pinned Intel OMZ format: NOT a generic YAML loader.

    Deliberately reject aliases, unrecognized file fields, repeated fields,
    multiple top-level files sections, and non-canonical indentation.
    """
    if type(text) is not str or not 0 < len(text.encode("utf-8")) <= MAX_MANIFEST_BYTES:
        raise ValueError("Missing or oversized model manifest")
    inside = False
    seen_section = False
    rows: list[dict] = []
    current = None
    license_lines = []
    input_shape = False
    for line in text.splitlines():
        if not inside:
            if line == "files:":
                if seen_section:
                    raise ValueError("Repeated upstream files section")
                seen_section = True
                inside = True
            elif line.startswith("license:"):
                license_lines.append(line.removeprefix("license:").strip())
            elif line == "    shape: [1, 3, 256, 128]":
                input_shape = True
            continue
        # A new unindented key terminates the files section.
        if line and not line.startswith(" "):
            inside = False
            if line == "files:":
                raise ValueError("Repeated upstream files section")
            if line.startswith("license:"):
                license_lines.append(line.removeprefix("license:").strip())
            continue
        if line.startswith("  - name: "):
            if current is not None:
                rows.append(current)
            current = {"name": line.removeprefix("  - name: ")}
            continue
        match = re.fullmatch(r"    (size|checksum|source): (\S+)", line)
        if match and current is not None:
            key, value = match.groups()
            if key in current:
                raise ValueError("Repeated artifact metadata key")
            current[key] = value
            continue
        raise ValueError("Unexpected upstream files metadata")
    if current is not None:
        rows.append(current)
    if (not seen_section or len(license_lines) != 1
            or license_lines[0] != OFFICIAL_LICENSE or not input_shape
            or not 1 <= len(rows) <= 20):
        raise ValueError("Missing official model/license/input section")
    names = set()
    for row in rows:
        if set(row) != {"name", "size", "checksum", "source"}:
            raise ValueError("Incomplete upstream file metadata")
        if row["name"] in names:
            raise ValueError("Repeated upstream artifact filename")
        names.add(row["name"])
        if (not re.fullmatch(r"[A-Za-z0-9_.\\/-]{1,160}", row["name"])
                or ".." in row["name"].split("/")
                or not re.fullmatch(r"[1-9][0-9]*", row["size"])
                or not re.fullmatch(r"[a-f0-9]{96}", row["checksum"])):
            raise ValueError("Invalid upstream file size/name/checksum")
        source = urlsplit(row["source"])
        if (source.scheme != "https" or source.username or source.password
                or source.query or source.fragment
                or row["source"] != SOURCE_PREFIX + row["name"]):
            raise ValueError("Unsafe or inconsistent upstream storage locator")
    return {"files": rows, "license_pointer": license_lines[0]}


def corroborate_intel_model_manifest(register_doc: dict, source_text: str) -> dict:
    """Corroborate immutable metadata ONLY; all deployment gates remain shut."""
    registered = validate_register(register_doc)
    if MODEL_ID not in registered:
        raise ValueError("Missing pinned Intel ONNX entry")
    model = registered[MODEL_ID]
    if (model["upstream_page"] != OFFICIAL_MANIFEST
            or model["source_purpose"] != "reid_trained"
            or model["format"] != "onnx"
            or model["checksum_algorithm"] != "sha384"):
        raise ValueError("Intel register metadata has unexpected source/format")
    source = parse_intel_manifest_subset(source_text)
    candidates = [r for r in source["files"] if r["name"] == model["file_name"]]
    if len(candidates) != 1:
        raise ValueError("Model filename absent from upstream 0287 manifest")
    candidate = candidates[0]
    if (int(candidate["size"]) != model["bytes"]
            or candidate["checksum"] != model["checksum"]):
        raise ValueError("Model register size/checksum diverges from upstream manifest")
    return {
        "status": "offline_intel_manifest_metadata_corroborated_only",
        "model_id": MODEL_ID,
        "source_manifest_sha256_checked_by_caller": False,
        "upstream_filename_matched_exactly": True,
        "registered_size_and_sha384_match_manifest": True,
        "source_declares_expected_fixed_nchw_input": True,
        "source_license_pointer_matches_omz_repository": True,
        "license_text_and_checkpoint_rights_verified": False,
        "upstream_storage_locator_syntax_checked": True,
        "upstream_source_origin_authenticated": False,
        "0287_directory_uses_0267_onnx_filename": (
            model["file_name"] == "person-reidentification-retail-0267.onnx"
        ),
        "actual_model_bytes_or_graph_identity_verified": False,
        "checkpoint_training_data_rights_verified": False,
        "checkpoint_redistribution_rights_verified": False,
        "local_android_performance_verified": False,
        "open_set_identity_accuracy_verified": False,
        "production_approved": False,
        "auto_confirm_allowed": False,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--manifest-sha256", required=True)
    args = parser.parse_args(argv)
    try:
        source = read_pinned_manifest(args.manifest, args.manifest_sha256)
        # Fixed bundled register only, not an untrusted user-supplied override.
        register = json.loads(REGISTER.read_text(encoding="utf-8"))
        report = corroborate_intel_model_manifest(register, source)
        report["source_manifest_sha256_checked_by_caller"] = True
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError):
        print("Refused incomplete or inconsistent pinned upstream model evidence",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
