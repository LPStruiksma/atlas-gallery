# OCR-0 benchmark harness

This dependency-free harness scores **real model output**; its unit tests verify scoring and input integrity, **not OCR model accuracy**. It never traverses a gallery. Prepare an explicitly licensed corpus directory outside the repository and a JSONL manifest with one row per image:

```text
{"id":"synthetic-sign-001","path":"corpus/sign-001.png","kind":"sign","language":"nl","text":"Verboden toegang","licence":"generated: project fixture recipe v1"}
```

Allowed `kind` values for the planned matrix are `screenshot`, `receipt`, `sign`, `no_text`, and the separately reported stretch `handwriting`. Use `language` values `en` or `nl` for text-positive fixtures; negatives may leave language unspecified (reported under `unspecified`, not silently assigned to one language). Record a source URL, SPDX expression or generation recipe in `licence`; do not use owner media or `eval/held-out/**`. A prediction file has `{"id":"...","text":"...","words":[{"text":"...","box":[x0,y0,x1,y1]}],"latency_ms":12.3}`. Detector misses must remain as empty predictions rather than disappearing.

The scorer rejects duplicate, missing, or extra IDs. Results contain pooled `cer`/`wer`, as well as `by_language`, `by_kind`, and `by_language_kind` entries with the **same** character/word denominators, edit counts, CER/WER and false-event counts. `no_text_samples` denominates `false_text_rate`; `text_samples` denominates `false_skip_rate` (an empty OCR prediction on a text-positive image, **not** a separately measured prefilter skip). Undefined rates are JSON `null` if their denominator is zero. Do not average per-slice rates to obtain pooled CER/WER: use the pooled edit and reference-length totals.

## Reproduction

1. Acquire each candidate's detector, recognizer and dictionary only after the report's three-part licence gate. Do not add them to Git.
2. Freeze SHA-256, byte size, source URL, opset, quantization method, runtime/providers, threads, warmups/runs, image dimensions, CPU/OS/RAM and corpus licence/counts in a run manifest.
3. Run the candidate's documented ONNX inference entry point over every manifest row, including no-text images, and write prediction JSONL. Time decode, detection and recognition separately with a monotonic clock; report median and p95 after at least five warmups.
4. Score with `python3 -m tools.ocr_bench.evaluate manifest.jsonl predictions.jsonl --output result.json` (add `--split dev|test` to score one split). CER/WER are pooled over text-positive images only; text on a no-text image is `false_text`, never CER insertions.
5. Run scoring/input-integrity checks with `python3 -m unittest tools.ocr_bench.test_metrics`.

For the prefilter experiment, predeclare a threshold on a development split, run it before OCR on the locked test split, and report bytes read, latency, skip rate and **false skips among text-positive images** separately from scorer-level empty predictions. A safe candidate is a 64×64 grayscale Sobel edge-density threshold; it is not admissible unless recall is measured. Store only manifests, aggregate results and redistributable generated fixtures.

## OCR-0A: real-model run (#873)

`requirements-ocr.txt` pins the benchmark-only packages. `fetch_models.py` downloads the pinned PP-OCRv5 and docTR/OnnxTR ONNX files and verifies SHA-256; `gen_corpus.py` renders the deterministic EN/NL corpus; `run_models.py ppocr|doctr` runs real inference and writes prediction JSONL with per-stage timings; `run_manifest.py` records host, versions and hashes. Recorded outputs: `docs/visual-search/results/ocr0a/`. `test_corpus.py` skips without Pillow.

## OCR-0B: non-production int8/prefilter experiment (#874)

Keep models, quantized files, and generated pixels outside Git. After the OCR-0A fetch and corpus
commands, run `python3 -m tools.ocr_bench.ocr0b quantize MODELS WORK/int8`, then validate each
candidate's detector and recognizer together by placing the generated files at their normal paths
in a disposable model tree and running `run_models.py` plus `evaluate` on the frozen corpus.
`SESSION_VALIDATED` means only that ONNX Runtime loaded the graph; it is **not** inference validation.
Record an unsupported operator or failed candidate run as `NOT MEASURED`, never as zero.

Run `python3 -m tools.ocr_bench.ocr0b prefilter CORPUS WORK/prefilter.json --minimum-recall .99`.
The threshold is chosen solely from the fixed development split; output independently reports
locked-test EN/NL and kind recall, false skips, no-text pass/skip, bytes read and latency. This
command does not invoke OCR, so an OCR empty prediction cannot become a prefilter skip.

Measure a candidate with the existing inference instrumentation and produce the charging-only
projection with `python3 -m tools.ocr_bench.ocr0b benchmark ppocr CORPUS MODELS WORK/bench.json
--prefilter-results WORK/prefilter.json` (use `doctr` for the other candidate). The first result
includes model construction and is cold; warmups are discarded before the warm sample. Output
reports independently measured decode, detector, recognizer and end-to-end totals, process peak
RSS, and uses the documented zero-based nearest-rank-down percentile (`floor(q * (n - 1))`). A
missing runtime, model, or unexecuted inference stage remains **NOT MEASURED**; it is never zero.
