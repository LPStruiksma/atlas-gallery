# PERF-0 — aggregate-only performance baseline

Source: [standing performance target #119](https://github.com/LPStruiksma/atlas-gallery/issues/119), [instrumentation unit #121](https://github.com/LPStruiksma/atlas-gallery/issues/121).

## Method

All metrics are **process-local, numeric-only**, bounded to 256 recent samples per metric, with nearest-rank p50/p95 and counts. No filenames, media URIs, photos, People identities, persistent logs, network telemetry or private fixtures. Missing samples appear as **Not measured**, not zero. The Android Trace sections are `atlas_activity_content` and `atlas_viewer_open`, and never contain identifiers.

Open **Settings → Advanced options → Performance** to read the latest anonymous summaries. These are observational diagnostics, not a benchmark: `firstGridFrame` currently records the next Compose frame after an authorized non-empty Photos grid is mounted; this is not a verified hardware presentation fence. The viewer statistic is filmstrip selection → first Coil preview as already defined by `ViewerTimingProbe`, not tap → full image. Any scope lacking a live production hook remains explicitly unmeasured. JVM tests prove ring-buffer and percentile math but not device frame latency.

For comparable S24+ numbers: launch from force-stop three times with cached catalogue, warm cache deliberately between runs, collect p50 and p95 with n; run one fixed scripted grid fling and compare frame budget violations against total frames; open the same synthetic viewer set; run face/visual indexing on charge with the same permitted corpus; record peak `(totalMemory-freeMemory)/maxMemory` while browsing 250k items. Collect only aggregates and test conditions. No owner-device samples have been captured in this PR.

## Targets and baseline status

| Metric | Goal (#119) | PERF-0 device baseline |
|---|---|---|
| Cold start → cached grid | p50 ≤ 500 ms | Pending owner-device samples |
| Grid frames above budget | < 1% | Pending frame-jank capture |
| Viewer tap → full image | p50 ≤ 150 ms | Pending full-image event (preview-only probe exists) |
| Face + visual indexing | +30% photos/min vs baseline | Pending throughput hooks and run |
| Peak Java heap at 250k | < 60% of heap limit | Pending device stress run |

These pending rows are deliberately not invented or inferred from JVM tests. Add dated aggregate-only results after a real measurement and record device/build, sample count, cache state and units.
