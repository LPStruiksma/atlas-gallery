# VS-B1: on-device model and retrieval benchmark

Goal [#468](https://github.com/LPStruiksma/atlas-native/issues/468), unit VS-B1. Source:
`app/src/androidTest/java/nl/atlas/gallery/benchmark/VisualSearchModelBenchmarkTest.kt` and
`BenchmarkReport.kt` in the same folder.

## What it measures

For each bundled model:

| Model | Graphs per iteration | Input shape |
|---|---|---|
| YuNet 2023mar (face detector) | 1 | 1×3×640×640 |
| SFace 2021dec int8 (face embedder) | 1 | 1×3×112×112 |
| SigLIP 2 B/16 224 int8 (image embedder) | 1 | 1×3×224×224 |
| BlazePose 2023mar (person detector, then landmarks) | 2, in order | 1×3×224×224, 1×256×256×3 |

- **Cold load**: read the pinned model bytes from assets and check their SHA-256 (for SigLIP:
  re-hash the already joined 100 MB file, as every app open does), then create the ONNX Runtime
  session(s). Joining the SigLIP asset parts happens once per install and is not counted.
- **First run**: the first `OrtSession.run` after load.
- **Warm p50/p95**: `OrtSession.run` only, after warm-up runs, at 1 and at 4 intra-op threads
  (inter-op 1, CPU only). Pre-processing (decode, resize, alignment) and post-processing are
  not included. The app wrappers use 2 threads for YuNet/SFace/SigLIP and 1 for BlazePose.
- **Memory**: process RSS before the model, RSS with its sessions open, and peak RSS
  (`VmHWM`). The test first resets the peak through `/proc/self/clear_refs`; when the kernel
  refuses, `peak_reset` is false and the peak is the process high-water mark since start
  (an upper bound). `native_heap_delta_mb` is the native heap growth.

Then the two brute-force query engines, exactly as the app runs them:

- **FaceQueryEngine** over 300,000 synthetic 128-d faces (150,000 synthetic media), int8 corpus
  built with `FaceSearchCorpus.Builder`. `setup_ms` is the corpus build.
- **VisualQueryEngine** over 150,000 synthetic 768-d vectors, streamed in the engine's own
  512-row pages from an in-memory source (no SQLite read, no int8 decode: those belong to the
  store). `setup_ms` is making the synthetic source.

40 planted vectors sit near the query in each corpus; the test fails if any is missed. Timings
never fail the test.

## Variants

| Variant | How | Warm-up / measured runs per model and thread count | Warm query repeats |
|---|---|---|---|
| short | default (every API35 CI run) | 1 / 5 | 3 |
| full | instrumentation argument `-e atlasBenchFull true` | 5 / 30 | 10 |

Corpus sizes are the same in both variants.

## Output

One JSON object of aggregate numbers, printed to the test's stdout as
`ATLAS_SCALE VS-B1 json {...}` and to logcat under tag `ATLAS_SCALE` as numbered chunks
(`VS-B1 json 1/2 …`) that join back to the same text. The API35 CI job prints every
`ATLAS_SCALE` line in its "ATLAS_SCALE synthetic SQLite measurements" group.

## CI emulator numbers (API35, short variant)

From the API35 `emulator-ui` job of PR #485 at head `8007071`
([job log](https://github.com/LPStruiksma/atlas-native/actions/runs/42339427861/job/128304035867)): Android SDK
x86_64 emulator, API 35, **2 vCPUs** on a GitHub-hosted runner, short variant (5 measured runs,
3 query repeats). These are emulator numbers, not phone numbers. With 2 vCPUs, 4 threads
oversubscribes the CPU, so the 4-thread rows are slower here; on the S24+ (8 cores) the phone run shows whether 4 threads pays off.

| Model | Size | Cold load | First run | p50 / p95, 1 thread | p50 / p95, 4 threads | Peak RSS over start |
|---|---|---|---|---|---|---|
| YuNet | 0.2 MB | 128 ms | 75 ms | 35 / 42 ms | 21 / 23 ms | +52 MB |
| SFace int8 | 9.4 MB | 102 ms | 89 ms | 87 / 87 ms | 164 / 167 ms | +52 MB |
| SigLIP 2 B/16 int8 | 98.1 MB | 438 ms | 467 ms | 463 / 470 ms | 661 / 723 ms | +115 MB |
| BlazePose det + landmarks | 16.7 MB | 178 ms | 22 ms | 17 / 18 ms | 52 / 56 ms | +97 MB |

| Query | Vectors | Setup | First query | Warm p50 / p95 | Planted found | Peak RSS over start |
|---|---|---|---|---|---|---|
| FaceQueryEngine | 300,000 × 128 | 6.8 s corpus build | 124 ms | 28 / 35 ms | 40 / 40 | +276 MB (build) |
| VisualQueryEngine | 150,000 × 768 | 0.7 s | 179 ms | 110 / 131 ms | 40 / 40 | +48 MB |

Notes:

- The peak reset worked on the emulator (`peak_reset: true`), so each peak belongs to its own step.
  The process already held about 880 MB from the earlier instrumentation tests in the same run, so only the "over start" column means something.
- The face corpus peak is mostly the builder: 300k face-id strings, a 38 MB int8 array and growth copies.
  The query itself allocates little.
- Visual query time excludes SQLite reads and int8 decoding in `VisualIndexStore`, as the JVM scale test does.

Raw JSON (logcat `ATLAS_SCALE`, one chunk):

```json
{"unit":"VS-B1","schema":1,"variant":"short","inputs":"synthetic, generated in code","device":{"manufacturer":"unknown","model":"Android SDK built for x86_64","sdk":35,"abi":"x86_64","cpus":2,"emulator":true},"models":[{"model":"yunet_2023mar","model_mb":0.2,"cold_load_ms":127.9,"first_run_ms":75,"latency":[{"threads":1,"iterations":5,"p50_ms":34.9,"p95_ms":42,"min_ms":9.4,"max_ms":42},{"threads":4,"iterations":5,"p50_ms":21.2,"p95_ms":22.6,"min_ms":20,"max_ms":22.6}],"memory":{"rss_before_mb":877.9,"rss_after_mb":930,"peak_rss_mb":930,"peak_over_before_mb":52.1,"peak_reset":true,"native_heap_delta_mb":35.3}},{"model":"sface_2021dec_int8","model_mb":9.4,"cold_load_ms":101.9,"first_run_ms":89.4,"latency":[{"threads":1,"iterations":5,"p50_ms":87,"p95_ms":87.1,"min_ms":86.5,"max_ms":87.1},{"threads":4,"iterations":5,"p50_ms":164.4,"p95_ms":167.4,"min_ms":156.1,"max_ms":167.4}],"memory":{"rss_before_mb":880.5,"rss_after_mb":932.5,"peak_rss_mb":932.5,"peak_over_before_mb":51.9,"peak_reset":true,"native_heap_delta_mb":27}},{"model":"siglip2_b16_224_int8","model_mb":98.1,"cold_load_ms":438.2,"first_run_ms":466.6,"latency":[{"threads":1,"iterations":5,"p50_ms":463.3,"p95_ms":470,"min_ms":449.4,"max_ms":470},{"threads":4,"iterations":5,"p50_ms":660.5,"p95_ms":723.2,"min_ms":637.5,"max_ms":723.2}],"memory":{"rss_before_mb":882.9,"rss_after_mb":990.4,"peak_rss_mb":998.1,"peak_over_before_mb":115.3,"peak_reset":true,"native_heap_delta_mb":113.3}},{"model":"blazepose_2023mar_detector_plus_landmarks","model_mb":16.7,"cold_load_ms":177.8,"first_run_ms":22.2,"latency":[{"threads":1,"iterations":5,"p50_ms":17.2,"p95_ms":17.6,"min_ms":17,"max_ms":17.6},{"threads":4,"iterations":5,"p50_ms":51.6,"p95_ms":55.8,"min_ms":38.5,"max_ms":55.8}],"memory":{"rss_before_mb":878.4,"rss_after_mb":975.2,"peak_rss_mb":975.3,"peak_over_before_mb":96.9,"peak_reset":true,"native_heap_delta_mb":68}}],"queries":[{"engine":"FaceQueryEngine","vectors":300000,"dimension":128,"setup_ms":6841.4,"first_query_ms":123.6,"warm_p50_ms":28.3,"warm_p95_ms":35.3,"warm_iterations":3,"hits":40,"planted":40,"planted_found":40,"memory":{"rss_before_mb":883.6,"rss_after_mb":1113.6,"peak_rss_mb":1159.7,"peak_over_before_mb":276.1,"peak_reset":true,"native_heap_delta_mb":2.7}},{"engine":"VisualQueryEngine","vectors":150000,"dimension":768,"setup_ms":708.6,"first_query_ms":178.6,"warm_p50_ms":109.7,"warm_p95_ms":131.4,"warm_iterations":3,"hits":40,"planted":40,"planted_found":40,"memory":{"rss_before_mb":956.3,"rss_after_mb":996.9,"peak_rss_mb":1004.1,"peak_over_before_mb":47.7,"peak_reset":true,"native_heap_delta_mb":0}}]}
```

## Optional run on the owner's S24+ (owner-initiated only)

This is never automated: no CI job, seat or script runs it on a phone. Only Lukas starts it,
from his own computer, when he wants phone numbers.

Prerequisites:

- The ATLAS app on the phone is a build from `main` at or after this unit (older builds lack
  SigLIP, the query engines or the BlazePose models), signed with his own key.
- The test APK is built from the same commit with the same signing environment, so Android
  lets it instrument the installed app:
  `gradle :app:assembleDebugAndroidTest` → `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- USB debugging on, exactly one device in `adb devices`, the phone plugged in and the screen on.

Commands:

```sh
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -r -e atlasBenchFull true \
  -e class nl.atlas.gallery.benchmark.VisualSearchModelBenchmarkTest \
  nl.atlas.gallery.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s ATLAS_SCALE:I
adb uninstall nl.atlas.gallery.test
```

Do **not** use `gradle connectedAndroidTest` or `connectedDebugAndroidTest` on the phone: Gradle
installs its own app APK and uninstalls the app afterwards, which deletes ATLAS's private data.
Do not run `adb uninstall nl.atlas.gallery`; only the test package (`nl.atlas.gallery.test`) is
removed. The full variant takes a few minutes; keep the phone on the charger and idle, and
expect the first SigLIP load to write about 100 MB to the app's cache, deleted at the end.

Paste the `VS-B1 json` chunks (or the stdout line) into #468; they hold only the numbers above
and the device class (manufacturer, model, Android SDK, ABI, CPU count).

## Privacy

What it reads: the bundled model files in the app's assets, and `/proc/self/status` for its own
memory. Every model input and every corpus vector is generated in code from fixed seeds.

What it does not read: MediaStore, photos or videos, the ATLAS People or visual index, any
database, settings or other app data, the network. It asks for no permission.

What it writes: a benchmark-only copy of the SigLIP model under the app's cache
(`cache/vs-b1-bench/`), deleted when the test ends, and one JSON object of aggregate numbers
(timings, sizes, counts, memory, device class) to stdout and logcat. It writes no vectors,
media keys, file paths or images.
