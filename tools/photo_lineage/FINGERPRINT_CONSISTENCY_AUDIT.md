# Opt-in Photo DNA visual-word cache consistency audit

Lane B / issue #83. A cached research SQLite fingerprint is
normally treated as unchanged when file size and mtime match.
A same-length image replacement with restored original mtime
can evade that shortcut, even though the underlying photo changed.

`fingerprint_consistency_audit.audit_fingerprints` is an explicitly
requested **read-only** inspector. On a bounded page (1–64 cached
numeric photo IDs), it checks that each source remains a regular
non-symlink path, requires size+mtime consistency, decodes through
the descriptor-anchored, parent-nofollow reader, checks the source
inode/dev/size/mtime/ctime again, reextracts the unchanged ORB
fingerprint and compares it with at most 385 SQLite stored words.
Results are only **aggregates**: verified, mismatched, metadata
stale/missing, or unreadable/undecodable. No file paths, pixels,
feature words or sensitive metadata appear in report output.

```bash
python -m tools.photo_lineage.fingerprint_consistency_audit \
  --db /path/to/disposable-index.sqlite --page-size 16 --after-id 0
```

`has_more_after_this_page` and `next_after_id` support explicit
pagination without pretending one page verifies the whole cache.
Independent page reads are distinct SQLite snapshots: concurrent
index writes can invalidate a multi-page sweep. For formal evidence
the index must be quiescent, and a complete traversal is required.
Unreadable media never count as verified. No automatic cache
pruning/repair, indexing, original photo deletion or movement.

The test demonstrates a real safety gap with only **fictional BMPs**:
a replacement of equal dimensions and exact byte length with the
previous mtime restored defeats the existing cheap metadata audit,
but the new optional full visual-word check finds the mismatch.
Other tests cover pagination and missing/symlinked sources.

This is NOT cryptographic byte-integrity verification: different
images can share ORB word projections. Fingerprint comparison cannot
establish common shutter exposure, camera provenance, rights or
representative false-match accuracy. An image whose bytes change
without changing visual words remains potentially undetected.
Large-gallery full-byte/descriptor scans need explicit owner resource
budgets and, ultimately, pinned hashes with an immutable manifest.

Only `tools/photo_lineage/**` research files are changed. No native
Android, V4/V5, private gallery, People DB, Termux, source writes,
release signing, secrets, network, model or automatic media operations.
