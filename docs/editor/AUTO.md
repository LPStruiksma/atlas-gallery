# EDIT-6 — Automatic straightening and tone (task scaffold)

Status: **implementation pending**. This file was seeded for the Codex task in [#724](https://github.com/LPStruiksma/atlas-gallery/issues/724); no algorithm, JVM benchmark, or parity acceptance has been demonstrated by this scaffold.

## Contract

- Pure Kotlin under `app/src/main/java/nl/atlas/gallery/featureeditor/auto/`; synthetic JVM tests under `app/src/test/java/nl/atlas/gallery/featureeditor/auto/`. No Android UI, Gradle/dependency changes, ML models, or writes to `featureeditor/engine/**`.
- `AutoStraighten.suggest(luma, width, height): StraightenSuggestion?` operates on a downscaled luminance plane, with a documented pixel representation. Detect consistent horizontal or vertical line evidence (Hough or equivalent gradient voting), report an angle in `-45..45` and meaningful confidence, return `null` for insufficient/ambiguous evidence. Convert an accepted suggestion to `EditOperation.Straighten(id, degrees)` for `EditHistory`. Confirm sign convention and no-op behaviour in tests.
- `AutoAdjust.suggest(stats): List<EditOperation.Adjust>` uses a documented immutable histogram/statistics representation. Emit deterministic, finite, within-`AdjustParam`-bounds values for exposure, contrast, highlights, shadows, temperature and tint. Stable operation IDs must be suitable for recipes/history. Neutral inputs should be approximately zero.
- Synthetic tests cover rotated line grids and horizons with maximum absolute angular error 0.5°, texture-only/no-line `null`, under-/over-exposed gradients moving toward mid-grey, white-balance casts, neutral images, determinism, invalid inputs, and bounds.
- Performance target: both suggestions ≤30 ms each at 512 px long edge on a reproducible JVM benchmark, with machine/JDK, warmup, sample count and percentile documented below. Phone/emulator timings are separate future proof.

## Measurement record

**Not measured yet.** The implementing task must replace this paragraph with actual JVM benchmark commands, environment, repeated timings and result. Never present hosted CI duration, an unexecuted timing assertion, or a desktop JVM run as S24+ measurements.

## Parity

The [EDIT-0 ledger](PARITY.md) marks Transform/Auto-straighten and Tone/Auto adjust as planned for M2/M3, with Samsung S24+ specifics still needing device observation. This algorithm unit proves neither Samsung UI parity nor render/export integration.
