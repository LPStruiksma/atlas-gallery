# V4 optional face metadata sidecar — post-release contract (21 October 2026)

## Motivation and non-goals

Samsung Gallery-inspired People roadmap #371/#372 calls for measured
face-aware portraits, manual cover continuity, and accurate "only them"
filters. The existing V4→native CSV has **exactly five columns**:
`person_id,person_name,image_path,face_id,confidence`. Neither that schema
nor current confirmed assignments may change to add metadata.

This standalone, **owner-initiated and read-only** command creates a separate
versioned JSONL sidecar. It is **not used by native ATLAS today** and is NOT a
prerequisite for issue #83's first Android release. It requires no Android,
OpenCV, NumPy, face detection, model inference, or phone access. It never
modifies the V4 SQLite database or original photographs. It does not contain
names, feature vectors or model-generated Person suggestions.

## Invocation (only on an explicitly chosen database and NEW destination)

```bash
python3 tools/v4/export_confirmed_face_sidecar.py \
  --db /path/to/existing/people.sqlite3 \
  --output /path/to/new/atlas_v4_face_metadata.jsonl
```

**Independently verify an exported file** before consuming it (no V4 database
needed):

```bash
python3 tools/v4/export_confirmed_face_sidecar.py \
  --verify /path/to/atlas_v4_face_metadata.jsonl
```

The verifier bounds every input line, enforces canonical UTF-8 JSONL and
exact schema fields, rejects invalid known-zero/Unknown status, malformed
crop geometry, out-of-order image/face records, missing/truncated/footer-extra
content, count mismatch, and any SHA-256 body mismatch. A producer can forge
an unkeyed digest; this is structural integrity only, **not authenticated
identity evidence**. Do not display People identity conclusions without the
authoritative five-column CSV and MediaStore/permission verification.

The source opens SQLite with `mode=ro` and `PRAGMA query_only=ON`; export
reads a single transaction snapshot. It streams one cursor of indexed images
and another cursor of eligible assigned faces, both in stable ID order:
**two SELECT statements regardless of image count**, rather than issuing
one face lookup per photo. The outer image cursor still validates the
indexed face-count including unassigned/ignored faces; only active,
non-ignored assignments appear in face metadata. Both streams are consumed
incrementally without materializing all images or face records in Python. The output is created with
exclusive/no-overwrite semantics and owner-only POSIX permissions where
supported. A prior output, including a symlink, is never replaced.
Write failure cleans up **only the newly created file with the same inode**.
A process killed mid-write may leave a partial new file; reject it unless the
footer validates. Never interpret a SHA-256 footer as a signer/authentication
mechanism.

## JSONL v2 contract (legacy v1 remains verifiable)

Each UTF-8 line is one canonical JSON object. Line 1 is a versioned header;
then records in ascending V4 image ID and ascending face ID within each
image; last is a footer with exact counts and a SHA-256 over all preceding
lines including their newline bytes.

- **Header:** `schema: "atlas.v4.face_metadata.v2"`,
  `identity_authority: "unchanged V4 five-column CSV"`, and a
  `crop_coordinates` warning.
- **Image record:** `type: "image"`, `v4_image_id`, `image_path`,
  `scan_state`, `total_faces` (integer or null), `count_status`
  ("verified" or "unknown"). V2 adds `indexed_source` with the V4
  database's indexed `size_bytes`, `mtime_ns`, `width_px` and `height_px`
  (dimensions may be null). These are **untrusted comparison hints**, not
  a MediaStore generation, signed content digest or evidence a file is
  still at that path. Future Lane F must revalidate actual media.
  The **only trusted zero** is a completed
  image whose `num_faces` agrees with all current face rows. Unscanned,
  errored and internally inconsistent records have null total: never
  infer there is no other person from a missing/unknown count.
- **Face record:** only non-ignored assignments to an active Person.
  Carries `face_id`, `person_id`, `image_path`, original
  `review_state` (historical `auto` is **not** called confirmed),
  `manual_cover` and raw V4 stored pixel `box`.
  V2 also adds `normalized_box`, using the same x/y/w/h keys as
  fractions of the V4-indexed decoded image width and height, all in [0,1].
  A missing/invalid crop forces BOTH boxes null. Neither box proves
  Android EXIF orientation or a matching MediaStore object. Invalid, nonfinite,
  negative, zero or out-of-bound boxes are **null** rather than clamped.
  Box dimensions **must** be checked against source EXIF orientation and
  MediaStore before use as an Android crop. No thumbnails or original
  media bytes leave the filesystem.
- **Footer:** `schema`, `sha256`, `image_records`,
  `assigned_face_records`, `unknown_count_images` and
  `invalid_crop_boxes`.

The exporter now emits v2 by default. The independent verifier accepts
both already-published v1 and v2 **strictly by their schema**: v1 does not
magically gain normalized boxes/source provenance; consumers must treat
these absent fields as Unknown, not infer values. Recomputed but internally
inconsistent v2 geometry or impossible indexed dimensions are rejected.

## Safe consumer interface

1. Native integrations must continue to accept the original five-column
   CSV independently; this sidecar is **optional metadata only**. In
   particular, it cannot establish Person identities by path alone.
2. Bind a face box or manual cover only after an exact
   `(image_path, face_id, person_id)` match against the confirmed CSV
   records and independently verified media identity/permissions. Drop
   unmatched, stale, permission-revoked or ambiguous entries to Unknown.
   Do not transfer face IDs between renamed/moved photos by filename
   guesses. Do not assume identical V4 `image_id` equals native MediaStore
   `_ID` (photo/video/volumes can overlap).
3. Never interpret historical `auto` review status as owner-confirmed
   identity without an independent explicit confirmation. `total_faces=1`
   only means **one indexed detection**, not that no unseen person or
   unindexed face exists in that photo.
4. Normalized V4 boxes are derived from the V4-decoded index dimensions,
   not necessarily native/original pixels. Verify actual image orientation,
   source dimensions and permission; reject stale mismatches rather than
   silently stretching/cropping a different photo. `indexed_source`
   hints can exclude obvious mismatches but cannot *prove* identity.
5. Cache keys need the originating media version and dataset generation
   before cropping, with permission-revocation invalidation. Future
   specialist lanes own native cache/UI behavior; Lane D exports only.

## Verification

`python3 tools/v4/test_confirmed_face_sidecar.py` uses the actual V4
schema with fictional people/images/faces and covers. It asserts deterministic
sealed v2 JSONL bytes, strict v1 read compatibility, normalized geometry,
re-sealed invalid source/box refusal, manual cover, Unicode paths,
valid/invalid boxes, true-zero vs Unknown, ignored/archived filtering,
exclusive creation,
failure cleanup and unchanged legacy CSV/source SQLite bytes.
`python3 tools/v4/test_sidecar_streaming.py` additionally generates
2,401 fictional images and 1,201 assigned faces, traces the **actual**
SQLite SELECT statements, asserts one image stream plus one face stream
(and no per-image SELECTs), verifies ordered exact metadata and checks
a second run reproduces identical checksum-sealed bytes. No private
data or physical device accessed.

No S24+ canary, Android API integration, or actual portrait rendering
is claimed by this optional exported interface.
