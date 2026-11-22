#!/usr/bin/env python3
"""Reproducible OCR-0B quantization, prefilter and projection helpers."""
from __future__ import annotations
import argparse, hashlib, json, os, platform, resource, statistics, time
from pathlib import Path

MODELS = {"ppocr_det": ("PaddlePaddle__PP-OCRv5_mobile_det_onnx", "inference.onnx"),
          "ppocr_rec": ("PaddlePaddle__latin_PP-OCRv5_mobile_rec_onnx", "inference.onnx"),
          "doctr_det": ("Felix92__onnxtr-db-mobilenet-v3-large", "model.onnx"),
          "doctr_rec": ("Felix92__onnxtr-crnn-mobilenet-v3-small", "model.onnx")}

def percentile(values, q):
    """Nearest-rank-down percentile (zero-based floor of q * (n - 1))."""
    return None if not values else sorted(values)[int(q * (len(values) - 1))]
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
    if not 0 <= minimum_recall <= 1: raise ValueError("minimum recall must be between zero and one")
    # A threshold is inclusive.  Restricting it to observed, distinct scores means a tied group is
    # either admitted in full or skipped in full; selecting between equal values could violate recall.
    eligible = [score for score in sorted(set(positives))
                if sum(value >= score for value in positives) / len(positives) >= minimum_recall]
    threshold = max(eligible)
    achieved = sum(value >= threshold for value in positives) / len(positives)
    if achieved < minimum_recall:  # defensive invariant, also protects future selection changes
        raise AssertionError("selected threshold does not meet development recall")
    return threshold

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

def _rss_mb():
    value = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return value / (1024 * 1024 if platform.system() == "Darwin" else 1024)

def benchmark(candidate, corpus, models, threads=1, warmup=5, runner_builder=None, decoder=None):
    """Measure the existing OCR inference runner; unavailable runtime pieces stay NOT MEASURED."""
    manifest = [json.loads(x) for x in (corpus / "manifest.jsonl").read_text().splitlines() if x.strip()]
    if not manifest: raise ValueError("corpus manifest is empty")
    try:
        if runner_builder is None:
            from tools.ocr_bench.run_models import build_doctr, build_ppocr
            runner_builder = build_ppocr if candidate == "ppocr" else build_doctr
        if decoder is None:
            from PIL import Image
            def decoder(path):
                with Image.open(path) as image: image.load()
        rss_before, build_start = _rss_mb(), time.perf_counter()
        runner = runner_builder(models, threads)
        build_ms = (time.perf_counter() - build_start) * 1000
        def measured(row):
            path = str(corpus / row["path"])
            start = time.perf_counter(); decoder(path); decode_ms = (time.perf_counter() - start) * 1000
            words, det_ms, rec_ms, inference_ms = runner(path)
            return {"decode_ms": decode_ms, "detector_ms": det_ms, "recognizer_ms": rec_ms,
                    "total_ms": decode_ms + inference_ms, "text_found": bool(words)}
        first = measured(manifest[0])
        cold = {key: summary(([build_ms + first[key]] if key == "total_ms" else [first[key]])
                             if first[key] is not None else [])
                for key in ("decode_ms", "detector_ms", "recognizer_ms", "total_ms")}
        for row in manifest[1:1 + warmup]: measured(row)
        samples = [measured(row) for row in manifest]
        warm = {key: summary([row[key] for row in samples if row[key] is not None])
                for key in ("decode_ms", "detector_ms", "recognizer_ms", "total_ms")}
        return {"status": "MEASURED", "candidate": candidate, "threads": threads,
                "warmup_images_discarded": min(warmup, max(0, len(manifest) - 1)),
                "percentile_convention": "zero-based nearest-rank-down: floor(q * (n - 1))",
                "cold": cold, "warm": warm, "rss": {"before_mb": rss_before,
                "peak_mb": _rss_mb(), "delta_mb": max(0, _rss_mb() - rss_before)}}
    except (ImportError, ModuleNotFoundError, FileNotFoundError) as exc:
        return {"status": "NOT MEASURED", "candidate": candidate,
                "reason": f"{type(exc).__name__}: {exc}"}

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
    b = sub.add_parser("benchmark"); b.add_argument("candidate", choices=("ppocr", "doctr")); b.add_argument("corpus", type=Path); b.add_argument("models", type=Path); b.add_argument("output", type=Path); b.add_argument("--threads", type=int, default=1); b.add_argument("--warmup", type=int, default=5); b.add_argument("--prefilter-results", type=Path)
    args = parser.parse_args()
    if args.command == "quantize": result = quantize(args.models, args.output)
    elif args.command == "prefilter": result = prefilter(args.corpus, args.minimum_recall)
    else:
        result = benchmark(args.candidate, args.corpus, args.models, args.threads, args.warmup)
        if result["status"] == "MEASURED" and args.prefilter_results:
            pf = json.loads(args.prefilter_results.read_text())["result"]
            stages = (result["warm"]["detector_ms"]["median_ms"],
                      result["warm"]["recognizer_ms"]["median_ms"])
            result["projection_150000"] = (project_150k(pf["latency"]["median_ms"],
                pf["test_pass_fraction"], *stages, result["rss"]["delta_mb"])
                if all(value is not None for value in stages) else
                {"status": "NOT MEASURED", "reason": "detector or recognizer stage did not execute"})
    destination = args.output / "quantization.json" if args.command == "quantize" else args.output
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps({"environment": {"python": platform.python_version(), "platform": platform.platform(), "cpu_count": os.cpu_count()}, "result": result}, indent=2) + "\n")
if __name__ == "__main__": main()
