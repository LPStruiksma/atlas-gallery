# GHOST 1 exported from the official release (IDS-15, #506)

**Result.** GHOST 1's generator (`G_unet_1block.pth`, the variant BENCH.md chose) and its own identity backbone are exported from ai-forever's release to ONNX opset 17 in three variants. With its own backbone instead of FaceFusion's CrossFace route, GHOST scores **0.691** on the IDS-2 bench, 0.023 above the 0.668 BENCH.md measured. **Int8 weights lose nothing measurable** (0.691, paired difference to FP32 +0.001, interval -0.001 to +0.003), so the recommended variant is int8: **130 MB generator + 16 MB MediaPipe mask = 146 MB to download**, against 805 MB for the bench's FaceFusion set. The identity encoder is listed separately below, because GHOST's own backbone is research-only (#505).

## What was exported

| Part | Source | Inputs → output | Licence |
|---|---|---|---|
| Generator (AEI-Net, U-Net attribute encoder, 1 AAD block) | `G_unet_1block.pth` from the [sber-swap-v2.0 release](https://github.com/ai-forever/ghost/releases/tag/sber-swap-v2.0), SHA-256 `01dbe8d1…6d96`; code from ai-forever/ghost at `44e58aad` | `target` 1×3×256×256 in [-1, 1], `source` 1×512 raw identity vector → `output` 1×3×256×256 in [-1, 1] | Apache-2.0 (tier D: trained against the research-only backbone, see [LICENCES.md](LICENCES.md)) |
| Identity backbone (iresnet100) | GHOST's `arcface_model/backbone.pth`, read with #505's restricted reader | `input` 1×3×112×112 in [-1, 1] → raw 512-d vector | InsightFace research-only: **bench only, never shipped** |

The generator call is the same one the bench already made of FaceFusion's `ghost_1_256.onnx`. A tensor comparison (#505's tool) shows FaceFusion's file holds the same weights as the official release: 92 tensors paired, 85 byte-equal, the other 7 equal per channel (cosine 1.0, batch norms folded). So the gain below comes from the identity source alone, not from different weights.

## Parity with PyTorch

Export with PyTorch 2.15 (CPU), TorchScript exporter, opset 17, constant folding; ONNX Runtime 1.29 on the host. Random inputs:

| Part | Variant | File | Max abs difference to PyTorch | Mean abs difference |
|---|---|---|---|---|
| Generator | FP32 | 514.7 MB | 4.4e-5 | 1.2e-6 |
| Generator | FP16 weights | 257.8 MB | 1.7e-2 | 5.7e-4 |
| Generator | int8 weights | 129.7 MB | 1.5 | 4.0e-2 |
| Backbone | FP32 | 260.7 MB | 2.8e-6 | 6.0e-7 |
| Backbone | FP16 weights | 130.4 MB | 2.5e-3 | 6.8e-4 |
| Backbone | int8 weights | 65.6 MB | 6.9e-2 | 1.6e-2 |

The FP32 graphs match PyTorch to float rounding. The int8 generator's maximum on a random-noise target is large because noise drives a few pixels into the steep part of the final tanh; on real faces the bench below shows no loss.

**Variants.** "FP16 weights" stores each weight as FP16 and casts it back in the graph; "int8 weights" stores each weight as symmetric int8 with one scale per output channel and a `DequantizeLinear` in front of the op. In both, activations and arithmetic stay FP32, so every phone CPU runs them unchanged. ONNX Runtime's dynamic int8 (activations quantised too) was tried and dropped: it breaks the generator (max difference 2.0 on a [-1, 1] output) and leaves the 7 transposed convolutions in FP32 (253 MB).

**Android.** ATLAS ships the full `onnxruntime-android` 1.23.2 AAR (`app/build.gradle.kts`), which registers every CPU kernel. All six graphs use only standard ai.onnx operators (Conv, ConvTranspose, Gemm, InstanceNormalization, BatchNormalization, Resize, LeakyRelu, PRelu, Sigmoid, Tanh, Cast, DequantizeLinear and shape ops), no contrib ops; `android_gaps` checks this on every export.

## The bench, per variant

