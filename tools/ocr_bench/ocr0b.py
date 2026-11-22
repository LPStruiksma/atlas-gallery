#!/usr/bin/env python3
"""Reproducible OCR-0B quantization, prefilter and projection helpers."""
from __future__ import annotations
import argparse, hashlib, json, math, os, platform, statistics, time
from pathlib import Path

MODELS = {"ppocr_det": ("PaddlePaddle__PP-OCRv5_mobile_det_onnx", "inference.onnx"),
          "ppocr_rec": ("PaddlePaddle__latin_PP-OCRv5_mobile_rec_onnx", "inference.onnx"),
          "doctr_det": ("Felix92__onnxtr-db-mobilenet-v3-large", "model.onnx"),
          "doctr_rec": ("Felix92__onnxtr-crnn-mobilenet-v3-small", "model.onnx")}

def percentile(values, q): return None if not values else sorted(values)[int(q * (len(values) - 1))]
def summary(values): return {"n": len(values), "median_ms": statistics.median(values) if values else None,
                             "p95_ms": percentile(values, .95)}
def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""): h.update(block)
    return h.hexdigest()

def edge_score(path):
    from PIL import Image
    import numpy as np
    start = time.perf_counter()
    with Image.open(path) as image: gray = np.asarray(image.convert("L").resize((64, 64)), dtype=np.float32)
    gx, gy = gray[1:-1, 2:] - gray[1:-1, :-2], gray[2:, 1:-1] - gray[:-2, 1:-1]
    return float(np.mean(np.hypot(gx, gy))), path.stat().st_size, (time.perf_counter() - start) * 1000

def select_threshold(rows, minimum_recall):
    positives = sorted(r["score"] for r in rows if r["text_positive"])
    if not positives: raise ValueError("development split has no text-positive samples")
    allowed = math.floor((1 - minimum_recall) * len(positives) + 1e-12)
    return positives[0] if allowed == 0 else math.nextafter(positives[allowed - 1], math.inf)

def slice_report(rows, threshold):
    groups = {"all": rows}
    for key in ("language", "kind"):
        for value in sorted({r[key] for r in rows if r["text_positive"]}):
            groups[f"{key}:{value}"] = [r for r in rows if r["text_positive"] and r[key] == value]
    out = {}
    for name, items in groups.items():
        pos, neg = [r for r in items if r["text_positive"]], [r for r in items if not r["text_positive"]]
        false_skips = sum(r["score"] < threshold for r in pos)
        out[name] = {"samples": len(items), "text_positive": len(pos), "false_skips": false_skips,
                     "text_recall": None if not pos else (len(pos) - false_skips) / len(pos),
                     "no_text_pass": sum(r["score"] >= threshold for r in neg),
                     "no_text_skip": sum(r["score"] < threshold for r in neg)}
    return out

def project_150k(prefilter_ms, pass_fraction, det_ms, rec_ms, rss_delta_mb, decoded_mb=4.0):
    ranges = {}
    for scale in (.5, 1.0, 2.0):
        seconds = 150000 * (prefilter_ms + pass_fraction * scale * (det_ms + rec_ms)) / 1000
        ranges[str(scale)] = {"hours": seconds / 3600, "hours_with_25pct_overhead": seconds * 1.25 / 3600}
    return {"photos": 150000, "charging_only": True, "host_to_s24_plus_scale": [.5, 1., 2.],
            "assumption": "phone stage time = host stage time x scale; no energy or throttling claim",
            "times": ranges, "peak_ram_bound_mb": rss_delta_mb + decoded_mb}

def quantize(models, output):
    from onnx import load
    from onnxruntime import InferenceSession
    from onnxruntime.quantization import QuantType, quantize_dynamic
    output.mkdir(parents=True, exist_ok=True); results = []
    for name, parts in MODELS.items():
        source, target = models.joinpath(*parts), output / f"{name}.int8.onnx"
        row = {"component": name, "method": "onnxruntime.quantize_dynamic/QInt8", "source": str(source)}
        try:
            quantize_dynamic(str(source), str(target), weight_type=QuantType.QInt8)
            model = load(str(target)); InferenceSession(str(target), providers=["CPUExecutionProvider"])
            row.update({"status": "SESSION_VALIDATED", "opset": max(x.version for x in model.opset_import),
                        "fp32_bytes": source.stat().st_size, "fp32_sha256": sha256(source),
                        "int8_bytes": target.stat().st_size, "int8_sha256": sha256(target)})
        except Exception as exc: row.update({"status": "NOT MEASURED", "error": f"{type(exc).__name__}: {exc}"})
        results.append(row)
    return results

def prefilter(corpus, minimum_recall):
    manifest = [json.loads(x) for x in (corpus / "manifest.jsonl").read_text().splitlines() if x.strip()]
    rows = []
    for item in manifest:
        score, size, elapsed = edge_score(corpus / item["path"])
        rows.append({"id": item["id"], "split": item["split"], "language": item.get("language", "unspecified"),
                     "kind": item["kind"], "text_positive": item["kind"] != "no_text", "score": score,
                     "bytes_read": size, "latency_ms": elapsed})
    dev, test = ([r for r in rows if r["split"] == split] for split in ("dev", "test"))
    threshold = select_threshold(dev, minimum_recall)
    return {"algorithm": "64x64 grayscale central-difference Sobel mean magnitude",
            "threshold_selection": f"largest dev-only threshold with recall >= {minimum_recall}",
            "threshold": threshold, "dev": slice_report(dev, threshold), "test": slice_report(test, threshold),
            "test_pass_fraction": sum(r["score"] >= threshold for r in test) / len(test),
            "bytes_read": sum(r["bytes_read"] for r in rows), "latency": summary([r["latency_ms"] for r in rows])}

def main():
    parser = argparse.ArgumentParser(); sub = parser.add_subparsers(dest="command", required=True)
    q = sub.add_parser("quantize"); q.add_argument("models", type=Path); q.add_argument("output", type=Path)
    p = sub.add_parser("prefilter"); p.add_argument("corpus", type=Path); p.add_argument("output", type=Path); p.add_argument("--minimum-recall", type=float, default=.99)
    args = parser.parse_args(); result = quantize(args.models, args.output) if args.command == "quantize" else prefilter(args.corpus, args.minimum_recall)
    destination = args.output / "quantization.json" if args.command == "quantize" else args.output
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps({"environment": {"python": platform.python_version(), "platform": platform.platform(), "cpu_count": os.cpu_count()}, "result": result}, indent=2) + "\n")
if __name__ == "__main__": main()
