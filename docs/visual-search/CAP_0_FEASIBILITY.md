# CAP-0 — On-device photo captions feasibility (research in progress)

Unit: [atlas-gallery#666](https://github.com/LPStruiksma/atlas-gallery/issues/666) · Goal: [Vision D #101](https://github.com/LPStruiksma/atlas-gallery/issues/101)

**Status: INCOMPLETE — task scaffold, not evidence.** This file is the bounded input to one Codex Cloud (Legacy) task on [the draft PR](https://github.com/LPStruiksma/atlas-gallery/pulls). Replace it with an evidence-backed feasibility report. Do not confuse proposed numbers with measured results.

## Research boundary

- The ATLAS SigLIP 2 image encoder does not produce prose; captioning requires a generative vision–language model and a validated inference path.
- Investigate the model/weight/processor licence chain separately for Florence-2 base and large, SmolVLM / SmolVLM2 256M and 500M, Moondream 2 and 0.5B, Qwen3-VL 2B (or the current small Qwen VL), and PaliGemma 2 / Gemma 3n. Mark unavailable, ambiguous or contradictory terms **unverified**. Do not infer the licence of weights from a code repo's licence.
- For each model distinguish `bundle`, `download after explicit install consent with SHA-256`, `bench only`, or `do not use`; check terms against commercial versus private use and publication constraints. Follow the field definitions in `docs/identity-studio/LICENCES.md`.
- Use only a confirmed-licensed, public test set, or a small synthetic fixture; commit identifiers or scripts, not original images, weights, private photos, credentials, output with sensitive details, or large model downloads. Verify each dataset's distribution, image and caption licences.
- Run **actual** CPU ONNX Runtime int8 host measurements only if the exact model conversion, processor, weights and dataset are accessible. Record commands, environment, model revision and SHA-256, provenance, durations, warm-up, n, p50/p95 latency, peak RAM, confidence/limitations. A Python data analysis script is not proof of an executable ONNX image-caption pipeline. Never fabricate benchmark data, images, licences, downloads, Dutch competence or S24+ numbers.
- Estimate S24+ Exynos 2400 versus Snapdragon 8 Gen 3 only through an explicit model with documented range and assumptions; **host** measurements and **unmeasured phone** estimates must occupy separate columns. Report cases where an ONNX export is infeasible or requires a different runtime.
- Include readable caption examples and a score (CIDEr or SigLIP image–caption agreement), hard-case hallucinations (counts, people, text) and Dutch output where supported; if impossible to run, mark each cell `NOT MEASURED` and include the exact blocking prerequisite and feasible protocol.
- Include 150,000-image hours-at-charging, RAM, storage and per-new-image formulas with sensitivity bands; keep hypothetical illustrative assumptions visibly separate from measurements.
- Decide whether fast whole-library captioner + optional on-demand rich captioner is justified; name CAP-1 scanner/runtime, CAP-2 search, CAP-3 Details/TalkBack Design brief (following BRAND-0 #628 and Lapis #654). Note privacy, charging, battery and incremental-index dedupe requirements. Update `docs/product/ATLAS_VISION.md` capability-map row from Research to Planned **only if the report supports it**.

## Output and validation contract

Produce `docs/visual-search/CAP_0_FEASIBILITY.md`, reproducible non-destructive files under `tools/caption_bench/**`, and the capability-map row where warranted; no app code, Gradle, workflows, second index or remote inference. Separate measured evidence, estimated values and unknowns. Include exact upstream URLs and access dates for every licence and model fact. Tests, if added, use synthetic/licensed fixtures only. End with an explicit done/not-done checklist and outstanding blockers.
