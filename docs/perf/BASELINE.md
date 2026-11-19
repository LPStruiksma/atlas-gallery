# PERF-0 — aggregate-only performance baseline

Source: [performance track #119](https://github.com/LPStruiksma/atlas-gallery/issues/119), [instrumentation unit #121](https://github.com/LPStruiksma/atlas-gallery/issues/121).

## Measurement and privacy boundary

The process-local `PerformanceAggregates` store retains at most 256 numeric samples per allowlisted metric. It reports nearest-rank p50/p90/p95 with counts; zero samples show **Not measured**, not a fabricated zero. It never stores media URIs, filenames, person identities, search text, bitmap bytes, persistent analytics or network telemetry. The named `android.os.Trace` sections contain only constant labels (`atlas_activity_content` and `atlas_grid_scroll`).

**Settings → Advanced options → Performance** shows the latest read-only aggregate rows (multi-line, so units and sample counts are visible). Opening the Performance section forwards pending viewer samples and samples peak Java heap once; there is no continuous polling while Settings is open.

Grid frame intervals come from consecutive `Choreographer.FrameCallback` timestamps while a Photos, Search or person grid is actively scrolling. The frame callback is registered only during active scroll and is removed when scrolling ends or the route changes. Sampling uses Android's display frame clock without keeping Compose's animation clock continuously pending (which can prevent UI tests from reaching idle). `grid_frame_over_budget` records a 0/1 sample using the current display refresh rate (`1000 / Hz` milliseconds); Settings reports the fraction over budget with its own count. These are sampled frame-clock intervals, **not** a Perfetto/JankStats end-to-end GPU presentation trace, and sampling only active scrolls does not establish a whole-app jank rate.

The viewer probe reports selection/pager entry → first Coil preview. This is neither finger-down-to-display latency nor full-resolution image decode. The cold-start proxy ends at the next Compose frame following a mounted non-empty Photos grid, not a hardware presentation fence. Scanner throughput measures **successful positive** face or visual stage completions per measured stage duration; empty, held or zero-duration work is omitted. Peak heap fraction is `(totalMemory - freeMemory) / maxMemory`, sampled on demand.

## A1–A22 coverage

| Action | Live timing boundary in PERF-0 | Status |
|---|---|---|
| A1 App open → cached grid | OS process start (`Process.getStartElapsedRealtime`) → first Compose Photos grid frame | Proxy wired |
| A2 Grid scroll / fling | Consecutive active-scroll Compose frame intervals | Wired |
| A3 Open photo | Initial viewer page entry → preview-ready | Preview proxy wired |
| A4 Viewer swipe | Subsequent page entry or filmstrip selection → preview-ready | Preview proxy wired |
| A5 Face + visual indexing | Successful bounded worker stage elapsed time | Wired |
| A6 New photo appears | Published, authorized non-cached catalogue → next Compose frame | Publication proxy wired |
| A7 Return from viewer | Viewer close → next Compose frame | Proxy wired |
| A8 Memory / stability | Duration of on-demand Java heap sample | Sampling proxy wired |
| A9 Open People | People-tab selection → next Compose frame | Wired |
| A10 Open person / scroll | Reviewed-person entry → next Compose frame; person-grid frame intervals | Wired |
| A11 Search | Search-tab selection → next Compose frame | Wired |
| A12–A22 | Fixed keys and Settings rows only; no source hooks | Explicit follow-up |

Every wired action records milliseconds under `action_aN_ms`. Only measurements actually seen in a running process have samples. The A3/A4 split is assigned when samples are forwarded, using the first completed viewer-preview sample after an open as A3. Sampling may miss interrupted gestures or image loads; it does not manufacture completions.

## Targets versus measured device baseline

| Metric | Target (#119) | Real S24+ device baseline |
|---|---|---|
| Cold start → cached grid | p50 ≤ 500 ms | Pending device runs |
| Grid frames above refresh budget | < 1% | Pending representative scroll runs |
| Viewer tap → **full** image | p50 ≤ 150 ms | Not measured: preview proxy only |
| Face + visual indexing | +30% photos/min versus earlier baseline | Pending matched-corpus device runs |
| Peak Java heap at 250k media | < 60% maximum heap | Pending device stress run |

To compare future device results: force-stop/relaunch three times with the same authorized cached catalogue; record p50/p95 and n; repeat a fixed scripted grid fling and its refresh Hz; open a synthetic viewer set; run permitted face and visual work under comparable charging/thermal state; and sample heap using an agreed 250k-item test corpus. Record build SHA, device, cache/permission conditions, units, timestamps and counts, **never** identifying media. Do not backfill these pending cells from a JVM unit test, screenshot or proxy metric.

## Verification and acceptance

Deterministic JVM tests cover percentile/ring-buffer bounds, unknown keys, frame over-budget ratios, and initial-viewer/swipe preview samples. `PerformanceSettingsJourneyTest` exercises the real Settings destination with synthetic MediaStore fixtures and declares two screenshot artifacts: `perf-0-settings-top.png` and `perf-0-settings-actions.png`.

The code and tests on this PR are **not automatically evidence of a green run**. The exact-head Android build/JVM checks must pass, followed by the API35 emulator shards, screenshot artifact validation, an independent CLEAN Review and a checked merge join. The PR's current draft/dev state must not be called accepted until the required CI and rendered images actually exist. No owner-phone measurements are claimed.
