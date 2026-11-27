# QUALITY-EVAL-1 — full Kotlin host-JVM cost

Unit: [#937](https://github.com/LPStruiksma/atlas-gallery/issues/937) · Feasibility: [QUALITY-0](QUALITY_0.md) · Human preference: [#1089](https://github.com/LPStruiksma/atlas-gallery/issues/1089)

**Status: benchmark implemented; measured results NOT MEASURED pending exact-head CI evidence.** The host benchmark is not a phone benchmark or calibration of keeper thresholds.

## Method

The JUnit test `PhotoQualitySignalsBenchmarkTest.benchmarkPredecodedArgb` calls the actual merged `PhotoQualitySignals.analyseArgb8888` with preallocated contiguous ARGB8888 arrays of 512×384, 1024×768 and 2048×1536 pixels. Each size uses a fixed-seed 32-bit LCG generating RGB values and occasional transparent pixels. No external images, user data or Android APIs are used. The production implementation samples at most 256 pixels on the longest edge, so these sizes all produce 256×192 sampled grids; larger input dimensions are not evidence of proportional arithmetic work.

An untimed reference call and 20 warmups per size precede 50 individually timed calls, measured with `System.nanoTime()`. Input generation, image decode, I/O, face detection, scheduling and result assertions are outside the timed interval. The test consumes each result and verifies deterministic equality, sample dimensions, valid sample counts and finite/bounded blur and exposure observations. Both observations are expected for these dense synthetic fixtures. Timing and allocation values never affect pass/fail.

Each size emits one `QUALITY_EVAL_1` line followed by a JSON object with environment, OS and JVM details, dimensions, warmup/sample counts, p50 and p95 in milliseconds (nearest rank of 50 sorted observations, ranks 25 and 48), and mean allocated bytes per measured call. Allocation uses the current worker thread's JVM allocation counter (the `com.sun.management.ThreadMXBean` extension) resolved by reflection at runtime to avoid Android compile-classpath dependencies, only if supported and enabled; missing or invalid readings produce the string `NOT MEASURED` rather than zero. The measured allocation delta includes incidental measurement overhead (including reflective counter reads), not retained or peak memory, and excludes the preallocated input array and correctness assertions. GC/JIT, runner contention and the single synthetic fixture per size limit generalization; the ordered sizes share one JVM process.

## Host CI results

Run with `gradle :app:testDebugUnitTest` (the existing `build-apk.yml` Gradle step), or for local inspection:

```sh
gradle :app:testDebugUnitTest --tests 'nl.atlas.gallery.featurecuration.quality.PhotoQualitySignalsBenchmarkTest' --info --no-daemon
```

| ARGB input | Warmups | Samples | p50 ms | p95 ms | Mean allocated bytes/call |
|---|---:|---:|---|---|---|
| 512×384 | 20 | 50 | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| 1024×768 | 20 | 50 | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| 2048×1536 | 20 | 50 | NOT MEASURED | NOT MEASURED | NOT MEASURED |

Evidence path: the benchmark prints all three lines and, when `GITHUB_STEP_SUMMARY` is set, appends the same lines as one fenced text block to the GitHub Actions step summary after completing the assertions. Default Gradle logging may suppress successful-test stdout. Before accepting #937, obtain all three lines from the benchmark head's GitHub-hosted CI, identify the head SHA and run URL, and replace the placeholders above. The summary path is unverified until the first successful run. If it is unavailable, route to Strategy for a separately authorised evidence-path change; do not manufacture measurements. If documenting the values changes the PR head, rerun CI at the final head and attach its lines to the PR, identifying the measured source revision and final evidence separately.

## Boundaries

- S24+ elapsed time, memory and energy: **NOT MEASURED**.
- Human-preference agreement: **NOT MEASURED** here; independent licensed-pair protocol and evaluation owned by [#1089](https://github.com/LPStruiksma/atlas-gallery/issues/1089).
- No production scanner, UI, signal thresholds, deletion, hiding, or keeper policy changes.
