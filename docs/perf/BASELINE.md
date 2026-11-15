# PERF-0 — aggregate-only performance baseline

Source: [standing performance target #119](https://github.com/LPStruiksma/atlas-gallery/issues/119), [instrumentation unit #121](https://github.com/LPStruiksma/atlas-gallery/issues/121).

## Method

All metrics are **process-local, numeric-only**, bounded to 256 recent samples per metric, with nearest-rank p50/p90/p95 and counts. No filenames, media URIs, photos, People identities, persistent logs, network telemetry or private fixtures. Missing samples appear as **Not measured**, not zero. The Android Trace section is `atlas_activity_content`, and contains no identifiers.

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

## User-action inventory coverage (#119)

A1–A22 are fixed, allowlisted metric identifiers (action_a1_ms through action_a22_ms), shown in Settings as read-only p50/p90/p95, sample count and milliseconds. They do not constitute event instrumentation by themselves: each action shows **Not measured** until an actual start/end event is connected. No action-specific samples or owner-phone measurements have been captured here. A1 cold-start currently has a separate proxy metric; it is not silently copied into A1. The next PERF implementation must wire each concrete interaction using non-identifying timing boundaries, starting with A1–A11, and test the synthetic UI/emulator flow before claiming baseline numbers. Do not treat zero samples as zero latency.

The current PR adds the 22-row display/allowlist and a bounded JVM regression test, but **does not yet satisfy the revised instrumentation scope of #121**. It is not eligible for independent acceptance until production hooks, exact-head JVM/emulator CI and a rendered Settings screenshot are supplied.

## Partial worker throughput integration

The face/visual worker reports positive completed indexing counts divided by measured stage elapsed time as process-local `photos/min` aggregates. Empty or held stages are omitted instead of being recorded as zero. This is not a measured owner-device baseline. Live A1–A11 boundaries and frame-jank hooks, exact-head JVM/emulator CI, and a rendered Settings screenshot remain required for acceptance.
