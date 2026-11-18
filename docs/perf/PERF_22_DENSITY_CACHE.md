# PERF-22 — density-plan retention contract

Issue: [#295](https://github.com/LPStruiksma/atlas-gallery/issues/295) · Parent: [#119](https://github.com/LPStruiksma/atlas-gallery/issues/119)

## Finding

Inspection of `RecentGridDensityPlans.kt` on `main` found an access-order `LinkedHashMap`, hard capacity range 1–3, eldest eviction after insertion, and a non-mutating `peek` implemented by entry iteration. `get` is intentionally access-order-mutating. `currentOrCachedGridPlan` checks **both** candidates against the caller's `isCurrent` predicate, selecting a valid latest plan first and valid cached plan second. There is no demonstrated deterministic defect in this generic data structure, so production code is unchanged.

The new `RecentGridDensityPlansScaleTest` constructs synthetic `IntArray(250_000)` plans for each of the 3/4/7/12/28-column levels. It checks capacities 1, 2 and 3 against an independent LRU oracle over 1,300 back/forth accesses per capacity; eldest eviction, replacement, `peek` non-mutation, `get` recency, rejected capacities, stale/null latest, matching-publication but wrong-viewport/density candidates and no insertion until explicit `putCompleted`.

The map retains **at most `capacity` plan references**, not at most `capacity` array elements: at capacity 3 with 250k elements per plan, its reachable plan payload may total 750k integers (plus object and array overhead). Test-owned fixtures also remain live during a test and do not count toward cache retention. This is a reference-count invariant, **not** a measured peak-heap bound.

## Responsibility and unresolved evidence

- Completion is a **caller-side** protocol: `putCompleted` accepts any non-null plan; it cannot detect failed, in-flight, cancelled or obsolete plans. The negative test proves the cache performs no implicit insertion, not that an upstream caller never misuses `putCompleted`.
- Publication identity, permission epoch, sorting, locale, timezone, measured viewport and header geometry are **not** owned by the generic class. Existing source documentation describes scoping its instance with Compose `remember`, but this unit did not inspect or prove the live owner's invalidation path. No cross-publication or revoked-media safety claim is made.
- A cache hit can be rejected by `currentOrCachedGridPlan` when the caller predicate rejects it, but rejection **does not evict** the stale entry. Any stronger guarantee requires a separate owner-integration proof outside #295's file scope.
- There is no before/after implementation to benchmark: allocations, lookup p50/p95, end-to-end frame time and device improvement are **not measured**. A synthetic JVM regression does not establish a performance gain. This is contract/retention regression coverage only.

## Reproduction and gates

Run on the same JVM / environment: `./gradlew :app:testDebugUnitTest --tests nl.atlas.gallery.featuregrid.RecentGridDensityPlansScaleTest`. Use exact-head hosted JVM/Android CI and an independent Review before merging. Local Node/Gradle/emulator execution is unavailable to this ChatGPT seat; no passing run is asserted. A plain revert removes the new test and this document.
