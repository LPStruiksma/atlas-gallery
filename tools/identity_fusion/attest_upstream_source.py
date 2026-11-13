#!/usr/bin/env python3
"""Offline, no-model source-commit corroboration for Intel 0287 metadata.

Compare independently obtained local model.yml and LICENSE bytes to the
Git blob IDs observed at one fixed Open Model Zoo commit. A Git object SHA-1
plus caller-provided SHA-256 establishes byte matches, NOT authorship,
commit-tree authenticity, grant of rights, or checkpoint correctness.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

from corroborate_intel_manifest import corroborate_intel_model_manifest
from verify_candidate_artifact import validate_register

PINS_FILE = Path(__file__).with_name("upstream_model_source_pins.json")
ARTIFACTS_FILE = Path(__file__).with_name("candidate_model_artifacts.json")
EXPECTED_REPO = "openvinotoolkit/open_model_zoo"
EXPECTED_MANIFEST_PATH = "models/intel/person-reidentification-retail-0287/model.yml"
EXPECTED_LICENSE_PATH = "LICENSE"
EXPECTED_ACCURACY_PATH = "models/intel/person-reidentification-retail-0287/accuracy-check.yml"
FIELDS = frozenset({
    "schema_version", "source_repository", "source_commit_sha1",
    "manifest_path", "manifest_git_blob_sha1", "license_path",
    "license_git_blob_sha1", "upstream_commit_author_date_utc",
    "source_verification_status", "checkpoint_redistribution_rights_status",
    "training_data_rights_status", "production_approved",
})
SHA1 = re.compile(r"[0-9a-f]{40}\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
V2_EXTRA_FIELDS = frozenset({"accuracy_check_path", "accuracy_check_git_blob_sha1"})
V3_EXTRA_FIELDS = frozenset({
    "manifest_sha256", "license_sha256", "accuracy_check_sha256",
})
# Fixed strong source digests independently computed from upstream GitHub
# bytes at the already recorded af810de commit; not a signed publisher ID.
OFFICIAL_SOURCE_SHA256 = {
    "manifest": "d424d78044d5da414d540514d28da01ee0e26e834965a51c0a6661c05ee190c7",
    "license": "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4",
    "accuracy_check": "6b69d25a54ecc8ce7dcdb304392d464351f1dc4e847feb6e3179b6a4843fb960",
}
MAX_MANIFEST_BYTES = 32768
MAX_LICENSE_BYTES = 16384


def validate_pins(pins: dict) -> dict:
    if (type(pins) is not dict or type(pins.get("schema_version")) is not int
            or pins["schema_version"] not in (1, 2, 3)
            or set(pins) != (
                FIELDS
                | (V2_EXTRA_FIELDS if pins["schema_version"] in (2, 3) else frozenset())
                | (V3_EXTRA_FIELDS if pins["schema_version"] == 3 else frozenset())
            )
            or pins["source_repository"] != EXPECTED_REPO
            or pins["manifest_path"] != EXPECTED_MANIFEST_PATH
            or pins["license_path"] != EXPECTED_LICENSE_PATH
            or any(type(pins[k]) is not str or not SHA1.fullmatch(pins[k])
                   for k in ("source_commit_sha1", "manifest_git_blob_sha1",
                             "license_git_blob_sha1"))
            or not re.fullmatch(r"20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z",
                                str(pins["upstream_commit_author_date_utc"]))
            or pins["source_verification_status"]
            != "commit_and_blob_identifiers_observed_via_github"
            or pins["checkpoint_redistribution_rights_status"] != "unverified"
            or pins["training_data_rights_status"] != "unverified"
            or pins["production_approved"] is not False):
        raise ValueError("Unsafe or malformed upstream source attestation")
    if (pins["schema_version"] in (2, 3)
            and (pins["accuracy_check_path"] != EXPECTED_ACCURACY_PATH
                 or type(pins["accuracy_check_git_blob_sha1"]) is not str
                 or not SHA1.fullmatch(pins["accuracy_check_git_blob_sha1"]))):
        raise ValueError("Invalid upstream accuracy-check source pin")
    if pins["schema_version"] == 3:
        # A schema-v3 fingerprint cannot be replaced by a caller's
        # recomputed checksum of another file.
        if any(pins[key + "_sha256"] != value
               for key, value in OFFICIAL_SOURCE_SHA256.items()):
            raise ValueError("Source v3 differs from immutable upstream SHA256 pins")
    return pins


def read_regular_pinned(path: Path, sha256: str, maximum: int) -> bytes:
    if (type(sha256) is not str or not SHA256.fullmatch(sha256)
            or path.is_symlink() or not path.is_file()
            or type(maximum) is not int or maximum <= 0):
        raise ValueError("Pinned non-symlink local source file required")
    size = path.stat().st_size
    if not 0 < size <= maximum:
        raise ValueError("Source file exceeds bounded size")
    with path.open("rb") as handle:
        raw = handle.read(maximum + 1)
    if (not 0 < len(raw) <= maximum or len(raw) != size
            or hashlib.sha256(raw).hexdigest() != sha256):
        raise ValueError("Local source does not match supplied SHA256")
    return raw


def git_blob_sha1(raw: bytes) -> str:
    if type(raw) is not bytes or not 0 < len(raw) <= MAX_MANIFEST_BYTES:
        raise ValueError("Unsupported Git blob bytes")
    return hashlib.sha1(b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw).hexdigest()


def validate_published_resize_config(raw: bytes) -> dict:
    """Exact-source Market1501 evaluation resize, not native pixel scaling.

    This describes one published validation recipe, never a complete ONNX
    preprocessing contract or device parity certificate.
    """
    if type(raw) is not bytes or not 0 < len(raw) <= MAX_MANIFEST_BYTES:
        raise ValueError("Invalid published model validation configuration")
    try:
        lines = raw.decode("utf-8").splitlines()
    except UnicodeError:
        raise ValueError("Unreadable validation configuration") from None
    requirements = (
        "  - name: person-reidentification-retail-0287",
        "      - name: market1501",
        "          - type: resize",
        "            dst_width: 128",
        "            dst_height: 256",
        "            use_pillow: True",
        "            interpolation: BILINEAR",
    )
    if any(lines.count(line) != 1 for line in requirements):
        raise ValueError("Upstream validation resize provenance differs")
    return {
        "published_validation_dataset": "market1501",
        "published_validation_target_width": 128,
        "published_validation_target_height": 256,
        "published_validation_resize_uses_pillow_bilinear": True,
        "native_intel_pixel_scale_and_resize_parity_verified": False,
    }


def corroborate_source_bytes(
    pins: dict, artifact_register: dict, manifest_raw: bytes, license_raw: bytes,
    *, accuracy_check_raw: bytes | None = None,
) -> dict:
    validate_pins(pins)
    validate_register(artifact_register)
    strong = pins["schema_version"] == 3
    if strong and accuracy_check_raw is None:
        raise ValueError("Strict source v3 requires independently pinned evaluation source")
    if strong and (
        hashlib.sha256(manifest_raw).hexdigest() != pins["manifest_sha256"]
        or hashlib.sha256(license_raw).hexdigest() != pins["license_sha256"]
        or hashlib.sha256(accuracy_check_raw).hexdigest()
           != pins["accuracy_check_sha256"]
    ):
        raise ValueError("Source differs from pinned immutable SHA256 bytes")
    if git_blob_sha1(manifest_raw) != pins["manifest_git_blob_sha1"]:
        raise ValueError("Manifest differs from pinned upstream Git object")
    if git_blob_sha1(license_raw) != pins["license_git_blob_sha1"]:
        raise ValueError("License differs from pinned upstream Git object")
    try:
        manifest = manifest_raw.decode("utf-8")
        license_text = license_raw.decode("utf-8")
    except UnicodeError:
        raise ValueError("Non-UTF8 upstream metadata") from None
    if ("Apache License" not in license_text[:120]
            or "Version 2.0, January 2004" not in license_text[:150]):
        raise ValueError("Unexpected official license header")
    observed = corroborate_intel_model_manifest(artifact_register, manifest)
    if accuracy_check_raw is not None and pins["schema_version"] not in (2, 3):
        raise ValueError("Legacy provenance has no pinned validation source")
    accuracy_report = None
    if accuracy_check_raw is not None:
        if git_blob_sha1(accuracy_check_raw) != pins["accuracy_check_git_blob_sha1"]:
            raise ValueError("Validation source differs from committed Git object")
        accuracy_report = validate_published_resize_config(accuracy_check_raw)
    response = {
        "status": "source_bytes_match_pinned_upstream_git_objects_only",
        "source_repository": EXPECTED_REPO,
        "source_commit_sha1": pins["source_commit_sha1"],
        "manifest_git_blob_sha1_matches": True,
        "license_git_blob_sha1_matches": True,
        "manifest_and_license_agree_with_registered_intel_0287_metadata": (
            observed["registered_size_and_sha384_match_manifest"]
            and observed["source_license_pointer_matches_omz_repository"]
        ),
        "source_sha256_pins_checked_by_cli": False,
        "source_sha256_fixed_to_upstream_revision": strong,
        "strong_three_source_sha256_pins_verified": strong,
        "source_sha256_is_a_signed_publisher_attestation": False,
        "validation_source_object_verified": accuracy_report is not None,
        "source_commit_tree_and_identity_cryptographically_authenticated": False,
        "git_sha1_collision_resistance_guaranteed": False,
        "model_weights_downloaded_or_executed": False,
        "model_bytes_or_graph_identity_verified": False,
        "specific_weight_redistribution_rights_verified": False,
        "training_data_rights_verified": False,
        "preprocessing_parity_verified": False,
        "rear_person_open_set_accuracy_verified": False,
        "production_approved": False,
        "auto_confirm_allowed": False,
    }
    if accuracy_report is not None:
        response["published_validation_preprocessing"] = accuracy_report
    return response


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("manifest", "license"):
        parser.add_argument("--" + name, required=True, type=Path)
        parser.add_argument("--" + name + "-sha256", required=True)
    parser.add_argument("--accuracy-check", type=Path)
    parser.add_argument("--accuracy-check-sha256")
    args = parser.parse_args(argv)
    try:
        if (args.accuracy_check is None) != (args.accuracy_check_sha256 is None):
            raise ValueError("Validation source and SHA256 required together")
        pins = validate_pins(json.loads(PINS_FILE.read_text("utf-8")))
        if pins["schema_version"] == 3:
            if args.accuracy_check is None:
                raise ValueError("Strict v3 source study requires validation config")
            for key, arg in (
                ("manifest", args.manifest_sha256),
                ("license", args.license_sha256),
                ("accuracy_check", args.accuracy_check_sha256),
            ):
                if arg != pins[key + "_sha256"]:
                    raise ValueError("Strong source digest must match registered source")
        artifact_register = json.loads(ARTIFACTS_FILE.read_text("utf-8"))
        manifest = read_regular_pinned(args.manifest, args.manifest_sha256,
                                       MAX_MANIFEST_BYTES)
        license_raw = read_regular_pinned(args.license, args.license_sha256,
                                          MAX_LICENSE_BYTES)
        accuracy_check_raw = (
            read_regular_pinned(args.accuracy_check, args.accuracy_check_sha256,
                                MAX_MANIFEST_BYTES)
            if args.accuracy_check is not None else None
        )
        report = corroborate_source_bytes(
            pins, artifact_register, manifest, license_raw,
            accuracy_check_raw=accuracy_check_raw,
        )
        report["source_sha256_pins_checked_by_cli"] = True
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError):
        # In particular, never print subject data, local paths or file bytes.
        print("Refused inconsistent offline upstream-source attestation",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
