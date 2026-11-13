#!/usr/bin/env python3
"""Synthetic-only, explicitly sourced re-ID preprocessing contracts.

No photo/video IO, model execution, networks, embeddings, or face recognition.
An unresolved preprocessing convention is a REFUSAL, not a guessed default.
"""
from __future__ import annotations

from array import array
from dataclasses import dataclass
import math

HEIGHT, WIDTH = 256, 128
SHAPE = (1, 3, HEIGHT, WIDTH)


@dataclass(frozen=True)
class PreprocessContract:
    identifier: str
    channel_order: str
    shape: tuple[int, int, int, int]
    pixel_scale: str | None
    mean: tuple[float, float, float] | None
    std: tuple[float, float, float] | None
    source: str
    spatial_resize_parity_proven: bool = False

    @property
    def ready_for_synthetic_tensor(self) -> bool:
        return self.pixel_scale == "divide_by_255" and self.mean is not None and self.std is not None


CONTRACTS = {
    # Torchreid build_transforms(...): Resize((256, 128)), ToTensor(),
    # Normalize([.485,.456,.406],[.229,.224,.225]) on RGB PIL images.
    # Deliberately do not pretend to reproduce PIL/torchvision resize here.
    "torchreid_osnet_eval": PreprocessContract(
        identifier="torchreid_osnet_eval",
        channel_order="RGB", shape=SHAPE, pixel_scale="divide_by_255",
        mean=(0.485, 0.456, 0.406), std=(0.229, 0.224, 0.225),
        source="https://github.com/KaiyangZhou/deep-person-reid/blob/master/torchreid/data/transforms.py",
    ),
    # Intel documents BGR/NCHW/256x128 but does NOT explicitly specify a
    # [0,1] vs [0,255] float input scale or resize kernel in the model spec.
    "intel_omz_0287": PreprocessContract(
        identifier="intel_omz_0287",
        channel_order="BGR", shape=SHAPE, pixel_scale=None,
        mean=None, std=None,
        source="https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/models/intel/person-reidentification-retail-0287/README.md",
    ),
}


def get_contract(identifier: str) -> PreprocessContract:
    if not isinstance(identifier, str) or identifier not in CONTRACTS:
        raise ValueError("Unknown or unreviewed preprocessing contract")
    return CONTRACTS[identifier]


def synthetic_rgb_stripes(*, width: int = WIDTH, height: int = HEIGHT) -> bytes:
    """Fixed-size artificial red/green/blue/gray vertical bands, no images."""
    if type(width) is not int or type(height) is not int or (height, width) != (HEIGHT, WIDTH):
        raise ValueError("Only audited fixed-shape synthetic probes supported")
    palette = ((255, 0, 0), (0, 255, 0), (0, 0, 255), (127, 127, 127))
    return bytes(channel for _y in range(height)
                 for x in range(width) for channel in palette[4 * x // width])


def audited_nchw_tensor(contract_id: str, rgb: bytes) -> array:
    """Reference RGB->NCHW for already-resized synthetic bytes ONLY.

    No resize, EXIF, crop, interpolation, source image or tensor library.
    Returns packed native-endian float32. A missing intensity scale refuses.
    """
    contract = get_contract(contract_id)
    if not contract.ready_for_synthetic_tensor:
        raise ValueError("Upstream numeric preprocessing convention unresolved")
    if not isinstance(rgb, bytes) or len(rgb) != HEIGHT * WIDTH * 3:
        raise ValueError("Only exact 256x128 synthetic RGB bytes supported")
    if contract.shape != SHAPE or contract.channel_order != "RGB":
        raise ValueError("Unreviewed channel/shape mapping")
    if contract.mean is None or contract.std is None:
        raise ValueError("Unreviewed normalization contract")
    if any(not math.isfinite(x) for x in (*contract.mean, *contract.std)) or any(s <= 0 for s in contract.std):
        raise ValueError("Invalid normalization coefficients")
    plane = HEIGHT * WIDTH
    result = array("f", [0.0]) * (3 * plane)
    for pixel_index in range(plane):
        offset = 3 * pixel_index
        for channel in range(3):
            result[channel * plane + pixel_index] = (
                (rgb[offset + channel] / 255.0 - contract.mean[channel]) / contract.std[channel]
            )
    return result


def synthetic_contract_tensor(contract_id: str) -> array:
    """Generate repeatable, *non-photographic* tensor for an audited contract."""
    return audited_nchw_tensor(contract_id, synthetic_rgb_stripes())


# This separate reference concerns Open Model Zoo's published Market-1501
# evaluation *data-handling path*, NOT the actual ONNX model's validated input
# semantics or an Android runtime implementation. At upstream commit
# af810de42bcf460a18610481f639dcdc86249e69:
# - accuracy-check.yml: 128x256, use_pillow=True, BILINEAR
# - data_reader.py + image_readers.py: default opencv_imread IMREAD_COLOR (BGR)
# - resize.py: Image.fromarray(data).resize(...), np.array (channel-preserving)
# - model_evaluator.py: launcher.fit_to_input passed to InputFeeder
# - openvino_launcher.py: 4D transpose to NCHW, cast dtype; no /255 here
# No model weights/ONNX graph or live Pixel/Android preprocessing inspected.
INTEL_PUBLISHED_EVAL_SOURCE_COMMIT = "af810de42bcf460a18610481f639dcdc86249e69"


def synthetic_intel_0287_published_eval_tensor() -> array:
    """Synthetic-only replica of the *published evaluation* BGR->NCHW cast.

    Input is the built-in already-resized artificial stripes ONLY, not a
    caller-supplied image. No JPEG decoding, Pillow resize, model execution,
    network, weights, filesystem or photo identity data.

    Reorders RGB synthetic probe bytes into OpenCV-style BGR planes at raw
    0..255 intensities, converted to float32. It does NOT establish that the
    actual Intel ONNX 0287 model requires this convention, or that an
    Android implementation matches the upstream evaluation pipeline.
    """
    rgb = synthetic_rgb_stripes()
    plane = HEIGHT * WIDTH
    result = array("f", [0.0]) * (3 * plane)
    for pixel in range(plane):
        base = pixel * 3
        result[pixel] = float(rgb[base + 2])  # B from source RGB
        result[plane + pixel] = float(rgb[base + 1])  # G
        result[2 * plane + pixel] = float(rgb[base])  # R
    return result


def synthetic_intel_0287_eval_sentinel_report() -> dict:
    """Only public synthetic channel/intensity sentinels; never identity."""
    sample = synthetic_intel_0287_published_eval_tensor()
    plane = HEIGHT * WIDTH
    def at(x):
        return [sample[x], sample[plane + x], sample[2 * plane + x]]

    return {
        "status": "published_openvino_market1501_evaluation_step_only",
        "upstream_commit": INTEL_PUBLISHED_EVAL_SOURCE_COMMIT,
        "shape_nchw": [1, 3, HEIGHT, WIDTH],
        "artificial_red_rgb_pixel_after_bgr_cast": at(0),
        "artificial_blue_rgb_pixel_after_bgr_cast": at(WIDTH // 2),
        "evaluation_step_uses_raw_0_to_255_float32": True,
        "original_onnx_graph_input_range_verified": False,
        "published_evaluation_resize_byte_parity_tested": False,
        "native_android_preprocessing_equivalent": False,
        "model_rights_or_identity_accuracy_verified": False,
        "actual_model_weight_bytes_or_execution_verified": False,
        "production_approved": False,
        "auto_confirm_allowed": False,
    }
