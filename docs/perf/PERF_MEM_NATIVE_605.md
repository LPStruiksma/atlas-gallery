# PERF-MEM-NATIVE (#605): bounded Codex task

Source: https://github.com/LPStruiksma/atlas-gallery/issues/605
Dependency: merged https://github.com/LPStruiksma/atlas-gallery/pull/566
Status: **task brief, not implementation or device measurements**.

## Outcome
Extend existing process-local PERF-0 A8 to show native heap and process PSS beside the Java heap summary in Settings → Advanced → Performance. Report each as MiB with **p50, p95, max and sample count**, leaving unsampled values as "No data yet". Give the app's privacy-redacted Last crash / recent-exit display the **last valid native/PSS sample**, clearly indicating whether it was from this process or a prior persisted process; never misattribute a current sample to a killed process. Android's historical exit PSS may be shown separately with its own source label.

## Technical boundaries
- Allowlist two aggregate metric keys in `PerformanceAggregates.kt`; preserve bounded numeric-only samples, existing Java heap series and A8 timing.
- `Debug.getNativeHeapAllocatedSize()` returns bytes; `Debug.getPss()` returns KiB. Convert correctly to MiB. Both may be expensive: call from a bounded, rate-limited background dispatcher, **never on Main**, including when Performance is opened. Coalesce concurrent requests. No polling loop while Settings is open.
- Sample at the existing A8 memory event and after successful or failed scan / Photo DNA passes where their existing completion paths are available. Do not make instrumentation or exception handlers block on a PSS collection. Keep the sampling overhead on Main below 1 ms per minute; pin the off-Main dispatch with a testable seam.
- If retaining last samples across a silent process kill, persist **only two numeric values and bounded timestamp/process context**, off Main; no identifiers, filenames, URIs, photo data, stacks, raw exception messages, dependencies, new permissions or network calls. Preserve prior fatal/handled report retention and user privacy guards.
- Touch only unprotected `app/src/`, `test/`, `docs/` paths, max 12 changed regular text files in the returned patch. Do not change Gradle, manifest, workflows or the apply guard.

## Proof requested
JVM tests: allowlist and bounded percentiles (p50/p95/max), missing/invalid data, unit conversion, background dispatcher and throttling/coalescing, and safe crash/exit formatting (including killed-process semantics). Extend `PerformanceReportTest`, `PerformanceSettingsJourneyTest` and synthetic Settings evidence script where feasible, without weakening existing PERF-0 assertions; document any unrendered evidence rather than asserting it exists. Update `docs/perf/BASELINE.md` to distinguish proposed S24+ PSS <= 1 GiB target from unmeasured device baseline.

Respond with **one** Git format-patch commit atop the current task PR head, using the parent header required by `docs/agents/codex-wake.md`, and reported commands/results. A response is not an applied commit or acceptance: trusted auto-apply, exact-head hosted Android CI, rendered UI evidence and independent Review remain mandatory.
