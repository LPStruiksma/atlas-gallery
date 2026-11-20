# OCR-0: on-device text-in-photo feasibility (research in progress)

Unit [#575](https://github.com/LPStruiksma/atlas-gallery/issues/575), under [Vision D #101](https://github.com/LPStruiksma/atlas-gallery/issues/101) and search [#175](https://github.com/LPStruiksma/atlas-gallery/issues/175). This is a **research scaffold**, not a feasibility verdict or release approval. On this seed commit **no OCR benchmark has been executed and no weights, dictionaries or runtime assets have been admitted**.

## Research contract

- Local on-device **English and Dutch** OCR for screenshots, receipts, signs and text in photos, with handwriting as a separately labelled stretch. The existing Android ONNX Runtime is a candidate execution path, not proof that any exported graph is compatible.
- Compare at least one **PP-OCR mobile detector + recognizer ONNX** option with another properly identified option. Verify **checkpoint/weight, dictionary and code licences separately** from primary sources, including commercial redistribution and any notice/attribution obligations. Distinguish measured int8 bytes from upstream marketing claims and unverified estimates.
- Measure character error rate (CER), word error rate (WER) and false positives on synthetic rendered EN/NL text and licensed inputs, with an explicit no-text negative set. Retain generated fixtures and their ground truth or deterministic generation scripts under `tools/`; do not commit weights, downloaded image pixels or personal photos. Count normalized Unicode (including Dutch diacritics), punctuation policy, whitespace, and detection misses explicitly; separate machine results from expectations.
- Report host CPU hardware, thread count, ONNX Runtime/version, per-stage detection/recognition latency, image resolution, and a *clearly labelled hypothetical* S24+ scaling range. Model charging-only 150k-photo scan time, memory, int8 model bytes, and a measured or clearly unverified cheap no-text pre-filter with recall/false-skips.
- Sketch per-media-key text and word boxes in the existing index store, model/version fence, incremental updates, deletion and revocation behavior, and join to faceted search and [scene search #94](https://github.com/LPStruiksma/atlas-gallery/issues/94). Do not propose cloud OCR.
- Finish with an evidence-calibrated **GO / CONDITIONAL / NO-GO** and concrete bounded follow-up units: model packaged as split APK asset (cf. NLS-1A), charging-only scanner stage, search facet, and viewer select/copy (with Design preflight).

## Evidence standard

Follow [NLS-0](NLS_0_TEXT_FEASIBILITY.md) for provenance and measurement style. Every numerical result needs a reproducible command, dataset/sample count, host metadata and a saved result. **If checkpoint downloads, licence verification, ONNX export or execution are inaccessible, say exactly which check is blocked and mark unmeasured cells as NOT MEASURED. Never invent results or licence permissions.** A script that computes CER on hard-coded strings is a metric unit test, not OCR model accuracy.

## Boundaries

No app code, Gradle file, new dependency, model weight, automatic image upload, personal pictures, translation, production scan or UI in this unit. Update the "Text in photos" capability-map row in `docs/product/ATLAS_VISION.md` alongside the eventual report. This scaffold alone is not complete.
