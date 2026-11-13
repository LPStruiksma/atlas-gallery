# Read-only incremental posting overlay research

**Lane B / ATLAS Photo DNA. Experimental and nonactivating.** This module does not open a photo, change a MediaStore record, modify the SQLite cache, persist a delta, change a snapshot receipt, upload metadata, or delete/merge images. It works on numeric photo IDs and ORB visual words only.

## Why

The new compact, immutable sorted index is memory-bounded to *build* but expensive to rebuild after a small edit, rename, or externally removed photograph. ATLAS must be able to invalidate obsolete cached descriptors **without ever touching the original media**. A naive union of old and new keypoints would allow an edited photo's obsolete features to keep generating apparent matches.

`delta_overlay.py` is a deliberately small read-side proof of the correct semantics:

- Source: independently verified immutable packed `.u64` postings (for example, a read-only NumPy mmap).
- Delta: a bounded mapping from uint32 photo ID to a **complete replacement set** of 26-bit words, or `None` as an explicit cached-ID tombstone. Empty replacement set is different from tombstone but returns no visual matches.
- Every updated ID is completely excluded from baseline hits before adding new descriptor matches. Newly added IDs can appear without rebuilding the baseline. Multiple in-memory layers can be composed oldest-to-newest; last replacement/tombstone wins.
- Query only scans posting ranges for its visual words using binary search, stable score/photo-ID sorting, a two-shared-word default threshold, and a hard posting-visit budget. Budget overflow raises rather than quietly returning an incomplete shortlist.
- Strict bounds: 4,096 changed IDs/layer or combined, 4,096 distinct replacement words/ID, 512 unique query words, 200,000 visited baseline postings/query, at most 100 returned scores.

## Reproduce

```bash
python -m unittest discover -s tools/photo_lineage/tests -p 'test_delta_overlay.py' -v
python -m unittest discover -s tools/photo_lineage/tests -v
python -m tools.photo_lineage.ci_gate
```

Ten new synthetic tests exercise complete replacement (no stale descriptors), permanent tombstone in that version, newly added IDs, later restoration by an explicit new generation, deduplicated queries, deterministic top-K ties, bounded scan failure, invalid words/IDs, one-shot generator, and exact parity with exhaustive recomputation across 80 procedural numeric photos and 20 heldout-by-generator queries.

**Security limitation:** this module assumes its read-only packed postings have already passed `external_index.verify_snapshot` and that a trusted caller validates/manages its delta inputs. It does not itself parse file paths or cryptographically sign updates. A caller that provides a mutable or unsorted index breaks the binary-search precondition. It does **not** implement durable delta publication, crash-resume, generation activation, permission boundaries, disk quota, concurrent commit arbitration, garbage collection or on-device IO scheduling.

The module is for candidate retrieval only. A shortlist miss is **not proof of absence**; a visual match is **not evidence of identical original shutter exposure**. No automatic image deletion, transitive grouping or unconfirmed people identification is permitted.

## Next required gate

Design a **separately pinned, immutable delta receipt and crash-recoverable bounded append-only generation chain**, then safely resolve it without observing a half-published update. Retain prior verified snapshot and receipt for rollback; implement delta compaction only with quota and copy-on-write guarantees. Add public-camera independently captured scene-heldout metrics before calibrating candidate thresholds. Native Android integration needs Lane A approval and device-specific synthetic performance evidence.
