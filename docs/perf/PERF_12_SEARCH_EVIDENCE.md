# PERF-12 search latency: scoped facet-path follow-up

Unit: [#141](https://github.com/LPStruiksma/atlas-gallery/issues/141). This is a partial, bounded data-path change, not a completed performance acceptance.

## Baseline and provenance

As of the main snapshot inspected for this turn (`f8ec26aca216a087826c6991f41c3560a7ae6e0d`), `IndexedSearchSuggestions` and its synthetic 250k JVM comparison are present in the codebase. Historical PR [#160](https://github.com/LPStruiksma/atlas-gallery/pull/160) was closed unmerged; that PR's claims do not independently establish shipped behavior. The actual main tree, rather than the abandoned PR, is the baseline.

## Change and exactness

`searchWithFacets` now computes the intersection of picked, confirmed-People paths before checking the 250k-row authorized catalogue. A narrow overlap no longer requires checking every path of every selected person at each candidate. The final source-order scan, permission-grant publication, provider-volume/URI checks, reviewed-path ambiguity rejection, text matching, face-count provenance, date, album and media-kind logic remain in place. No SQL schema, permissions, search UI, ranking or filtering semantics are intentionally changed.

`ReviewedPersonIntersectionPerfTest` uses synthetic paths and checks identical ordered results for intersected and disjoint confirmed people, plus a synthetic 250k-row elapsed-time diagnostic. This diagnostic does not claim before/after timings: benchmarking must run on a representative JVM and actual device. It is deliberately not a wall-clock CI threshold.

## Remaining acceptance

- Suggestions: verify measured p50 keystroke-to-suggestion ≤50 ms on device using the indexed path; existing 250k synthetic JVM test is not a device measurement.
- Date/person/folder facet first frame: verify ≤300 ms p50 with 250k authorized rows, recording before/after JVM numbers and real emulator timings. This PR only targets confirmed-People intersection; `searchWithFacets` still scans an in-memory list and no SQL index is added without a grounded query plan.
- Find similar: verify ≤500 ms p50 at 250k vectors with exact result parity. `VisualQueryEngine` presently performs a page-wise exact 768-d cosine scan. No approximate prefilter or quantisation change is claimed; an ANN/prefilter would need a separately proven recall and exact-result contract.

No Kotlin/Gradle, Node, or emulator tests were executed by this ChatGPT seat; rely on exact-head CI and independent emulator Review. Do not close #141 until all gates have measured support.

Rollback: plain revert. No persistent user data, dependency, schema, grant, or Android manifest change.
