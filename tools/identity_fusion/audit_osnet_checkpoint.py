#!/usr/bin/env python3
"""Offline OSNet checkpoint-stage and exact-byte audit; NEVER deserialize .pth.

The MIT model-card label, a publisher-listed SHA256 and a local byte match
neither prove re-ID fitness nor clear training-data/weights redistribution.
No downloads, Torch, pickle, ONNX, image/model execution or file writes.
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

REPO = "kaiyangzhou/osnet"
REVISION = "01af85e82a9db4f3a4f6ed3a72ed9150bd416d04"
MAX_CHECKPOINT_BYTES = 32 * 1024 * 1024
SHA256 = re.compile(r"[a-f0-9]{64}\Z")

# Independently observed publisher-provided *file* SHA-256s at fixed HF revision.
# SHA-256 agrees with bytes ONLY if the caller later stages an authorized local
# copy. This is not a cryptographically signed author/model provenance claim.
CHECKPOINTS = {
    "osnet_x0_25_imagenet": (
        "osnet_x0_25_imagenet.pth",
        "f54941a66bad4ddd07f2907f498c810ce639ce7a1abeaf2a151f8da118d84693",
        "imagenet_backbone_only", "ImageNet",
    ),
    "osnet_x0_25_msmt17": (
        "osnet_x0_25_msmt17_combineall_256x128_amsgrad_ep150_stp60_lr0.0015_b64_fb10_softmax_labelsmooth_flip_jitter.pth",
        "cf55163d78fc44c62c82f85ab62d39f10438679b5abe8c698ae08cfa84aa6e18",
        "person_reid_trained", "MSMT17",
    ),
    "osnet_x0_5_imagenet": (
        "osnet_x0_5_imagenet.pth",
        "f10ea9e26ee55b1a65cee3c6e88aa1ae105fe981af3e37692fa68a72fc64bf8e",
        "imagenet_backbone_only", "ImageNet",
    ),
    "osnet_x0_5_msmt17": (
        "osnet_x0_5_msmt17_combineall_256x128_amsgrad_ep150_stp60_lr0.0015_b64_fb10_softmax_labelsmooth_flip_jitter.pth",
        "e96cbd20ee9cc3c6dcc0e8f4fbba8c8069d47647a5a96a59ce381fb785c54f68",
        "person_reid_trained", "MSMT17",
    ),
}


def verify_local_bytes(path: Path, expected_sha256: str) -> int:
    """Stream checksum only from one bounded, unaliased, regular local file.

    Injectable digest allows tests on fictional bytes. The public CLI chooses
    the expected digest exclusively from its closed, hardcoded publisher pins.
    Bytes are NEVER loaded as executable pickle/model input.
    """
    if (not isinstance(path, Path)
            or type(expected_sha256) is not str
            or not SHA256.fullmatch(expected_sha256)):
        raise ValueError("Unsafe checkpoint verification inputs")
    # Reject symlink intermediates as well as the final component; .resolve()
    # would erase the very evidence of an unexpected alias.
    absolute = path.absolute()
    if any(p.is_symlink() for p in (absolute, *absolute.parents)):
        raise ValueError("Checkpoint path contains a symlink")
    # Explicit directory/device refusal yields a stable API failure instead
    # of an OS-specific IsADirectoryError before fdopen/fstat. fstat below
    # remains mandatory: this precheck alone cannot prevent path races.
    if not absolute.is_file():
        raise ValueError("Expected a regular local checkpoint")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    with os.fdopen(os.open(absolute, flags), "rb") as stream:
        first = os.fstat(stream.fileno())
        if (not stat.S_ISREG(first.st_mode) or first.st_nlink != 1
                or not 0 < first.st_size <= MAX_CHECKPOINT_BYTES):
            raise ValueError("Expected one bounded, unaliased checkpoint")
        digest = hashlib.sha256()
        total = 0
        while True:
            chunk = stream.read(min(1024 * 1024, MAX_CHECKPOINT_BYTES + 1 - total))
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_CHECKPOINT_BYTES:
                raise ValueError("Checkpoint exceeds safe byte budget")
            digest.update(chunk)
        last = os.fstat(stream.fileno())
        if (total != first.st_size or total != last.st_size
                or first.st_ino != last.st_ino or first.st_dev != last.st_dev
                or first.st_mtime_ns != last.st_mtime_ns
                or first.st_ctime_ns != last.st_ctime_ns
                or digest.hexdigest() != expected_sha256):
            raise ValueError("Local bytes differ from independently pinned file")
        return total


def audit(artifact: str, local_file: Path | None = None) -> dict:
    """Return aggregate/public metadata only; zero identity authority."""
    if type(artifact) is not str or artifact not in CHECKPOINTS:
        raise ValueError("Unknown registered checkpoint")
    filename, digest, stage, training_dataset = CHECKPOINTS[artifact]
    direct_reid = stage == "person_reid_trained"
    size = verify_local_bytes(local_file, digest) if local_file is not None else None
    return {
        "status": ("offline_local_digest_matches_publisher_pin"
                   if size is not None else "publisher_metadata_pin_only"),
        "registered_artifact": artifact,
        "published_filename": filename,
        "publisher_repository": REPO,
        "publisher_revision": REVISION,
        "published_sha256": digest,
        "published_model_card_license_label": "mit",
        "checkpoint_training_stage": stage,
        "checkpoint_named_training_dataset": training_dataset,
        "reid_finetuning_claimed_by_published_artifact_name": direct_reid,
        "imagenet_only_not_a_reid_evaluation_checkpoint": not direct_reid,
        "local_staged_checkpoint_digest_verified": size is not None,
        "local_checkpoint_bytes": size,
        "published_digest_independently_signed_by_author": False,
        "training_dataset_rights_verified": False,
        "weight_redistribution_rights_independently_verified": False,
        "torch_pickle_executed": False,
        "actual_model_weights_or_architecture_semantics_verified": False,
        "onnx_export_or_preprocessing_parity_verified": False,
        "android_runtime_or_accuracy_measured": False,
        "validity_of_market1501_model_zoo_metric_for_this_checkpoint_proven": False,
        "permitted_for_person_reid_inference": False,
        "permitted_for_native_bundle": False,
        "auto_person_confirmation_allowed": False,
        "caveat": (
            "Publisher's checksum does not authenticate consent, training "
            "rights, model semantics, or an untrusted pickle. ImageNet-only "
            "weights are NOT a person-reID-trained checkpoint. MSMT17-trained "
            "weights require independent rights/consent review. No weights "
            "were deserialized, inferred or added to this repository."
        ),
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", required=True, choices=sorted(CHECKPOINTS))
    parser.add_argument("--file", type=Path, default=None,
                        help="Optional already-obtained local .pth, read-only")
    args = parser.parse_args(argv)
    try:
        print(json.dumps(audit(args.artifact, args.file), indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, TypeError):
        # Do not echo user paths or model bytes in exception messages.
        print("Refused unrecognized, aliased, unsafe or mismatched checkpoint",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
