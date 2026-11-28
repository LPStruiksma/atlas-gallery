# AIE-4: accepted image save, EXIF and local lineage

**Scope:** `featureaiedit/save` in `app/src`; no provider calls, credentials,
new permissions, or edits to the source. Called only after a user chooses Accept.
The ordinary Samsung/system Edit handoff is not changed.

## Contract

- `AiEditSaver.save(candidate, onRefresh, onFocus)` takes a selected
  `content://media/...` original and a separate candidate `content:` URI.
  It verifies the original's image MIME, folder and actual volume.
- The accepted bitmap is decoded at full output resolution, with orientation
  applied to pixels, then encoded into a **new** MediaStore image. JPEG uses
  quality 100, PNG and Android 11+ WebP are lossless. Encoding JPEG a second
  time can incur a small quality loss; a future lossless JPEG metadata path
  should be benchmarked before replacing the safe re-encode.
- Use the original's mounted volume and `RELATIVE_PATH`; choose the first
  available `<source-stem>_AI_<n>.<ext>` name in that folder. Preserve the
  original `DATE_TAKEN` when known so timeline presentation places related
  photos near one another. A same-folder race with another process may still
  cause provider renaming; inspect `displayName` when UI is integrated.
- Before insertion, write a durable journal intent containing a random
  `atlas-ai-pending-<UUID>.<ext>` temporary filename, not the collision-prone
  final `_AI_n` filename. Insert with `IS_PENDING=1`, record the exact row
  URI, write pixels/allowlisted EXIF and lineage, and atomically rename to
  `<source-stem>_AI_<n>.<ext>` while publishing `IS_PENDING=0`.
  An exception before publication cleans up only its identified pending row;
  it never deletes/rewrites an original or an unrelated pending image.
- `AiEditLineageStore` uses an app-private **no-backup** SQLite database. It
  records saved URI, original URI and revision, provider/model/version, prompt,
  settings, timestamp and candidate parent. No credential or endpoint field.
  Before MediaStore insert, the journal records volume/path and the
  token-bearing temporary name. If the process dies **after the insert but
  before `recordInserted`**, recovery may reclaim only the exact token-named
  app-owned row; a same-folder final-name collision cannot match it. Once
  `recordInserted` commits, recovery uses the exact URI, including after
  the final-name rename. Published rows remain with lineage. Legacy journals
  with only a non-unique final name and no URI fail closed: recovery drops the
  unbound intent rather than risking deletion of unrelated media.
- Identity Studio uses the same saver, pending-row journal and publish
  transaction. Its additive, nullable schema-version-1 lineage block stores
  only `face`/`head`, engine id/version, execution route, opaque profile and
  variant ids, candidate index/seed and an optional refinement parent. Older
  rows read with a null identity block. Deleting a profile does not rewrite
  saved lineage; clients resolve a missing opaque id as **deleted profile**.
  Profile display names, reference photos, face geometry/templates,
  embeddings and credentials are never accepted by this block.
- `onRefresh` and `onFocus(newUri)` run on the main dispatcher after
  publishing. The `saveForGallery` adapter calls existing
  `GalleryViewModel.refresh()`, and clears viewer metadata cache. AI Edit
  navigation must **hold the new URI as a pending focus target** until the
  refreshed gallery snapshot contains it; the original URI is returned as
  the anchor. The AIE UI unit wires those callbacks to its viewer state.

## EXIF and uploads

Image pixels are re-encoded to prevent accidental retention of the original's
or a provider candidate's hidden metadata. On the saved copy:

