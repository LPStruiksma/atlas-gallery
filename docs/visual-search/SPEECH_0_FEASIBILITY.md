# SPEECH-0: local spoken-word search feasibility (research seed)

Issue: [atlas-gallery#669](https://github.com/LPStruiksma/atlas-gallery/issues/669) · Goals: [Vision D #101](https://github.com/LPStruiksma/atlas-gallery/issues/101) and [search everywhere #175](https://github.com/LPStruiksma/atlas-gallery/issues/175).

**Status: RESEARCH IN PROGRESS — no model, licence, WER, latency, battery or S24+ result is yet validated by this seed.** This is a bounded specification for a reproducible host-only Python benchmark. A later Codex patch must replace this disclaimer with linked evidence or clearly state why a measurement could not be obtained. No app code, Gradle dependency, cloud speech API, translated subtitle content or private/owner video is in scope.

## Question and decision

Can ATLAS, without INTERNET permission and without a new Android inference dependency, use local transcription to index *spoken words in videos* in English and Dutch, provide word/segment timestamps for search results and captions, and do so only during battery-safe background work?

A usable SPEECH-0 decision requires all of the following, not merely an available Hugging Face model: verified redistribution and deployment rights for **each exact code, checkpoint, tokenizer, VAD model and dataset revision**; Dutch **and** English recognition; reproducible WER and host real-time factor (RTF); an honest, uncertainty-bounded S24+ *estimate* (not a fabricated device measurement); and a specific ONNX Runtime compatibility assessment against the version already shipped in ATLAS.

## Candidate inventory (verify, do not assume)

| Candidate | Test | Checks before qualifying |
|---|---|---|
| OpenAI Whisper **multilingual** tiny/base/small | Primary accuracy–speed frontier | Verify checkpoint-specific licence, download revision/hash, output languages, audio/frontend, encoder/decoder export and tokenizer redistribution; do not substitute `*.en` checkpoints for Dutch |
| Distilled Whisper variants | Optional challenger | Many public distilled checkpoints are English-only: reject Dutch claims until a specified revision demonstrates Dutch support; verify the parent, derived model and tokenizer licences separately |
| Moonshine | Optional challenger | Verify *specific checkpoint* language support, weights licence, ONNX export and segment timestamp method; English-only models cannot meet the bilingual requirement |
| Silero VAD (optional pre-pass) | Silence skipping | Verify code and model licences and exact revision; test false-negative speech trimming, including Dutch, rather than reporting speed only |

Use official upstream model cards/repos and licence files at pinned commits or revisions. Record source URL, revision, SHA-256, actual licence text/scope, commercial distribution status, and whether the licence covers **weights, code and tokenizer**. An unknown field is `UNVERIFIED`, never inferred from a repository-wide code licence. Do not commit downloaded weights, tokenizers, datasets, audio or transcript text. If a suitable bilingual ONNX artifact cannot legally be fetched or run, report a reproducible blocker and a *not measured* row instead of substituting an unvalidated pipeline.

## Licensed evaluation protocol

1. Select a **versioned**, independently licensed English and Dutch speech corpus, such as Common Voice or FLEURS **after** reviewing its current terms, access restrictions, redistribution rules and speaker split. Record dataset URL, release/config, licence evidence, revision and sample IDs only. No owner media, speaker names, URLs containing authentication, raw clips or individual transcript text in Git.
2. Hold out an evaluation subset **before** any normaliser, model, VAD threshold or decoding parameters are tuned. Select a reproducible seed and deterministic sample IDs stratified by language and duration (at least short and long utterances); record sample counts, total hours, split, exclusions, accents/domain limitations, sampling mechanism and unavailable subsets. Never evaluate on a model's known training split without disclosing leakage risk.
3. Decode locally to 16 kHz mono, checking clipped, silent, malformed and missing files. Preserve originals only in a local ignored cache. Include audio resampling/decode in a separate end-to-end timing.
4. Compute **WER separately for English and Dutch** with `(S+D+I)/N` under one pinned, documented, language-neutral normalisation (Unicode NFC, Unicode case-fold, strip punctuation consistently, collapse whitespace). Keep diacritics and lexical contractions unless the normaliser explicitly specifies and justifies changes. Report reference word count, S/D/I counts, macro WER by clip and corpus-micro WER, both raw and normalised if feasible. Empty-reference clips are excluded from WER but counted and reported; a hallucinated nonempty transcription for silence is a separate error.
5. VAD is measured both disabled and enabled on a common fixed subset. Account for skipped silent duration, false-trimmed reference speech, added decode/chunk overlap cost, word/segment boundary errors and end-to-end accuracy. Do not award VAD a gain merely for dropping difficult spoken content.
6. Keep all output at the aggregate level. A `tools/speech_bench/` CLI and README should generate a machine-readable report with model/dataset hashes and parameter values. Include reproducibility commands and a small synthetic fixture so a reviewer can exercise the scoring formula offline without a downloaded corpus.

**Measurement matrix** (populate with observed numbers and provenance; `not measured` until run):

| Model revision | EN clips / hours | NL clips / hours | EN WER | NL WER | Host RTF | Peak RSS MB | Status |
|---|---:|---:|---:|---:|---:|---:|---|
| Whisper multilingual tiny | — | — | — | — | — | — | not measured |
| Whisper multilingual base | — | — | — | — | — | — | not measured |
| Whisper multilingual small | — | — | — | — | — | — | — | not measured |
| Distilled multilingual candidate (if verified) | — | — | — | — | — | — | — | not measured |
| Moonshine bilingual candidate (if verified) | — | — | — | — | — | — | — | not measured |

## Timing and S24+ extrapolation

RTF is **wall-clock seconds / decoded audio seconds** (RTF below 1 is faster than real time). Record host CPU architecture, vCPU count, RAM, OS, Python/ONNX Runtime versions, provider, threads, decoder/beam parameters, quantisation, model SHA and batch size. Measure cold model load separately from warm inference; for each sample report aggregate median and p95 wall time and RTF over at least 3 warm repetitions, plus preprocess, VAD and postprocess phases. Do not compare batch processing against streaming without disclosure. Measure model disk bytes, peak RSS and any temporary buffers. Label any CUDA/GPU results separately; a host CUDA number is not a phone CPU result.

A Snapdragon S24+ estimate must disclose exact regional SoC uncertainty (Snapdragon 8 Gen 3 versus Exynos 2400 variants), whether ATLAS can access NNAPI/XNNPACK or only CPU in the **shipped** ONNX Runtime package, RAM/thermal throttling, decoder overhead and a defensible slowdown factor *range*, with citations or calibration to existing ATLAS ONNX benchmarks. Compute low/nominal/high RTF scenarios, **never** label any extrapolation a measured S24+ throughput. Without suitable comparable benchmarks give `device estimate not defensible` and specify an owner-safe on-device validation gate for the next unit.

For a library containing **N video hours**, total ASR CPU hours = `N × RTF`; 1-hour charging windows needed = `ceil(N × RTF / 1 hour)` (plus decode/scan/VAD overhead and scheduling fragmentation). Show 1, 10 and 100 hours as illustrative inputs; identify the fraction actually spoken if VAD is used, and do not assert battery mAh or percentage without device power measurement. Estimate transcript storage as segments × average UTF-8 bytes plus timestamps/index metadata; report assumptions. Charging-only, idle, thermal-limit and permission-aware incremental indexing is the safe default, not a claim that the S24+ has been tested.

## ONNX Runtime: a necessary proof, not an assumption

Inspect the version and Android execution providers in `app/build.gradle.kts` and the shipped code. Verify each candidate's **encoder and autoregressive decoder** can be expressed in supported ONNX ops and opsets, along with KV-cache handling, decoder loop orchestration, tokenizer/normaliser, log-mel frontend, language detection, VAD integration and timestamp alignment. Run session construction and a known synthetic/appropriately licensed clip through the export on the **same ORT API/opset family** as the app. An ONNX graph that merely loads on host ORT does not prove compatibility with the APK. Disclose models requiring ONNX Runtime Extensions, a custom op, a bundled tokenizer runtime or a new AAR, as **not sufficient without dependency change**. Give a yes/no/conditional table with evidence for each component and distinguish `uses existing ORT` from `works on S24+`.

## Proposed downstream units (not built here)

**SPEECH-1 — scanner/indexing, no UI:** Feature-gated and off by default until consent/performance guardrails are validated. Process only locally accessible video audio, with permission checks, charging/idle constraints, bounded work chunks, thermal/battery pause, durable resume, dedup/version fences and per-video invalidation. Store transcript segments with `mediaId`, `startMs`, `endMs`, `language`, `text`, `modelVersion`, and privacy-compatible deletion. Exclude silent videos, DRM, inaccessible media and failed decodes gracefully. Preserve personal speech exclusively on-device, including diagnostics.

**SPEECH-2 — search + viewer subtitles, UI:** Requires its own Design brief and independent design preflight before any UI implementation. Search transcript words/phrases alongside existing visual search with language-aware tokenisation, jump to timestamp, captions toggle, synchronization and screen-reader/TalkBack semantics. Explicitly distinguish generated from authored subtitles; allow disabling and deletion. No speech-to-text cloud endpoint or automatic translation.

**Docs impact:** On acceptance, update the capability map in `docs/product/ATLAS_VISION.md` with a **“Spoken words in videos”** row that distinguishes feasibility from implementation, links SPEECH-0 evidence and the follow-up units. SPEECH-0 must not claim the feature is shipped.

## Evidence register

- Issue/constraints: [#669](https://github.com/LPStruiksma/atlas-gallery/issues/669).
- Existing on-device search pipeline: [VS-S0](VS_S0_MODEL_SPIKE.md), [NLS-0](NLS_0_TEXT_FEASIBILITY.md).
- Upstream model, dataset and licence references: **Codex to pin exact URLs and immutable revisions**.
- Benchmark outputs, raw aggregate result JSON, commands, env and hashes: **Codex to create or report blockers**.
- Exact-head CI result: **pending**.
