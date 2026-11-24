#!/usr/bin/env python3
"""Offline, opt-in ONNX Runtime 1.23.2 CPU admission and timing probe."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import statistics
import time
from pathlib import Path

ORT_VERSION = "1.23.2"
MAX_MODEL_BYTES = 128 * 1024 * 1024
MAX_RIGHTS_BYTES = 64 * 1024


def verify_regular_file(path: Path, expected_sha256: str) -> int:
    if len(expected_sha256) != 64 or any(c not in "0123456789abcdef" for c in expected_sha256):
        raise ValueError("sha256 must be 64 lowercase hexadecimal characters")
    st = path.lstat()
    if not path.is_file() or path.is_symlink() or st.st_nlink != 1 or st.st_size > MAX_MODEL_BYTES:
        raise ValueError("model must be a non-linked regular file no larger than 128 MiB")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != expected_sha256:
        raise ValueError("model sha256 mismatch")
    return st.st_size


def verify_rights_file(path: Path, model_sha256: str) -> None:
    st = path.lstat()
    if not path.is_file() or path.is_symlink() or st.st_nlink != 1 or st.st_size > MAX_RIGHTS_BYTES:
        raise ValueError("rights approval must be a non-linked regular text file no larger than 64 KiB")
    rights = path.read_text(encoding="utf-8")
    if model_sha256 not in rights or "APPROVED FOR LOCAL EVALUATION" not in rights:
        raise ValueError("rights file must explicitly approve this exact digest for local evaluation")


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int((len(ordered) - 1) * fraction))]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--rights-file", type=Path, required=True,
                        help="local text approval; must contain the exact model SHA-256")
    parser.add_argument("--runs", type=int, default=0,
                        help="actual synthetic-input timed runs; zero performs graph admission only")
    args = parser.parse_args(argv)
    if args.runs < 0 or args.runs > 1000:
        raise ValueError("runs must be in [0,1000]")
    size = verify_regular_file(args.model, args.sha256)
    verify_rights_file(args.rights_file, args.sha256)

    import numpy as np
    import onnxruntime as ort
    if ort.__version__ != ORT_VERSION:
        raise RuntimeError(f"onnxruntime {ORT_VERSION} required, found {ort.__version__}")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    session = ort.InferenceSession(str(args.model), sess_options=options,
                                   providers=["CPUExecutionProvider"])
    inputs, outputs = session.get_inputs(), session.get_outputs()
    if len(inputs) != 1 or len(outputs) != 1:
        raise ValueError("exactly one input and one output are required")
    shape = inputs[0].shape
    if inputs[0].type != "tensor(float)" or len(shape) != 4 or shape[0] not in (1, "batch", None):
        raise ValueError("expected one float32 NCHW input with batch one")
    concrete = [1 if value in (None, "batch") else value for value in shape]
    if any(isinstance(value, bool) or not isinstance(value, int) or value <= 0 for value in concrete):
        raise ValueError("input dimensions must be fixed positive integers except batch")
    report = {"model_sha256": args.sha256, "model_bytes": size,
              "onnxruntime": ort.__version__, "provider": "CPUExecutionProvider",
              "input": {"shape": concrete, "type": inputs[0].type},
              "output": {"shape": outputs[0].shape, "type": outputs[0].type},
              "actual_timing": "NOT MEASURED"}
    if args.runs:
        tensor = np.zeros(concrete, dtype=np.float32)
        session.run(None, {inputs[0].name: tensor})
        elapsed = []
        for _ in range(args.runs):
            start = time.perf_counter_ns()
            session.run(None, {inputs[0].name: tensor})
            elapsed.append((time.perf_counter_ns() - start) / 1_000_000)
        report["actual_timing"] = {
            "runs": args.runs, "input": "synthetic_zero_tensor_no_media",
            "p50_ms": statistics.median(elapsed), "p95_ms": percentile(elapsed, .95),
            "host": {"platform": os.uname().sysname, "machine": os.uname().machine},
            "preprocessing_wall_time": "NOT MEASURED",
        }
    print(json.dumps(report, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
