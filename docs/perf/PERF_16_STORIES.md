# PERF-16 — Stories deck planning (A19)

Issue: https://github.com/LPStruiksma/atlas-gallery/issues/223

## Intent and boundaries

Reduce allocations and planning delay for a 250,000-row Stories catalogue without changing any published decks or weakening access, cancellation or visibility rules. This is an in-memory optimization, not a new catalogue or cache. No dependencies, manifest, UI or protected files change.

The **first full catalogue pass is intentionally retained**. Hidden/trashed copies must veto the entire URI globally, even if they appear after a visible record. The second pass keeps first-seen deduplication, allowed content URIs, manual-story resolution and grant/revision checks; both passes keep their cancellation checkpoints at each 1,024th row. Per-card checkpoints and limits stay in place.

## Optimization

1. Skip time-zone/calendar conversion when a row is neither eligible for automatic suggestions nor requested by a manual story. The timestamp/URI checks and first-seen behavior still run.
2. Compare the existing previous-month year/month to the row's scalar year/month, avoiding one temporary `YearMonth` per suggestion-eligible dated row.
3. Keep each day’s top three candidates by bounded ordered insertion. This removes per-insertion comparator-chain creation, full bounded-list sort and trim. Ranking is still favorite descending, capture timestamp descending, then URI ascending, including tie handling.

The two catalogue passes and time-zone conversions for eligible suggestions are **not** removed. Avoid claims about a measured win before numerical evidence is captured.

## Correctness evidence

`app/src/test/java/nl/atlas/gallery/featurestories/StoryPlannerPerfTest.kt` embeds the original planner/helper algorithms from production blob `605b0ecb01757158f4316c241f5f390e52ce3479`, with only private symbol names changed, as the before-reference.

It compares full `StoryDeck` values (scope metadata, card keys, text, kind, ordered `StoryMedia` fields and missing counts), cover media, 24-slide and 3-per-day bounds. Adversarial fixtures include dense calendars, sparse 20-year calendars, leap day, Amsterdam and New York DST, favorites, duplicated URI blocked by hidden/trashed rows in both orders, current and revoked grants, blank revision, invalid and missing/future media, manual stories, policy suppression, URI/date exclusions, long-range timestamps and cancellation checkpoint counts. The paired benchmark asserts exact equality on every sample.

## Same-JVM BEFORE/AFTER benchmark

Synthetic-only fixture; no private media. Use the same JVM, thread and hardware for the frozen baseline and optimized production path:

```bash
./gradlew :app:testDebugUnitTest --tests 'nl.atlas.gallery.featurestories.StoryPlannerPerfTest' --info
```

The dedicated test executes **two** 250,000-record fixtures: dense (31 days in the previous month) and sparse (20 years). Per fixture: two paired warm-ups, then nine paired BEFORE/AFTER measurements, alternating which implementation runs first. Logs print `PERF16` lines giving sample size, p50 and nearest-rank p95 in:

- elapsed wall-clock time, nanoseconds;
- current-thread CPU time, nanoseconds, when the JVM exposes it;
- current-thread allocated bytes, when the HotSpot MXBean exposes it;
- live heap **delta**, bytes (noisy, GC-sensitive, not the peak heap).

All numbers include normal JVM/GC variance. Nine samples give only coarse p95 (the maximum); repeat on identical hardware for firmer conclusions. The CPU/allocation fields explicitly print `unavailable` if not exposed. Gradle’s default test-task logging may suppress test stdout: use `--info` to capture.

**Evidence status (at Dev handoff): no numbers measured by this seat.** This ChatGPT environment cannot run Node/Gradle or JVM tests. The benchmark was authored but no numerical p50/p95 or allocation result has yet been verified. Hosted exact-head CI must confirm tests; passing CI alone does not prove a measurable optimization and cannot establish a Samsung S24+ phone gain. Do not infer device-level latency or heap-peak improvements from these JVM numbers.

## Rollback

A plain revert restores the original planner and removes the dedicated test and performance note.
