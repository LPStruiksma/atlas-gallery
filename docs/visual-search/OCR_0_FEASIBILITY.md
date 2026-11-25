# OCR-0: offline English/Dutch text-in-photo feasibility

Unit [#575](https://github.com/LPStruiksma/atlas-gallery/issues/575), Vision D [#101](https://github.com/LPStruiksma/atlas-gallery/issues/101), scene search [#94](https://github.com/LPStruiksma/atlas-gallery/issues/94), and search [#175](https://github.com/LPStruiksma/atlas-gallery/issues/175). Evidence captured 2026-11-20 (OCR-0M) and 2026-11-21 (OCR-0A measurements). This study did not access owner photos, credentials, or `eval/held-out/**`.

## Outcome: CONDITIONAL (OCR-0A measured, 2026-11-21)

Both candidates ran **real offline ONNX inference** (detector + recognizer, CPU, 1 thread) over a deterministic 240-image EN/NL synthetic corpus ([#873](https://github.com/LPStruiksma/atlas-gallery/issues/873)). On that corpus both read clean printed text well and neither skipped a text image. The corpus is synthetic and clean-ish, so these numbers are an **upper bound for printed text, not real-photo accuracy**; real-photo accuracy, int8 size, prefilter recall and S24+ cost stay **NOT MEASURED**. Do not ship either candidate: no weight or dictionary clears the rights gate below, and a corpus of real photographs the project may redistribute does not exist.

Proceed to OCR-0B (quantization, stage latency, prefilter) only as a non-production benchmark. A GO still needs: acceptable accuracy on non-synthetic, rights-cleared images; no-text false positives acceptable (docTR 12/60 here); a written redistribution decision for every shipped file; and practical charging-only cost.

## Candidates and three-part licence gate

Every artifact is pinned to an immutable Hugging Face revision with SHA-256 and byte size in `tools/ocr_bench/fetch_models.py` and `docs/visual-search/results/ocr0a/run_manifest.json`. GitHub was unreachable from the benchmark host (HTTP 400/403), so **code licences are taken from the pinned PyPI wheels' own metadata**, not from a GitHub LICENSE file at a commit.

| Part | PP-OCR mobile (PaddlePaddle) | docTR via OnnxTR (Mindee weights, ONNX conversion by a third party) |
|---|---|---|
| Inference code | `rapidocr-onnxruntime==1.2.3` (wheel sha256 `c707d3a6…7819`) declares Apache-2.0; used only for DB post-processing and CTC decoding glue. Runtime `onnxruntime` 1.32.0: MIT | `onnxtr==0.9.0` (wheel sha256 `bb8a47af…a05`) declares Apache-2.0 |
| Detector weights | `PaddlePaddle/PP-OCRv5_mobile_det_onnx` @ `e6f4fa85…` `inference.onnx` 4,826,518 B, sha256 `a4319856…e61d`; model card metadata `license: apache-2.0` | `Felix92/onnxtr-db-mobilenet-v3-large` @ `21338515…` `model.onnx` 16,076,965 B, sha256 `4987e7bd…4c64`; card `license: apache-2.0` |
| Recognizer weights | `PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx` @ `89d3a50e…` `inference.onnx` 8,042,023 B, sha256 `78881130…1498`; card `license: apache-2.0` | `Felix92/onnxtr-crnn-mobilenet-v3-small` @ `96dcb282…` `model.onnx` 8,331,378 B, sha256 `bded4d49…3b33`; card `license: apache-2.0` |
| Dictionary / vocabulary | 836 Latin characters inside the recognizer's `inference.yml` (sha256 `0bbe9845…5c84`, same card and licence field). Includes Dutch diacritics (é ë ï ö ü ç) | `vocab` in `config.json` (sha256 `5d23566e…1747`): ASCII, `°£€¥¢฿` and French accents (àâéèêëîïôùûüç). **No ö, ä or ß** |
| Attribution | Apache-2.0 NOTICE text not shipped inside the ONNX files; attribution to PaddlePaddle/PaddleOCR needed | Attribution to Mindee docTR and the OnnxTR author needed |
| Redistribution conclusion | **NOT CLEARED.** The only grant is a card metadata field; the cards state no training-data provenance or per-artifact LICENSE file | **NOT CLEARED**, same reason, plus the ONNX files are a third party's conversion of Mindee weights |

“Apache-2.0 on a model card” is evidence, not clearance. Unknown permission is NOT CLEARED, so nothing is packaged (OCR-1 stays held). Clearing needs an owner/legal reading of the upstream training-data terms or a first-party Apache-2.0 LICENSE and NOTICE shipped with the weights.

## Corpus (rights provenance)

`tools/ocr_bench/gen_corpus.py` (recipe `ocr0a-synthetic-v1`, seed 873) renders original text written for this project with DejaVu Sans and Liberation fonts (system fonts, rendering their glyphs into generated images is permitted by those licences) using Pillow. No third-party pixels, owner media or `eval/held-out/**`. Images are not committed; the manifest with ground truth, split and per-image PNG hashes is `docs/visual-search/results/ocr0a/corpus_manifest.jsonl` (hashes depend on the Pillow build and fonts, the text/strata/split do not).

| Stratum | Images | Notes |
|---|---:|---|
| EN × screenshot / receipt / sign | 30 each | 10 dev, 20 test per cell; every fifth image low contrast (foreground pulled to ~38% of its distance from the background) |
| NL × screenshot / receipt / sign | 30 each | Dutch pool deliberately holds ë é ï ö ç, “ij”, comma decimals |
| No-text negatives | 60 | gradients, shapes, stripes, grids, noise and text-like line texture (hard negatives), 20 dev / 40 test |
| Total | 240 | 180 text-positive; handwriting (non-gating stretch) not generated |

## Results

Host: Intel Xeon @ 2.30 GHz, 4 logical CPUs, 15 GiB, Linux 6.18, Python 3.13, ONNX Runtime 1.32.0 `CPUExecutionProvider`, intra-op threads 1, 5 discarded warm-up images, one timed pass per image, images 460–900 px wide by 230–960 px high (PP-OCR detector limit side 960; docTR detector resizes to 1024×1024). Detection is forced on every image, so empty predictions are genuine detector or recognizer-score-filter outcomes: **there is no prefilter in this unit, so no unmeasured prefilter skips exist**. Score = pooled over text-positive images (edits and reference lengths summed; negatives never add insertions to CER/WER, they are counted as false text). Full files: `docs/visual-search/results/ocr0a/{ppocr,doctr}_{all,dev,test}.json`.

Locked **test split** (120 text-positive, 40 no-text):

| Candidate | CER | WER | False text on no-text | False skips on text | Median / p95 per image |
|---|---:|---:|---:|---:|---:|
| PP-OCRv5 mobile det + latin rec | 0.21% | 0.91% | 0/40 | 0/120 | 332 / 523 ms (all 240) |
| docTR DBNet MobileNetV3-L + CRNN MobileNetV3-S | 0.38% | 2.16% | 7/40 | 0/120 | 684 / 813 ms (all 240) |

Per language × kind on the test split (CER / WER %):

| Language × kind | PP-OCR | docTR |
|---|---:|---:|
| EN screenshot | 0.00 / 0.00 | 0.00 / 0.00 |
| EN receipt | 0.2 / 1.1 | 0.08 / 0.4 |
| EN sign | 0.00 / 0.00 | 1.4 / 5.1 |
| NL screenshot | 0.00 / 0.00 | 0.5 / 3.3 |
| NL receipt | 0.26 / 1.0 | 0.22 / 1.7 |
| NL sign | 2.0 / 7.3 | 3.5 / 20.0 |

Pooled by language (test): PP-OCR EN 0.11% / 0.67%, NL 0.31% / 1.17%; docTR EN 0.18% / 0.80%, NL 0.58% / 3.65%. Dev split and all-240 files are in the results folder; the same strata ranking holds.

What the errors are (all 33 imperfect docTR rows and 16 imperfect PP-OCR rows were inspected): both models read “Reïntegratie” as “Reintegratie”, so the ï is lost by both even though PP-OCR's dictionary contains it. docTR's recognizer vocabulary has no ö, so “coördinatie” can never be right (“coordinatie”, twice); it also drops or swaps accents elsewhere (“Naieve”, “Belgié”, “Financièle”), and adds stray `-`, `I` or doubled letters in several signs and receipts. PP-OCR's remaining errors are reading-order swaps on two-line Dutch signs (“Uitgang en Koffie gebak”, “Koffie een gebak”) and a stray trailing character on some receipt tokens (“250g g”). docTR's 12/60 false text (20%) comes from the 10 text-like `fine_lines` textures (7 hits) and the 9 `noise` images (5 hits); PP-OCR produced no false text on any negative style. An earlier revision of this unit clipped long sign text (“randdeur … houde”); the generator now shrinks the font until every ground-truth line fits and records `text_px` / `text_limit_px` per row, and the committed results come from the corrected corpus.

Per-stage latency (median, with the number of images in which the stage ran): PP-OCR detector 165.5 ms (240 of 240) and recognizer 162.1 ms (188 of 240; the recognizer does not run when the detector finds no box, and its internal score filter then empties 8 detector hits); docTR detector, including its 1024×1024 resize and post-processing, 656.5 ms (240 of 240) and recognizer 17.4 ms (240 of 240). A stage that did not execute is recorded as `null`, never as 0.

**Limits of this evidence.** Synthetic, clean, single-font-family renders; one seed; one timed pass; no handwriting, no real photographs, no rotated or perspective text beyond ±2.5°. A near-zero CER here says the pipeline is wired correctly and the strata are separable, not that scene-text accuracy on a phone gallery will be as good.

**Still NOT MEASURED:** real-photo accuracy; int8 detector + recognizer sizes (fp32 sizes above: PP-OCR 12.9 MB, docTR 24.4 MB); prefilter skip rate, bytes read and recall; warm/cold RSS; any S24+ number; handwriting.

### Environment metadata

Captured 2026-11-21 UTC and stored with hashes, package versions and commands in `docs/visual-search/results/ocr0a/run_manifest.json`. Reproduce with `tools/ocr_bench/README.md`.

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

1. **OCR-0A (done, [#873](https://github.com/LPStruiksma/atlas-gallery/issues/873)):** exact pinned artifacts, 240-image synthetic corpus, real ONNX results for both candidates. Open: a real-photo, rights-cleared corpus and the artifact licence decision.
2. **OCR-0B (research, ≤2 days):** quantize reproducibly, benchmark warm/cold per-stage host latency and RSS, then measure the prefilter at a predeclared recall threshold. Produce the assumption-based S24+ range only after host data exists.
3. **OCR-1 (only after GO):** package one model/dictionary as a split APK asset and prove offline initialization, notices and size budget. No scanner or UI.
4. **OCR-2 (separate unit):** charging-only, resumable scanner and transactional versioned store with deletion/revocation tests.
5. **OCR-3 / OCR-4 (separate design-gated units):** Search facet integration, then viewer select/copy with word-box overlays and accessibility review.

Handwriting remains a stretch result and cannot rescue poor printed-text performance. Each follow-up may terminate independently without admitting app code in this feasibility unit.

## OCR-0B execution result (2026-11-21): REJECT / NOT MEASURED

The exact-head OCR-0B attempt could not acquire its benchmark-only Python dependencies: the pip
proxy returned `Tunnel connection failed: 403 Forbidden`, followed by `Could not find a version
that satisfies the requirement onnx==1.20.1 (from versions: none)`. Consequently all four separate
PP-OCR/docTR detector/recognizer int8 artifacts (hashes, bytes, graph loading and inference
validation), cold/warm decode/detector/recognizer/total CPU median/p95 and RSS are **NOT MEASURED**.
Scorer runtime was not substituted for inference timing. The live-main agent instructions were also
unavailable from this host (`curl: (22) The requested URL returned error: 403`); the repo-local plan
was followed and the failure is preserved in the OCR-0B run manifest.

The dependency failure also prevented regenerating OCR-0A pixels, so the development-only Sobel
threshold and independent locked-test EN/NL recall, false skips, no-text pass/skip, bytes and latency
are **NOT MEASURED**. The checked-in experiment selects the largest 64x64 grayscale Sobel threshold
meeting 99% recall using development positives only and emits the required test slices when the
pinned environment is available. It never equates an empty OCR prediction with a prefilter skip.

A charging-only 150,000-photo range is **NOT MEASURED** because measured stage costs, admission
fraction and RSS do not exist. When available, the script calculates 0.5x/1x/2x host-to-S24+ stage
scaling and a separate 25% I/O/thermal allowance; these are explicit assumptions, not phone,
energy or no-throttling measurements. Peak RAM is measured runtime RSS delta plus a decoded-image
buffer bound.

**Verdict: REJECT for compute feasibility in this environment, conditional on rerunning the exact
commands where pinned dependencies, models and generated corpus are available.** Packaging remains
an unconditional **NO-GO**: every detector, recognizer and dictionary right is **NOT CLEARED**. No
weights, dictionaries, binaries, generated pixels, owner photos, app or Gradle changes are included.
