# PERF-23 — 250k timeline visual row geometry

Issue: https://github.com/LPStruiksma/atlas-gallery/issues/296

## Scope and invariants

The current `TimelineGridGeometry.planTimelineVisualRows` scans timeline slots, groups adjacent media into rows of at most `columns`, treats each header as a full-width row, maps slots into primitive `IntArray`, and accumulates row top coordinates in `Long`. `targetMedia` binary-searches those row tops. The regression does not alter index chronology, permission/epoch handling, UI behavior, or any dependency.

New synthetic JVM regression: `TimelineGridGeometryScaleTest`, covering empty and singleton plans, 250,000 media with no headers, one header per media, sparse-day boundaries every 127 media and a single large day, columns 1/3/4/7/12/28, partial final rows, first/middle/last media and cancellation frequency. It walks rows to verify contiguous slot coverage, header isolation, row counts, row top offsets and total height, plus computed focal target reconstruction. A separate stress case uses `Int.MAX_VALUE` finite dimensions, gap and header height to verify `Long` accumulated offsets beyond `Int.MAX_VALUE` and clamped bounds.

## Verification limits and decision

No locally executed Kotlin, Gradle, Node or emulator tests are available in this ChatGPT seat. The test is a candidate, **not a recorded pass**. Hosted exact-head JVM/build CI and independent review must establish it compiles and passes. Run `./gradlew :app:testDebugUnitTest --tests nl.atlas.gallery.featuregrid.TimelineGridGeometryScaleTest` where Gradle is supported.

This test adds deterministic regression coverage; it does **not** establish a measured performance improvement. No before/after p50/p95, allocation sample or same-JVM A/B was collected. Production geometry is deliberately unchanged pending a demonstrated failing case and reproducible measurement. A genuine optimization follow-up must run controlled warmup/sampling on the same JVM and fixture before claiming gains. Revert the test/documentation commit to roll back.
