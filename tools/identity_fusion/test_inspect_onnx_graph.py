#!/usr/bin/env python3
"""Pure synthetic protobuf fixtures: no images, model weights or ONNX runtime."""
import contextlib
import hashlib
import io
import json
import random
from pathlib import Path
import tempfile
import unittest

from inspect_onnx_graph import (
    inspect_onnx_bytes, checked_file, main, verify_intel_register_match,
)


def varint(n):
    result = bytearray()
    while n > 127:
        result.append((n & 127) | 128)
        n >>= 7
    result.append(n)
    return bytes(result)


def field(k, value):
    if isinstance(value, int):
        return varint(k << 3) + varint(value)
    if isinstance(value, str):
        value = value.encode()
    return varint((k << 3) | 2) + varint(len(value)) + value


def shape(dims):
    shape_data = b"".join(field(1, field(1, d)) for d in dims)
    tensor_type = field(1, 1) + field(2, shape_data)
    return field(1, "fake-opaque") + field(2, field(1, tensor_type))


def model(*, input_dims=(1, 3, 256, 128), output_dims=(1, 256),
          opset=12, node_attr=b"", node_domain=b"", initializer=b""):
    node = field(1, "input") + field(2, "output") + field(4, "Relu")
    if node_domain:
        node += field(7, node_domain)
    if node_attr:
        node += field(5, node_attr)
    graph = field(1, node) + field(11, shape(input_dims)) + field(12, shape(output_dims))
    if initializer:
        graph += field(5, initializer)
    return field(1, 8) + field(7, graph) + field(8, field(2, opset))