`python3 -m tools.identity_bench explore` on the 58 photoreal pairs, IDS-2 pipeline (YuNet, face-parser mask, colour match). Each exported generator takes its source vector from the same-variant backbone (mean raw vector over the identity's reference photos), so the int8 row is the int8 generator with the int8 backbone.

| Candidate | Judge mean | Median | p10 | Rank-1 | Retention | To original | SFace mean | A1 to A4, A6 |
|---|---|---|---|---|---|---|---|---|
| FaceFusion `ghost_1_256` + CrossFace (BENCH.md row) | 0.668 | 0.680 | 0.528 | 1.00 | 0.81 | 0.185 | 0.590 | pass |
| Export FP32 + own backbone | **0.691** | 0.714 | 0.548 | 1.00 | 0.84 | 0.188 | 0.599 | pass |
| Export FP16 + own backbone | 0.690 | 0.714 | 0.561 | 1.00 | 0.84 | 0.188 | 0.596 | pass |
| Export int8 + own backbone | 0.691 | 0.717 | 0.555 | 1.00 | 0.84 | 0.186 | 0.597 | pass |

Paired differences on the same 58 pairs (95 % bootstrap): FaceFusion route minus int8 -0.023 (-0.036 to -0.011); FP32 minus int8 -0.001 (-0.003 to +0.001); FP16 minus int8 -0.001 (-0.003 to 0.000). The IDS-2 noise band from landmark jitter is a standard deviation of 0.015 for GHOST, so the three variants are indistinguishable and all three beat the FaceFusion route. Untouched pixels outside the mask: 0 changed for every variant (A4). Rendered identities (12 pairs, reported apart): 0.650 FaceFusion, 0.645 FP32, 0.647 FP16, 0.639 int8.

By hard case the gain over the FaceFusion route is largest on occlusion (+0.05), profile (+0.04 to +0.05) and beard (+0.04 to +0.05); glasses are the one case 0.01 to 0.02 lower.

## Host latency and memory

`python3 -m tools.identity_bench cost`, fresh process per variant, 4 threads, x86 cloud CPU. **Not phone numbers.**

| Candidate | Load generator | Swap (generator only) | Mask (face parser) + colour + paste | Peak memory |
|---|---|---|---|---|
| FaceFusion `ghost_1_256` | 1.58 s | 212 ms | 175 ms | 1,535 MB |
| Export FP32 | 1.54 s | 152 ms | 162 ms | 1,806 MB |
| Export FP16 weights | 1.16 s | 162 ms | 181 ms | 1,820 MB |
| Export int8 weights | **0.12 s** | 278 ms | 180 ms | **1,208 MB** |

Int8 loads thirteen times faster and peaks 600 MB lower, because the weights stay int8 in memory; it pays about 125 ms more per swap to dequantise them. FP16 peaks highest, as ONNX Runtime keeps both copies while folding the casts.

The backbone runs once per reference photo, not per swap: 58 ms per face for FP16 and 195 ms for int8. The FP32 backbone takes 867 ms on x86 because 194,248 of its weights are denormal floats (it was trained in FP16); FP16 storage flushes them. A phone build of the FP32 file should set ONNX Runtime's denormals-as-zero option.

## What would ship, and the download

| File | Size | Status |
|---|---|---|
| GHOST 1 generator, int8 weights (`ghost1_generator_int8.onnx`) | 129.7 MB | Apache-2.0 file, register tier D (provenance gap, see LICENCES.md) |
| MediaPipe multiclass selfie segmenter (mask) | 16.4 MB | Apache-2.0, tier A |
| **Download (generator + mask)** | **146 MB** | |
| Identity encoder | GHOST backbone int8: 65.6 MB | **To be decided by #507.** GHOST's own backbone is research-only; [IDENTITY_PATH_B.md](IDENTITY_PATH_B.md) (#507) compares the routes, and an SFace adapter measured 0.491 instead of 0.691 |

YuNet and SFace are already in the APK and add nothing. Against BENCH.md's FaceFusion set for GHOST (805 MB: generator, CrossFace, w600k_r50, face parser) the download shrinks to under a fifth, and the face parser (research data) is replaced by the shippable mask.

## Reproduce

```bash
python3 -m unittest tools/identity_bench/export/test_ghost1_export.py      # offline; the round-trip test needs PyTorch, else it skips
pip install torch                                                           # CPU is enough; PyPI's wheel pulls several GB of CUDA libraries
python3 -m tools.identity_bench.export.ghost1_export --blocks 1             # downloads 514 MB + backbone, writes 6 graphs, about 1 min
python3 -m tools.identity_bench one --target T04 --identity 0213ccb4776a --candidate ghost1_generator_int8
python3 -m tools.identity_bench explore --candidates ghost_1_256,ghost1_generator_fp32,ghost1_generator_fp16,ghost1_generator_int8
python3 -m tools.identity_bench cost --candidates ghost_1_256,ghost1_generator_fp32,ghost1_generator_fp16,ghost1_generator_int8
```

The export, its checkout of the GHOST code and the weights live in `~/.cache/atlas-identity-bench/export/`; the report with hashes and operator lists in `runs/export/ghost1_export.json`. Nothing is committed. The bench names the exported graphs `ghost1_generator_<variant>` and runs them only by name (they are not in the screening set, which needs no PyTorch).
