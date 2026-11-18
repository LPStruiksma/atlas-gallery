# PERF-26 — deferred grid anchor commit validity at 250k

Issue: [#310](https://github.com/LPStruiksma/atlas-gallery/issues/310). Parent: [#119](https://github.com/LPStruiksma/atlas-gallery/issues/119). Synthetic, pure JVM regression only; no production or UI changes.

## Baseline and decision

The existing `GridAnchorCommit.isCurrent` on main compares publication, view, density, viewport dimensions, media index/key and scroll destination fields. `GridCatalogIdentity.equals` compares the source **by reference** plus the permission/publication epoch. Neither function traverses the catalogue. The calling layer must supply the key at the captured media index; this test models that adapter with a single indexed `getOrNull` per attempt. No failing production contract was demonstrated from the source inspection, so no change to `GridAnchorCommit.kt` is proposed.

## Deterministic evidence

`GridAnchorCommitScaleTest.kt` builds a 250,000-key synthetic catalogue. It accepts the last media anchor when publication, view, density, viewport and destination match. It rejects a new publication with equal contents, a changed access epoch, a revoked/empty publication, changed view, every other density, resize/rotation, stale key/index, absent key, negative or out-of-bounds index, blank key and negative destination index/offset. A bounded wrapper counts key lookups: 32 valid attempts require exactly 32 indexed accesses; each invalid candidate uses one indexed access. No `indexOf`, `find`, `contains` or whole-catalogue search is part of the commit path or test lookup.

The unit does **not** prove hardware latency or full Compose integration. Synthetic list construction and one deliberate equality assertion in the *test* are O(n); these are fixture/verification costs, not per-anchor commit operations. This is an access-count contract, not a time benchmark.

## Verification and rollback

Run `./gradlew :app:testDebugUnitTest --tests nl.atlas.gallery.featuregrid.GridAnchorCommitScaleTest` in hosted CI on the exact PR head, and confirm the repository's other required checks. The ChatGPT seat cannot execute local Gradle/Node/emulator tests. Compare CI failures before making any production repair. Revert the two additive files to roll back.
