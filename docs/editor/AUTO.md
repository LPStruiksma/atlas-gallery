# EDIT-6 — Automatic straightening and tone

Status: **pure Kotlin suggestion algorithms implemented**. Render/export and UI integration remain separate work.

## Contract

- Pure Kotlin under `app/src/main/java/nl/atlas/gallery/featureeditor/auto/`; synthetic JVM tests under `app/src/test/java/nl/atlas/gallery/featureeditor/auto/`. No Android UI, Gradle/dependency changes, ML models, or writes to `featureeditor/engine/**`.
- `AutoStraighten.suggest(luma, width, height): StraightenSuggestion?` operates on a downscaled luminance plane, with a documented pixel representation. Detect consistent horizontal or vertical line evidence (Hough or equivalent gradient voting), report an angle in `-45..45` and meaningful confidence, return `null` for insufficient/ambiguous evidence. Convert an accepted suggestion to `EditOperation.Straighten(id, degrees)` for `EditHistory`. Confirm sign convention and no-op behaviour in tests.
- `AutoAdjust.suggest(stats): List<EditOperation.Adjust>` uses a documented immutable histogram/statistics representation. Emit deterministic, finite, within-`AdjustParam`-bounds values for exposure, contrast, highlights, shadows, temperature and tint. Stable operation IDs must be suitable for recipes/history. Neutral inputs should be approximately zero.
- Synthetic tests cover rotated line grids and horizons with maximum absolute angular error 0.5°, texture-only/no-line `null`, under-/over-exposed gradients moving toward mid-grey, white-balance casts, neutral images, determinism, invalid inputs, and bounds.
- Performance target: both suggestions ≤30 ms each at 512 px long edge on a reproducible JVM benchmark, with machine/JDK, warmup, sample count and percentile documented below. Phone/emulator timings are separate future proof.

## Algorithms

`AutoStraighten` accepts a row-major, stride-free unsigned 8-bit luminance plane. It applies a
Sobel filter, separates approximately horizontal and vertical evidence, and performs a bounded
`-20..20` degree Hough projection vote at 0.25 degree resolution. A peak must have both absolute
line concentration and separation from the next non-neighbouring peak; otherwise the result is
`null`. The reported angle is the correction (clockwise positive in `EditOperation`), rather than
the detected source tilt. Ceiling-divided stride keeps **at most 8,000 uniformly selected edge points** in the vote,
including dense high-contrast 512 px inputs. This prevents a previously unbounded 8,001–15,999
edge case from overrunning the intended sampling budget.

`ToneStatistics` owns defensive copies of three 256-bin RGB histograms with equal, non-zero sample
counts. `AutoAdjust` derives mean and 5th/95th percentiles, applies documented neutral targets
(middle grey and a 5–95% range), and uses channel means for grey-world temperature/tint. It always
returns the same six stable operation IDs in renderer order and clamps every finite value to its
`AdjustParam` range.

## Measurement record

Desktop JVM measurement on 2026-11-20: Linux x86_64 container, OpenJDK 21.0.2, Kotlin compiler
2.0.21. `AutoBenchmarkTest` constructs a 512×384 synthetic grid and neutral histograms, performs
20 warm-up calls, then records 200 calls per algorithm with `System.nanoTime()`. Run it with:

    gradle :app:testDebugUnitTest --tests nl.atlas.gallery.featureeditor.auto.AutoBenchmarkTest --no-daemon

The repository checkout did not contain `./gradlew`, and the installed Gradle could not resolve the
future Android Gradle Plugin 8.13.2. Therefore the same benchmark body was compiled directly with
the installed Kotlin compiler and run with `java`. Results were: auto-straighten median **18.430
ms**, p95 **20.097 ms**, max **25.807 ms**; auto-tone median **0.042 ms**, p95 **0.150 ms**, max
**0.312 ms**. The earlier desktop p95 values met the 30 ms target **before the sampling-cap correction**.
The regression benchmark now also records `straighten-dense` on 512×384 two-pixel stripes;
record its new timings from a fresh run rather than treating the earlier numbers as exact-head
measurements. These are reproducible desktop JVM numbers, not hosted-CI or Samsung S24+ measurements.

## Parity

The [EDIT-0 ledger](PARITY.md) marks Transform/Auto-straighten and Tone/Auto adjust as planned for M2/M3, with Samsung S24+ specifics still needing device observation. This algorithm unit proves neither Samsung UI parity nor render/export integration.
