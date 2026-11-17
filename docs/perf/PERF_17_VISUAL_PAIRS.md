# PERF-17 — bounded Photo DNA visual-pair search

Issue: https://github.com/LPStruiksma/atlas-gallery/issues/224

The production search now uses a stable counting distribution for bands up to 20 bits. The default six bands are 10–11 bits, so candidate construction is linear in the hash count rather than sorting 250,000 packed keys six times. Insertion by descending source index makes the distributed indices ascending within each band value; candidate visitation therefore remains exactly `(band, value, left index, right index)`, including when a comparison budget truncates the search. Wider non-default bands retain the incumbent packed-key sort to cap the counting table at `2^20` entries.

## Scope and invariants

Optimize `findCurationVisualPairs` in `app/src/main/java/nl/atlas/gallery/featurecuration/CurationVisualHash.kt` without changing `curationDHash`, keeper decisions, deletion actions, grants, or People persistence.

Candidate enumeration must preserve all pairs at Hamming distance `<= maxDistance`, and the existing cap, `skip`, `checkpoint` cancellation, deterministic closest-first/index tie-break order, `comparisons`, and `truncated` semantics. A candidate is charged a comparison before its exact Hamming calculation; only the first shared band retains a pair. Measure and explicitly characterize any candidate-order differences, especially under a truncated comparison budget.

## Reproduction and evidence to complete

Add synthetic adversarial equivalence tests against brute force for small fixtures and compare behavior against the unchanged incumbent across 250,000 hashes on random, near-duplicate clusters, all-equal collision flood and zero-match distributions. Include non-default distance/budget/cap, a skip predicate and cancellation checks.

`CurationVisualHashPerfTest` contains an exact copy of the incumbent as an oracle. Its ordinary tests compare full results (matches, order, comparisons and `truncated`) over adversarial fixtures, distances, caps, budgets and a skip predicate, compare unbounded output with brute force, and verify cancellation checkpoint compatibility.

The opt-in benchmark (`-Datlas.perf17=true`) defines four seeded 250,000-hash workloads: random, planted near-duplicate clusters, all-equal collision flood and a SplitMix64 zero-match distribution. It performs one untimed warmup of each implementation, then five BEFORE and five AFTER samples per workload. It reports current-thread CPU p50/p95 in milliseconds and the maximum per-invocation thread-allocated bytes as an allocation proxy. `System.gc()` is requested before each sample; it is not a guarantee of collection and allocated bytes are not retained or peak live heap.

No performance win is established in this change. On the available container, `gradle :app:testDebugUnitTest --tests nl.atlas.gallery.featurecuration.CurationVisualHashPerfTest --no-daemon` was blocked before Kotlin compilation because Gradle could not resolve the Android application plugin `com.android.application:8.13.2` from Google, Maven Central or the Gradle Plugin Portal. Consequently there are no honest BEFORE/AFTER sample counts, CPU percentiles or allocation results to publish from that machine. Run the command below in a dependency-complete checkout to collect them; JVM results characterize only that host/runtime and cannot establish a Galaxy S24+ gain.

```sh
gradle :app:testDebugUnitTest \
  --tests nl.atlas.gallery.featurecuration.CurationVisualHashPerfTest \
  -Datlas.perf17=true --no-daemon --info
```

## Dispatch

Codex Cloud (Legacy) will return one parent-bound format patch on this PR. The ChatGPT Dev driver validates and applies it to the same branch; hosted exact-head CI and independent Review are required before any merge.
