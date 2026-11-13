#!/usr/bin/env python3
"""Dependency-free offline ONNX structural preflight; NEVER execute model bytes.

Inspect a checksum-pinned standalone ONNX protobuf prior to optional runtime
inference. Supports a deliberately narrow, fail-closed subset of ONNX IR.
Structural checks do not authenticate semantic model identity or legal rights.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys

MAX_BYTES = 64 * 1024 * 1024
MAX_FIELDS_PER_MESSAGE = 100_000
MAX_NODES = 10_000
MAX_INITIALIZERS = 10_000
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
EXPECTED_INPUT = (1, 3, 256, 128)
EXPECTED_OUTPUT = (1, 256)


def _varint(data: memoryview, pos: int) -> tuple[int, int]:
    value = 0
    for i in range(10):
        if pos >= len(data):
            raise ValueError("Truncated protobuf varint")
        byte = data[pos]
        pos += 1
        if i == 9 and byte > 1:
            raise ValueError("Oversized protobuf varint")
        value |= (byte & 127) << (7 * i)
        if not byte & 128:
            return value, pos
    raise ValueError("Nonterminating protobuf varint")


def _fields(message: memoryview):
    pos = 0
    count = 0
    while pos < len(message):
        count += 1
        if count > MAX_FIELDS_PER_MESSAGE:
            raise ValueError("Too many protobuf fields")
        key, pos = _varint(message, pos)
        field, wire = key >> 3, key & 7
        if field == 0 or field > (1 << 29) - 1:
            raise ValueError("Invalid protobuf field ID")
        if wire == 0:
            value, pos = _varint(message, pos)
        elif wire == 1:
            if len(message) - pos < 8:
                raise ValueError("Truncated protobuf fixed64")
            value, pos = message[pos:pos + 8], pos + 8
        elif wire == 2:
            size, pos = _varint(message, pos)
            if size > len(message) - pos:
                raise ValueError("Protobuf length exceeds message bounds")
            value, pos = message[pos:pos + size], pos + size
        elif wire == 5:
            if len(message) - pos < 4:
                raise ValueError("Truncated protobuf fixed32")
            value, pos = message[pos:pos + 4], pos + 4
        else:
            raise ValueError("Protobuf groups or unsupported wire types refused")
        yield field, wire, value


def _decode(value, *, limit=96):
    if not isinstance(value, memoryview) or len(value) > limit:
        raise ValueError("Non-string or oversized ONNX metadata")
    try:
        return value.tobytes().decode("utf-8")
    except UnicodeError:
        raise ValueError("Invalid ONNX metadata encoding") from None


def _one(fields, field_id, wire):
    found = [v for k, w, v in fields if k == field_id and w == wire]
    if len(found) != 1 or sum(k == field_id for k, _, _ in fields) != 1:
        raise ValueError("Missing, duplicated, or wrong-wire ONNX field")
    return found[0]


def _shape(value):
    # ValueInfoProto {name=1, type=2}; TypeProto tensor_type=1;
    # TypeProto.Tensor {elem_type=1, shape=2}; TensorShapeProto dim=1;
    # Dimension {dim_value=1, dim_param=2}. Reject dynamic dimensions.
    info = list(_fields(value))
    if any(field not in (1, 2, 3, 4) for field, _, _ in info):
        raise ValueError("Unsupported ValueInfo fields")
    _decode(_one(info, 1, 2))
    kind = list(_fields(_one(info, 2, 2)))
    if len([x for x in kind if x[0] == 1]) != 1 or any(k not in (1, 6) for k, _, _ in kind):
        raise ValueError("Unsupported ONNX value type")
    tensor = list(_fields(_one(kind, 1, 2)))
    if _one(tensor, 1, 0) != 1 or any(k not in (1, 2) for k, _, _ in tensor):
        raise ValueError("Expected float32 tensor type")
    dims = list(_fields(_one(tensor, 2, 2)))
    result = []
    for field, wire, raw in dims:
        if field != 1 or wire != 2:
            raise ValueError("Unexpected tensor shape metadata")
        dim = list(_fields(raw))
        if len(dim) != 1 or dim[0][0:2] != (1, 0) or dim[0][2] <= 0:
            raise ValueError("Dynamic, ambiguous or nonpositive tensor dimension")
        result.append(dim[0][2])
    if not 1 <= len(result) <= 4:
        raise ValueError("Missing or unsupported tensor rank")
    return tuple(result)


def _tensor(value):
    # TensorProto fields 13 (external_data) and 14 (data_location) veto even
    # when packed alongside inline bytes. Ignore values; never load weights.
    fields = list(_fields(value))
    if any(k in (3, 13, 14) for k, _, _ in fields):
        raise ValueError("Segmented or externally referenced tensors not allowed")
    valid = {1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 16}
    if any(k not in valid for k, _, _ in fields):
        raise ValueError("Unexamined tensor metadata")
    _one(fields, 2, 0)
    return True


def _attribute(value):
    fields = list(_fields(value))
    if any(k in (6, 11, 21, 22, 23) for k, _, _ in fields):
        raise ValueError("Embedded graph, sparse tensor or function reference refused")
    valid = {1, 2, 3, 4, 5, 7, 8, 9, 10, 13, 14, 15, 20}
    if any(k not in valid for k, _, _ in fields):
        raise ValueError("Unknown attribute field")
    _decode(_one(fields, 1, 2))
    attr_type = [v for k, w, v in fields if k == 20 and w == 0]
    # AttributeProto.type enum: only ordinary scalar/list/tensor values.
    # The discriminator may be absent on legacy exports, but a present
    # unknown/UNDEFINED/graph/sparse type must NEVER be silently accepted.
    supported = {1, 2, 3, 4, 6, 7, 8, 9}
    if (len(attr_type) > 1 or any(k == 20 and w != 0 for k, w, _ in fields)
            or any(v not in supported for v in attr_type)):
        raise ValueError("Unreviewed ONNX attribute discriminator")
    for field, wire, raw in fields:
        if field in (5, 10):
            if wire != 2:
                raise ValueError("Malformed constant tensor attribute")
            _tensor(raw)
        if field in (14, 15):
            raise ValueError("Nested type attributes outside reviewed static subset")


def _node(value):
    fields = list(_fields(value))
    if any(k not in (1, 2, 3, 4, 5, 6, 7, 8, 9, 10) for k, _, _ in fields):
        raise ValueError("Unexpected node field")
    op = _decode(_one(fields, 4, 2), limit=64)
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", op):
        raise ValueError("Unrecognized operator identifier")
    domain = [_decode(raw) for k, wire, raw in fields if k == 7 and wire == 2]
    if (len(domain) > 1 or any(k == 7 and w != 2 for k, w, _ in fields)
            or (domain and domain[0] not in ("", "ai.onnx"))):
        raise ValueError("Custom operator domain outside portable baseline")
    if any(k == 8 for k, _, _ in fields):
        raise ValueError("Operator overload not reviewed")
    for k, wire, raw in fields:
        if k == 5:
            if wire != 2:
                raise ValueError("Invalid node attribute")
            _attribute(raw)
    return op


def _graph(value):
    ops = Counter()
    inputs, outputs = [], []
    initializer_count, node_count = 0, 0
    for field, wire, raw in _fields(value):
        if field not in (1, 2, 5, 10, 11, 12, 13, 14, 16):
            raise ValueError("Unsupported or sparse ONNX graph field")
        if field == 1:
            if wire != 2:
                raise ValueError("Malformed node")
            ops[_node(raw)] += 1
            node_count += 1
            if node_count > MAX_NODES:
                raise ValueError("Too many ONNX nodes")
        elif field == 5:
            if wire != 2:
                raise ValueError("Malformed initializer")
            initializer_count += 1
            if initializer_count > MAX_INITIALIZERS:
                raise ValueError("Too many ONNX initializers")
            _tensor(raw)
        elif field in (11, 12):
            if wire != 2:
                raise ValueError("Malformed graph value")
            (inputs if field == 11 else outputs).append(_shape(raw))
        elif field == 13:
            if wire != 2:
                raise ValueError("Malformed graph value hints")
            _shape(raw)
    if inputs != [EXPECTED_INPUT] or outputs != [EXPECTED_OUTPUT]:
        raise ValueError("Graph I/O differs from Intel 0287 published contract")
    if not ops:
        raise ValueError("No graph operators")
    return {"nodes": node_count, "initializers": initializer_count,
            "distinct_standard_operator_types": len(ops),
            "standard_operator_counts": dict(sorted(ops.items()))}


def inspect_onnx_bytes(blob: bytes) -> dict:
    """Inspect model protobuf; never execute, decode images or output tensors."""
    if type(blob) is not bytes or not 0 < len(blob) <= MAX_BYTES:
        raise ValueError("A bounded standalone ONNX byte string is required")
    ir_versions, opsets, graphs = [], [], []
    for field, wire, raw in _fields(memoryview(blob)):
        if field not in (1, 2, 3, 4, 5, 6, 7, 8, 14):
            raise ValueError("Unreviewed model functions, training data or fields")
        if field == 1:
            if wire != 0:
                raise ValueError("Invalid ONNX IR version")
            ir_versions.append(raw)
        elif field == 7:
            if wire != 2:
                raise ValueError("Invalid graph protobuf")
            graphs.append(_graph(raw))
        elif field == 8:
            if wire != 2:
                raise ValueError("Malformed ONNX opset")
            f = list(_fields(raw))
            d = [_decode(v) for k, w, v in f if k == 1 and w == 2]
            versions = [v for k, w, v in f if k == 2 and w == 0]
            if (len(d) > 1 or len(versions) != 1
                    or any(k not in (1, 2) or w not in (0, 2) for k, w, _ in f)
                    or any(k == 1 and w != 2 or k == 2 and w != 0 for k, w, _ in f)
                    or (d and d[0] not in ("", "ai.onnx")) or not 1 <= versions[0] <= 25):
                raise ValueError("Custom/ambiguous/unsupported operator set")
            opsets.append(versions[0])
    if len(ir_versions) != 1 or not 3 <= ir_versions[0] <= 13 or len(graphs) != 1 or len(opsets) != 1:
        raise ValueError("Missing or ambiguous ONNX IR/graph/opset")
    return {"status": "static_onnx_container_preflight_only", "model_bytes": len(blob),
            "model_sha256": hashlib.sha256(blob).hexdigest(),
            "ir_version": ir_versions[0], "default_opset_version": opsets[0],
            "input_nchw": list(EXPECTED_INPUT), "output_embedding_shape": list(EXPECTED_OUTPUT),
            "graph": graphs[0], "no_external_tensors_or_custom_domains_seen": True,
            "protobuf_wire_fields_within_inspected_subset": True,
            "upstream_checkpoint_identity_verified": False,
            "model_semantics_or_operators_validated_by_onnx_checker": False,
            "full_execution_safety_guaranteed": False,
            "preprocess_pixel_scale_verified": False,
            "weights_training_rights_verified": False,
            "open_set_identity_accuracy_verified": False,
            "android_runtime_measured": False,
            "auto_confirm_allowed": False, "production_approved": False}


def verify_intel_register_match(blob: bytes, register: dict) -> bool:
    """Match locally registered 0287 SHA384/length; not legal or origin proof."""
    from verify_candidate_artifact import validate_register
    item = validate_register(register)["intel_omz_reid_0287_onnx"]
    if (item["source_purpose"] != "reid_trained" or item["format"] != "onnx"
            or item["checksum_algorithm"] != "sha384"
            or type(item["bytes"]) is not int or item["bytes"] != len(blob)
            or hashlib.sha384(blob).hexdigest() != item["checksum"]):
        raise ValueError("ONNX bytes do not match pinned Intel 0287 artifact")
    return True


def checked_file(path: Path, digest: str) -> bytes:
    if type(digest) is not str or not SHA256.fullmatch(digest) or path.is_symlink() or not path.is_file():
        raise ValueError("Pinned, regular local ONNX file required")
    if not 0 < path.stat().st_size <= MAX_BYTES:
        raise ValueError("ONNX exceeds model size budget")
    with path.open("rb") as handle:
        result = handle.read(MAX_BYTES + 1)
    if len(result) > MAX_BYTES or hashlib.sha256(result).hexdigest() != digest:
        raise ValueError("ONNX byte pin mismatch")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--require-registered-intel-0287", action="store_true",
                        help="Also match bundled 0287 ONNX byte-size/SHA384 pin")
    args = parser.parse_args(argv)
    try:
        blob = checked_file(args.model, args.sha256)
        report = inspect_onnx_bytes(blob)
        report["registered_intel_0287_sha384_matches"] = False
        if args.require_registered_intel_0287:
            registry = Path(__file__).with_name("candidate_model_artifacts.json")
            register = json.loads(registry.read_text(encoding="utf-8"))
            report["registered_intel_0287_sha384_matches"] = verify_intel_register_match(
                blob, register)
        print(json.dumps(report, sort_keys=True, indent=2))
        return 0
    except (ValueError, OSError, TypeError, KeyError, OverflowError, json.JSONDecodeError):
        print("Refused unsafe or unsupported ONNX structural preflight", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
