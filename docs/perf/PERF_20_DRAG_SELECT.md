# PERF-20 — drag-selection hit projection

Issue: [#253](https://github.com/LPStruiksma/atlas-gallery/issues/253) · parent [#119](https://github.com/LPStruiksma/atlas-gallery/issues/119).

## Change

The original pointer and auto-scroll paths called `hitCells()`, which used
`visibleItemsInfo.map { DragHitCell(...) }` for every hit lookup. Each call
allocated a list and one `DragHitCell` per visible item. The new
`hitSlot(x, y)` snapshots the current `layoutInfo` on each lookup and scans
`visibleItemsInfo` directly. It keeps the original row-first, nearest-x
selection rule, strict right/bottom bounds, first-match ties and
`viewportStartOffset` coordinate translation. It does not retain geometry
across scrolling or remeasurement.

The gesture control flow is unchanged: long-press refusal, movement tolerance,
second-finger abort, `onExtend` media-slot filtering, event consumption,
edge auto-scroll and `onEnd` in `finally` remain as before.

## Reproducible allocation accounting

For `N` visible items and `E` hit-test events, the old projection creates
`E` intermediate lists and `N × E` `DragHitCell` objects. The new direct
scan creates **zero intermediate lists and zero `DragHitCell` objects** per
event. For 10,000 synthetic events and 20 visible items this removes 10,000
lists and 200,000 hit-cell objects. These are code-path counts, **not observed
heap allocation measurements**. The test suite also exercises 10,000
seeded events with varying visible-item counts, including empty layouts.

`GridDragHitPerfTest` compares the old projection oracle to the new direct
scan for 10,000 deterministic samples plus explicit gaps, header rows,
horizontal padding, viewport offset changes and altered slot indices/geometry.
`GridDragSelectTest` remains untouched and exercises original hit semantics.

## Evidence limits and review checklist

No instrumented allocation measurement, p50/p95 wall-clock processing-time
measurement, GC trace or device-level latency claim is available from this
connector-only Dev seat. Therefore **no measured performance gain is claimed**.
Run the hosted JVM unit test task on the exact PR head before review, then
reviewer should check S24+ drag selection in the emulator: empty/header
areas, padding and row gaps, scroll boundaries, changed layout,
second-finger cancellation, start refusal and exactly-once `onEnd`.
Only real rendered/emulator evidence can establish gesture parity.
