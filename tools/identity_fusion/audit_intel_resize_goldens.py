#!/usr/bin/env python3
"""Frozen synthetic-only resize and BGR/NCHW reference vector admission.

Pinned PIL bilinear bytes are drawn from locally calculated deterministic
non-photographic patterns using Pillow 12.3.0. No images from disk, model
weights, ONNX/IR loading, gallery, identity metadata, or network access.
Matches ONLY published evaluation *input-step* parity, never model readiness.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys

from benchmark_resize_parity import PROBES, TARGET, artificial_rgb
from model_preprocessing import INTEL_PUBLISHED_EVAL_SOURCE_COMMIT

ACCURACY_CHECK_GIT_BLOBS = {
    "intel_omz_0287": "7e4aed04f03f88540533e8ac74c281589d8b18ae",
    "intel_omz_0288": "28d6981f6d9e0f055e1c7f6107e5b1b0208516d0",
}
REFERENCE_GENERATOR_VERSION = "Pillow 12.3.0 Image.Resampling.BILINEAR"
# These source/resize/tensor SHA256 vectors were independently generated
# from synthetic pattern definitions in benchmark_resize_parity.py; the
# Pillow resize is PIL Image.frombytes('RGB').resize((128,256),BILINEAR).
# The upstream evaluation's OpenCV BGR->Pillow resize preserves channels;
# then transpose to float32 NCHW at raw 0..255 intensity (little endian).
# Not a claim that the actual released graph's input has been inspected.
REFERENCE_SHA256 = {
    "gradient": {
        "input_rgb": "561d7a8282b1009c32b81725ec1e853ce5137670fbdf98c83ae492f5a4d42d67",
        "resized_rgb": "8775971d006d9562e314f5364c35f9a56f66cde265cff8a1f5d2f63f34eabaa5",
        "tensor_bgr_f32le": "648d4a04d6f63edb6d60f76b7402fe8f03727c1675456564c52ec4a14a58075c",
    },
    "checker": {
        "input_rgb": "f7efebb2a00a063dbb5a10e81526394519f0d328362d66387adacde7c6c34e19",
        "resized_rgb": "3e972a66b36bfe40c3c91b749e5f0499e79fa4794b8b4df469f45a695435c980",
        "tensor_bgr_f32le": "4cb9600d5ac44d1f949a68b990dc5a8c57fa477454b4d42e426c1959d6fb1b04",
    },
    "colorbars": {
        "input_rgb": "557bcb50a18be07eb21b61e07b1ce31c510d7f7c1f854c244037b00b56bc8a7e",
        "resized_rgb": "557bcb50a18be07eb21b61e07b1ce31c510d7f7c1f854c244037b00b56bc8a7e",
        "tensor_bgr_f32le": "4bb93c3050b8595ddf84aacb7e49c5bf5996995d7d7e64ebad641746e9564391",
    },
    "impulse": {
        "input_rgb": "557d233b74c884c3eec07da9fe07c452cfbaa0b1bb6cf3ee39c64881010bdcc0",
        "resized_rgb": "ee20f8a19b4ae60776dbcb2c85e5942f952bd303cc56091a036c79defc5971a2",
        "tensor_bgr_f32le": "fdc32dff70634041782eeb4254dd9a75bc1ed930b4f95ffb621b65e8593bff31",
    },
}
PATTERN_DIMENSIONS = {name: (w, h) for name, w, h in PROBES}
PLANE = TARGET[0] * TARGET[1]


def rgb_to_raw_bgr_nchw_float32le(rgb: bytes) -> bytes:
    """Only already-resized 256x128 RGB synthetic bytes; BGR, raw 0..255."""
    if type(rgb) is not bytes or len(rgb) != PLANE * 3:
        raise ValueError("Invalid fixed-size artificial 256x128 RGB bytes")
    result = bytearray(3 * PLANE * 4)
    for index in range(PLANE):
        src = 3 * index
        for channel, value in enumerate((rgb[src + 2], rgb[src + 1],
                                         rgb[src])):
            struct.pack_into("<f", result, 4 * (channel * PLANE + index),
                             float(value))
    return bytes(result)


def audit_published_intel_resize(candidate_resize, *, model_id):
    """Strict byte-level candidate against frozen publisher-step goldens.

    Candidate callable: width,height,synthetic_rgb_bytes -> RGB output
    of shape 128x256. No paths or photo files accepted. Byte parity with
    one versioned test oracle is NOT model correctness or release approval.
    """
    if not callable(candidate_resize) or model_id not in ACCURACY_CHECK_GIT_BLOBS:
        raise ValueError("Expected an approved source-path label and callable")
    results = []
    for label, width, height in PROBES:
        synthetic = artificial_rgb(label, width, height)
        expected = REFERENCE_SHA256[label]
        raw_digest = hashlib.sha256(synthetic).hexdigest()
        if raw_digest != expected["input_rgb"]:
            raise ValueError("Frozen synthetic probe definition has drifted")
        resized = candidate_resize(width, height, synthetic)
        if type(resized) is not bytes or len(resized) != PLANE * 3:
            raise ValueError("Malformed, unsafe or unexpected resize output")
        tensor = rgb_to_raw_bgr_nchw_float32le(resized)
        resized_digest = hashlib.sha256(resized).hexdigest()
        tensor_digest = hashlib.sha256(tensor).hexdigest()
        pixel_match = resized_digest == expected["resized_rgb"]
        tensor_match = tensor_digest == expected["tensor_bgr_f32le"]
        # Since BGR tensor conversion is a bijection over uint8 RGB values,
        # equal resized RGB bytes MUST give the same tensor digest and vice
        # versa. This guards accidental mismatch of fixture hashes.
        if pixel_match != tensor_match:
            raise ValueError("Pinned resize and tensor goldens contradict")
        results.append({
            "pattern": label,
            "source_width": width,
            "source_height": height,
            "pillow_12_3_rgb_byte_parity": pixel_match,
            "raw_bgr_nchw_float32le_tensor_byte_parity": tensor_match,
            "candidate_resized_rgb_sha256": resized_digest,
            "candidate_bgr_tensor_sha256": tensor_digest,
            "reference_resized_rgb_sha256": expected["resized_rgb"],
            "reference_bgr_tensor_sha256": expected["tensor_bgr_f32le"],
        })
    all_match = all(row["raw_bgr_nchw_float32le_tensor_byte_parity"]
                    for row in results)
    return {
        "status": "synthetic_published_evaluation_preprocessing_only",
        "model_source_label": model_id,
        "upstream_source_commit": INTEL_PUBLISHED_EVAL_SOURCE_COMMIT,
        "accuracy_check_source_blob_sha1": ACCURACY_CHECK_GIT_BLOBS[model_id],
        "frozen_reference_generator": REFERENCE_GENERATOR_VERSION,
        "target_nchw": [1, 3, TARGET[1], TARGET[0]],
        "channel_order": "BGR",
        "intensity_scale_evaluation_only": "raw_uint8_0_to_255_as_float32",
        "tensor_byte_order": "little_endian_float32",
        "all_four_synthetic_probes_exact": all_match,
        "results": results,
        "independent_real_image_decoder_or_exif_verified": False,
        "actual_0288_ir_or_0287_onnx_graph_input_verified": False,
        "mobile_android_pixel_or_model_parity_verified": False,
        "real_person_accuracy_or_training_rights_verified": False,
        "real_model_inference_executed": False,
        "auto_confirm_allowed": False,
        "production_approved": False,
    }


def pillow_resize(width, height, rgb):
    """Optional external package, operating on GENERATED raw bytes only."""
    from PIL import Image
    return Image.frombytes("RGB", (width, height), rgb).resize(
        TARGET, resample=Image.Resampling.BILINEAR).tobytes()


def opencv_linear_resize(width, height, rgb):
    """Deliberately comparison only; usual INTER_LINEAR is not PIL parity."""
    import cv2
    import numpy as np
    source = np.frombuffer(rgb, dtype=np.uint8).reshape(height, width, 3)
    return cv2.resize(source, TARGET,
                      interpolation=cv2.INTER_LINEAR).tobytes()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-id", required=True, choices=sorted(ACCURACY_CHECK_GIT_BLOBS))
    parser.add_argument("--candidate", choices=("pillow", "opencv_linear"),
                        required=True)
    args = parser.parse_args(argv)
    try:
        callback = (pillow_resize if args.candidate == "pillow"
                    else opencv_linear_resize)
        report = audit_published_intel_resize(
            callback, model_id=args.model_id)
        report["candidate"] = args.candidate
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0 if report["all_four_synthetic_probes_exact"] else 2
    except (ImportError, ValueError, OSError, RuntimeError, TypeError,
            OverflowError, MemoryError):
        print("Synthetic-only Intel resize benchmark refused",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
