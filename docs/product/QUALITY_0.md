# QUALITY-0 — conservative, local whole-photo signals

Unit: [#668](https://github.com/LPStruiksma/atlas-gallery/issues/668) · Goal: [#102](https://github.com/LPStruiksma/atlas-gallery/issues/102)

**Status: PARTIAL.** Pure host-independent scorers and synthetic tests exist, but phone performance and human preference accuracy are **NOT MEASURED**. This is feasibility work, not production wiring or a disposal decision.

## Contract and algorithms

`PhotoQualitySignals.analyseArgb8888` accepts an ARGB8888 `IntArray`, positive width/height, and a pixel stride at least width. The last addressed pixel must exist. Invalid shapes return unknown signals. It samples a regular thumbnail with longest edge at most 256 without allocating an image; alpha below 128 is masked. At least 64 valid samples are required. This bounds storage to 65,536 doubles plus a 256-bin histogram and bounds work to O(256²). Sampling is deterministic but can alias periodic detail; a future integration should use an area prefilter.

Channels are decoded from sRGB to linear light: `c/12.92` at `c <= 0.04045`, otherwise `((c+0.055)/1.055)^2.4`; luminance is `0.2126R + 0.7152G + 0.0722B`. For each valid non-border pixel whose four neighbours are valid, `L = north + south + east + west - 4*centre`. Blur evidence is population variance `sum((L-mean)^2)/N`, computed with Welford's algorithm. Larger is more local edge energy, not proof of a better or focused photograph. A minimum 64 valid Laplacians is independently required.

Exposure uses a 256-bin histogram of linear luminance. Dark clipping is bins 0–5, light clipping bins 250–255, each divided by valid sample count; spread is `p95-p05`. Quantiles select the first bin reaching the requested cumulative count. These cutoffs, the keeper 0.25 clip limit, 0.08 spread limit and 0.002 variance margin are **provisional, not calibrated**.

Every signal is nullable. Masked borders, small inputs, malformed arrays, or insufficient observations yield unknown, not zero. Face aggregation consumes caller-supplied authorised `leftEyeOpen`, `rightEyeOpen`, and `smile` observations. It performs no detection. Missing eyes or smiles remain unknown; it never infers closed eyes. Existing `featureface/FaceQuality` is a face-crop sharpness/pose input and COVER-1/#165 remains the detector owner; QUALITY-0 does not duplicate either.

## Conservative keeper policy

The rule applies only inside a stack already established by another subsystem. Existing user preference and established edit/RAW preference is first. Multiple preferred files cause abstention. Then all candidates must have whole-photo blur and exposure observations. Any missing value, hard-negative flag, potentially distinct image, material clipping/low spread, or top-two tie inside the provisional margin causes abstention. Only a clear blur difference among otherwise safe observations produces an advisory suggestion. IDs only make sorting deterministic; they never break a quality tie. User override always wins, and no result authorises hiding, deletion, or treating a distinct image as disposable.

Hard negatives that the caller must conservatively flag include intentional shallow depth of field, night/low-key scenes, backlit silhouettes, long exposure or motion, monochrome/high-key scenes, faces looking away, no face, crops, and screenshots/text. This list is deliberately over-inclusive because sharpness must not be an unguarded sole winner.

QUALITY-0 does not connect to `StackGroupingRules`, Stories, TIDY, scanning, persistence, or UI. QUALITY-1 would require calibrated thresholds, stack-only integration, explanations, user override, telemetry-free validation, and independent safety/review approval.

## Licence and aesthetics decision

Access checked 2026-11-20. No external image, label, model weight, or private photo is included.

| Component | Primary source | Licence/redistribution finding | Decision |
|---|---|---|---|
| sRGB transfer and luminance constants | [IEC 61966-2-1 catalogue](https://webstore.iec.ch/en/publication/6169) | Standard is copyrighted; formulas are implemented, no standard text redistributed | allowed implementation reference |
| Kotlin | [Kotlin licence](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) | Apache-2.0 toolchain; no new runtime dependency | existing toolchain only |
| AVA | [authors' project](https://people.csail.mit.edu/xiao/ava/) | Page describes research dataset; this task did not verify redistribution rights for every underlying image/label | **BLOCKED** for inclusion/training |
| LAION-Aesthetics | [official repository](https://github.com/LAION-AI/aesthetic-predictor) | Repository licensing does not establish rights to every referenced image or derived checkpoint for this product | **BLOCKED** pending legal/asset-chain review |
| SigLIP 2 | [Google Research paper](https://arxiv.org/abs/2502.14786) | Concept only; checkpoint and training-asset redistribution chain not verified here | no weights/head shipped |
| Synthetic fixtures | generators in tests and `tools/quality_bench/bench.py` | Original deterministic numeric patterns; no third-party assets | included |

An aesthetic head is therefore unimplemented. No licence approval, model score, checkpoint, or preference result is claimed. The fallback is the model-free, nullable evidence above.

## Evidence and benchmark methodology

JVM tests generate flat fields, alternating edges, blurred stripes, black/white and mid-range patterns, malformed/masked/small inputs, missing landmarks, ties, preferred edits, clipping, and hard-negative abstention. These validate mechanics only.

The Python harness generates luminance through a fixed LCG and times only an analogue of the Laplacian loop after warm-up. Decode, ARGB/sRGB conversion, exposure histogram, face work and embeddings are excluded. It prints host, Python, dimensions, samples, warm-ups, p50/p95 and memory status. A local result may vary and is not an Android/Kotlin result.

**MEASURED locally 2026-11-20:** Linux 6.18.57 x86_64, Python 3.12.13, 256×192, 10 warm-ups and 50 samples produced p50 16.616 ms and p95 18.202 ms. Decode was excluded and memory was **NOT MEASURED**. This is a Python harness measurement only, not a Kotlin or S24+ estimate.

Human best-shot agreement: **NOT MEASURED**. Pair count, annotators, inclusion criteria, split/leakage control, class balance, confidence interval and pairwise accuracy are all **NOT MEASURED** because no verified licensed, human-labelled pair set was available. Synthetic tests must not be interpreted as human agreement.

### S24+ estimate (not measured)

**ESTIMATED**, not benchmarked: a 256×192 thumbnail has 49,152 samples and about 48,260 interior Laplacians. Assuming integration already supplies/downscales pixels and a mobile CPU sustains roughly 20–100 million scalar operations/s, approximately 1–5 ms/image for signal arithmetic is a planning range. Decode/downscale and thermal/background scheduling could dominate. At 150,000 photos, serial arithmetic is roughly 2.5–12.5 minutes; allowing decode, I/O, throttling and scheduler overhead makes total elapsed time uncertain by at least an order of magnitude. Exynos and Snapdragon clocks/cores vary by regional SKU and power state; no clock value is asserted. S24+ runtime, energy and peak memory are **NOT MEASURED**.

## Status and verification

| Item | Status | Evidence / missing acceptance criterion |
|---|---|---|
| Pure blur/exposure mechanics | MEASURED | synthetic JVM assertions when the listed Gradle test passes |
| Determinism/abstention mechanics | MEASURED | synthetic JVM assertions only |
| Host Python loop timing | MEASURED | only when command output is recorded; not Android/Kotlin |
| S24+ batch cost | ESTIMATED | broad arithmetic range above; device benchmark missing |
| S24+ p50/p95, energy, memory | NOT MEASURED | representative-device benchmark missing |
| Human pairwise accuracy/CI | NOT MEASURED | licensed labelled pairs and protocol missing |
| Aesthetic model/head | BLOCKED | per-asset/label/weight licence chain and evaluation missing |
| Production integration | NOT MEASURED | explicitly out of scope; QUALITY-1 review missing |

Verification commands:

- `./gradlew :app:testDebugUnitTest --no-daemon --tests 'nl.atlas.gallery.featurecuration.quality.*'`
- `python3 tools/quality_bench/bench.py --width 256 --height 192 --warmups 10 --samples 50`
- `python3 -m py_compile tools/quality_bench/bench.py`

QUALITY-0 is not fully done: licensed preference evidence, calibrated thresholds, representative S24+ measurements, accessibility/product review, and safe QUALITY-1 integration remain acceptance gaps.