class SyntheticOnnxPreflightTests(unittest.TestCase):
    def test_valid_fictional_graph_only_asserts_structure_not_model_identity(self):
        raw = model(initializer=field(2, 1) + field(9, b"\0\0\x80?"))
        report = inspect_onnx_bytes(raw)
        self.assertEqual(report["input_nchw"], [1, 3, 256, 128])
        self.assertEqual(report["output_embedding_shape"], [1, 256])
        self.assertEqual(report["graph"]["nodes"], 1)
        self.assertEqual(report["graph"]["initializers"], 1)
        self.assertEqual(report["graph"]["standard_operator_counts"], {"Relu": 1})
        self.assertFalse(report["upstream_checkpoint_identity_verified"])
        self.assertFalse(report["full_execution_safety_guaranteed"])
        self.assertFalse(report["weights_training_rights_verified"])
        self.assertFalse(report["production_approved"])
        self.assertNotIn("fake-opaque", json.dumps(report))

    def test_reject_wrong_shapes_dynamic_and_ambiguous_contract(self):
        for kwargs in (dict(input_dims=(1, 3, 224, 224)), dict(output_dims=(1, 512)),
                       dict(opset=30), dict(opset=0)):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                inspect_onnx_bytes(model(**kwargs))
        dynamic = field(1, 8) + field(7, field(1, field(4, "Relu")) +
                  field(11, field(1, "input") + field(2, field(1,
                        field(1, 1) + field(2, field(1, field(2, "N")))))) +
                  field(12, shape((1, 256)))) + field(8, field(2, 12))
        with self.assertRaises(ValueError):
            inspect_onnx_bytes(dynamic)

    def test_refuse_nested_graphs_custom_ops_and_external_weights(self):
        plain = model()
        cases = [model(node_domain="com.external"),
                 model(node_attr=field(1, "IfBody") + field(6, b"embedded-graph")),
                 model(node_attr=field(1, "Constant") +
                       field(5, field(2, 1) + field(13, field(1, "location")))),
                 model(initializer=field(2, 1) + field(14, 1)),
                 model(initializer=field(2, 1) + field(13, b"../external.bin")),
                 plain + field(20, b"training-info"),
                 plain + field(25, b"local-function"),
                 plain + field(26, b"other-device-config"),
                 plain + field(7, b"extra-graph")]
        for i, blob in enumerate(cases):
            with self.subTest(case=i), self.assertRaises(ValueError):
                inspect_onnx_bytes(blob)

    def test_fail_closed_on_attribute_types_and_duplicate_shape(self):
        for v in (5, 10):
            with self.subTest(attribute=v), self.assertRaises(ValueError):
                inspect_onnx_bytes(model(node_attr=field(1, "weird") + field(20, v)))
        duplicated = field(1, 8) + field(7, field(1, field(4, "Relu")) +
            field(11, field(1, "input") + field(2, field(1,
                field(1, 1) + field(2, field(1, field(1, 1))) +
                field(2, field(1, field(1, 1)))))) +
            field(12, shape((1, 256)))) + field(8, field(2, 12))
        with self.assertRaises(ValueError):
            inspect_onnx_bytes(duplicated)

    def test_unknown_and_undefined_attribute_discriminators_fail_closed(self):
        # A number that parses as a protobuf varint is NOT necessarily a
        # known ONNX attribute kind. None of these is in the reviewed subset.
        for kind in (0, 5, 10, 11, 12, 13, 14, 99, 2**32):
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                inspect_onnx_bytes(model(node_attr=(
                    field(1, "FakeAttr") + field(20, kind))))

    def test_seeded_fuzz_mutations_never_throw_unhandled_or_authorize_identity(self):
        # A deliberately tiny reproducible wire mutator: no ONNX imports,
        # image data, user embeddings, model downloads or execution.
        original = model(initializer=field(2, 1) + field(9, b"somebytes"))
        seed = random.Random(20261019)
        accepted = rejected = 0
        for _ in range(350):
            raw = bytearray(original)
            for _ in range(seed.randint(1, 3)):
                offset = seed.randrange(len(raw))
                raw[offset] ^= seed.randint(1, 255)
            try:
                report = inspect_onnx_bytes(bytes(raw))
            except ValueError:
                rejected += 1
            else:
                accepted += 1  # some valid metadata/ops are mutable
                self.assertFalse(report["upstream_checkpoint_identity_verified"])
                self.assertFalse(report["full_execution_safety_guaranteed"])
                self.assertFalse(report["auto_confirm_allowed"])
                self.assertFalse(report["production_approved"])
        self.assertGreater(rejected, 0)
        self.assertEqual(accepted + rejected, 350)

    def test_bounded_protobuf_field_count_rejects_metadata_flood(self):
        # Repeated benign top-level doc_string fields trigger the global
        # field-count cap instead of exhausting memory or running a model.
        overwhelming = model() + field(6, "") * 100_001
        with self.assertRaisesRegex(ValueError, "Too many protobuf fields"):
            inspect_onnx_bytes(overwhelming)

    def test_intel_sha384_and_length_requires_separately_pinned_artifact(self):
        raw = model()
        item = {
            "id": "intel_omz_reid_0287_onnx",
            "file_name": "person-reidentification-retail-0267.onnx",
            "upstream_page": "https://github.com/openvinotoolkit/open_model_zoo",
            "source_purpose": "reid_trained", "format": "onnx",
            "bytes": len(raw), "checksum_algorithm": "sha384",
            "checksum": hashlib.sha384(raw).hexdigest(),
            "repository_license_label": "Apache-2.0",
            "weight_rights_status": "unverified", "production_approved": False,
            "caution": "Entirely fictional test artifact, never deploy",
        }
        register = {"schema_version": 1, "artifacts": [item]}
        self.assertTrue(verify_intel_register_match(raw, register))
        with self.assertRaises(ValueError):
            verify_intel_register_match(
                raw, {"schema_version": 1, "artifacts": [dict(item, bytes=len(raw) + 1)]})
        with self.assertRaises(ValueError):
            verify_intel_register_match(
                raw, {"schema_version": 1, "artifacts": [dict(item, checksum="a" * 96)]})

    def test_truncated_protobuf_duplicate_ir_and_oversized_varints_fail(self):
        cases = [b"", b"\x3a\xff", b"\x3a\x80\x80\x80\x80\x80\x80\x80\x80\x80\x03",
                 model() + field(1, 9), b"\x0b", model() + b"\x08\x80",
                 model()[:-1], b"\x00"]
        for i, blob in enumerate(cases):
            with self.subTest(case=i), self.assertRaises(ValueError):
                inspect_onnx_bytes(blob)

    def test_cli_protects_exact_bytes_redacts_paths_and_never_loads_model(self):
        with tempfile.TemporaryDirectory(prefix="private-people-path-") as root:
            path = Path(root) / "synthetic.onnx"
            payload = model()
            path.write_bytes(payload)
            digest = hashlib.sha256(payload).hexdigest()
            self.assertEqual(checked_file(path, digest), payload)
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                result = main([str(path), "--sha256", digest])
            self.assertEqual(result, 0, stderr.getvalue())
            self.assertEqual(json.loads(stdout.getvalue())["model_sha256"], digest)
            self.assertNotIn(root, stdout.getvalue())
            path.write_bytes(payload + b"injected")
            with contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
                result = main([str(path), "--sha256", digest])
            self.assertEqual(result, 2)
            self.assertNotIn(root, err.getvalue())
            self.assertEqual(out.getvalue(), "")
            alias = Path(root) / "alias.onnx"
            alias.symlink_to(path)
            with self.assertRaises(ValueError):
                checked_file(alias, digest)


if __name__ == "__main__":
    unittest.main()
