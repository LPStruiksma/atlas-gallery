# CAP-0 — on-device photo-caption feasibility

Unit: [atlas-gallery#666](https://github.com/LPStruiksma/atlas-gallery/issues/666) · Evidence cut-off: 2026-11-20 UTC

## Decision

**Not ready to plan or ship.** No model was downloaded, converted, executed, or measured; no phone was available. All latency, memory, quality, Dutch, and hallucination results are therefore **NOT MEASURED**. CAP-0 supports only a bounded CAP-1 experiment, not a capability-map change.

Two tiers should be tested, not adopted:

1. **Fast whole-library:** SmolVLM2 256M and 500M, bench-only until the dependency/data licence chain and Android conversion are approved. The publisher labels checkpoints Apache-2.0 and exposes ONNX files, but those large artefacts were not downloaded or executed.
2. **Optional on-demand rich:** Florence-2-base first, with large as comparator; bench-only. Do not bundle PaliGemma 2, Gemma 3n, small Qwen VL, or Moondream without the legal/runtime gates below.

This is not a licence approval. “Publisher says” records provenance, not ATLAS admissibility.

## IDS-1-style admissibility register

Code, weights, conversion, processor and evaluation data are independent artefacts; a green cell cannot cure an unknown one. URLs are upstream publisher/project pages accessed 2026-11-20. `UNVERIFIED` means exact artefact text or executable closure was not locally retained and reviewed.

| Candidate | Code / processor | Weights / checkpoint terms | Conversion / runtime | Data licensing | Disposition |
|---|---|---|---|---|---|
| Florence-2 base / large | Cards require Transformers `trust_remote_code`; code/dependency closure **UNVERIFIED** | Publisher repos label MIT; base has revision-pinned licence. Exact large revision/hash **UNVERIFIED** | No official pinned mobile CPU-int8 bundle validated: **UNVERIFIED / NOT MEASURED** | Training corpus rights not audited; no eval set used | **bench only**, base first |
| SmolVLM / SmolVLM2 256M / 500M | Transformers/Idefics3; dependency closure **UNVERIFIED** | SmolVLM2 card says checkpoints Apache-2.0. Exact 500M/original SmolVLM revisions/hashes **UNVERIFIED** | 256M repo exposes ONNX; provenance, quantization, processor parity, Android ops untested | Card lists a multi-dataset mixture; every source image/text grant remains **UNVERIFIED** | **bench only**, best fast-tier lead |
| Moondream 2 / 0.5B | Page uses custom code; code-to-weight closure **UNVERIFIED** | Moondream 2 page labels Apache-2.0. An immutable official 0.5B checkpoint/terms were not established: **UNVERIFIED** | No publisher Android CPU-int8 pipeline validated | Training/eval grants **UNVERIFIED** | **do not use** until closed |
| Qwen2.5-VL-3B-Instruct | Implementation/dependencies **UNVERIFIED** | Pinned repo licence is Qwen Research License: non-commercial absent commercial licence | Conversion, mobile ops and processor parity **UNVERIFIED / NOT MEASURED** | **UNVERIFIED** | **reject for product**; research only after counsel |
| PaliGemma 2 3B | Dependency closure **UNVERIFIED** | Gated Gemma terms; obligations/restrictions not legally reviewed | BF16 published; no approved Android int8 conversion | Card names WebLI, CC3M derivatives, OpenImages and WIT; underlying rights not audited | **do not use** pending legal/runtime gates |
| Gemma 3n E2B | Dependency closure **UNVERIFIED** | Gated Gemma terms; accepted text/redistribution plan absent | Advertised low-resource, but ATLAS Android/ONNX path untested | 140+ spoken languages is not Dutch-caption proof; data rights **UNVERIFIED** | **on-demand research only** |

Primary upstream evidence:

- Florence-2 [base card](https://huggingface.co/microsoft/Florence-2-base), [pinned MIT licence](https://huggingface.co/microsoft/Florence-2-base/blob/579b82bf58da309400d35772326fbcb4043b811f/LICENSE), and [large card](https://huggingface.co/microsoft/Florence-2-large).
- SmolVLM2 [256M](https://huggingface.co/HuggingFaceTB/SmolVLM2-256M-Video-Instruct), [500M](https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct), and [paper](https://arxiv.org/abs/2504.05299). The 256M card calls the language English and checkpoint Apache-2.0; neither proves Dutch quality or dataset admissibility.
- Moondream [2 repository](https://huggingface.co/vikhyatk/moondream2) and [0.5B documentation](https://docs.moondream.ai/). The latter did not establish an immutable checkpoint/licence here.
- Qwen [pinned 2.5-VL-3B licence](https://huggingface.co/Qwen/Qwen2.5-VL-3B-Instruct/blob/acbce9a6c80e81356680a632166380c8cc1d27ec/LICENSE) and [card](https://huggingface.co/Qwen/Qwen2.5-VL-3B-Instruct).
- Google [PaliGemma 2 card](https://huggingface.co/google/paligemma2-3b-pt-224), [Gemma terms](https://ai.google.dev/gemma/terms), and [Gemma 3n E2B-it card](https://huggingface.co/google/gemma-3n-E2B-it).
- ONNX Runtime [quantization](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html) and [mobile guidance](https://onnxruntime.ai/docs/tutorials/mobile/).

### Dataset licensing

No public photo dataset is admitted. Dataset-level licences may not grant rights in every underlying photo/caption. CAP-1 needs an immutable asset register with source/revision, licence-text hash, copyright/consent basis, annotation terms, allowed use/redistribution and approval owner for every sample.

`protocol.py make-fixtures` creates only geometric PPMs and factual manifests; no downloaded/owner photo. It tests plumbing, **not quality**. Quality is blocked until Legal approves public data or ATLAS commissions consented images kept outside Git.

## Safe reproducible protocol

The offline, model-agnostic harness never downloads a model or sends images over a network. A trusted operator supplies an immutable local bundle and adapter. The adapter receives a local path/prompt and emits one JSON object.

1. Record hardware/OS/CPU, Python/ORT; model repo/revision, file and licence SHA-256; conversion command/container digest, opset, quantization and calibration provenance.
2. Generate synthetic fixtures. `check-manifest` rejects symlinks, missing/mismatched hashes, duplicates, non-absolute paths and absent rights metadata.
3. Convert in isolation. Compare source and int8 on approved parity data before timing. Static int8 needs licensed calibration data. Never silently partition to remote inference.
4. Run the local adapter with network externally disabled and deterministic decoding. The included harness creates a **new process per inference**, so its results are **cold process-launch** timings only. The optional discarded trial launches do not warm later model state. For warm-throughput measurement CAP-1 must use a separate persistent, resident adapter. Record attempt/success/failure counts and success-only cold p50/p95; a run with zero valid captions fails closed.
5. Retain only results permitted by data terms; no private bytes/EXIF. The harness deliberately reports peak memory **NOT MEASURED** rather than misusing the cumulative child-process high-water mark. Measure per-process host RSS and Android PSS with validated external tools in CAP-1; keep cold/warm and host/phone results separately.

Canonical commands are in `tools/caption_bench/README.md`. They are protocol, not proof a VLM ran.

### Quality, Dutch and hallucination

Pre-approve and freeze ≥100 licensed images stratified across people/no-people, indoor/outdoor, low light, occlusion, text, food, animals and negatives. Two Dutch-fluent raters independently score main entity/action/setting (0–2 each), Dutch requested/grammatical/idiomatic (0–2 each), and flag invented identity/relationship, object, count, action, location, OCR, sensitive attribute and unsupported certainty. Also flag unsupported names/sensitive inference.

Resolve disagreements blind to model; report raw counts, Wilson 95% intervals and inter-rater agreement. Model output is not ground truth. CIDEr requires licensed references; SigLIP agreement is secondary and misses hallucinations. Publish examples only when redistribution permits. CAP-0 has **zero examples/scores: NOT MEASURED**.

## Device-free S24+ estimate bands

No numeric S24+ runtime/RAM estimate is defensible without a validated graph and measured reference. Conditional bands:

| Band | Assumption |
|---|---|
| optimistic | entire int8 graph on one accelerator, no fallback, resident weights |
| central | mixed accelerator/CPU with bounded copies, thermal steady state |
| pessimistic | CPU fallback/token loop, cold load, memory pressure, throttling; possibly OOM |

For Exynos 2400 and Snapdragon 8 Gen 3, latency, RAM, energy and throughput are **UNVERIFIED / NOT MEASURED**. CAP-1 must measure both physical variants at controlled temperature/battery. Do not convert chipset TOPS or desktop latency into phone seconds. Capture p50/p95, cold/warm load, first/per-token time, peak PSS/RSS, joules/image, throttling and delegate trace.

## Hypothetical 150,000-image sensitivity

Let `N=150000`, measured throughput `r` images/hour charging, retry multiplier `q≥1`, caption bytes `c`, row/index overhead `m`, model bytes `W`:

- initial hours = `N*q/r`; storage = `W + N*(c+m)`;
- per-new-image hours = `q/r`; daily charging hours for `d` arrivals = `d*q/r`.

Publish low/central/high grids using **measured** CAP-1 `r,q,c,m,W`, not guesses. No second visual index: CAP-2 must join derived text to existing photo identity and version/model/prompt hash with dedupe/invalidation. Work must be opt-in, charging-only by default, resumable, thermal-aware and deletable.

## Runtime limits and gates

A generative VLM includes vision preprocessing, encoder/projector, tokenizer, KV-cache decoder, sampling and postprocessing. An ONNX badge does not prove parity/Android support. Dynamic shapes, int64, custom ops/code and KV growth can cause fallback or infeasibility; weight int8 does not imply activation int8/delegation.

Downloads require explicit consent, immutable revision/SHA-256, integrity/resume, notices, disk check and deletion. Captions are fallible derived personal data: local-only, opt-in, deletable, no identity/sensitive inference, non-AI fallback.

- **CAP-1 scanner/runtime:** approved artefact/data register; pinned conversion; host parity/int8; physical Exynos/Snapdragon runs; Dutch/hallucination review; thermal/energy/storage and local-only proof.
- **CAP-2 search:** thresholds; existing-index schema; versioning, dedupe/invalidation, deletion, ranking/false-positive evaluation.
- **CAP-3 Details/TalkBack:** generated label; edit/remove/report; uncertainty/accessibility wording; latency fallback; privacy/consent.

Only after all gates and independent legal/security/privacy review may Research move to Planned.

## Completion and blockers

- [x] Compared every requested family; separated code, weights, runtime and data.
- [x] Added offline validation, synthetic smoke fixtures, orchestrator and capacity calculator.
- [ ] Weights/hashes, conversion, host int8 — **NOT DONE / NOT MEASURED**.
- [ ] Approved quality set, examples, Dutch/hallucinations — **BLOCKED / NOT MEASURED**.
- [ ] Physical S24+ latency/RAM/energy — **NOT MEASURED**; no device.
- [ ] Capability map — unchanged; evidence does not support Planned.

Historical provenance: the **detached Codex task checkout** (before the report was integrated into this PR) had no remote/main ref, so its attempted `git show origin/main:{AGENTS.md,docs/agents/codex-wake.md,roles/dev.md}` returned `fatal: invalid object name 'origin/main'`. The later connector-written PR branch merged live main; this historical error does **not** describe the integrated PR. Model execution was intentionally not attempted without approved artefacts/data, conversion, or devices.
