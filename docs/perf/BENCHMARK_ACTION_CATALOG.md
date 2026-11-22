# PERF-BENCH-1 — action catalog contract

Source of truth: `UsageAction` in `app/src/main/java/nl/atlas/gallery/UsageAggregates.kt`. The v1 catalog is `BenchmarkActionCatalog.kt`, with JVM tests in `BenchmarkActionCatalogTest.kt`. The 22 A IDs are **currently defined** in the usage enum; the 19 D IDs are screen dwell identifiers, not executable benchmark actions. Neither ID inventory proves that an action is instrumented or automated.

**Catalog version: 1.** A deterministic integer, changed explicitly whenever an ID, availability, step key, precondition or control assertion changes. This is not the usage-export schema version (also currently 1). Catalog entries are declared in numeric A-ID order. The test compares the complete catalog against the source enum in **both directions**, so adding or removing a source ID without updating the catalog fails JVM CI. Duplicate action IDs and runnable step keys fail. A changed UI control must update its assertion and version; if the old assertion fails, the future runner must stop **before** recording a sample.

## Coverage inventory

| ID | Contract status | Step key / reason |
|---|---|---|
| A1 | contract-ready | open-cached-grid |
| A2 | contract-ready | fling-pictures |
| A3 | contract-ready | open-synthetic-photo |
| A4 | contract-ready | swipe-viewer |
| A5 | unavailable | Background indexing lacks a screen boundary |
| A6 | unavailable | Capture/MediaStore mutation excluded |
| A7 | contract-ready | return-grid |
| A8 | unavailable | Aggregate memory/stability diagnostic |
| A9 | contract-ready | open-people |
| A10 | contract-ready | scroll-person |
| A11 | contract-ready | search-synthetic |
| A12 | contract-ready | pinch-grid |
| A13 | unavailable | Tile completion contract missing |
| A14 | contract-ready | play-synthetic-video |
| A15 | contract-ready | browse-albums |
| A16 | contract-ready | select-photos |
| A17 | unavailable | Destructive bulk-action rollback missing |
| A18 | unavailable | Identity mutations excluded |
| A19 | unavailable | Story completion condition missing |
| A20 | contract-ready | details-favourite |
| A21 | unavailable | Trash review dataset/rollback missing |
| A22 | contract-ready | open-settings |

**Contract-ready does not mean implemented, runnable today or benchmarked.** All 22 actions currently have **zero on-device benchmark executions** from this unit. The 14 contract-ready entries describe intended future synthetic UI steps; eight are explicitly unavailable. Every ready entry has a unique step key, a synthetic state precondition and a visible control/result assertion in source. Actual UI selectors, fixture compatibility and availability must be verified by the subsequent runner unit; a selector mismatch stops measurement. Non-UI A5/A8 need separate background/memory instrumentation rather than a fabricated UI latency.

## Runner interface and safety

`BenchmarkStepPort.assertPrecondition(step)` must validate the synthetic dataset, starting screen and expected control **before** timing; `executeSynthetic(step)` must return a nonnegative elapsed duration only after the asserted step completes. `runOne` gates on availability and the precondition. The next unit must add a cancellable Android implementation, fixture setup/teardown, result assertions, timing aggregation and a device-level verification matrix. Do not use production photos, names, URIs, permissions, network, camera, or trash changes. Never export per-media identifiers.

## Updating IDs

1. Add/remove the `UsageAction` enum ID only with a deliberate usage-export compatibility decision; do not renumber existing IDs silently (the private codec uses ordinals).
2. Add/remove the corresponding catalog row and update the version. Specify a step key and both assertions only when a safe synthetic runner contract exists; otherwise use `UNAVAILABLE` and state why.
3. Run `BenchmarkActionCatalogTest` and `UsageAggregatesTest` in exact-head JVM CI. Check the 22-row inventory, duplicate and missing/stale checks, changed-control failure, and absence of any reported device timing.
4. Update this table and review compatibility with `scripts/usage_rank.py` and `docs/perf/USAGE_EXPORT.md`. A D-screen ID change is a separate usage schema decision.

No device timings, throughput, FPS or real-world performance results are claimed.
