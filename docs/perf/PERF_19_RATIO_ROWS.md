# PERF-19: original-ratio row packing at 250k synthetic media

Issue: [#252](https://github.com/LPStruiksma/atlas-gallery/issues/252). Scope is limited to the pure `planRatioRows` row builder, its synthetic JVM regression and this report.

## Baseline and change

The original planner already reuses a `DoubleArray(maxItemsPerRow)` ratio workspace, but creates a boxed `ArrayList<Int>` per row, appends each pixel width and then uses a separate `sum()` traversal. For a large catalogue this repeats once per emitted row. The candidate instead fills a primitive `IntArray(count)`, checks the running integer sum directly and exposes `IntArray.asList()` as the per-row read-only `List<Int>` contract. This removes intermediate per-width boxed `Integer` instances from the packing loop on JVMs that do not cache the width value, and removes the second traversal. It does not eliminate each row's backing array or row object. Individual `List<Int>` retrievals still box on access, so this is a hypothesis, not a guaranteed end-to-end win.

The cumulative rounding, clamping, candidate admission, gap, cap, row index, top offset, final-row raggedness and cancellation checkpoint logic are intentionally unchanged. `asList()` wraps the private array; the array never escapes separately or mutates after construction. `RatioRow` keeps its existing `List<Int>` public interface.

## Proof and measurement

`JustifiedRowsPerfTest` includes a frozen copy of the original planner from `main` at `31231dd82a851657477641947d8c5a9462a7e6f7`. It compares every `RatioRow` (media order and count, top, height, integer widths, spansFullWidth), `contentHeightPx`, and representative row-index/anchor scroll targets. Adversarial fixtures cover 11/24/120/420/1080/1440 pixel viewports, rotated dimensions, null/NaN/infinite/negative ratios, extremely wide/tall pictures, different final-row lengths, and cancellation. A 250,000-item synthetic fixture also tests all row values and representative anchor mappings.

The paired JVM probe alternates baseline/candidate after three warm-up rounds, reports n=7 median elapsed nanoseconds and median per-thread allocated bytes where the JVM supports `com.sun.management.ThreadMXBean`. It prints values with a `PERF19` prefix; no pass/fail threshold is set on noisy elapsed time. `allocated_bytes=unavailable` is a valid JVM outcome. These are synthetic **planner-only** numbers, not Gallery scroll FPS, phone heap, real MediaStore performance or cold-start latency.

**Before latency:** unmeasured at authoring. **After latency:** unmeasured at authoring. **Before allocated bytes:** unmeasured at authoring. **After allocated bytes:** unmeasured at authoring. Fill these only from a completed exact-head hosted JVM run, noting the runner and SHA. Without verified readings, claim **no demonstrated performance win**.

## Validation and rollback

Local Node/Gradle/emulator tests are unavailable to this ChatGPT seat. Exact-head hosted JVM tests and compilation, then independent Review, remain mandatory. Review should inspect whether `IntArray.asList()`'s read-only wrapper and allocation profile are advantageous in practice. A plain revert restores the prior boxed width packing.