| Field | Policy |
| --- | --- |
| DateTime / DateTimeOriginal / DateTimeDigitized | Copied from original if present |
| Orientation | Set to normal (orientation is applied to pixels) |
| GPS latitude/longitude/altitude and references | **Discarded by default**; opt-in `keepGps=true` copies only these from the original |
| Software | `ATLAS AI Edit` (all three output types; #458 readback test) |
| ImageDescription | Bounded `provider/model` identifiers; identity output uses `ATLAS AI identity edit` |
| Camera serials, MakerNote, UserComment, other personal EXIF | Not copied |
| Prompt/settings/source lineage | Private SQLite only, not embedded or uploaded |
| Authentication tokens | Never accepted by this API, never written |

## AI label on saved copies (AIE-26, #458)

A saved AI edit must say so after it leaves the phone, without a watermark in
the pixels and without a new Gradle dependency. Decision, with sources:

| Field | Where | Decision |
| --- | --- | --- |
| IPTC Digital Source Type `compositeWithTrainedAlgorithmicMedia` | XMP `Iptc4xmpExt:DigitalSourceType` | **Written** on every saved AI edit, face/head identity edits included. The [IPTC Photo Metadata Standard](https://www.iptc.org/std/photometadata/specification/IPTC-PhotoMetadata#digital-source-type) defines the property; the [IPTC Digital Source Type vocabulary](https://cv.iptc.org/newscodes/digitalsourcetype/) defines this term as trained-algorithm (generative) content composited with other media, such as inpainting or outpainting, which matches an edit of an existing photo. `trainedAlgorithmicMedia` (wholly generated) would overstate it. |
| XMP `xmp:CreatorTool` | XMP | **Written**: `ATLAS AI Edit`. |
| EXIF `Software` / `ImageDescription` | EXIF | Unchanged from AIE-4 (above). EXIF has no standard "AI edited" field, so these are hints only and the UI never reads them. |
| Prompt, provider settings, source URI, people | none | **Not embedded.** They stay in the private lineage store. |
| C2PA manifest | JUMBF | **Not written; ATLAS claims no C2PA conformance.** The [C2PA specification](https://c2pa.org/specifications/specifications/2.1/specs/C2PA_Specification.html) requires a COSE signature over the claim with an X.509 signing certificate and a JUMBF store. Without a dependency that means hand-written CBOR, COSE and JUMBF, plus a signing key and certificate ATLAS does not have; a self-signed one would validate only as untrusted. Revisit only with a real signing identity and an owner decision. |

**Where the XMP goes** (Adobe XMP Specification Part 3, storage in files,
[XMP Toolkit docs](https://github.com/adobe/XMP-Toolkit-SDK/tree/main/docs)):

| Output | Location | How |
| --- | --- | --- |
| JPEG | APP1 segment `http://ns.adobe.com/xap/1.0/`, after JFIF/Exif | `AiEditXmp` inserts it. Android's own `ExifInterface` reads it back as `TAG_XMP` (instrumented test). |
| PNG | uncompressed `iTXt` chunk, keyword `XML:com.adobe.xmp`, after `IHDR` ([PNG spec](https://www.w3.org/TR/png-3/)) | `AiEditXmp` inserts it with its CRC. |
| WebP (Android 11+, lossless) | `XMP ` chunk at the end, XMP flag set in `VP8X` ([WebP container](https://developers.google.com/speed/webp/docs/riff_container)) | `AiEditXmp` sets the flag; a simple `VP8L` file is first promoted to `VP8X`. |

Android's [`ExifInterface`](https://developer.android.com/reference/android/media/ExifInterface)
writes EXIF to JPEG, PNG and WebP but has no API that puts XMP in these
standard locations (its `TAG_XMP` setter writes TIFF tag 700 inside the EXIF
block, which most readers of JPEG, PNG and WebP do not look at). So the saver
writes EXIF first, then `AiEditXmp.embed` adds the packet with plain byte
operations to the still-pending, app-owned row. A failure there fails the
save and rolls the pending row back like any other save failure. The original
is never opened for writing.

**Proof and limits.** JVM tests (`AiEditXmpTest`) check the byte layout,
CRC, `VP8X` promotion and that JPEG/PNG still decode. The instrumented test
saves JPEG, PNG and WebP from a synthetic original, reopens each from
MediaStore and reads back the packet, the EXIF allowlist and full-size
decoding, and checks the original's bytes are unchanged and unlabelled.
Untested: what third-party apps (Google Photos, Samsung Gallery, social
networks) show for these fields, and whether a share target strips them.
Many upload paths remove metadata, so the label is best effort once a copy
leaves ATLAS.

**In the app** the "Edited with AI" row in viewer Details and the label under
saved-edit tiles in Photo DNA come only from the private lineage row of that
exact, still-readable URI, never from this embedded metadata, a filename,
EXIF text or visual similarity. An original with saved edits is not marked.
If the original is deleted, the edit keeps its label and shows no
"Edited from…" row. Imported files carrying someone else's IPTC field are not
labelled; reading imported metadata would need its own tested unit.

`createSanitizedUploadCopy(original)` returns a JPEG in app cache whose
metadata is **entirely stripped**, including capture dates and GPS. A remote
adapter must only transmit this copy and delete the file in a `finally` block.
It does not initiate network transfer, and does not silently upload originals.

## Session Undo of a published copy (AIE-21, #433)

- `AiEditSaver.undoPublished(result)` deletes **only** the exact Uri that this
  save returned, serialized with saves and recovery under the same mutex.
- Before deleting, `AiEditUndoPolicy` requires: the Uri is not the original;
  it equals the canonical `content://media/<volume>/images/media/<id>` form;
  MediaStore still shows the row as this app's own (`OWNER_PACKAGE_NAME`),
  published (not pending), in the original's folder when that is still
  readable; and the lineage row for that Uri names the same original. Any
  failed check deletes nothing.
- The delete is the app deleting its own row: no consent prompt and no
  broader permission. A `SecurityException` or a row count other than 1 is
  reported as "Couldn't undo. Copy still saved." A row that is already gone
  counts as removed; its lineage row is forgotten.
- Undo is offered only on the Saved slab of the editor session that made the
  copy (the result is kept in memory, never persisted), so it can never act
  after navigation away or after process death. It never reuses pending-save
  recovery, which only reclaims rows that were never published.
- View sets the pending focus target to exactly the saved Uri; Done exits
  without moving focus.

## Test and integration boundaries

Synthetic on-emulator tests exercise untouched original bytes, folder,
published state, capture metadata, default GPS exclusion, local lineage,
explicit failed-insert rollback, interrupted journal recovery and stripped
upload copies. JVM tests cover name collisions and record data. An emulator
job is needed for the real provider/EXIF implementation; JVM-only success is
not proof.

The AI Edit UI and permission-gated user consent flow are separate AIE units:
no AI edit action is added to permanent gallery chrome in AIE-4. Android
MediaStore + a private SQLite DB cannot form a single cross-provider atomic
transaction; the durable journal ensures recovery of interrupted pending rows.
