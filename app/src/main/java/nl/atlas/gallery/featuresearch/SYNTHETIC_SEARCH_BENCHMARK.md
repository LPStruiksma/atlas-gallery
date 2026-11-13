# Lane U synthetic search benchmark · 22 October 2026

**Classification: OFF-DEVICE, WARM-JVM EXPERIMENT. NOT an S24+ latency, memory, thermal, energy or UI-frame measurement.**

## Exact source and environment

The `LocalFacetedSearch.kt` Git blob `a16d1aabb3b5db7375be27c7e0950d3ba0a4dfa4` was copied locally and independently SHA-1-checked as a Git blob. It compiled with `PeopleExperience.kt` / `PortraitSidecarGuard.kt` and a synthetic same-signature `MediaRecord/PersonEntry` stub under `kotlinc-jvm 1.9.0`, JRE 21.0.12.1. The smoke suite produced 19 People/portrait + 13 Search passing assertions.

The benchmark ran a local Java process with `-Xmx512m`; no Android phone, media permission, filesystem photos, network, account identifiers or GitHub Actions were involved.

**Dataset:** 150,000 fictional `MediaRecord` objects, uniquely keyed content URIs, 4 cyclic synthetic folders (Camera/Beach/WhatsApp/B&W), deterministic synthetic filenames. 200 fictional People profiles, each with 350 deterministic photo-path memberships. Searches ran against that single immutable in-memory list.

**Protocol:** for each query, run `searchWithFacets` 3 times for JIT warmup and then 12 timed calls with `System.nanoTime()`/`measureNanoTime`. Record result cardinality, the 7th ordered sample as approximate median and the largest of 12 as a conservative upper sampled tail (NOT a statistically trustworthy 95th percentile). Every call scans 150k input records. No I/O, image decode, database fetch, Compose state, sort or render is included.

| Query | Matching items | Warm median | Largest of 12 samples |
|---|---:|---:|---:|
| Empty/all | 150,000 | 10 ms | 25 ms |
| Album keyword `Beach` | 37,500 | 17 ms | 20 ms |
| Confirmed person name keyword | 350 | 37 ms | 51 ms |
| Two confirmed people (AND) | 0 | 31 ms | 37 ms |

These values come from **one local machine and one JVM run**, not an inter-device benchmark. Repeated JVM GC, different cores, string counts or additional face-count maps may change them.

## Design implication

Even the warm sample's slowest observed query was 51 ms, longer than a 60 Hz frame budget. **Never run this on Compose's UI thread.** Lane I must keep the existing identity-keyed, cancel-on-supersession `produceState` pattern with `Dispatchers.Default` and a checkpoint callback, and only publish results for matching MediaStore permission and People generations.

Profiling before optimization: benchmark per-stage time/cold JVM, GC pressure and memory for a realistic distribution of confirmed paths; repeat on owner-approved local synthetic Android media for frame p50/p95 if permitted. Do not infer that Samsung's own SCS cache or privileged face providers are portable. Do not cache selected-result URIs across permission revocations.

## 23 October follow-up — typed multi-volume filter and name bitset

Changes from the previous benchmark: added exact MediaStore-volume + album path matching (default `mediaVolumeOf` returns null and intentionally yields no typed matches), kept explicitly path-only all-volume behavior, capped text to **160 characters/12 tokens** and replaced per-token name/path sets with one path-indexed bitmask. Selected album volumes are pre-indexed instead of allocating an album key per item. Cancellation now checks during an individual very large Person path set.

**Synthetic host experiment (not benchmark of exact Git blob or Android):** copied the same core Kotlin logic into a minimal JVM 1.9.0 harness, mocked only MediaRecord/PersonEntry/verified face-count model, 150,000 fictional photos (every fourth in `Pictures/Beach/`; mixed fictional provider volumes) plus 200 fictional People ×250 confirmed path references. Host JRE 21 `-Xmx256m`; 3 warmups, 9 measured iterations each, single-worker, elapsed wall ms.

| Filter | Invented results | 9-run median | Slowest |
| --- | ---: | ---: | ---: |
| Exact external-primary Beach folder | 25,000 | 6.7 ms | 62.9 ms |
| Reviewed person-name bitmask (`fictional person 19`) | 1,667 | 49.8 ms | 53.3 ms |
| Exact external-primary Beach + text Beach | 25,000 | 12.8 ms | 13.3 ms |

Reconstructed local contract smoke **20/20** passing; separate scanner UI phase-contract smoke **69/69** passing; the latter covers all eleven P phase names. This does not prove Android scrolling, p95/p99, disk/index speed, runtime memory, JUnit execution or real S24+ behavior, and cannot be compared as a before/after speedup because data generation and run conditions changed. All data is invented; no originals touched.
