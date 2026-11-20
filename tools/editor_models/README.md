# EDIT-11 — AI model feasibility bench (issue #729)

This directory is a **task scaffold** for the Codex feasibility work. It contains no model weights or photos.

## Evidence contract

- Enumerate 2–3 open-model candidates per task: ×2/×4 upscaling, face restoration, monocular depth for portrait blur, shadow removal, reflection removal, and prompt/tap subject segmentation.
- Cite **original hosts** and individual licence terms; document original artifact SHA-256 obtained from the real downloaded bytes. Never invent checksums or redistribute non-redistributable weights.
- Describe reproducible export and operator coverage for ONNX Runtime **1.23.2 CPU/XNNPACK**; distinguish a proposed export path from a successful export.
- Benchmark on **licensed/synthetic** media only. For 12 MP tiled inference, report host CPU model, process/RAM, threads, input tile shape, overlap/stitching, cold/warm runs, aggregation, exported graph size, and quality metric/ground truth; explicitly say **host-only** (not S24+).
- Missing model binaries, licences, reference datasets, or runtimes are **not measured**, not zeros, guesses, or a fabricated ranking. Include a falsifiable collection recipe and limits before making a provisional recommendation.
- Keep all files within `tools/editor_models/**`, `docs/editor/AI_MODELS.md`, and `docs/identity-studio/LICENCES.md` only if the model is shared with Identity Studio.

Related: [M5 goal](https://github.com/LPStruiksma/atlas-gallery/issues/629), [12 MP tiling](https://github.com/LPStruiksma/atlas-gallery/issues/244), [shared cut-out](https://github.com/LPStruiksma/atlas-gallery/issues/519).
