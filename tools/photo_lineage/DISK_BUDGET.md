# Photo DNA: conservative scratch-space admission and recovery

**Lane B, 17 October 2026. Research-only numeric index builder.** This gate does not read photographs, inspect a private phone, alter MediaStore, rewrite old index generations, or authorize photo deletion/identity merges. It applies only to the existing `external_index.build_snapshot` entry point.

## Problem

Building an immutable, sorted uint64 posting stream requires temporary disk runs. Even though the builder limits memory, its multi-pass merge can briefly require roughly two complete sets of posting bytes (old source runs *and* newly merged runs). A large library cannot safely treat 8 bytes × posting count as the maximum **peak** disk footprint. A failed or interrupted index update must never damage a previously verified index.

## Implemented failure behavior

`build_snapshot(..., max_postings=N, reserve_free_bytes=64*1024*1024)` now checks the volume's **reported free bytes before creating any scratch directory**. Admission requires at least:

```text
64 MiB default free-space reserve
+ 3 × N × 8 bytes  (three full posting lengths: source, output, safety margin)
+ ceil(N / chunk_records) × 4 KiB  (conservative per-run overhead)
+ 1 MiB fixed overhead
```

Here `N` is the caller's explicit **maximum allowed posting count**, not an estimate of actual images. The requirement is intentionally pessimistic. For example, the default 500,000 posting ceiling and chunk size 8,192 imply approximately **76.7 MiB required free space** on admission (including the 64 MiB reserve). Raising `max_postings` to tens of millions requires a correspondingly large verified free-space budget **before** work starts. Reject with `DiskBudgetError` instead of attempting a risky partial build.

Before writing *each* sorted chunk and each merged run, the builder separately checks the **current** free space against the next run's possible full byte growth plus the protected reserve. This catches some contention/depletion after admission. Failure raises without publishing the output and the existing temporary-directory context normally removes its own scratch artifacts. Existing index snapshots and receipts are never removed or overwritten. Invalid budget types (booleans, floats, negative numbers, values over 1 TiB) are refused.

### Critical caveat: not an OS storage reservation

`shutil.disk_usage(...).free` is advisory, not an exclusive disk allocation. Another app/process can fill the volume **after** any check, or filesystem quotas, inode exhaustion, sparse allocation, journal overhead and hardware faults can still cause ENOSPC/EIO. The builder propagates those errors rather than misreporting a successful publication. Sudden kill/power loss can leave its own scratch directory; a future safe cleanup workflow must treat old unknown files as private data and never broadly delete them. The caller must select a **dedicated protected cache volume outside source photos**, account for the old snapshot and backup generations, and plan periodic chunked work and owner-visible stop/cancel UX.

The `reserve_free_bytes` parameter defaults to **64 MiB**, but is configurable to support bounded synthetic experiments; reducing it on a real phone is not recommended or evidence of operational safety. This is a reliability admission gate, **not** proof of power-loss crash consistency, secure quota reservation, or practical 150,000-photo Android indexing.

## Regression and CI evidence

Run from repository root (pure synthetic numeric entries, no real media):

```bash
python -W error::ResourceWarning -m unittest discover -s tools/photo_lineage/tests -p 'test_disk_headroom.py' -v
python -m unittest discover -s tools/photo_lineage/tests -v
python -m tools.photo_lineage.ci_gate
```

Six new tests inject mocked low free space at **preflight, second chunk, and merge**, simulate an unavoidable OS ENOSPC write error, verify a successful threshold-admitted round trip, and check strict argument validation/preexisting file preservation. All synthetic folders are temporary. They explicitly confirm old output bytes remain untouched and failed builders leave no published generation under normal exception unwinding.

## Next safety steps

Build a bounded, append-only immutable delta *file format* with a checksum-pinned versioned receipt and explicit tombstones, separate from the read-only overlay already merged in PR #157. Activation/rollback must be designed with a protected authoritative generation pointer, a verifiable old-generation fallback and protection against partial disk exhaustion. Do not wire into the gallery or run on private images until independently measured Android resource usage and the licensed same-scene distinct-exposure validation protocol have been completed.
