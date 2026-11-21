#!/usr/bin/env python3
"""Write the OCR-0A run manifest: host, package versions, artifact and corpus hashes, stage timings.

    python3 tools/ocr_bench/run_manifest.py CORPUS_DIR MODELS_DIR PRED_DIR OUT.json --threads 1
PRED_DIR holds ppocr.jsonl and doctr.jsonl written by run_models.py.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata as md
import json
import platform
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from tools.ocr_bench.fetch_models import ARTIFACTS  # noqa: E402


def _stats(values: list[float]) -> dict:
    ordered = sorted(values)
    return {"median_ms": round(statistics.median(values), 1),
            "p95_ms": round(ordered[int(0.95 * (len(ordered) - 1))], 1), "n": len(values)}


def main() -> None:
    parser = argparse.ArgumentParser()
    for name in ("corpus", "models", "preds", "output"):
        parser.add_argument(name, type=Path)
    parser.add_argument("--threads", type=int, default=1)
    args = parser.parse_args()
    rows = [json.loads(line) for line in (args.corpus / "manifest.jsonl").read_text("utf-8").splitlines()]
    cpu = next((line.split(":", 1)[1].strip() for line in open("/proc/cpuinfo") if line.startswith("model name")), "?")
    mem_kb = next((int(line.split()[1]) for line in open("/proc/meminfo") if line.startswith("MemTotal")), 0)
    manifest = {
        "host": {"platform": platform.platform(), "python": platform.python_version(), "cpu": cpu,
                 "logical_cpus": __import__("os").cpu_count(), "ram_kb": mem_kb,
                 "execution_provider": "CPUExecutionProvider", "intra_op_threads": args.threads,
                 "inter_op_threads": 1, "gpu": False},
        "packages": {p: md.version(p) for p in ("onnxruntime", "numpy", "opencv-python", "rapidocr-onnxruntime",
                                                 "onnxtr", "pillow", "pyyaml")},
        "corpus": {"recipe": rows[0]["recipe"], "seed": rows[0]["seed"], "images": len(rows),
                   "text_positive": sum(1 for r in rows if r["text"]),
                   "no_text": sum(1 for r in rows if not r["text"]),
                   "splits": {s: sum(1 for r in rows if r["split"] == s) for s in ("dev", "test")},
                   "image_sizes": sorted({f'{r["width"]}x{r["height"]}' for r in rows}),
                   "manifest_sha256": hashlib.sha256((args.corpus / "manifest.jsonl").read_bytes()).hexdigest()},
        "artifacts": [{"repo": r, "revision": v, "file": f, "bytes": b, "sha256": h} for r, v, f, b, h in ARTIFACTS],
        "run": {"warmup_images_discarded": 5, "timed_runs_per_image": 1,
                "detection_forced_on_every_image": True, "prefilter": "none (OCR-0B)",
                "commands": ["python3 tools/ocr_bench/fetch_models.py MODELS",
                             "python3 -m tools.ocr_bench.gen_corpus CORPUS --seed 873",
                             "python3 tools/ocr_bench/run_models.py ppocr CORPUS MODELS ppocr.jsonl --threads 1",
                             "python3 tools/ocr_bench/run_models.py doctr CORPUS MODELS doctr.jsonl --threads 1",
                             "python3 -m tools.ocr_bench.evaluate CORPUS/manifest.jsonl X.jsonl [--split dev|test]"]},
        "latency": {},
    }
    for name in ("ppocr", "doctr"):
        preds = [json.loads(line) for line in (args.preds / f"{name}.jsonl").read_text("utf-8").splitlines()]
        manifest["latency"][name] = {
            "total": _stats([p["latency_ms"] for p in preds]),
            "detector": _stats([p["detector_ms"] for p in preds if p["detector_ms"]]),
            "recognizer": _stats([p["recognizer_ms"] for p in preds if p["recognizer_ms"]]),
            "predictions_sha256": hashlib.sha256((args.preds / f"{name}.jsonl").read_bytes()).hexdigest(),
            "empty_predictions": sum(1 for p in preds if not p["text"])}
    args.output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
