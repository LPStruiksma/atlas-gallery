# PERF-17 — bounded Photo DNA visual-pair search

Issue: https://github.com/LPStruiksma/atlas-gallery/issues/224

The production search now uses a stable counting distribution for bands up to 20 bits. The default six bands are 10–11 bits, so candidate construction is linear in the hash count rather than sorting 250,000 packed keys six times. Insertion by descending source index makes the distributed indices ascending within each band value; candidate visitation therefore remains exactly `(band, value, left index, right index)`, including when a comparison budget truncates the search. Wider non-default bands retain the incumbent packed-key sort to cap the counting table at `2^20` entries.

## Scope and invariants

Optimize `findCurationVisualPairs` in `app/src/main/java/nl/atlas/gallery/featurecuration/CurationVisualHash.kt` without changing `curationDHash`, keeper decisions, deletion actions, grants, or People persistence.

Candidate enumeration must preserve all pairs at Hamming distance `<= maxDistance`, and the existing cap, `skip`, `checkpoint` cancellation, deterministic closest-first/index tie-break order, `comparisons`, and `truncated` semantics. A candidate is charged a comparison before its exact Hamming calculation; only the first shared band retains a pair. Measure and explicitly characterize any candidate-order differences, especially under a truncated comparison budget.

## Reproduction and evidence to complete

Add synthetic adversarial equivalence tests against brute force for small fixtures and compare behavior against the unchanged incumbent across 250,000 hashes on random, near-duplicate clusters, all-equal collision flood and zero-match distributions. Include non-default distance/budget/cap, a skip predicate and cancellation checks.

`CurationVisualHashPerfTest` contains an exact copy of the incumbent as an oracle. Its ordinary tests compare full results (matches, order, comparisons and `truncated`) over adversarial fixtures, distances, caps, budgets and a skip predicate, compare unbounded output with brute force, and verify cancellation checkpoint compatibility.

The opt-in benchmark (environment variable `ATLAS_PERF17=1`; Gradle does not pass `-D` flags to the unit-test JVM) defines four seeded 250,000-hash workloads: random, planted near-duplicate clusters, all-equal collision flood and a SplitMix64 zero-match distribution. It performs one untimed warmup of each implementation, then nine BEFORE and nine AFTER samples per workload, at two comparison budgets: 1,000,000 and the production default 25,000,000. Every AFTER result is asserted equal to its BEFORE result. It reports current-thread CPU p50/p95 in milliseconds and the maximum per-invocation thread-allocated bytes as an allocation proxy. `System.gc()` is requested before each sample; it is not a guarantee of collection and allocated bytes are not retained or peak live heap.

## Result (JVM, one container)

Host: Claude cloud container, 4 vCPU, OpenJDK 21.0.13, run through Gradle `testDebugUnitTest` on 2026-11-18. CPU time is current-thread CPU in milliseconds; allocation is the per-call thread-allocated bytes (peak of the samples). Every row truncated at its budget, so both versions did exactly the same comparisons; the difference is the candidate build per band (counting distribution instead of sorting 250,000 packed keys).

| Workload | Budget | BEFORE p50 / p95 ms | AFTER p50 / p95 ms | BEFORE / AFTER alloc |
|---|---|---|---|---|
| random | 1M | 17.5 / 26.9 | 9.4 / 38.6 | 2.06 MB / 1.01 MB |
| near-duplicate | 1M | 17.2 / 22.2 | 3.3 / 3.8 | 2.00 MB / 1.01 MB |
| all-equal | 1M | 21.3 / 36.7 | 12.8 / 16.6 | 42.0 MB / 41.0 MB |
| zero-match | 1M | 18.0 / 19.0 | 2.6 / 4.0 | 2.00 MB / 1.04 MB |
| random | 25M | 72.5 / 127.0 | 61.3 / 90.7 | 2.00 MB / 1.01 MB |
| near-duplicate | 25M | 80.4 / 96.9 | 55.7 / 68.2 | 9.78 MB / 8.78 MB |
| all-equal | 25M | 553.4 / 570.5 | 380.6 / 416.5 | 1001.9 MB / 1000.9 MB |
| zero-match | 25M | 75.5 / 88.7 | 45.9 / 69.3 | 2.00 MB / 1.01 MB |

Reading: at the production budget the p50 CPU time drops by 15–40 % on every workload and the scratch allocation halves (one `IntArray` instead of one `LongArray` of keys). The p95 of the AFTER random run at 1M (38.6 ms) is one noisy sample out of nine on a shared container; its p50 still halves. The all-equal flood allocates about 1 GB in both versions, almost all of it `CurationVisualMatch` objects for pairs that the cap then drops; that is unchanged here and is a candidate for a separate unit. These numbers describe this JVM only and do not establish a Galaxy S24+ gain.

```sh
ATLAS_PERF17=1 gradle :app:testDebugUnitTest \
  --tests 'nl.atlas.gallery.featurecuration.CurationVisualHashPerfTest' --no-daemon -i
```

## Dispatch

Codex returned patch 280eef4 on draft PR #339. A Claude Dev seat applied it, fixed the test compile classpath (`java.lang.management` is reached reflectively because it is absent from android.jar), capped the brute-force test's `maxPairs` (an `Int.MAX_VALUE` cap allocates a `PriorityQueue` that size and fails with OutOfMemoryError in both versions), and ran the benchmark above. Hosted exact-head CI and an independent Review are required before merge.
