#!/usr/bin/env python3
"""Offline synthetic-only ONNX CPU latency probe; never opens photographs.

Input is a checksum-pinned, standalone local ONNX model. All model I/O is
in-memory and the synthetic input is deterministic. This does not evaluate
identity correctness, pretrained-weight rights, NNAPI or Android performance.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import time
from typing import Callable

MAX_MODEL_BYTES = 64 * 1024 * 1024
MAX_HW = 512
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def verified_model_bytes(path: Path, sha256: str) -> bytes:
    """Read one standalone local model, pinned by complete SHA-256.

    ONNX Runtime will receive these bytes, NOT the supplied path. This
    deliberately does not resolve external tensor data or custom plugins.
    """
    if not isinstance(sha256, str) or not SHA256.fullmatch(sha256):
        raise ValueError("Expected a 64-character lowercase SHA-256")
    if path.is_symlink() or not path.is_file():
        raise ValueError("Expected a regular, non-symlink local model")
    size = path.stat().st_size
    if not 0 < size <= MAX_MODEL_BYTES:
        raise ValueError("Model exceeds allowed standalone byte budget")
    with path.open("rb") as handle:
        data = handle.read(MAX_MODEL_BYTES + 1)
    if not data or len(data) > MAX_MODEL_BYTES:
        raise ValueError("Model exceeds allowed standalone byte budget")
    if hashlib.sha256(data).hexdigest() != sha256:
        raise ValueError("Model differs from pinned SHA-256")
    return data


def check_input_spec(inputs: list) -> tuple[str, tuple[int, int, int, int]]:
    """Reject dynamic/multi-input graphs and expensive synthetic input shapes."""
    if len(inputs) != 1:
        raise ValueError("Only standalone, single-input image graphs supported")
    spec = inputs[0]
    shape = getattr(spec, "shape", None)
    if (getattr(spec, "type", None) != "tensor(float)"
            or not isinstance(shape, (tuple, list)) or len(shape) != 4
            or any(type(x) is not int for x in shape)
            or shape[0] != 1 or shape[1] != 3
            or not all(64 <= side <= MAX_HW for side in shape[2:])):
        raise ValueError("Only fixed NCHW float32 [1,3,H,W] supported")
    name = getattr(spec, "name", None)
    if not isinstance(name, str) or not 1 <= len(name) <= 128:
        raise ValueError("Missing or malformed ONNX input name")
    return name, tuple(shape)


def _nearest_rank(times: list[int], fraction: float) -> float:
    ordered = sorted(times)
    index = max(0, math.ceil(len(ordered) * fraction) - 1)
    return round(ordered[index] / 1_000_000, 4)


def run_profile(
    session_factory: Callable[[], object],
    tensor_factory: Callable[[tuple[int, int, int, int]], object],
    *, warmups: int = 5, repeats: int = 50,
    clock: Callable[[], int] = time.perf_counter_ns,
) -> dict:
    """Time only session creation + inference, not tensor construction.

    Injectable factories permit deterministic stdlib-only tests. The result
    never contains source paths, inputs, embeddings or model outputs.
    """
    if type(warmups) is not int or not 1 <= warmups <= 100:
        raise ValueError("Warmup count must be 1..100")
    if type(repeats) is not int or not 10 <= repeats <= 2000:
        raise ValueError("Measured runs must be 10..2000")
    t0 = clock()
    session = session_factory()
    t1 = clock()
    name, shape = check_input_spec(session.get_inputs())
    tensor = tensor_factory(shape)
    feed = {name: tensor}
    t2 = clock()
    first_output = session.run(None, feed)
    t3 = clock()
    if not isinstance(first_output, list) or not first_output:
        raise ValueError("Model did not produce an output array")
    # Validate output numerics without returning any embeddings. Validation
    # is outside the timed inference span and only for the first run.
    for output in first_output:
        import numpy as np
        arr = np.asarray(output)
        if (arr.size == 0 or arr.size > 65536
                or arr.dtype.kind not in "fiu"
                or not np.isfinite(arr).all()):
            raise ValueError("Unusable or nonfinite model output")
    for _ in range(warmups):
        session.run(None, feed)
    times = []
    for _ in range(repeats):
        start = clock()
        session.run(None, feed)
        end = clock()
        if end < start:
            raise ValueError("Non-monotonic benchmark clock")
        times.append(end - start)
    if t1 < t0 or t3 < t2:
        raise ValueError("Non-monotonic benchmark clock")
    return {
        "status": "synthetic_cpu_runtime_only",
        "input_shape": list(shape),
        "first_inference_ms": round((t3 - t2) / 1_000_000, 4),
        "session_creation_ms": round((t1 - t0) / 1_000_000, 4),
        "warmups": warmups,
        "measured_runs": repeats,
        "steady_p50_ms": _nearest_rank(times, .50),
        "steady_p95_ms": _nearest_rank(times, .95),
        "steady_p99_ms": _nearest_rank(times, .99),
        "steady_max_ms": _nearest_rank(times, 1),
        "no_identity_accuracy_claim": True,
        "auto_confirm_allowed": False,
    }


def profile_onnx_bytes(model: bytes, *, warmups: int = 5,
                       repeats: int = 50, threads: int = 2,
                       input_contract: str = "random") -> dict:
    """Optional local ONNX Runtime/NumPy dependencies; CPU provider only."""
    if type(threads) is not int or not 1 <= threads <= 8:
        raise ValueError("Thread budget must be 1..8")
    import numpy as np
    import onnxruntime as ort
    if input_contract not in {"random", "torchreid_osnet_eval"}:
        raise ValueError("No verified synthetic model input contract")

    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    options.enable_profiling = False
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

    def new_session():
        sess = ort.InferenceSession(model, sess_options=options,
                                    providers=["CPUExecutionProvider"])
        if sess.get_providers() != ["CPUExecutionProvider"]:
            raise ValueError("Unexpected ONNX execution provider")
        return sess

    def make_tensor(shape):
        # No photos, EXIF or downloaded fixtures. An audited alternative uses
        # synthetic RGB stripes transformed using Torchreid eval coefficients.
        if input_contract == "random":
            return np.random.default_rng(713).random(shape, dtype=np.float32)
        from model_preprocessing import SHAPE, synthetic_contract_tensor
        if tuple(shape) != SHAPE:
            raise ValueError("Model shape disagrees with audited input contract")
        return np.asarray(synthetic_contract_tensor(input_contract),
                          dtype=np.float32).reshape(shape).copy()

    report = run_profile(new_session, make_tensor,
                         warmups=warmups, repeats=repeats)
    report.update({
        "execution_provider": "CPUExecutionProvider",
        "intra_op_threads": threads,
        "onnxruntime_version": ort.__version__,
        "model_bytes": len(model),
        "input_contract": input_contract,
        "resize_parity_verified": False,
        "caveat": "Wall-clock measurements are not cold OS-cache timings, Android "
                  "latency, energy, peak model RAM or identity accuracy. "
                  "Weights/training data must be cleared separately.",
    })
    return report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("--sha256", required=True,
                        help="Full lowercase digest independently pinned to a source")
    parser.add_argument("--warmups", type=int, default=5)
    parser.add_argument("--repeats", type=int, default=50)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--input-contract", choices=["random", "torchreid_osnet_eval"],
                        default="random")
    args = parser.parse_args(argv)
    try:
        model = verified_model_bytes(args.model, args.sha256)
        result = profile_onnx_bytes(model, warmups=args.warmups,
                                    repeats=args.repeats, threads=args.threads,
                                    input_contract=args.input_contract)
        result["sha256"] = args.sha256
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, ImportError, RuntimeError):
        # Do not leak model paths, private directory names or native tracebacks.
        print("Refused model or unsupported local CPU inference configuration",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
