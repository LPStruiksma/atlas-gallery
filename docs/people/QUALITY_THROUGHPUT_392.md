# COVER-0b — quality-scoring throughput evidence (#392)

**Status:** measurement implementation committed; **device/emulator result not yet collected**.
There are no defensible before/after throughput numbers in this document until
the opt-in instrumented benchmark completes. The ≤5% target is **unverified**;
do not treat a source-only regression test as performance acceptance.

## Paired PERF-4 procedure

The existing `FaceScanThroughputBenchmarkTest` is the PERF-4 (#125) harness:
24 synthetic 4032×3024 JPEGs are inserted into a temporary MediaStore folder
and deleted after measurement. It uses the real pinned YuNet/SFace ONNX
sessions, bounded Android decoder, current persistence, scan worker and
charging/power checks. No private user photos or network access are involved.

Run the existing opt-in instrumentation test on the **candidate PR head**,
on the same API-35 device/emulator, with:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.atlasFaceScanBench=true \
  -Pandroid.testInstrumentationRunnerArguments.class=nl.atlas.gallery.benchmark.FaceScanThroughputBenchmarkTest \
  --no-daemon
```

Extract the `ATLAS_SCALE` JSON line from logcat (or the test's printed
output). The benchmark performs a warmup and the original PERF-4
before/after thread-budget comparison. It then performs **quality_off →
quality_on → quality_off_again** with identical thread count, synthetic
inputs and fresh private stores. The off pass performs SFace
alignment/inference without scoring; the on pass adds
`FaceQualityScorer` on the already-aligned crop and stores quality in the
same completion transaction.

The JSON includes exact `photos_per_s_exact` for each pass,
`quality_regression_percent = 100 × [1 − on / mean(off, off_again)]`,
`quality_off_drift_percent` and `quality_within_5pct`. Report whether
the off passes drift enough to invalidate the comparison; rerun on an
idle/thermal-stable device if so. Check **identical processed-face counts**
and visually compare the synthetic output fixture when using a new model.

## Acceptance record (populate from the actual head-bound run)

| Field | Actual evidence |
| --- | --- |
| PR head SHA | Pending benchmark run |
| API level / device | Pending |
| Input | 24 synthetic 4032×3024 JPEGs |
| Quality OFF (photos/s) | Pending |
| Quality ON (photos/s) | Pending |
| Quality OFF again (photos/s) | Pending |
| Off-pass drift | Pending |
| Quality-on throughput regression | Pending |
| Target (regression ≤5%) | **Unverified** |
| Raw `ATLAS_SCALE` run / artifact URL | Pending |

The same CI head must also pass the JVM source-size/charging-idempotency
regressions. A clean compile alone does not meet the throughput criterion.
