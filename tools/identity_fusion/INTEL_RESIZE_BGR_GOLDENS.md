# V5 Intel 0287 / 0288 — synthetic preprocessing goldens

**Purpose:** ensure we never substitute a non-equivalent resize or color
normalization path while comparing candidate local-first person-ReID
backends. **Status: offline synthetic input-step evidence only.**
No actual Intel IR/ONNX weights, real portraits, personal identity data,
Termux, Galaxy S24+ installation or ML runtime were accessed.

## What Intel's immutable source says, and what remains unknown

The publisher's frozen `openvinotoolkit/open_model_zoo` source at
commit `af810de42bcf460a18610481f639dcdc86249e69` has distinct
`accuracy-check.yml` files:

- [0287](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/models/intel/person-reidentification-retail-0287/accuracy-check.yml)
  Git blob `7e4aed04f03f88540533e8ac74c281589d8b18ae`
- [0288](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/models/intel/person-reidentification-retail-0288/accuracy-check.yml)
  Git blob `28d6981f6d9e0f055e1c7f6107e5b1b0208516d0`

Both specify **128×256, `use_pillow: True`,
`interpolation: BILINEAR`** for their Market-1501 evaluation. The
corresponding READMEs state **BGR channel order** and NCHW
`1×3×256×128` shape, with 256-dimensional embeddings.
The existing `model_preprocessing.py` documents Open Model Zoo's
published evaluation **OpenCV BGR reader, Pillow resize and raw
0–255 float32 transpose** for 0287. This procedure can be reproduced
on synthetic input for the 0288 published evaluation path too, but
it is **not confirmation** of graph input scales or conversion parity
without inspecting independently admitted real model files.

Do not mix this protocol up with Torchreid OSNet's **RGB,
divide-by-255, ImageNet-mean/std normalized** evaluation input.
Do not feed raw float32 BGR to an OSNet graph based on the Intel
published evaluation's convention.

## Reproducible golden vector matrix

`audit_intel_resize_goldens.py` uses only the **four synthetic pattern
generators** already committed in `benchmark_resize_parity.py`;
no photograph, file path or external media is ever accepted.

| Invented stimulus | Input width × height | Why |
| --- | --- | --- |
| Gradient | 341×173 | Real downsampling/filter dependence and spatial shifts |
| Checker | 199×397 | High-frequency texture, aliasing, large downsampling |
| Colorbars | 128×256 | Identity shape control; channel order and intensity |
| Impulse | 65×83 | Isolated point/upsampling rounding |

**Frozen reference:** Pillow **12.3.0**, `Image.frombytes("RGB",
(w,h),rgb).resize((128,256), Image.Resampling.BILINEAR)`.
The publisher's OpenCV BGR input is represented by synthetic RGB
bytes then channel-swapped **after** the per-channel spatial
interpolation (equivalent because the Pillow filter treats channels
independently). Convert every byte to **raw float32 0–255** in
**BGR, NCHW**, explicitly **little-endian**, without undocumented
mean/std or /255 normalization. Output tensor has
`3×128×256×4 = 393,216` bytes.

We independently measured the following aggregate resize
differences on a local Python host with Pillow **12.3.0**,
OpenCV **4.13.0** and NumPy, all on fictional stimuli:

| Synthetic input | RGB channel values different: Pillow vs OpenCV INTER_LINEAR | Largest absolute channel difference |
| --- | ---: | ---: |
| Gradient | **51,995 / 98,304 (52.9%)** | **82** |
| Checker | **48,693 / 98,304 (49.5%)** | **76** |
| Colorbars (no resize) | **0 / 98,304** | **0** |
| Impulse | **92 / 98,304** | **1** |

**Interpretation:** an Android implementation using OpenCV's default
`INTER_LINEAR` merely because both APIs say "bilinear" could
feed substantially different values to the model. This is a
**preprocessing parity issue**, not demonstrated model embedding drift
or real-person identification error. PIL downsampling uses its own
resampling/filter area behavior; interpolation names alone are
not a rigorous contract. Identity-shape/colorbars alone are
**not a sufficient canary**, because they show perfect parity even
when gradient/checker diverge drastically.

Every source RGB buffer, Pillow resized RGB buffer, and
Pillow-reference 393,216-byte BGR planar float32 tensor has
an immutable **SHA-256** registered in
`audit_intel_resize_goldens.py`.
This forms a reproducible, portable test vector contract for
*prospective Android preprocessing*, not an image/media asset.
A new backend may generate its own resized RGB synthetic buffers
and compare exact hashes without running any model or accessing
personal photos.

### Run only artificial stimuli

```sh
# Requires optional Pillow; no model, no input files
python tools/identity_fusion/audit_intel_resize_goldens.py \
  --model-id intel_omz_0288 --candidate pillow

# Intentionally fails the complete reference-parity gate;
# requires optional OpenCV and NumPy.
python tools/identity_fusion/audit_intel_resize_goldens.py \
  --model-id intel_omz_0288 --candidate opencv_linear

# Pure-stdlib fixture/oracle tests always run.
# Optional Pillow/OpenCV-specific tests run if installed.
python tools/identity_fusion/test_audit_intel_resize_goldens.py
```

The CLI emits only named synthetic patterns, public source identifiers,
dimensions, digest strings, and aggregate parity booleans. It does
not write any files. Exit **0** means all FOUR input-step vectors
match this fixed versioned reference; exit **2** means refusal or a
difference. It **never** claims Android runtime integration,
model-byte provenance, legal rights, accuracy, production readiness or
automatic identity linking. If Pillow versions differ, require a new
documented, independently checked reference/version comparison; do
not retroactively edit hashes to green a candidate.

## Real device bridge (not yet performed)

Once separate model/archive permissions and signed app experiment
access exist, reproduce these **same four generated input buffers**
inside the Android implementation without making a local photo
request. Hash the post-resize RGB buffers and the final
little-endian BGR float32 planar tensor. For any differing hash,
inspect pixel deltas *before* benchmarking model embeddings.
Then compare exact real model outputs against an allowed trusted
reference on independently authorized test images; report cold/warm
p50/p95/p99 timing, RAM, power, thermal and GPU/NNAPI delegate
fallbacks on the actual Galaxy S24+.

A matching synthetic hash is necessary for the *chosen test path*
but **never sufficient** for end-to-end photo/EXIF/crop parity,
model correctness, cross-year lookalike accuracy, or a reviewed
Unknown-person false-offer certificate. Every identity suggestion
remains unconfirmed until human review.
