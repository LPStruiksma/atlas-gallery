# Verified immutable generation catalog

**Lane B research, 17 October 2026.** This is a *nonactivating*, checksum-pinned index-generation catalog—not an Android production database, not automatic deduplication, and not a deletion or identity decision system. Everything operates on previously staged **numeric** local cache metadata. Original photos, private People databases and running Termux remain untouched.

## Why the catalog matters

PR #163 added immutable delta segments that bind cryptographically to their predecessor. But a chain of two valid segments can look valid even if a caller **accidentally omits the most recent third segment**. This is a freshness/continuity problem, not an ordinary checksum corruption problem.

A generation catalog solves the narrower problem by enumerating the **exact complete roster** of required segments alongside the independently verifiable base-snapshot receipt:

```text
Independently retained trusted catalog SHA-256
       |
       v
Immutable generation.catalog.json
       |--- pinned base snapshot receipt --> verify whole compact snapshot
       |--- segment #1 (pinned SHA, bound to base receipt SHA)
       |--- segment #2 (pinned SHA, bound to #1 SHA)
       +--- ... segment #N (pinned SHA, bound to #N-1 SHA)
```

If **any** listed segment is removed, modified, reordered, replaced or hash-inconsistent, verification fails and **does not return a partial overlay**. A tampered catalog that deletes its final segment also fails as long as the reader holds the original, independently trusted catalog hash.

## API

Use a dedicated protected cache directory, outside any original photo folder, with already verified immutable artifacts. This illustration uses fictional digest values; these must be replaced by actual independently retained pins:

```python
from pathlib import Path
from tools.photo_lineage.generation_catalog import publish_catalog, verify_catalog

root = Path("/private/synthetic-cache")
base_receipt_pin = "a" * 64  # EXAMPLE ONLY, not a real trust anchor
segment_1_pin = "b" * 64
segment_2_pin = "c" * 64

# publish_catalog re-verifies every referenced file before publication.
published = publish_catalog(
    root / "version-2.catalog.json",
    generation="version2",
    base_receipt_name="snapshot.json",
    base_receipt_sha256=base_receipt_pin,
    segments=[
        ("first.delta.json", segment_1_pin),
        ("second.delta.json", segment_2_pin),
    ],
)
# Persist published["catalog_sha256"] in a separately trusted store.
review_state = verify_catalog(
    root / "version-2.catalog.json", published["catalog_sha256"]
)
# Review state is an in-memory numeric overlay only, not an active index switch.
```

A zero-segment catalog is permitted for a pure base snapshot. Otherwise the full ordered roster is required (maximum 32 segments). Catalog data is canonical ASCII JSON with exact schema fields; no duplicate JSON keys, outside-directory paths, repeated filenames, unsafe basenames or unsupported schema versions. The catalog itself is at most 8 KiB, published through an fsynced mode-0600 temporary file and a **no-overwrite hard link**. Old catalogs, receipts, snapshots and segments are never rewritten or deleted.

The reader checks the catalog against its externally pinned SHA first, verifies the full referenced snapshot receipt and underlying binary index, and validates every chained delta in order. Only then does it return a numeric replacement/tombstone overlay. The implementation never creates a mutable `current` pointer, and it does not activate any generation.

## Safety boundaries and limitations

- A catalog digest computed from the same untrusted catalog is **not** an independent trust anchor. A malicious or accidentally stale *catalog+digest pair* can still be presented together; the caller must retain a trusted expected digest outside these cache files. This prototype has no signature or external authorization chain.
- Checking all listed segments establishes completeness **relative to that pinned catalog**, not that the catalog is the newest generation in the world. No process can infer a newer unpublished generation from an old valid catalog.
- File changes can race after verification; an eventual native reader must pin open file descriptors/generation handles or revalidate before use. Filesystem power-loss semantics vary; a sudden kill may leave only its own temporary catalog artifact.
- No generation activation, multi-writer arbitration, deletion or garbage collection of old snapshots, guaranteed disk allocation or Android thermal/battery measurements. The catalog requires coordination with Lane A before native integration.
- A visual-match candidate is never proof of identical original camera exposure; all actual photo-family decisions remain human-reviewed.

## Tests and integration gate

```bash
python -W error::ResourceWarning -m unittest discover -s tools/photo_lineage/tests -p 'test_generation_catalog.py' -v
python -m unittest discover -s tools/photo_lineage/tests -v
python -m tools.photo_lineage.ci_gate
```

Ten new synthetic tests create a real compact numeric snapshot, independently SHA-checked receipt and chained deltas, exercise lookup parity against source numeric postings, detect a missing or altered final segment, refuse a shortened roster with the old pinned catalog digest, reject wrong parents/sequences, detect a modified base receipt, protect existing catalogs and simulate concurrent publication. No photographs, cloud data or phone files are involved.

Before merging, demand **exact-head** success in the dedicated `photo-lineage-safety.yml` workflow and clean GitHub mergeability.

## Next bottleneck

A native, owner-approved integration would need an atomic **generation selection pointer** that switches only from a verified old catalog to a verified new catalog, with independent retention of trust pins, fallback and recovery after torn writes or full disk. That is a separate safety protocol. Genuine independently captured, individually licensed same-scene photographs and on-device performance profiling also remain uncompleted.
