# Immutable Photo DNA delta chain — research only

**Lane B / issue #83.** This module is a bounded prototype for recording **numeric cache changes** while preserving an already verified compact snapshot and all older generations. No personal photographs, photo paths, biometrics, Android APKs, live gallery state or remote services are accessed. It neither deletes nor automatically merges any photograph.

## Model

After a reviewer-approved external source change, callers may produce a new immutable `.delta.json` segment containing a complete replacement of an indexed numeric photo ID's projected ORB word set, or `null` for a tombstone. The first segment binds to a **separately trusted SHA-256 of the base snapshot receipt**. The second binds to the SHA-256 of the first, and so on, with strictly increasing sequence numbers. No mutable `current generation` pointer is written.

The segment is canonical ASCII JSON with the exact fields `schema`, `format`, `sequence`, `parent_sha256`, and `updates`. Each update is a unique, increasing uint32 photo ID paired with either `null` or a sorted unique array of 26-bit visual words. Unknown future fields, duplicate JSON keys, noncanonical encodings and invalid numerics are refused. Numeric descriptors are private metadata and should **not** be uploaded or included in issue reports.

Bounded limits: at most **128 changed photo IDs**, **8192 words across the segment**, 4096 words per single photo, **1 MiB per serialized segment**, and **32 linked segments per verification**. If these limits are exceeded, plan compaction against a new immutable snapshot; **do not silently truncate any delta**.

## Safe publication and replay

```python
from pathlib import Path
from tools.photo_lineage.immutable_delta import publish_delta, read_delta_chain
from tools.photo_lineage.delta_overlay import shortlist_with_updates

cache = Path("/separate/private/synthetic-cache")  # preexisting, private; not image root
trusted_base_receipt_sha256 = "a" * 64  # EXAMPLE ONLY: real digest must be externally verified

first = publish_delta(
    cache / "segment-1.delta.json",
    {7: [11, 12], 9: None},
    parent_sha256=trusted_base_receipt_sha256,
    sequence=1,
)
second = publish_delta(
    cache / "segment-2.delta.json",
    {7: None, 10: [11, 12]},
    parent_sha256=first["segment_sha256"],
    sequence=2,
)

verified = read_delta_chain([
    (cache / "segment-1.delta.json", first["segment_sha256"]),
    (cache / "segment-2.delta.json", second["segment_sha256"]),
], base_receipt_sha256=trusted_base_receipt_sha256)
# The numeric overlay may now be passed to shortlist_with_updates(
#     already_verified_readonly_packed_snapshot, query_words, verified["updates"])
```

The publisher serializes and validates its bounded payload before file creation, writes a mode-0600 temporary file in the same cache folder, fsyncs its bytes and uses a **no-overwrite hard link** to publish. It never mutates a pre-existing delta, snapshot or receipt. After a normal exception it cleans only its own temporary file. A sudden process kill can leave a scratch file that requires **reviewed** cleanup; power-loss durability depends on the filesystem.

The reader first authenticates each complete bounded file with a supplied **independently pinned SHA-256**, verifies canonical encoding and explicit parent/sequence continuity, then combines full replacements and tombstones newest-wins with the already merged `delta_overlay` semantics. A damaged, missing, substituted or reordered segment **fails the entire chain** rather than silently ignoring changes. The result remains an in-memory overlay; this code never switches an active index generation.

**Trust limitation:** a caller who omits the last segment from the list can create an internally valid but *stale* prefix. Therefore the number and exact per-segment SHA-256 pins must come from a separately trusted authoritative catalog, not a self-asserted directory listing. Neither this module nor a locally calculated checksum authenticates an actual camera exposure or copyright provenance.

## Verification

From repository root:

```bash
python -W error::ResourceWarning -m unittest discover -s tools/photo_lineage/tests -p 'test_immutable_delta.py' -v
python -m unittest discover -s tools/photo_lineage/tests -v
python -m tools.photo_lineage.ci_gate
```

Nine focused synthetic test methods cover immutable round trips, chain recovery and exact shortlist replacement/tombstone behavior, wrong parent or sequence, file tampering, ambiguous/unsorted JSON, unsafe IDs and words, existing/symlink output preservation, oversized segment refusal, and concurrent publication. Tests use tiny disposable numeric indexes, never personal photos or a real phone.

## Next bottleneck

An independently verified **active-generation catalog** must identify the base receipt, exact linked segment roster, retained fallback and storage quotas. A robust implementation must withstand a torn catalog update, replay attempts and full disk without exposing a half-built generation or deleting the last known-good state. This requires a separate protocol and tests. Real independent-exposure photographic calibration and Android CPU/RAM/thermal acceptance remain open; none of these deltas authorize grouping or deletion.
