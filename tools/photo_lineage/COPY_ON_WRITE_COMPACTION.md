# Photo DNA copy-on-write verified index compaction

**Lane B research, 17 October 2026.** This is a numeric-only local cache compactor; **it never reads, changes, uploads or deletes actual photographs**, never rewrites an existing index or receipt, and never changes the Android gallery's active generation. It does not establish that visually related images originated from the same camera exposure.

## Why

The immutable snapshot supports high-throughput candidate retrieval. Incremental immutable delta segments and an externally pinned complete generation catalog safely describe changed or missing numeric photo IDs. But the catalog is limited to 32 bounded segments. To avoid indefinite lookup layers, compaction must combine a verified base with every verified replacement and tombstone into an entirely new immutable sorted posting stream.

A naive implementation could mutate the last good snapshot, reintroduce obsolete feature words, use unbounded RAM to transpose the index, or exhaust storage. This prototype avoids those particular failures.

## Algorithm and authoritative input

The caller supplies a verified source compact .u64 path, its complete generation catalog path, a **separately trusted expected SHA-256 of that catalog**, and a never-used output .u64 basename. All files must share a dedicated protected cache directory outside the photo library.

The compactor:

1. Calls generation_catalog.verify_catalog to validate the pinned catalog, underlying receipt, original index and **complete ordered delta roster**. Missing/tampered segments fail before scratch files are created.
2. Verifies the supplied source .u64 against the exact SHA committed by the catalog. A similarly named but different source index is rejected.
3. Builds a bounded scratch posting file for **only** the final descriptor replacements, using the existing memory-bounded external index builder. Tombstoned and empty replacements contribute no words.
4. Streams the already-sorted original postings while filtering *all* postings belonging to changed IDs, then merges the unchanged old postings with replacement postings using streaming heap merge. Deduplicates safely on write. No per-photo in-memory index of the whole library is constructed.
5. Re-verifies the original source **after** deriving the new stream, then validates sorted/unique form, dimensions and SHA of the completed output. Only then publishes the fully written output through a no-overwrite hard link. Source snapshots, receipts and delta catalog are preserved.

A new receipt/catalog/selection ticket for the compacted index must be published and independently trusted by a separate subsequent step. The compactor **does not activate** its output or garbage-collect old snapshots, even if successful.

## Storage and failure budgets

The caller can cap max_output_bytes (default 1,000,000,000). The preflight reports insufficient free space if there is not enough room for one complete expected output **plus six replacement-posting byte copies**, a fixed 2 MiB reserve/metadata allowance, 4 KiB per expected replacement run and a protected default 64 MiB free-space floor. The nested replacement index builder also performs its own conservative checks, and the final streamed merge receives a fresh check.

These are pessimistic *advisory* filesystem checks, **not actual allocation reservations**. Another process may fill storage between checks; filesystem inode quotas, power loss or ENOSPC can still occur. Failure propagates as an error and does not publish a new index. On normal exception exit, only this operation's own temporary directory is removed. Sudden process death can leave scratch artifacts and needs reviewed, scope-limited cleanup. Never run this directly in an original photo directory or attempt automatic removal of unrecognized files.

## Example (synthetic, not runnable until files are prepared)

    from tools.photo_lineage.compact_index import compact_verified_generation
    report = compact_verified_generation(
        "/private/synthetic-cache/baseline.u64",
        "/private/synthetic-cache/generation.catalog.json",
        "/private/synthetic-cache/compacted-new.u64",
        trusted_catalog_sha256=INDEPENDENTLY_PINNED_CATALOG_SHA256,
    )
    # Independently verify/publish a NEW receipt and catalog after this succeeds.
    # Never delete the older index automatically.

## Synthetic tests and limitations

    python -m unittest discover -s tools/photo_lineage/tests -p test_compact_index.py -v
    python -m unittest discover -s tools/photo_lineage/tests -v
    python -m tools.photo_lineage.ci_gate

Eleven new disposable numeric tests exercise full two-delta parity, total removal, additions and untouched records, existing output preservation, untrusted catalog rejection, missing latest segment, insufficient disk admission, OS ENOSPC during final merge, changed source during compaction, explicit output/reserve budgets and base-only catalog no-op refusal. The GitHub exact-head Photo DNA workflow remains the integration gate.

**Limitations:** both-source-verifications still leave a narrow filesystem TOCTOU window against hostile concurrent process mutation, native app integration is absent, read-only gallery handles and true Android filesystem atomicity are not proven, and no resource/performance numbers from the S24+ are claimed. No real independent-camera exposure benchmark has yet succeeded due unavailable external image bytes. Candidate pairs remain human-review-only, never auto-delete/auto-merge authority.
