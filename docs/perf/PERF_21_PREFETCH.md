# PERF-21 — bounded thumbnail prefetch windows (A2)

Issue: [#294](https://github.com/LPStruiksma/atlas-gallery/issues/294). Parent performance goal: [#119](https://github.com/LPStruiksma/atlas-gallery/issues/119).

## Observed baseline and risk

The original pure Kotlin `ThumbnailPrefetchPlan.slots` computed `rowsAhead * columns` and `ROWS_BEHIND * columns` as signed `Int`, then selected `after + before` for an `IntArray`. With `rowsAhead=12` and `columns=Int.MAX_VALUE`, multiplication wraps negative; the result can silently omit candidates or request a negative array length. Oversized requests could also ask for excessive temporaries. This was observed by inspecting source, **not by measuring an app stall or a crashing handset**.

## Candidate and invariants

First clamp the visible bounds to the valid catalogue (reject start index beyond end); compute each requested window in `Long`; clamp it to the available physical index gap before converting back to `Int`. The allocation is exactly `after + before`, and the two sides are disjoint, hence at most `slotCount - visibleLength` elements. Valid slotCount cannot exceed `Int.MAX_VALUE`. No changes to caller cancellation, permission checks, warm identity, image selection, visibility, or public UI.

The previous index emission order is unchanged: positive direction first goes increasing above the visible tail, then decreasing below the visible head; negative does the reverse; idle interleaves equal-distance slots with forward then backward precedence. The existing `ThumbnailPrefetchPlanTest` asserts normal cases. The new `ThumbnailPrefetchPlanScaleTest` includes a baseline copy of the previous algorithm for *ordinary non-overflowing* inputs and compares its exact arrays with candidate output.

## Synthetic fixture matrix

The new JVM tests cover slotCounts 0, 1, 250,000, `Int.MAX_VALUE`; columns 1, 3, 4, 7, 12, 28, negative, zero, 178,956,971 and `Int.MAX_VALUE`; scroll direction -1, 0, +1; velocities NaN, negative infinity, positive infinity, slow negative, zero, normal and extreme positive; empty, inverted, negative, valid, overlong, end-of-catalogue and beyond-end visible spans. Each emitted array is checked for uniqueness, in-range indices and exclusion of the visible interval. Very large catalogues use intentionally small physical windows where needed to keep the synthetic fixture itself bounded.

The optional `atlas.perf21.benchmark=true` JVM test warms the method and prints uncalibrated elapsed nanoseconds and thread-allocated bytes for 100k calls, on both the preserved baseline expression and the candidate; this is a diagnostic, not a stable performance assertion. To run locally/hosted:

```sh
./gradlew :app:testDebugUnitTest --tests 'nl.atlas.gallery.featuremedia.ThumbnailPrefetchPlanScaleTest'
./gradlew :app:testDebugUnitTest -Datlas.perf21.benchmark=true --tests 'nl.atlas.gallery.featuremedia.ThumbnailPrefetchPlanScaleTest.optionalHotPathSample'
```

## Evidence and limits

Before/after hot-path CPU and allocated-byte figures: **not yet measured**. This ChatGPT seat cannot execute Gradle/JVM/Node, so no JVM test PASS is claimed. Hosted exact-head CI and independent review must validate compilation and assertions. Ordinary inputs should produce identical slot arrays by the direct baseline parity fixture; this is a test expectation until CI executes. No measured win, S24+ fling jank reduction, physical allocation trace or cancellation/permission outcome is claimed. An `IntArray` still necessarily scales with physically returnable prefetch slots if the caller supplies an implausibly large valid full-grid span; the change prevents arithmetic overflow and allocation beyond the physically bounded windows, not a new absolute per-frame prefetch cap.

**Rollback:** revert the source change and delete this new test/doc (one PR revert). No dependency or Android permission changes.
