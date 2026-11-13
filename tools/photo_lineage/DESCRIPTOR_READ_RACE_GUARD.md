# Descriptor-anchored, race-aware photo indexing

Lane B / issue #83. Photo DNA research is local-only and never edits
original photographs. A pre-opening `Path.is_symlink()` check alone
cannot prevent another process from atomically replacing a pathname
with a symlink just before a Pillow decoder opens it.

The grayscale loader now opens an OS read-only descriptor with POSIX
`O_NOFOLLOW` and decodes with Pillow from that **already opened file**.
`fstat` verifies the descriptor refers to an ordinary regular file.
If safe no-follow flags are unavailable on the host, the research
decoder refuses rather than silently falling back to a raceable
pathname open. This is an **OS boundary**, not a filesystem sandbox
or proof against adversaries controlling parent directory mounts.

The SQLite research indexer additionally uses `lstat` before and
after image decoding, checking device/inode/size/mtime/ctime remain
unchanged. If a synthetic photo is atomically replaced or swapped
to a symlink mid-decode, indexing rolls back that image's savepoint
and reports an error; a later explicit pass can index the new stable
source. No source media is rewritten. This cannot catch a file
maliciously rewritten while all metadata fields are restored, and
the existing cache schema still detects *unchanged* indexed files
only by size + mtime. It does not supply cryptographic source
integrity, guaranteed physical shutter provenance, or a durable
production cache invalidation mechanism.

Four synthetic tests exercise symlink substitution at OS-open time
and inode replacement between descriptor open and Pillow decode,
plus atomic normal-file substitution and symlink swap during SQLite
index extraction. They assert no unexpected image decoding, no
metadata/posting contamination, and no source mutation by ATLAS.

Only `tools/photo_lineage/**` is touched. No Android/V4/V5 code,
native APK, People DB, private photo directory, release pipeline,
model, external network access, signing key or deletion is involved.
