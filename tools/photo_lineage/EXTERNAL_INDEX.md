# Bounded-memory posting index — research prototype

**Lane B / issue #83.** This is an *isolated immutable snapshot builder*, not an Android service, photo-library scanner, replacement for the resumable SQLite cache, or authority to delete/merge photographs. The builder reads a stream of **numeric** (photo_id, visual-word iterable) records only. It cannot see photos, EXIF, names, people, cloud services or camera libraries. It writes temporary private posting runs *only* inside the output directory and publishes a new immutable-format .u64 file, never overwriting an existing file.

## Why another index?

The existing packed-index benchmark demonstrated that compact uint64 sorted postings can preserve the 96-keypoint × 4-band ORB feature budget while saving space compared with SQLite. However, the original builder builds a giant Python list of every posting before sorting. At approximately 383 postings/photo in our procedural corpus, 150k photos could require ~57.5 million postings. The on-disk 8-byte pairs would occupy roughly 439 MiB **before metadata**, but the Python list of integers would consume far more RAM.

The new external_index.build_snapshot instead takes one document at a time, sorts at most chunk_records postings, writes temporary **little-endian unsigned 64-bit** sorted runs, and merges no more than fan_in runs at a time. Duplicate (visual_word, photo_id) records are eliminated across run boundaries. Encoding is identical to the existing packed-index proof of concept: (26_bit_visual_word shifted left 32) OR 32_bit_photo_id. Photo IDs must increase strictly; words must be 26-bit integers.

The private output is published after complete writing, fsync, alignment and SHA-256 measurement, using an **atomic hard link that refuses an existing output**. Exceptions clean scratch files under normal Python unwinding. Abnormal termination can leave a .atlas-postings-* scratch directory; cleanup must be **manual, reviewed, and restricted to this research directory**. Output is mode 0600 on POSIX; the numeric descriptors and photo IDs are potentially linkable and should remain private. The caller must supply an output path outside all original media trees and preserve authoritative photo metadata separately.

**Boundaries:** no on-device incremental append/delete, no photo metadata table, no crash-safe snapshot manifest/delta compaction, no verified disk quota, no mobile thermal/battery budget, no automatic old-snapshot cleanup, no defense against malicious replacement of ancestor directories. Conservative default input budget is 500,000 postings. Raising max_postings can consume substantial **disk scratch space** (up to roughly twice output size). Only use a disposable sandbox with adequate free space. This builder is not wired to the gallery CLI.

## Reproducible synthetic benchmark

From repository root:

    python -W error::ResourceWarning -m unittest discover -s tools/photo_lineage/tests -p 'test_external_index.py' -v
    python -m tools.photo_lineage.benchmark_external_index --images 1200 --words-per-image 384

**Local Linux CPU, October 16, 2026**, 1,200 synthetic *numeric* documents with 384 generated words each, **not photographs or ORB extraction**:

| Measurement | Result |
|---|---:|
| Records / packed index | 460,800 postings / **3.5156 MiB** |
| Initial sorted runs / fan-in | 57 / 16 |
| External merge rounds | 2 |
| Build-only time | **0.638 seconds** |
| Peak process resident memory | approximately 99 MiB, including Python interpreter/imports |
| New regression tests | **10/10 passed** |

These are highly regular numeric values, not varied real photos. The benchmark excludes feature extraction, JPEG decoding, paths/metadata, persistence of indexes over app upgrades, source validation, Android power/thermal and full pair verification; these results cannot be projected as a mobile performance guarantee. The existing 1,208-photo ORB extraction and packed-query experiment is documented separately in SCALING_EVIDENCE.md.

Regressions cover read-only integrity verification (independently pinned SHA-256, sorted/unique records, byte alignment, size bounds, symlinks and tampering), NumPy mmap compatibility, multiple bounded-fan-in merge passes, byte-for-byte equality to the sorted reference, duplicate removal across chunks, fail-closed refusal to overwrite existing data, cleanup on invalid input, strict numeric limits and a one-pass generator. Passing these tests establishes only the format/construction behavior — *not* same-exposure accuracy or safe automatic deletion.

## Next bottleneck

Before product integration: design a private versioned manifest linking immutable snapshots to bounded append-only deltas, validate atomic generation switches, and provide stale-path invalidation, quota management, cancellation and resume without touching originals. Then benchmark on opt-in synthetic-only Android data and verify actual independently captured same-scene photos under file-specific reuse rights. All image family relationships remain human-reviewed candidates.


## Larger numeric-only bounded-memory stress run

The same code was exercised at **10,000 entirely synthetic numeric documents**, not photographs:

    python -m tools.photo_lineage.benchmark_external_index --images 10000 --words-per-image 384

Measured on the same Linux CPU, independent process: **3,840,000 postings**, **29.2969 MiB** final sorted stream, **469 initial runs**, **3 bounded merge passes**, **6.185 seconds build** (excludes image decoding/ORB extraction), **~98,572 KiB peak RSS** from external `/usr/bin/time`. The 1,200-document run measured ~99,472 KiB peak RSS. This supports bounded-memory **posting construction** over the tested sizes; it is **not** a demonstration that Android can index 150,000 photographs, because native ORB extraction, file manifests, memory-mapped retrieval, OS caches, free space and thermal throttling are not included. Temporary scratch can consume significantly more storage than the final file, so the builder still requires caller-approved disk budget and its explicit posting cap.


## Fail-closed read-only snapshot verification

Before any hypothetical future query, call the independent `verify_snapshot(path, expected_sha256, max_bytes=...)` function. Its SHA-256 **must come from a separately trusted manifest or pinned release record**; computing the expected digest from the same possibly compromised file is not evidence of provenance. The verifier streams the .u64 contents without changing the file and rejects symlinks, size-budget overruns, misaligned/truncated records, unsorted or duplicate entries, out-of-range visual words, digest disagreement, and observable file changes during the read. It returns only aggregate counts and digest, no photo paths. This is **not** yet a crash-safe generation manifest or a full adversarial filesystem race defense. Three new synthetic tests plus seven earlier tests pass (**10/10**).


## Repository integration check (2026-10-17)

The Photo DNA synthetic safety workflow is now active in `.github/workflows/photo-lineage-safety.yml`. Exact-head GitHub unit-test and `ci_gate.py` success, along with clean PR mergeability, is required before this compact-index research is integrated. This is not Android-device validation, and it does not grant permission to enable index activation or run the builder on private media.
