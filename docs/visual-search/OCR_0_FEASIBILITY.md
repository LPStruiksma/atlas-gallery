# OCR-0: offline English/Dutch text-in-photo feasibility

Unit [#575](https://github.com/LPStruiksma/atlas-gallery/issues/575), Vision D [#101](https://github.com/LPStruiksma/atlas-gallery/issues/101), scene search [#94](https://github.com/LPStruiksma/atlas-gallery/issues/94), and search [#175](https://github.com/LPStruiksma/atlas-gallery/issues/175). Evidence captured 2026-11-20. This study did not access owner photos, credentials, or `eval/held-out/**`.

## Outcome: CONDITIONAL

Proceed only to a bounded, non-production benchmark after all three asset licences and hashes are reviewed and the locked corpus is available. **Do not ship either candidate yet.** No ONNX runtime, weights, dictionary, or licensed image corpus was present; GitHub downloads returned HTTP 403. Consequently inference, accuracy, false-text/false-skip, prefilter, int8 byte size, and speed are **NOT MEASURED**. The included metric test proves arithmetic only.

A GO requires, on a locked EN/NL set, separately reported screenshot/receipt/sign performance, acceptable no-text false positives, zero unjustified prefilter skips, practical charging-only cost, and written redistribution clearance for every shipped file. A NO-GO follows if those gates fail or Dutch characters cannot be represented without an unredistributable dictionary.

## Candidates and three-part licence gate

| Candidate | Offline pipeline | Code licence | Pretrained weights | Dictionary/tokenizer |
|---|---|---|---|---|
| PP-OCR mobile ONNX (detector + recognizer) | PaddleOCR PP-OCR mobile detection and multilingual recognition exported to ONNX | PaddleOCR repository declares Apache-2.0 in its [upstream LICENSE](https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE) | **UNVERIFIED / no redistribution assertion.** Model-list links are not a per-artifact licence grant; review the exact detector and recognizer archive provenance before download. [Official model list](https://paddlepaddle.github.io/PaddleOCR/latest/en/version3.x/pipeline_usage/OCR.html) | **UNVERIFIED.** Hash and review the exact Dutch/Latin character dictionary separately; repository code licensing alone is insufficient. [Official dictionary documentation](https://paddlepaddle.github.io/PaddleOCR/latest/en/version3.x/module_usage/text_recognition.html) |
| docTR DBNet + CRNN, exported to ONNX | docTR detector/recognizer pipeline; ONNX export must be validated operator-by-operator | docTR repository declares Apache-2.0 in its [upstream LICENSE](https://github.com/mindee/doctr/blob/main/LICENSE) | **UNVERIFIED / no redistribution assertion.** The documentation's downloadable pretrained parameters do not by themselves establish artifact redistribution terms. [Official model documentation](https://mindee.github.io/doctr/using_doctr/using_models.html) | **UNVERIFIED.** Vocabulary configuration and any tokenizer/dictionary artifact need an independent source, hash and grant. [Official recognition model documentation](https://mindee.github.io/doctr/modules/models.html#text-recognition) |

These are primary upstream project materials, but only the code grants were inspectable as explicit licence files. “Open source code” must not be converted into a claim about separately hosted checkpoints or dictionaries. Before admitting an artifact, save URL, immutable revision, SHA-256, bytes, notices, attribution and commercial redistribution conclusion.

## Locked benchmark plan and current results

The planned corpus is deterministic synthetic or explicitly licensed: EN/NL screenshots, receipts and signs; visually varied no-text negatives; and handwriting as a non-gating stretch. Ground truth preserves punctuation and NFC Dutch diacritics. Scoring case-folds and collapses whitespace as documented in `tools/ocr_bench/metrics.py`; a missing detection is an empty hypothesis. Split and tune thresholds before locking test data.

| Candidate | Corpus / n | CER | WER | False text on no-text | False skips on text | int8 detector + recognizer | Host CPU latency |
|---|---:|---:|---:|---:|---:|---:|---:|
| PP-OCR mobile ONNX | NOT MEASURED / 0 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| docTR DBNet + CRNN ONNX | NOT MEASURED / 0 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |

**Exact blockers:** the checkout contains no ONNX artifacts or corpus; Python lacks `onnxruntime`, NumPy, OpenCV and Pillow; outbound GitHub/raw/API requests returned HTTP 403. Therefore there is no honest model output, quantized file size, or latency distribution to report. Reproduction steps and JSONL contracts are in `tools/ocr_bench/README.md`. The evaluator reports corpus-level CER/WER plus false text and false skips, and rejects missing/extra IDs.

### Environment metadata

Captured on 2026-11-20 UTC: Linux 6.18.57 x86_64 (glibc 2.39) container; Intel Xeon Platinum 8370C @ 2.80 GHz; 18,440,436 kB host-visible RAM; Python 3.12.13; ONNX Runtime **not installed**; NumPy/OpenCV/Pillow **not installed**; model execution provider, thread count, warmups and input resolution are **NOT MEASURED** because inference did not run. A future result must save `platform`, `/proc/cpuinfo` model, RAM, package versions, providers/options, thread counts, model hashes/bytes, quantization recipe, image resolution, per-stage median/p95, warmups and timed runs.

## Prefilter and S24+ projection

A 64×64 grayscale Sobel edge-density prefilter is cheap in principle, but its threshold, latency, skip rate, recall and false skips are **NOT MEASURED**. It stays disabled unless a held-out text-positive split demonstrates the predeclared recall gate; low-contrast receipts and screenshots are mandatory hard cases.

There is no defensible S24+ number without host timings. After measurement, report a range—not a point estimate—using these explicit assumptions: charging-only execution, 150,000 photos, one OCR pass per admitted photo, measured prefilter pass fraction `p`, phone/host stage scaling factors `s_det` and `s_rec`, and no thermal throttling claim. Estimate compute time as `150000 × (t_prefilter + p × (s_det×t_det + s_rec×t_rec))`, then show 0.5×/1×/2× scaling and 25% thermal/IO overhead. Peak RAM is runtime RSS delta plus decoded-image buffers. All present S24+ time, energy and RAM values are **NOT MEASURED**.

## Versioned storage, deletion and search integration

Key records by the existing stable media key, never a filesystem path:

```text
OcrText(mediaKey, modelId, modelVersion, schemaVersion, sourceRevision,
        languageHint, normalizedText, scannedAt, status)
OcrWord(mediaKey, modelVersion, ordinal, text, confidence,
        left, top, right, bottom)  # normalized [0,1], source orientation
```

Use a uniqueness fence `(mediaKey, modelVersion)` and transactional replace so partial output is invisible. `normalizedText` is a derived, rebuildable local search field; word boxes permit highlighting/copy without retaining crops. A model/schema change marks older rows stale and incrementally requeues them. Media deletion, permission revocation, hidden/private-boundary changes, volume removal and index reset synchronously delete text and boxes before search can return them. No OCR content enters logs, telemetry, backups by default, or a network service.

For [#175](https://github.com/LPStruiksma/atlas-gallery/issues/175), expose OCR as a separately labelled “Text in photos” field in the existing faceted index and join results by `mediaKey`; phrase/term matching must not masquerade as semantic understanding. For [#94](https://github.com/LPStruiksma/atlas-gallery/issues/94), OCR is bounded evidence for reranking or exact text constraints, not input that silently dominates visual similarity. Index rows must carry model version so explanations and deletion remain auditable.

## Bounded follow-ups

1. **OCR-0A (research, ≤3 days):** obtain licence-approved exact artifacts and a 25-per-stratum development corpus plus at least 50 no-text negatives; publish hashes/rights manifest and real ONNX results from both candidates.
2. **OCR-0B (research, ≤2 days):** quantize reproducibly, benchmark warm/cold per-stage host latency and RSS, then measure the prefilter at a predeclared recall threshold. Produce the assumption-based S24+ range only after host data exists.
3. **OCR-1 (only after GO):** package one model/dictionary as a split APK asset and prove offline initialization, notices and size budget. No scanner or UI.
4. **OCR-2 (separate unit):** charging-only, resumable scanner and transactional versioned store with deletion/revocation tests.
5. **OCR-3 / OCR-4 (separate design-gated units):** Search facet integration, then viewer select/copy with word-box overlays and accessibility review.

Handwriting remains a stretch result and cannot rescue poor printed-text performance. Each follow-up may terminate independently without admitting app code in this feasibility unit.
