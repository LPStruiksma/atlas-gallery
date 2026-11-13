#!/usr/bin/env python3
"""No-execution, source-only FastReID Market1501 BoT/AGW provenance audit.

Only independently staged public TEXT is inspected. No network, photos, model
weights, unsafe pickle, PyTorch, package imports, images or inference.
A SOURCE blob match never grants checkpoint/weights/training-data rights.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys

UPSTREAM_REPO = "JDAI-CV/fast-reid"
UPSTREAM_COMMIT = "c9bc3ceb2f7a6438b62fb515ea3df6d1e999e95d"
# Observed using upstream Git object contents at the SAME exact commit.
PINS = {
    "zoo": ("MODEL_ZOO.md", "d4fc8749c022fe05a5503e8086f4d0db8edbcdb8"),
    "license": ("LICENSE", "f632668fe3fa3d7727567d0324975f3e5c501413"),
    "bot": ("configs/Market1501/bagtricks_R50.yml", "4c822ddbf6255029d4937c1c19b0452130f7a899"),
    "agw": ("configs/Market1501/AGW_R50.yml", "236803dd5ba5274dc372dc0c99c6c9463fa885c0"),
    "base_bot": ("configs/Base-bagtricks.yml", "9bfb11fd986e911e6ad937acb66d2b1b582cc757"),
    "base_agw": ("configs/Base-AGW.yml", "b737bb8d3d963fc6383672adcfe3ff33c62384fc"),
}
# SHA-256 independently computed from GitHub-served exact source bytes at
# UPSTREAM_COMMIT. These strong pins supplement Git's legacy SHA-1 blob IDs;
# they do not cryptographically authenticate the upstream publisher.
SOURCE_SHA256 = {
    "zoo": "72c4f5c6bba0e44d1f11f5badee651df592b60c3b6a8de6a0650068f2494d632",
    "license": "14a8a9a670fa989f040bc7a5d964fa7a478ffd7cb44407a9061303240d9e21df",
    "bot": "1c8de024f01fdc559cb1925e9ea617bf7c6b30942e587f398f65e68f97c09d08",
    "agw": "007d471f5640989ea33697f622568ea4375cc700c3511bbfd08cc2b0e98cd1ea",
    "base_bot": "2054e2d0e3be7fe7daf6a5814a5ffaf98279c05cf59e805fe81d6c43179327e3",
    "base_agw": "bd061de5f34af6d943233e09566759f18302bf0ae71edbd1ad5d2ee8de458939",
}
MAX_SOURCE_BYTES = 32 * 1024
SHA256 = re.compile(r"[a-f0-9]{64}\Z")
# The release is a separate asset from the Git repository: it is NOT pinned
# by MODEL_ZOO's own Git blob hash.
RELEASE_URLS = {
    "bot_r50_market1501":
        "https://github.com/JDAI-CV/fast-reid/releases/download/v0.1.1/market_bot_R50.pth",
    "agw_r50_market1501":
        "https://github.com/JDAI-CV/fast-reid/releases/download/v0.1.1/market_agw_R50.pth",
}


def git_blob_sha1(blob: bytes) -> str:
    if type(blob) is not bytes or not 0 < len(blob) <= MAX_SOURCE_BYTES:
        raise ValueError("Unsupported bounded source bytes")
    return hashlib.sha1(
        b"blob " + str(len(blob)).encode("ascii") + b"\x00" + blob
    ).hexdigest()


def read_pinned_text(path: Path, sha256: str) -> bytes:
    """Read a regular unaliased file with independent SHA256 and size cap."""
    if (not isinstance(path, Path) or type(sha256) is not str
            or not SHA256.fullmatch(sha256)):
        raise ValueError("Valid independent source SHA256 and Path are required")
    full = path.absolute()
    if any(p.is_symlink() for p in (full, *full.parents)):
        raise ValueError("Staged source contains symlink")
    if not full.is_file():
        raise ValueError("Staged source must be regular")
    fd = os.open(full, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(fd, "rb") as stream:
        first = os.fstat(stream.fileno())
        if (not stat.S_ISREG(first.st_mode) or first.st_nlink != 1
                or not 0 < first.st_size <= MAX_SOURCE_BYTES):
            raise ValueError("Unsafe size or alias in staged source")
        raw = stream.read(MAX_SOURCE_BYTES + 1)
        last = os.fstat(stream.fileno())
        if (len(raw) != first.st_size or len(raw) != last.st_size
                or first.st_dev != last.st_dev or first.st_ino != last.st_ino
                or first.st_mtime_ns != last.st_mtime_ns
                or first.st_ctime_ns != last.st_ctime_ns
                or hashlib.sha256(raw).hexdigest() != sha256):
            raise ValueError("Staged source changed or SHA256 disagrees")
        return raw


def _unique_line(text: str, needle: str) -> None:
    if text.splitlines().count(needle) != 1:
        raise ValueError("Published source settings do not match frozen model rows")


def _semantic_check(docs: dict[str, str]) -> dict:
    if set(docs) != set(PINS):
        raise ValueError("Incomplete source document inventory")
    zoo, lic = docs["zoo"], docs["license"]
    if ("FastReID Model Zoo and Baselines" not in zoo
            or "### Market1501 Baselines" not in zoo
            or "### DukeMTMC Baseline" not in zoo
            or "Apache License" not in lic[:160]
            or "Version 2.0, January 2004" not in lic[:200]):
        raise ValueError("Unexpected frozen source/model scope")
    market = zoo.split("### Market1501 Baselines", 1)[1].split(
        "### DukeMTMC Baseline", 1
    )[0]
    labels = {
        "bot_r50_market1501": ("BoT(R50)", "bagtricks_R50.yml",
                               "94.4%", "86.1%"),
        "agw_r50_market1501": ("AGW(R50)", "AGW_R50.yml",
                               "95.3%", "88.2%"),
    }
    for key, (model, config, rank1, map_) in labels.items():
        name = "[{}]".format(model)
        candidates = [x for x in market.splitlines()
                      if x.strip().startswith("| " + name + "(")]
        if len(candidates) != 1:
            raise ValueError("Missing or ambiguous FastReID Market model row")
        row = candidates[0]
        columns = [s.strip() for s in row.split("|")[1:-1]]
        if (len(columns) != 6 or columns[1] != "ImageNet"
                or columns[2] != rank1 or columns[3] != map_
                or not columns[0].startswith(name + "(")
                or "configs/Market1501/" + config not in columns[0]
                or columns[-1] != "[model](" + RELEASE_URLS[key] + ")"):
            raise ValueError("Market row differs from fixed upstream contract")
    for key, base in (
        ("bot", "_BASE_: ../Base-bagtricks.yml"),
        ("agw", "_BASE_: ../Base-AGW.yml"),
        ("base_agw", "_BASE_: Base-bagtricks.yml"),
    ):
        _unique_line(docs[key], base)
    for setting in ("    PRETRAIN: True", "  SIZE_TEST: [ 256, 128 ]",
                    "    DEPTH: 50x", "    FEAT_DIM: 2048"):
        _unique_line(docs["base_bot"], setting)
    _unique_line(docs["base_agw"], "    WITH_NL: True")
    _unique_line(docs["base_agw"], "    POOL_LAYER: GeneralizedMeanPooling")
    for key in ("bot", "agw"):
        _unique_line(docs[key], '  NAMES: ("Market1501",)')
        _unique_line(docs[key], '  TESTS: ("Market1501",)')
    return {"upstream_market1501_bot_r50_rank1_pct": 94.4,
            "upstream_market1501_bot_r50_map_pct": 86.1,
            "upstream_market1501_agw_r50_rank1_pct": 95.3,
            "upstream_market1501_agw_r50_map_pct": 88.2,
            "published_height_px": 256, "published_width_px": 128}


def attest_sources(source_bytes: dict[str, bytes], *,
                   _synthetic_pins: dict | None = None) -> dict:
    """Verify immutable Git blob sources, then interpret only fixed metadata.

    A synthetic override is for pure unit tests; it cannot claim official
    revision verification and does not reach a CLI/release path.
    """
    pins = PINS if _synthetic_pins is None else _synthetic_pins
    if _synthetic_pins is None and (
        set(SOURCE_SHA256) != set(PINS)
        or any(type(x) is not str or SHA256.fullmatch(x) is None
               for x in SOURCE_SHA256.values())
    ):
        raise ValueError("Official source SHA256 inventory is incomplete")
    if not isinstance(source_bytes, dict) or set(source_bytes) != set(PINS):
        raise ValueError("All six source documents must be supplied")
    if not isinstance(pins, dict) or set(pins) != set(PINS):
        raise ValueError("Missing pinned source inventory")
    docs = {}
    for key in PINS:
        if (not isinstance(pins[key], (tuple, list)) or len(pins[key]) != 2
                or pins[key][0] != PINS[key][0]
                or type(pins[key][1]) is not str
                or re.fullmatch(r"[a-f0-9]{40}", pins[key][1]) is None
                or git_blob_sha1(source_bytes[key]) != pins[key][1]
                or (_synthetic_pins is None and
                    hashlib.sha256(source_bytes[key]).hexdigest()
                    != SOURCE_SHA256[key])):
            raise ValueError("Source differs from frozen Git SHA1/SHA256 object pins")
        try:
            docs[key] = source_bytes[key].decode("utf-8")
        except UnicodeError:
            raise ValueError("Source metadata must be UTF8") from None
    provenance = _semantic_check(docs)
    official_pins = _synthetic_pins is None
    return {
        "status": ("official_upstream_git_source_blobs_matched"
                   if official_pins else "synthetic_source_validation_only"),
        "source_repository": UPSTREAM_REPO,
        "source_commit_sha1": UPSTREAM_COMMIT,
        "source_object_count": len(PINS),
        "official_git_blob_pins_checked": official_pins,
        "official_git_source_sha256_pins_checked": official_pins,
        "git_source_sha256_is_publisher_signature": False,
        "publisher_reported_reid_models": {
            key: {
                "published_reid_release_url": url,
                "backbone_pretraining_column": "ImageNet",
                "published_checkpoint_training_task": "Market1501_person_reid",
                "publisher_asset_sha256_available_in_pinned_model_zoo": False,
                "asset_bytes_verified": False,
                "independent_checkpoint_provenance_and_rights_verified": False,
                "model_inference_permitted": False,
            } for key, url in RELEASE_URLS.items()
        },
        "published_benchmark_metadata_only": provenance,
        "repo_license_text_header": "Apache-2.0",
        "release_asset_license_independently_verified": False,
        "training_dataset_consent_and_rights_verified": False,
        "source_commit_signature_and_tree_authenticated": False,
        "published_pretraining_is_not_final_reid_checkpoint": True,
        "preprocessing_pixel_scale_color_or_resize_parity_verified": False,
        "checkpoint_bytes_downloaded_or_deserialized": False,
        "safe_onnx_conversion_and_android_runtime_verified": False,
        "real_open_set_rear_view_accuracy_measured": False,
        "auto_person_confirmation_allowed": False,
        "production_approved": False,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in PINS:
        parser.add_argument("--" + name.replace("_", "-"), type=Path, required=True)
        parser.add_argument("--" + name.replace("_", "-") + "-sha256",
                            required=True)
    args = parser.parse_args(argv)
    try:
        # A recomputed digest over poisoned source cannot replace a pin.
        if any(getattr(args, name + "_sha256") != SOURCE_SHA256[name]
               for name in PINS):
            raise ValueError("Source SHA256 differs from fixed upstream pin")
        docs = {
            name: read_pinned_text(
                getattr(args, name), SOURCE_SHA256[name])
            for name in PINS
        }
        report = attest_sources(docs)
        report["local_source_sha256_pins_checked"] = True
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, TypeError, KeyError, UnicodeError):
        # Never echo paths or raw untrusted source bytes.
        print("Refused unpinned or inconsistent FastReID source inventory",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
