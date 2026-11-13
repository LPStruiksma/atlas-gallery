#!/usr/bin/env python3
"""No installed ONNX/NumPy packages or real weights required by these tests."""
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stderr
from unittest.mock import patch

from benchmark_onnx_runtime import (
    check_input_spec, main, run_profile, verified_model_bytes,
)


class FakeInput:
    def __init__(self, shape=(1, 3, 256, 128), name="synthetic", dtype="tensor(float)"):
        self.shape = shape
        self.name = name
        self.type = dtype


class FakeSession:
    def __init__(self, input_spec=None):
        self.input_spec = input_spec or FakeInput()
        self.calls = 0

    def get_inputs(self):
        return [self.input_spec]

    def run(self, names, feed):
        self.calls += 1
        if names is not None or list(feed) != [self.input_spec.name]:
            raise AssertionError("Runner used unexpected ONNX feed")
        return [[1.0, 2.0, 3.0]]


class SyntheticOnnxRuntimeBenchmarkTests(unittest.TestCase):
    def test_sha_pin_prevents_weight_swap_and_never_mutates_file(self):
        with tempfile.TemporaryDirectory() as directory:
            model_path = Path(directory) / "synthetic.onnx"
            blob = b"fake-standalone-graph-bytes"
            model_path.write_bytes(blob)
            digest = hashlib.sha256(blob).hexdigest()
            self.assertEqual(verified_model_bytes(model_path, digest), blob)
            self.assertEqual(model_path.read_bytes(), blob)
            for incorrect in ("0" * 64, digest.upper(), "not-a-digest", ""):
                with self.subTest(digest=incorrect):
                    with self.assertRaises(ValueError):
                        verified_model_bytes(model_path, incorrect)
            model_path.write_bytes(blob + b"changed")
            with self.assertRaises(ValueError):
                verified_model_bytes(model_path, digest)
            link = Path(directory) / "model-link.onnx"
            link.symlink_to(model_path)
            with self.assertRaises(ValueError):
                verified_model_bytes(link, hashlib.sha256(blob + b"changed").hexdigest())
            with patch("benchmark_onnx_runtime.MAX_MODEL_BYTES", len(blob) - 1):
                with self.assertRaises(ValueError):
                    verified_model_bytes(model_path, hashlib.sha256(blob + b"changed").hexdigest())

    def test_non_image_dynamic_or_oversized_inputs_rejected(self):
        invalid = [
            FakeInput(shape=("batch", 3, 256, 128)),
            FakeInput(shape=(2, 3, 256, 128)),
            FakeInput(shape=(1, 4, 256, 128)),
            FakeInput(shape=(1, 3, 32, 128)),
            FakeInput(shape=(1, 3, 256, 513)),
            FakeInput(shape=(1, 3, True, 128)),
            FakeInput(dtype="tensor(float16)"),
            FakeInput(name=""),
            FakeInput(name="x" * 129),
        ]
        for spec in invalid:
            with self.subTest(shape=spec.shape, name=spec.name):
                with self.assertRaises(ValueError):
                    check_input_spec([spec])
        for specs in ([], [FakeInput(), FakeInput()]):
            with self.assertRaises(ValueError):
                check_input_spec(specs)
        self.assertEqual(check_input_spec([FakeInput()]),
                         ("synthetic", (1, 3, 256, 128)))

    def test_reproducible_percentiles_and_no_embedded_private_identifiers(self):
        class FakeArray:
            size = 3
            dtype = type("DType", (), {"kind": "f"})()
        class FakeNp:
            @staticmethod
            def asarray(output):
                return FakeArray()
            @staticmethod
            def isfinite(arr):
                return type("Truth", (), {"all": lambda self: True})()
        timer = iter(range(0, 10_000_000_000, 1_000_000))
        session = FakeSession()
        with patch.dict("sys.modules", {"numpy": FakeNp}):
            report = run_profile(lambda: session, lambda shape: "synthetic-data",
                                 warmups=2, repeats=10, clock=lambda: next(timer))
        self.assertEqual(session.calls, 13)
        self.assertEqual(report["input_shape"], [1, 3, 256, 128])
        self.assertEqual(report["session_creation_ms"], 1.0)
        self.assertEqual(report["first_inference_ms"], 1.0)
        self.assertEqual(report["steady_p50_ms"], 1.0)
        self.assertEqual(report["steady_p95_ms"], 1.0)
        self.assertEqual(report["steady_p99_ms"], 1.0)
        self.assertFalse(report["auto_confirm_allowed"])
        rendered = json.dumps(report)
        self.assertNotIn("synthetic-data", rendered)
        self.assertNotIn("FakeSession", rendered)

    def test_bounded_iterations_and_error_logging_do_not_leak_paths(self):
        for warmups, repeats in ((0, 10), (101, 10), (1, 9), (1, 2001),
                                 (True, 10), (1, False), ("1", 10)):
            with self.subTest(warmups=warmups, repeats=repeats):
                with self.assertRaises(ValueError):
                    run_profile(lambda: None, lambda shape: None,
                                warmups=warmups, repeats=repeats)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "SECRET-personal-model.onnx"
            path.write_bytes(b"not-a-real-model")
            err = io.StringIO()
            with redirect_stderr(err):
                code = main([str(path), "--sha256", "0" * 64])
            self.assertEqual(code, 2)
            self.assertNotIn(str(path), err.getvalue())
            self.assertNotIn("SECRET", err.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
