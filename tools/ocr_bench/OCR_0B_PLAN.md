# OCR-0B benchmark plan (issue #874)

This is a non-production, evidence-only continuation of OCR-0A (#873). The original, reproducible synthetic EN/NL corpus, model hashes, licences and baseline inference code are in `tools/ocr_bench/`, `docs/visual-search/results/ocr0a/` and `docs/visual-search/OCR_0_FEASIBILITY.md`. Use the pinned detector, recognizer and dictionary separately for both PP-OCRv5 and docTR/OnnxTR. No owner photos, unlicensed redistributed weights or app changes.

## Required work

1. Reproduce fp32 → int8 ONNX quantization for detector and recognizer independently, with command, method, bytes, SHA-256, opset, version, validation status and explicit unsupported/errors. Compare original fp32 and int8 inference on the same frozen corpus. Never infer int8 usability from file size alone.
2. Measure actual offline CPU image decode, detector, recognizer and total runtime, cold vs warm, median/p95, image counts, memory/RSS, runtime provider, thread count, warmups, resolutions and host/environment. A stage not run is `null`, not zero. Scorer test timing is not model timing.
3. Evaluate a cheap 64×64 grayscale prefilter on OCR-0A's existing fixed development/test split. Select its threshold on **dev only**, freeze it before test, and report independent test recall and false skips among text-positive images (by EN/NL and screenshot/receipt/sign), no-text pass/skip rates, bytes read and prefilter latency. Never treat OCR empty predictions as prefilter skips; if recall is inadequate, reject/disable the prefilter.
4. Use actually measured stage costs and admission fraction in a transparent *charging-only* 150,000-photo model. Show host-to-S24+ scaling assumptions/ranges and separate unsupported phone claims; include peak RAM bounds. Decide GO, CONDITIONAL or NO-GO subject to the rights gate.
5. Update `docs/visual-search/OCR_0_FEASIBILITY.md` and source-controlled scripts/tests/results with reproducible commands, pinned package versions, environment, aggregate measurements, limitations and exact evidence links. If inference, network, model or licence checking fails, explicitly state NOT MEASURED/NOT CLEARED rather than fabricating numbers.

## Safety and verdict

**All weights and dictionaries remain NOT CLEARED for redistribution** until separately evidenced upstream licences, notices and provenance are reviewed; model-card metadata alone is not clearance. A non-production compute-feasibility finding is possible, but a shipping/packaging GO is not. Do not modify Android, Gradle, CI/security/authority config or protected paths, or read `eval/held-out/**`. Keep downloaded models and non-redistributable pixels outside Git.
