# LLM-0 feasibility benchmark — task scaffold

This directory is a **host-only, synthetic-data** benchmark scaffold for [issue #671](https://github.com/LPStruiksma/atlas-gallery/issues/671). It is **not** an Android or on-device model integration. The implementation and results are pending a Codex evidence task on this draft PR; this initial file provides no performance or accuracy measurements.

## Contract for the evidence task

- Use a fixed, reviewed English/Dutch set of natural-language photo-library queries and explicit expected structured constraints. Include names, locations, seasonal/relative dates, inclusions, exclusions, ambiguity and out-of-scope questions; do not use real names or personal library content.
- Define a machine-readable schema for parsed filters (people, date range, place, tags, exclusions and unresolved/unsupported), deterministic exact-match or per-field scoring and a reproducible host command. Separate baseline/heuristic and any real model outputs. Never label hard-coded expectations or mock outputs as model accuracy.
- Document checkpoint and licence primary-source URLs, runtime constraints, RAM/KV-cache assumptions, quantisation, estimated throughput (not measured S24+ tokens/s), cold start, privacy and offline requirements. No model download or network call at Android runtime.
- Write `docs/visual-search/LLM_0_FEASIBILITY.md` with query understanding, Story titles and metadata Q&A go/no-go and explicit prerequisites (TAGS-0, CAP-0). Add the 'Local language model' row in `docs/product/ATLAS_VISION.md`.
- Do not add an Android dependency, model weights, APK asset, hidden evaluation files, private data or unsupported performance claims. Any recommendation remains research until measured on S24+ and independently reviewed.
