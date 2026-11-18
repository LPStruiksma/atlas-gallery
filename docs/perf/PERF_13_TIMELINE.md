# PERF-13 — timeline run-boundary date conversion

Issue: https://github.com/LPStruiksma/atlas-gallery/issues/220

## Scope and hypothesis

The previous `buildGridTimelineIndex` converted a timestamp to a local calendar
bucket while scanning ahead and then converted that **same boundary item**
again at the start of its next run. Sparse-day merging additionally scanned the
first item of an adjacent run when deciding whether to merge. These repeated
`Instant -> ZonedDateTime -> LocalDate` paths are avoidable for sparse
chronological grids, but **no pre-change CPU/heap profile was captured** in
this ChatGPT seat. This is a source-identified opportunity, not a proven
device bottleneck.

The change carries one primitive `Long` ordinal for the first item beyond
the current run and updates it when sparse-day lookahead merges a run. Each
media timestamp's bucket ordinal is computed at most once during grouped
index construction, except its separate header-only `groupFor` formatting
on a run start. No per-media cache, global state, permission state or extra
materialization is added. The distinct-occurrence header-key guard, month
boundaries, historical local-time discontinuities, unknown timestamps,
current-year formatting and fail-closed unsorted behavior remain unchanged.
An additional checkpoint is present inside sparse same-day scans; existing
1024-item grouping and ordering checkpoints remain.

## Equivalence oracle

`app/src/test/java/nl/atlas/gallery/featuregrid/TimelineIndexPerfTest.kt`
includes `legacyBuildGridTimelineIndex`, a frozen copy of the main-branch
implementation (source blob `229c41e715f583edd9e735e3b7bdf02c18a8baf2`;
only function name differs). Differential assertions compare **all** slots,
media entry position arrays, header occurrence positions, labels, stable keys,
grouped flag and bubble lookups. Fixed-clock synthetic fixtures include
ascending/descending sorts, sparse consecutive days/full-row breaks, all
densities, 0/large merge thresholds, DST, St John's 1987 backward-midnight
offset, Pacific/Apia, year boundaries, ISO extended years, zero/overflow
dates, random/nonchronological sort, stale chronological ordering, and
distinct synthetic grant-key epochs. No actual library content is used.

## Same-JVM benchmark protocol

Run the JVM test on the exact PR head:

```sh
./gradlew :app:testDebugUnitTest --tests 'nl.atlas.gallery.featuregrid.TimelineIndexPerfTest' --info
```

The test warms up both builders twice, then alternates measured order.
It emits paired **BEFORE** (frozen implementation) and **AFTER** lines,
each with nearest-rank p50/p95 and sample count:

| Fixture | Samples per version | Algorithm condition |
| --- | ---: | --- |
| 250,000 synthetic media | 10 | 1 record/day, sparse merge threshold 4 |
| 1,000 synthetic media | 16 | many records/day, sparse merging off |

Recorded values and units are wall-time `ms`, current-thread CPU `ms`,
thread-allocated `bytes` (if supported/enabled), and observed pre/post
heap-use delta `bytes`. `n/a` means unavailable. The heap delta is **not**
peak retained heap; garbage collection during a sample can make it negative.
`System.gc()` runs outside each timed region. There is no performance-pass
threshold because shared CI hardware is noisy; assess regression and benefit
from paired samples on the **same runner/JVM**, not disjoint runs.

## Result status

**BEFORE/AFTER timings and allocations: not yet measured.**
This seat cannot run Gradle/Node or an emulator. Exact-head CI must execute
the differential oracle and print metrics before Review can say a measurable
win exists. If the hosted run omits CPU or allocations, report those fields as
unavailable rather than guessing. JVM data cannot establish smoother frames
or an S24+ gain. The 1k case is a no-regression watch, not a claimed win.

The change can be reverted with the PERF-13 PR. No new dependencies,
permissions, manifests, Gradle configuration, secrets or production data.
