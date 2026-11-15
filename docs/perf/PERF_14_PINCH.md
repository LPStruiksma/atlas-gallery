# PERF-14 · Pinch pointer-path allocations

Issue: [#221](https://github.com/LPStruiksma/atlas-gallery/issues/221). Scope is limited to the pointer hot path in `GalleryPinchTracker.kt`, a synthetic JVM parity/diagnostic test, and this report. No UI, dependency, manifest, media, or grant logic changes.

## Baseline and intervention

Previously, each `PointerEventPass.Initial` sample made `changes.filter { it.pressed }`, two `FloatArray` instances, and (when nonempty) a `Triple` from `pinchSpan`. The new loop allocates **one** `PinchEventMeasurement` per gesture and reuses it for every pointer sample. It examines only pressed changes; an empty pressed set returns exactly the former `(NaN, NaN, NaN)` observation.

**Numerical fidelity:** the span is the **arithmetic mean of Euclidean distances from the arithmetic centroid**, not RMS separation or a pairwise approximation. The exact expression requires the centroid before distance accumulation, so it uses two pointer scans (one centroid, one span). The pressed-pointer order, Float arithmetic, `hypot` invocation, NaN/infinity propagation, and zero-finger behavior are unchanged. This avoids allocation without silently changing the gesture threshold or pixel focus.

All existing `GalleryPinchTracker.onSample` state transitions are unchanged: >touch-slop activation, count-change re-baselining, finger lifts after activation, zoom factors/clamps, focal-point updates, cancellation and settled destinations. The Initial-pass event consumption, onZoom callbacks and cancellation exception path were deliberately left intact. `Offset` passed to a consumer callback is still built when a callback runs; this change targets the pointer-measurement allocations rather than unrelated callback contracts.

## Deterministic equivalence

`GalleryPinchPerfTest.kt` implements an independent copy of the **old production sampling expression** and compares its results to the reuse-based sampler for every event, using Float bit comparisons. Fixtures cover 0–5 pressed fingers (plus inactive changes), joins and lifts, touch-slop boundary, a started pinch and release, zero and negative-zero positions, `NaN`, both infinities and `Float.MAX_VALUE`. A second test feeds both observations through independent state machines for all six initial stops and both original-route permissions; it compares consume decisions, started state, live zoom, focus, progress and final destination at every sample. Existing `GalleryPinchTrackerTest` covers transitions and the span formula independently.

This is JVM evidence for sampling/state parity only. It does **not** exercise Android cancellation dispatch, clickables, a physical multi-touch device, real rendering, or any S24+ input latency.

## Before/after same-JVM diagnostic protocol

The JVM test `printSameJvmBeforeAfterDiagnosticsForTenThousandEvents` prepares identical synthetic `PointerInputChange` objects **outside** its timed windows. Each arm executes 10,000 warm-up events and **10,000 measured events on the same JVM thread**, first the exact old production expression, then the reusable sampler. It reports:

- p50/p95 nanoseconds **per processed pointer event**, based on 10,000 per-event `System.nanoTime` intervals for each arm.
- `com.sun.management.ThreadMXBean` allocated **bytes per 10,000 events**, if the JDK exposes thread allocation accounting (otherwise `unavailable`). This includes JVM/runtime overhead; it is not an allocation-object counter.
- The signed `totalMemory - freeMemory` **heap delta in bytes per 10,000 events**. GC and JIT can change this independently of object production; it is not peak heap.

The synthetic loop deliberately cycles through 0–5 active fingers and invalid coordinates. Baseline source constructs a filtered collection and two arrays per event, plus a `Triple` for nonempty samples; the optimized sampler creates **no per-event measurement objects**, just its one per-gesture accumulator. JIT escape analysis may eliminate baseline allocations in the JVM even when Android behavior differs.

**Result:** measurable JVM or device improvement **not yet established**. Numeric before/after figures should be copied from the exact-head CI test output if visible; absent that output, the PR must not claim an observed p50/p95, heap or allocation win. The two arms have fixed order, so figures are diagnostic, not a statistical proof. No Android/S24+ gain is inferred from the JVM comparison.

## Reproduction and rollback

Run `./gradlew :app:testDebugUnitTest --tests nl.atlas.gallery.featuregrid.GalleryPinchPerfTest` in a supported Android SDK/JDK environment, then the normal exact-head PR CI. The test intentionally asserts **equivalence**, never a timing or byte threshold (hardware and GC make these flaky).

A plain revert of the production/test/report commits restores the old pinch path. This change introduces no durable state or schema migration.
