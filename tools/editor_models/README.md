# EDIT-11 — AI model feasibility bench (issue #729)

This directory is a **task scaffold** for the Codex feasibility work. It contains no model weights or photos.

## Reproduce the scaffold checks

The checked-in candidate catalogue deliberately contains no downloaded artifact hashes or
performance numbers. Validate it with only the Python standard library:

```sh
python3 -m unittest discover -s tools/editor_models/tests -p 'test_*.py' -v
python3 tools/editor_models/benchmark.py validate
python3 tools/editor_models/benchmark.py report
```

After obtaining a model from the candidate's original URL, record evidence without copying
the model into this repository:

```sh
python3 tools/editor_models/benchmark.py artifact --candidate realesrgan_x4plus --model /private/models/RealESRGAN_x4plus.onnx
python3 tools/editor_models/benchmark.py probe --candidate realesrgan_x4plus --model /private/models/RealESRGAN_x4plus.onnx
```

`artifact` prints the bytes and SHA-256 actually read. `probe` requires the separately
installed `onnxruntime==1.23.2`, creates a CPU session, lists the execution providers in that
wheel, and fails rather than implying that XNNPACK was tested when `XnnpackExecutionProvider`
is absent. It does not run task-specific preprocessing or claim Android/S24+ compatibility.

Generate the deterministic, synthetic 4000 x 3000 RGB fixture and its tile plan with:

```sh
python3 tools/editor_models/benchmark.py fixture --output /tmp/editor-models-12mp.ppm
python3 tools/editor_models/benchmark.py tiles --width 4000 --height 3000 --tile 512 --overlap 32
```

The fixture and tiler are plumbing inputs, not a quality dataset and not a model latency
result. A follow-up adapter must define preprocessing, outputs, stitching, warm-up and metrics
before recording latency or quality.

## Evidence contract

- Enumerate 2–3 open-model candidates per task: ×2/×4 upscaling, face restoration, monocular depth for portrait blur, shadow removal, reflection removal, and prompt/tap subject segmentation.
- Cite **original hosts** and individual licence terms; document original artifact SHA-256 obtained from the real downloaded bytes. Never invent checksums or redistribute non-redistributable weights.
- Describe reproducible export and operator coverage for ONNX Runtime **1.23.2 CPU/XNNPACK**; distinguish a proposed export path from a successful export.
- Benchmark on **licensed/synthetic** media only. For 12 MP tiled inference, report host CPU model, process/RAM, threads, input tile shape, overlap/stitching, cold/warm runs, aggregation, exported graph size, and quality metric/ground truth; explicitly say **host-only** (not S24+).
- Missing model binaries, licences, reference datasets, or runtimes are **not measured**, not zeros, guesses, or a fabricated ranking. Include a falsifiable collection recipe and limits before making a provisional recommendation.
- Keep all files within `tools/editor_models/**`, `docs/editor/AI_MODELS.md`, and `docs/identity-studio/LICENCES.md` only if the model is shared with Identity Studio.

Related: [M5 goal](https://github.com/LPStruiksma/atlas-gallery/issues/629), [12 MP tiling](https://github.com/LPStruiksma/atlas-gallery/issues/244), [shared cut-out](https://github.com/LPStruiksma/atlas-gallery/issues/519).
