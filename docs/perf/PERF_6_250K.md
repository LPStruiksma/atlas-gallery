# PERF-6: 250k synthetic Android heap acceptance

Unit: [#127](https://github.com/LPStruiksma/atlas-gallery/issues/127) · Goal: [#119](https://github.com/LPStruiksma/atlas-gallery/issues/119), A8.

## Scope and target

`GalleryInventoryHeapStressTest.synthetic250kRetainedStagesAndSampledHighWater` constructs 250,000 deterministic MediaStore-shaped `MediaRecord` values, then keeps the catalogue, sorted list, two timeline density plans, album summaries, and a lazy People view/directory reachable simultaneously. It checks catalogue size, index resolution, album count and directory population.

The test samples `Runtime.totalMemory() - Runtime.freeMemory()` on a 2 ms background loop while the model is built. It records each retained stage, baseline, maximum observed used heap, `Runtime.maxMemory()`, the observed high-water fraction and final retained bytes per item. It asserts **sampled peak / maxMemory < 0.60**. This is a *sampled* high-water mark, not an exact allocation peak: short-lived allocations can fall between samples. GC and emulator heap configuration affect the number.

The `ATLAS_SCALE: PERF-6-250k` log line is the evidence surface. The exact-head Review run should preserve the line together with the PR head SHA, emulator image/API/heap configuration and CI URL. If the existing emulator runner does not collect this test, register it in the runner before treating the result as acceptance.

## Evidence status

- JVM retained-holder baseline: [PERF_6_HEAP.md](PERF_6_HEAP.md), including the existing `GalleryInventoryMemoryTest` bytes/item and People projection coverage.
- Android 250k combined model: **pending exact-head emulator execution**. No peak fraction or pass result is claimed here.
- Owner-device / real MediaStore / bitmap decode / cursor / live permission behavior: **not measured**.

This model-only test cannot establish that a full real-device scan stays below 60% of the heap limit. It is a repeatable synthetic regression gate, not a substitute for PERF-0's real pipeline measurement. No `largeHeap` flag, feature removal, real media or private data is involved.
