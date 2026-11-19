# COVER-0b — quality-scoring throughput evidence (#392)

**Status:** measured on API 35 (two independent CI emulators, 2026-11-19).
Quality-on throughput regression is **0.41%** and **0.69%**, within the ≤5%
target on both. The off passes drifted by less than 0.6%, so the comparison
holds.

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
| App code SHA measured | `289a4b9be07f33e238704c453b0198e07ad6de41` (candidate head of PR #613; the evidence commit on top changes only this doc) |
| How | Throwaway head `2073eb3` = `289a4b9` + `scripts/ci-opt-in/stress-classes` + `-e atlasFaceScanBench true` in the stress loop (script only, no app or test change). [Run 44633056258](https://github.com/LPStruiksma/atlas-gallery/actions/runs/44633056258), `build-apk.yml` dispatch, API 35 |
| API level / device | API 35 CI emulator, 2 CPUs, production ORT intra-op threads = 1; shard 0 and shard 1 are separate emulators |
| Input | 24 synthetic 4032×3024 JPEGs, 48 faces processed in every pass (identical counts) |
| Quality OFF (photos/s) | shard 0: 2.962 · shard 1: 3.135 |
| Quality ON (photos/s) | shard 0: 2.958 · shard 1: 3.107 |
| Quality OFF again (photos/s) | shard 0: 2.979 · shard 1: 3.123 |
| Off-pass drift | shard 0: +0.58% · shard 1: −0.38% |
| Quality-on throughput regression | shard 0: **0.41%** · shard 1: **0.69%** (`quality_within_5pct: true` on both) |
| Target (regression ≤5%) | **Met** on both emulators |
| Raw `ATLAS_SCALE` | Below; job logs [shard 0](https://github.com/LPStruiksma/atlas-gallery/actions/runs/44633056258/job/135947017011), [shard 1](https://github.com/LPStruiksma/atlas-gallery/actions/runs/44633056258/job/135947017049) |

The same run executed `FaceIndexStoreTest#legacyQualityBackfillIsNullOnlyRevisionFencedAndIdempotent`
three times per shard: 6 of 6 PASS (TestRunner `started`/`finished`, no
assumption skip). That run's aggregate `emulator-ui` job is red by design on
a stress head (the full suite is skipped there), so it is not merge
evidence; the full suite runs on the PR head.

Side observation, not in #392 scope: on these 2-CPU emulators the PERF-4
`after` pass (1 intra-op thread, 2.96 and 3.13 photos/s) is slower than
`before` (2 threads, 4.66 and 5.00). Both quality passes use the production
setting, so the comparison above is unaffected.

Raw `ATLAS_SCALE`, shard 0:

```json
{"bench":"face_scan_552","photos":24,"source_px":"4032x3024","cpus":2,"intra_op_threads":1,"in_process":{"warmup":{"processed":24,"faces":48,"wall_ms":5390,"photos_per_s":4.5,"photos_per_s_exact":4.452830706243801,"decode_ms_per_photo":20.9,"yunet_ms_per_photo":19.5,"sface_ms_per_face":89,"other_ms_per_photo":6.2,"setup":"Setup(threads=2, quality=true)"},"before":{"processed":24,"faces":48,"wall_ms":5145,"photos_per_s":4.7,"photos_per_s_exact":4.664541551938566,"decode_ms_per_photo":20.9,"yunet_ms_per_photo":17.4,"sface_ms_per_face":86,"other_ms_per_photo":4.1,"setup":"Setup(threads=2, quality=true)"},"after":{"processed":24,"faces":48,"wall_ms":8099,"photos_per_s":3,"photos_per_s_exact":2.96327751377354,"decode_ms_per_photo":20.6,"yunet_ms_per_photo":22.4,"sface_ms_per_face":145.3,"other_ms_per_photo":3.9,"setup":"Setup(threads=1, quality=true)"},"before_again":{"processed":24,"faces":48,"wall_ms":5190,"photos_per_s":4.6,"photos_per_s_exact":4.6240795130787395,"decode_ms_per_photo":21,"yunet_ms_per_photo":19,"sface_ms_per_face":86.1,"other_ms_per_photo":4.1,"setup":"Setup(threads=2, quality=true)"},"quality_off":{"processed":24,"faces":48,"wall_ms":8103,"photos_per_s":3,"photos_per_s_exact":2.9619614616281633,"decode_ms_per_photo":20.7,"yunet_ms_per_photo":22,"sface_ms_per_face":145.7,"other_ms_per_photo":3.5,"setup":"Setup(threads=1, quality=false)"},"quality_on":{"processed":24,"faces":48,"wall_ms":8113,"photos_per_s":3,"photos_per_s_exact":2.9583306842355173,"decode_ms_per_photo":20.7,"yunet_ms_per_photo":22.9,"sface_ms_per_face":145.5,"other_ms_per_photo":3.4,"setup":"Setup(threads=1, quality=true)"},"quality_off_again":{"processed":24,"faces":48,"wall_ms":8056,"photos_per_s":3,"photos_per_s_exact":2.979101871469909,"decode_ms_per_photo":20.4,"yunet_ms_per_photo":22.1,"sface_ms_per_face":145,"other_ms_per_photo":3.2,"setup":"Setup(threads=1, quality=false)"}},"quality_regression_percent":0.4107339588705128,"quality_within_5pct":true,"quality_off_drift_percent":0.5786844313741879,"workmanager":{"processed":24,"faces":48,"wall_ms":8311,"photos_per_s":2.9,"photos_per_s_exact":2.8876935969210695,"decode_ms_per_photo":21,"yunet_ms_per_photo":22.2,"sface_ms_per_face":146,"other_ms_per_photo":11.1,"timed_out":false}}
```

Raw `ATLAS_SCALE`, shard 1:

```json
{"bench":"face_scan_552","photos":24,"source_px":"4032x3024","cpus":2,"intra_op_threads":1,"in_process":{"warmup":{"processed":24,"faces":48,"wall_ms":4889,"photos_per_s":4.9,"photos_per_s_exact":4.90905481370342,"decode_ms_per_photo":19.9,"yunet_ms_per_photo":19.3,"sface_ms_per_face":79.9,"other_ms_per_photo":4.7,"setup":"Setup(threads=2, quality=true)"},"before":{"processed":24,"faces":48,"wall_ms":4798,"photos_per_s":5,"photos_per_s_exact":5.002170697184638,"decode_ms_per_photo":19.5,"yunet_ms_per_photo":18,"sface_ms_per_face":79.5,"other_ms_per_photo":3.3,"setup":"Setup(threads=2, quality=true)"},"after":{"processed":24,"faces":48,"wall_ms":7664,"photos_per_s":3.1,"photos_per_s_exact":3.1314750561716362,"decode_ms_per_photo":20.3,"yunet_ms_per_photo":22.6,"sface_ms_per_face":136.5,"other_ms_per_photo":3.4,"setup":"Setup(threads=1, quality=true)"},"before_again":{"processed":24,"faces":48,"wall_ms":4856,"photos_per_s":4.9,"photos_per_s_exact":4.942019061727053,"decode_ms_per_photo":20.3,"yunet_ms_per_photo":18.5,"sface_ms_per_face":80.1,"other_ms_per_photo":3.4,"setup":"Setup(threads=2, quality=true)"},"quality_off":{"processed":24,"faces":48,"wall_ms":7656,"photos_per_s":3.1,"photos_per_s_exact":3.1348482343048434,"decode_ms_per_photo":20.3,"yunet_ms_per_photo":22.8,"sface_ms_per_face":136.4,"other_ms_per_photo":3.1,"setup":"Setup(threads=1, quality=false)"},"quality_on":{"processed":24,"faces":48,"wall_ms":7723,"photos_per_s":3.1,"photos_per_s_exact":3.107467958720493,"decode_ms_per_photo":20.3,"yunet_ms_per_photo":23,"sface_ms_per_face":137.7,"other_ms_per_photo":3.1,"setup":"Setup(threads=1, quality=true)"},"quality_off_again":{"processed":24,"faces":48,"wall_ms":7685,"photos_per_s":3.1,"photos_per_s_exact":3.123031549960902,"decode_ms_per_photo":20.5,"yunet_ms_per_photo":22.8,"sface_ms_per_face":137,"other_ms_per_photo":3,"setup":"Setup(threads=1, quality=false)"}},"quality_regression_percent":0.6862366856700053,"quality_within_5pct":true,"quality_off_drift_percent":-0.37694597826557485,"workmanager":{"processed":24,"faces":48,"wall_ms":7914,"photos_per_s":3,"photos_per_s_exact":3.032449280741126,"decode_ms_per_photo":20.2,"yunet_ms_per_photo":24.1,"sface_ms_per_face":137.3,"other_ms_per_photo":10.8,"timed_out":false}}
```

The same CI head must also pass the JVM source-size/charging-idempotency
regressions. A clean compile alone does not meet the throughput criterion.
