# M5 editor AI model feasibility (EDIT-11)

**Evidence date:** 2026-11-20. **Result:** catalogue and reproducibility scaffold only; every
model result is **not measured**. No weight was downloaded, converted, or executed in this
unit. Consequently there is no verified artifact SHA-256, exported size, operator result,
12 MP latency, or quality score to report. This is not S24+ evidence and is not approval to
ship any model.

The machine-readable source of truth is
[`tools/editor_models/candidates.json`](../../tools/editor_models/candidates.json). It has
2–3 candidates for each requested capability and preserves `null` rather than invented
hashes. URLs point to the upstream project or its stated artifact host; a repository licence
does not automatically license a checkpoint or its training data. Licence wording below is
a triage note, not legal clearance, and must be re-read at the pinned revision before a
download/build unit.

## Candidate and evidence matrix

| Capability | Candidate (original source) | Licence triage | Artifact SHA-256 / ONNX 1.23.2 CPU + XNNPACK / exported bytes | Host 12 MP tiled latency / quality |
|---|---|---|---|---|
| Upscale ×2/×4 | [Real-ESRGAN x4plus](https://github.com/xinntao/Real-ESRGAN) | BSD-3-Clause repo; checkpoint grant not separately verified | **not measured** | **not measured** |
| Upscale ×2/×4 | [SwinIR x2/x4](https://github.com/JingyunLiang/SwinIR) | Apache-2.0 repo; checkpoint grant not separately verified | **not measured** | **not measured** |
| Face restoration | [GFPGAN v1.4](https://github.com/TencentARC/GFPGAN) | Apache-2.0 with third-party exclusions; not cleared | **not measured** | **not measured** |
| Face restoration | [CodeFormer](https://github.com/sczhou/CodeFormer) | S-Lab 1.0; non-commercial conditions | **not measured** | **not measured** |
| Monocular depth | [Depth Anything V2 Small](https://github.com/DepthAnything/Depth-Anything-V2) | Small is Apache-2.0; do not substitute NC Giant | **not measured** | **not measured** |
| Monocular depth | [MiDaS 3.1](https://github.com/isl-org/MiDaS) | MIT repo; backbone/checkpoint terms unresolved | **not measured** | **not measured** |
| Shadow removal | [ShadowFormer](https://github.com/GuoLanqing/ShadowFormer) | licence/checkpoint grant unverified | **not measured** | **not measured** |
| Shadow removal | [DC-ShadowNet](https://github.com/jinyeying/DC-ShadowNet-Hard-and-Soft-Shadow-Removal) | licence/checkpoint grant unverified | **not measured** | **not measured** |
| Reflection removal | [ERRNet](https://github.com/Vandermode/ERRNet) | licence/checkpoint grant unverified | **not measured** | **not measured** |
| Reflection removal | [IBCLN](https://github.com/JHL-HUST/IBCLN) | licence/checkpoint grant unverified | **not measured** | **not measured** |
| Tap/subject segmentation (#519 shared) | [MobileSAM](https://github.com/ChaoningZhang/MobileSAM) | Apache-2.0 repo; checkpoint provenance must be re-read | **not measured** | **not measured** |
| Tap/subject segmentation (#519 shared) | [SAM 2 Tiny](https://github.com/facebookresearch/sam2) | Apache-2.0 repo/checkpoints subject to upstream terms | **not measured** | **not measured** |
| Tap/subject segmentation (#519 shared) | [MediaPipe Interactive Segmenter](https://github.com/google-ai-edge/mediapipe) | Apache-2.0 framework; selected model needs its model card | **not measured** | **not measured** |

No rows were added to `docs/identity-studio/LICENCES.md`: none of these artifacts was
verified or selected, and subject segmentation is shared functionality rather than an
Identity Studio model acquisition in this unit.

## Reproducible collection protocol

### 1. Catalogue and artifact identity

Run from the repository root:

```sh
python3 -m unittest discover -s tools/editor_models/tests -p 'test_*.py' -v
python3 tools/editor_models/benchmark.py validate
python3 tools/editor_models/benchmark.py report
```

For each candidate, first pin an upstream commit/release and archive its licence/model card.
Download only from that row's `artifact_url` into private storage, then replace `ID` and
`FILE` below. The command hashes the bytes actually present; it does not bless their origin.

```sh
python3 tools/editor_models/benchmark.py artifact --candidate ID --model FILE
```

Record download URL, redirect-resolved URL, retrieval UTC time, byte count, SHA-256, upstream
revision, licence text revision, and whether the licence explicitly covers the artifact.
Leave the catalogue hash `null` until those facts are independently reviewed. Do not commit
weights.

### 2. Export and ONNX Runtime compatibility

Each follow-up must preserve the upstream environment, export command, opset, static/dynamic
axes, preprocessing, and output interpretation in an adapter-specific README. For a supplied
ONNX file run:

```sh
python3 -m pip install --target /tmp/edit11-ort 'onnxruntime==1.23.2'
PYTHONPATH=/tmp/edit11-ort python3 tools/editor_models/benchmark.py probe --candidate ID --model FILE
```

The probe verifies file identity, exact runtime version, CPU session construction, graph I/O,
and whether that Python build exposes `XnnpackExecutionProvider`. A successful CPU session is
**not** XNNPACK evidence. If the provider is absent, the command exits 5 and reports the exact
blocker. Android XNNPACK still requires a separate ORT 1.23.2 Android harness using the same
hash, representative input, provider options, and a CPU-vs-XNNPACK numerical comparison.
Unsupported operators, partition/fallback logs, output maximum/mean error, and exported bytes
must be retained; conversion success alone is not model correctness.

Current blocker: no checkpoint/ONNX artifacts were available to this unit, and no online
weight download was attempted because checkpoint grants and provenance have not been cleared.
Therefore exports and runtime probes could not truthfully be run.

### 3. Host-only 12 MP tiled latency

Create a deterministic plumbing fixture and inspect the exact tile plan:

```sh
python3 tools/editor_models/benchmark.py fixture --output /tmp/editor-models-12mp.ppm
python3 tools/editor_models/benchmark.py tiles --width 4000 --height 3000 --tile 512 --overlap 32 > /tmp/editor-models-tiles.json
```

An adapter must decode to the model's declared colour/range, use reflected halo where needed,
blend overlapping output with a documented window, and crop to exactly 4000×3000. Report CPU
model, OS, Python, ORT, execution provider/options, physical/logical cores, thread settings,
process peak RSS, model hash/bytes, input and output tile shapes, overlap, number of tiles,
stitch method, one cold run, five warm runs, and median/p95 wall time. Time preprocess,
session, and stitch separately with `time.perf_counter_ns`. The synthetic fixture tests only
plumbing; it cannot establish visual quality. These are **host-only** results and must never
be relabelled as S24+ latency.

### 4. Quality on licensed or synthetic sets

Before evaluation, add a fixture manifest containing source URL, licence, file SHA-256, split,
ground-truth derivation and consent/provenance. Personal media is prohibited. Use fixed seeds
and save per-image results, not only averages.

| Capability | Required test/metric (all currently **not measured**) |
|---|---|
| Upscale | Licensed paired LR/HR: RGB PSNR and SSIM at ×2 and ×4; synthetic downsample kernel recorded. |
| Face restoration | Synthetic degradations of licensed/synthetic faces: PSNR/SSIM plus landmark drift; do not use an identity judge without separate licence/bias review. |
| Depth | Licensed depth ground truth or rendered geometry: scale/shift-aligned AbsRel and δ1; portrait blur boundary errors separately. |
| Shadow removal | Synthetic renderer with shadow-free ground truth: masked/unmasked RMSE and outside-mask change. |
| Reflection removal | Synthetic linear composites with known transmission: PSNR/SSIM and residual/reflection leakage. |
| Segmentation | Synthetic shapes plus a licensed subject set: click protocol fixed in advance, mask IoU, boundary F-score, latency per click, and failure rate. |

## Provisional picks—conditional, not benchmark rankings

These ordering choices are **feasibility queues only**. They reflect apparent route clarity and
mobile scope, not measured speed or quality; every pick can change after licence, export,
runtime, size, latency and quality gates.

1. **Upscale:** Real-ESRGAN, then SwinIR. Real-ESRGAN has the most direct practical tiling
   probe, conditional on checkpoint clearance and a faithful ONNX export.
2. **Face restoration:** GFPGAN, then CodeFormer. Both remain non-shipping probes; GFPGAN's
   third-party exclusions and CodeFormer's non-commercial terms are gating issues.
3. **Depth:** Depth Anything V2 **Small**, then MiDaS. The Small-only licence distinction is
   mandatory; pick neither until backbone/checkpoint terms and mobile graphs are verified.
4. **Shadow removal:** ShadowFormer, then DC-ShadowNet. This is a paper-relevance ordering only;
   both are blocked first on an explicit checkpoint grant.
5. **Reflection removal:** ERRNet, then IBCLN. This is a probe ordering only; both are blocked
   on licence/provenance and likely export work.
6. **Tap/subject segmentation:** MobileSAM, then MediaPipe Interactive Segmenter, then SAM 2
   Tiny. MobileSAM is the first size-oriented ONNX experiment; MediaPipe may be preferable if
   its model card and existing runtime route clear. This unit does not add a protected runtime
   dependency. Coordinate the resulting mask contract with #519.

## Suggested follow-up build units

1. **EDIT-11a—provenance:** pin revisions, capture original licence/model cards and download
   headers, hash real artifacts, and reject candidates without an explicit usable grant.
2. **EDIT-11b—exports:** one isolated exporter per cleared candidate, deterministic containers,
   graph equivalence vectors, operator inventory, and exported-size evidence.
3. **EDIT-11c—ORT harness:** CPU then Android XNNPACK session/partition tests on ORT 1.23.2;
   no Gradle/protected dependency change until the owner gate approves it.
4. **EDIT-11d—licensed fixtures:** reviewable manifests and deterministic synthetic generators,
   followed by the per-capability quality metrics above.
5. **EDIT-11e—performance:** host 12 MP tiling first, then an explicitly labelled S24+ thermal,
   memory and cancellation run. Preserve raw JSON and logs.
6. **EDIT-11f—selection:** update the shared licence register only for an acquired shared model,
   publish evidence-backed rankings, budgets and fallback behavior, and split #519's interactive
   mask adapter from model acquisition.
