#!/usr/bin/env python3
"""Run REAL offline ONNX OCR inference over an OCR-0A corpus manifest (OCR-0A, #873).

Needs `tools/ocr_bench/requirements-ocr.txt` (onnxruntime, numpy, opencv, rapidocr-onnxruntime,
onnxtr) and the model files fetched by `fetch_models.py`; nothing here is imported by the app
or by the scorer's unit tests.

    python3 tools/ocr_bench/run_models.py ppocr  CORPUS_DIR MODELS_DIR OUT.jsonl [--threads 1]
    python3 tools/ocr_bench/run_models.py doctr  CORPUS_DIR MODELS_DIR OUT.jsonl [--threads 1]

Prediction rows follow the README contract and add per-stage timings. Detector misses stay as
empty predictions. Detection is *always* run: library shortcuts that skip detection for short or
very wide images are disabled so a skip cannot masquerade as a detector miss.
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
from pathlib import Path


def _sess_opts(threads: int):
    from onnxruntime import ExecutionMode, GraphOptimizationLevel, SessionOptions
    opts = SessionOptions()
    opts.log_severity_level = 4
    opts.execution_mode = ExecutionMode.ORT_SEQUENTIAL
    opts.graph_optimization_level = GraphOptimizationLevel.ORT_ENABLE_ALL
    opts.intra_op_num_threads = threads
    opts.inter_op_num_threads = 1
    return opts


# ---------------------------------------------------------------- PP-OCRv5 (det + latin rec)
def build_ppocr(models: Path, threads: int):
    import numpy as np  # noqa: F401
    import rapidocr_onnxruntime.utils as ru
    import yaml
    from rapidocr_onnxruntime import RapidOCR
    from rapidocr_onnxruntime.ch_ppocr_v3_det import TextDetector
    from rapidocr_onnxruntime.ch_ppocr_v3_rec import TextRecognizer

    class _Session(ru.OrtInferSession):  # same session, explicit thread pinning
        def __init__(self, config):
            from onnxruntime import InferenceSession
            self.session = InferenceSession(config["model_path"], sess_options=_sess_opts(threads),
                                            providers=["CPUExecutionProvider"])
            self.meta_dict = {}

        def have_key(self, key="character"):
            return False

    import rapidocr_onnxruntime.ch_ppocr_v3_det.text_detect as td
    import rapidocr_onnxruntime.ch_ppocr_v3_rec.text_recognize as tr
    td.OrtInferSession = tr.OrtInferSession = _Session

    det_dir = models / "PaddlePaddle__PP-OCRv5_mobile_det_onnx"
    rec_dir = models / "PaddlePaddle__latin_PP-OCRv5_mobile_rec_onnx"
    det_yml = yaml.safe_load((det_dir / "inference.yml").read_text(encoding="utf-8"))
    rec_yml = yaml.safe_load((rec_dir / "inference.yml").read_text(encoding="utf-8"))
    post = det_yml["PostProcess"]
    chars = list(rec_yml["PostProcess"]["character_dict"])
    engine = RapidOCR()
    engine.text_detector = TextDetector({
        "model_path": str(det_dir / "inference.onnx"), "use_cuda": False,
        "limit_side_len": 960, "limit_type": "max",
        "thresh": post["thresh"], "box_thresh": post["box_thresh"],
        "max_candidates": post["max_candidates"], "unclip_ratio": post["unclip_ratio"],
        "use_dilation": False, "score_mode": "fast"})
    engine.text_recognizer = TextRecognizer({
        "model_path": str(rec_dir / "inference.onnx"), "use_cuda": False,
        "rec_img_shape": [3, 48, 320], "rec_batch_num": 6, "keys_path": chars})
    seen = {"det": None, "rec": None}  # None = stage did not execute for this image

    class Timed:  # records each stage's own elapsed time even when the pipeline returns no text
        def __init__(self, name, inner):
            self.name, self.inner = name, inner

        def __call__(self, *args, **kwargs):
            out = self.inner(*args, **kwargs)
            seen[self.name] = out[1] * 1000  # both rapidocr stages return (result, elapsed_s)
            return out

        def __getattr__(self, attr):
            return getattr(self.inner, attr)
    engine.text_detector = Timed("det", engine.text_detector)
    engine.text_recognizer = Timed("rec", engine.text_recognizer)
    engine.use_angle_cls = False      # orientation classifier is not part of this candidate
    engine.min_height = 0             # never skip detection on short images
    engine.width_height_ratio = -1    # nor on wide ones
    engine.text_score = 0.5

    def run(path: str):
        seen["det"] = seen["rec"] = None
        t0 = time.perf_counter()
        result, _ = engine(path)
        total = (time.perf_counter() - t0) * 1000
        if not result:
            return [], seen["det"], seen["rec"], total
        words = [{"text": text, "box": [min(p[0] for p in box), min(p[1] for p in box),
                                        max(p[0] for p in box), max(p[1] for p in box)],
                  "score": float(score)} for box, text, score in result]
        return words, seen["det"], seen["rec"], total
    return run


# ---------------------------------------------------------------- docTR (DBNet + CRNN)
def build_doctr(models: Path, threads: int):
    from onnxtr.io import DocumentFile
    from onnxtr.models import crnn_mobilenet_v3_small, db_mobilenet_v3_large, ocr_predictor
    from onnxtr.models.engine import EngineConfig

    cfg = EngineConfig(providers=["CPUExecutionProvider"], session_options=_sess_opts(threads))
    det = db_mobilenet_v3_large(str(models / "Felix92__onnxtr-db-mobilenet-v3-large" / "model.onnx"),
                                engine_cfg=cfg)
    rec = crnn_mobilenet_v3_small(str(models / "Felix92__onnxtr-crnn-mobilenet-v3-small" / "model.onnx"),
                                  engine_cfg=cfg)
    predictor = ocr_predictor(det_arch=det, reco_arch=rec, assume_straight_pages=True,
                              det_engine_cfg=cfg, reco_engine_cfg=cfg)
    stage = {"det": None, "rec": None}  # None = stage did not execute for this image

    class Timed:  # proxy: OCRPredictor calls its sub-predictors as plain callables
        def __init__(self, name, inner):
            self.name, self.inner = name, inner

        def __call__(self, *args, **kwargs):
            start = time.perf_counter()
            out = self.inner(*args, **kwargs)
            stage[self.name] = (stage[self.name] or 0.0) + (time.perf_counter() - start) * 1000
            return out

        def __getattr__(self, attr):
            return getattr(self.inner, attr)
    predictor.det_predictor = Timed("det", predictor.det_predictor)
    predictor.reco_predictor = Timed("rec", predictor.reco_predictor)

    def run(path: str):
        stage["det"] = stage["rec"] = None
        pages = DocumentFile.from_images([path])
        t0 = time.perf_counter()
        doc = predictor(pages)
        total = (time.perf_counter() - t0) * 1000
        words = []
        for page in doc.pages:
            h, w = page.dimensions
            for block in page.blocks:
                for line in block.lines:
                    for word in line.words:
                        (x0, y0), (x1, y1) = word.geometry
                        words.append({"text": word.value, "score": float(word.confidence),
                                      "box": [x0 * w, y0 * h, x1 * w, y1 * h]})
        return words, stage["det"], stage["rec"], total
    return run


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("candidate", choices=("ppocr", "doctr"))
    parser.add_argument("corpus", type=Path)
    parser.add_argument("models", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--threads", type=int, default=1)
    parser.add_argument("--warmup", type=int, default=5)
    args = parser.parse_args()

    run = (build_ppocr if args.candidate == "ppocr" else build_doctr)(args.models, args.threads)
    manifest = [json.loads(line) for line in
                (args.corpus / "manifest.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    for row in manifest[: args.warmup]:  # warm-up passes are discarded
        run(str(args.corpus / row["path"]))
    out, totals = [], []
    tidy = lambda words: [{**w, "score": round(w["score"], 3), "box": [round(v) for v in w["box"]]}
                          for w in words]  # integer pixels: long decimals trip the public-text guard
    for row in manifest:
        words, det_ms, rec_ms, total_ms = run(str(args.corpus / row["path"]))
        text = " ".join(w["text"] for w in words)  # each library's native reading order
        out.append({"id": row["id"], "text": text, "words": tidy(words), "latency_ms": round(total_ms, 3),
                    "detector_ms": None if det_ms is None else round(det_ms, 3),
                    "recognizer_ms": None if rec_ms is None else round(rec_ms, 3)})
        totals.append(total_ms)
    args.output.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in out), encoding="utf-8")
    ordered = sorted(totals)
    print(f"{args.candidate}: {len(out)} images, median {statistics.median(totals):.1f} ms, "
          f"p95 {ordered[int(0.95 * (len(ordered) - 1))]:.1f} ms, empty {sum(1 for r in out if not r['text'])}")


if __name__ == "__main__":
    main()
