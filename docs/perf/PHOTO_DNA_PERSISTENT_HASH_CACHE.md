# Photo DNA persistent visual-hash cache (#604)

Photo DNA keeps its successful 64-bit thumbnail dHashes in compact primitive
arrays (250,000-image cap, no larger than 40 B per entry). Since #604 it may
also keep **successful image hashes only** in a small, versioned
`noBackupFilesDir/photo-dna-dhash-v1.bin` file. This is derived, deletable
on-device data, not the original image, a thumbnail, a pair/relationship, or
an authoritative MediaStore inventory. No hash, media key or identity goes to
backup, telemetry, GitHub or a remote service.

## Admission and lifecycle

- The file is **not restored at ordinary app startup**. A small header check
  may purge a file on grant loss/transition; restoration happens only when
  Photo DNA opens and a **fresh real MediaStore scan publication** has completed.
  The cold-start inventory snapshot is never sufficient.
- Both the live Android image grant and the fresh publication grant must
  match. Selected/partial-photo scopes never read or write this file:
  Android permits a selection re-pick without changing permission bits.
- File permission bits and each volume's MediaStore provider version are bound
  to its content. Each successful image row includes volume, item id, media
  kind (image only), existing id/size/mtime/generation revision digest, dHash
  and informative/flat state. Every restored value is matched again to a
  newly authorized MediaRecord and current revision. Absent, changed, edited
  or unreadable rows are recomputed.
- On an observed revoke or changed grant the header check deletes the cache.
  It also runs after a process restart, including when Android killed the app
  before cleanup. Files with absent or mismatched grant/provider data are
  discarded; the app remains functional using its in-memory cache. The cache
  may be deleted without affecting image/library data.
- Format is bounded to at most 250,000 entries, 32 volumes and 8 MiB.
  Writes occur cancellably on IO, through a temporary file replaced
  atomically. Corrupt, truncated, version-mismatched, duplicate or trailing
  bytes are rejected. Failed thumbnail decodes are never stored.

Visual pairs remain **suggestions for Compare photos** only. Nothing in this
cache changes Photo DNA's exact-byte digest or its separate fresh
revision/byte Trash preflight.

## Synthetic verification

`PhotoDnaVisualCacheStoreTest` exercises the cold-process restored cache,
one changed revision, grant narrowing/loss, partial-photo selection, provider
version/volume changes, corruption/truncation and cancellation, plus
250,000-record file and primitive-array bounds. Counts and disk bytes are
reported as `PHOTO_DNA_604`; the restore path records only count and
elapsed milliseconds (`ATLAS_PHOTO_DNA`), never hashes or media identifiers.
The JVM regression uses fake records and values only. Device-specific grant
revocation still requires Android instrumentation to prove the platform
callback and process-death lifecycle.
