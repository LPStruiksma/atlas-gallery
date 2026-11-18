# PERF-12 search latency: partial implementation and evidence gate

Unit: [#141](https://github.com/LPStruiksma/atlas-gallery/issues/141). This branch targets suggestion computation only. Exact results, no censorship and unchanged ranking remain mandatory.

## Changed

- IndexedSearchSuggestions builds exact substring candidate postings once per authorized name/album publication on Dispatchers.Default.
- An asynchronous 20 ms-debounced lookup avoids per-keystroke traversal of the full suggestion catalog on Compose's main thread.
- Permission epoch and catalog-publication identity fence the index. Query and publication changes suppress previous suggestion results.
- JVM parity tests compare original and indexed paths for duplicate identities, accents, query admission, exact ordering and limits. A 250,000 synthetic-source benchmark prints construction, scan and indexed lookup timing.

## Not yet established

No Kotlin/Gradle, Node or emulator execution was available in this ChatGPT seat. The benchmark produces output when CI executes; no timing numbers have been measured here. The 50 ms suggestion budget remains a target.

The existing searchWithFacets traverses the authorized in-memory MediaRecord list. This branch does not add SQL-backed indexed facets or prove 300 ms p50 first-frame latency on 250k rows. Changes need permission-fenced, generation-aware storage with matching date, album-volume and reviewed People provenance.

VisualQueryEngine performs an exact, paged 768-dimensional scan with per-vector cosine. This branch does not add approximate retrieval or prove 500 ms p50 at 250k vectors. Acceleration must retain near-duplicate counts, candidate bands, video-moment collapsing, ranking and stale/revoked behavior. An approximate prefilter needs an explicit recall and result-set parity check.

## Review / acceptance

Review may assess this bounded suggestion change, but do not mark the entire PERF-12 issue completed until the requested synthetic 250k JVM comparisons and emulator timing cover all three targets with equal results. This test benchmarks 250k suggestion sources, not media facets or visual vectors. No on-device timing was collected.

Rollback: plain revert. No database schema, MediaStore permission, dependencies or persistent data changed.
