# Photo DNA index scale / Android deployment risks — 2026-10-16

This is **local Linux synthetic research**, not a benchmark on the Galaxy S24+ or any real gallery. Source code and invocation already exist in `benchmark_packed_index.py` (stacked upstream PR #104); no photos from the phone, private archives or publicly licensed datasets are involved. This note records a larger reproduced sample without changing index implementation.

```bash
python -m tools.photo_lineage.benchmark_packed_index --anchors 8 --distractors 1200
```

| Measurement | Observed Linux CPU result |
|---|---:|
| Procedurally generated index photos | 1,208 |
| ORB visual-word postings | 463,161 |
| Immutable packed u64 index | 3.534 MiB |
| Packed `sorted()` build only | 0.262 s |
| Full program wall time, including synthetic image rendering/extraction | 28.38 s |
| Process peak resident memory (includes synthetic image construction) | 200,392 KiB |
| Rotated-crop queries | 56 |
| Retrieval recall@1 (within this procedural benchmark) | 55/56 (98.21%) |
| Retrieval recall@5 | 56/56 (100%) |
| Median / p95 packed query | 2.15 / 2.60 ms |

**Conditional capacity projection, not a measurement:** roughly **383.4 postings/photo** in this generated corpus. At 150,000 photos, the same density would imply about **57.5 million postings / 438.8 MiB** at 8 bytes each for a compact immutable index **alone**. It excludes image paths, an ID-to-path manifest, write-ahead/incremental update structures, SQLite bookkeeping, filesystem caches, journal space and embedded image metadata. Real descriptor frequencies, re-identification recall, photo dimensions, camera sensors and crops may differ substantially. This must not be used as a deployment budget or a claim of on-device speed.

**Do not deploy the Python packed builder as is.** It constructs a potentially huge Python list of integers before sorting. An Android implementation needs memory-bounded shard generation, streaming external merges, checksum-verified atomic snapshot publication and a separate crash-safe incremental-delta layer. It also needs a background-job budget (storage, battery, thermal), pause/resume, privacy-preserving deletion/reindex invalidation, and bounded per-query IO with legitimate `unknown` results when the index is stale/unavailable. Nondestructive original media access and local-only persistence remain mandatory.

**Engineering direction:** keep candidate *retrieval* and pair *verification* separate; avoid trying to index all images through an O(N²) matcher. Index snapshots/deltas are disposable caches, not user-authoritative photo-family data. Do not promote `candidate` into same-exposure or deletion authority without reviewed independent-capture evidence. No files were moved, copied to cloud, or deleted by this benchmark.
