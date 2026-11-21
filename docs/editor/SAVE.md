# EDIT-3: Save, Save as copy and Revert

Unit [#633](https://github.com/LPStruiksma/atlas-gallery/issues/633), goal [#629](https://github.com/LPStruiksma/atlas-gallery/issues/629). Code: `app/src/main/java/nl/atlas/gallery/featureeditor/save/`. This is the editor's **one** save path: native edits and, once EDIT-5 moves them here, AI Edit, Face Swap and Erase results (`AI_TOOLS.md`).

## Input

`EditedImageSource` is the only thing the save path takes from the renderer: the full-resolution base `Bitmap` with every step applied (orientation, crop, colour, overlays), plus for Ultra HDR sources a `Gainmap` that went through the **same** geometry (`EditedImageSource.transformGainmap`, built on `RenderPlan.gainmap`). EDIT-2's export supplies it; tests build it from synthetic bitmaps with `warpThroughPlan`. The recipe is saved next to it.

## Save (in place)

1. Write access. Android asks the user before an app changes a photo another app created. If Atlas cannot write the item, `save` returns `NeedsWriteConsent(intentSender)` (Android 11+: `MediaStore.createWriteRequest`; Android 10: the `RecoverableSecurityException` action) and writes nothing. The editor launches it and calls Save again. No permission or manifest change.
2. Encode to the source's own format (below), with metadata, into memory.
3. Journal in `noBackupFilesDir/editor-save/<sha256(uri)>/`: `original.bin` (the bytes from before the **first** Atlas Save), then `pending.bin` and the recipe, `state=writing`. If the photo still holds exactly what Atlas last saved, the earlier `original.bin` is kept, so Revert always reaches the true original. If it changed outside Atlas, the current bytes become the new original and the old record is dropped.
4. Truncate-write the MediaStore item and read it back; the bytes must match.
5. Commit: the recipe becomes current, `pending.bin` is deleted.

Process death between 3 and 5 is recovered on the next `save`, `revert` or `recover()`: a write that never started is rolled back, a partial or finished write is completed from `pending.bin`.

### Location redaction

Android redacts GPS EXIF when an app without ACCESS_MEDIA_LOCATION opens another app's photo read-only. Atlas does not request that permission (no permission change in this unit), so every read of the photo being saved over or reverted goes through a writable descriptor, which MediaProvider never redacts: the stored original, the hashes and Revert match the file on disk byte for byte. **Limit:** Save as copy of a photo another app created has no write access, so its source read may be redacted and the copy can lose its location. Keeping it needs ACCESS_MEDIA_LOCATION, an owner-gated permission change for a later unit.

## Save as copy

Inserts a new pending item in the same volume and `RELATIVE_PATH`, named with a random `atlas-edit-pending-<uuid>` token, writes and verifies the bytes, then renames to `<stem>_edited.<ext>` (then `_edited_2`, ...) and publishes in one update. `DATE_TAKEN` is copied so the copy sorts next to the source. A failure deletes only that pending row; recovery deletes only this app's still-pending token-named rows. The source is never opened for writing. The copy's record holds the recipe, the source URI and both hashes, not the source bytes.

## Revert

Only for an in-place Save. If the photo still holds what Atlas saved, Revert writes `original.bin` back (after the same consent check), reads it back, and deletes the record and recipe: the photo is byte-for-byte the original. If the photo changed outside Atlas since, `revert` returns `ChangedOutside` and needs `force = true`. A copy has nothing to revert (`NothingToRevert`); delete it instead.

## Reopen

`reopen(uri)` returns the recipe when the photo still holds exactly what Atlas saved: for a Save with `originalFile` (decode this, the recipe applies to the original), for a copy with `baseUri` (the source, if it is unchanged). Otherwise null, and the editor starts a new edit. History then starts from that recipe, so every step can be undone (`ENGINE.md`).

## Formats (EDIT-0 media rows, `PARITY.md`)

| Source | Save | Save as copy |
| --- | --- | --- |
| JPEG | JPEG, quality 95 | JPEG |
| PNG | lossless PNG | PNG |
| WebP | lossless WebP (Android 11+) | WebP; JPEG before Android 11 |
| HEIF/HEIC, AVIF, other | `CopyRequired`: never transcoded in place | JPEG |

Android has no HEIF encoder in `Bitmap.compress`, and a new Gradle dependency is out of scope, so HEIF is never silently re-encoded: the editor offers "Save as copy (JPEG)" and says so. A HEIF encoder can be added later as its own unit.

## Metadata

- **EXIF:** every tag the platform `ExifInterface` reads is carried over, except those describing the old pixel layout (orientation, dimensions, thumbnail, strips, subject area) and the maker note, whose internal offsets break when moved. Orientation is written as normal because rotation is in the pixels; pixel dimensions are the new size. **Location as today:** GPS is kept (`keepLocation = true`), like any other edit of the user's own photo; the AI Edit path keeps its own stricter default (`docs/ai-edit/SAVE_POLICY.md`). PNG/WebP EXIF is written from Android 11.
- **XMP:** the source packet is carried over without the parts that describe the old file layout (`hdrgm:`, the GContainer directory, motion-photo flags and offsets, extended-XMP pointer); titles, ratings, keywords and other descriptive fields stay. For Ultra HDR output it is merged into the encoder's own packet; it is never placed where it would move the MPF offsets of the gainmap (then it is left out and `xmpCarried = false`).
- **Ultra HDR:** the gainmap is re-encoded with the base (`Bitmap.compress` on Android 14+). Colour steps change the SDR base only, so the HDR boost per pixel stays as captured. PNG/WebP cannot hold a gainmap: `HdrOutcome.DROPPED_FORMAT`.
- **Motion photos** (Google Motion Photo format and legacy MicroVideo): geometry-only recipes (rotate, flip, straighten, perspective, crop) keep the embedded clip, re-attached unchanged with a fresh descriptor (`ClipOutcome.KEPT`); the clip itself is not cropped. Any colour, filter, overlay or patch step drops it (`DROPPED_EDITED`), because the still would no longer match the clip. A Samsung-only trailer or a clip this code cannot place safely is dropped (`DROPPED_UNSUPPORTED`). EDIT-4 must tell the user before a Save drops a clip (`PARITY.md` state matrix: motion-clip warning).

## Cache refresh

`save`/`revert` clear the viewer metadata cache and call `onRefresh` and `onSaved`/`onReverted` on the main thread. The viewer passes the URI to `EditorReturnState.savedInPlace(uri)`, which gives only that photo a new `editedPhotoMemoryKey` revision, exactly like a return from the system editor. A copy is a new item: the gallery refresh shows it.

## Proof and limits

JVM: `JpegMetadataTest`, `EditorSaveStoreTest`, `EditorReturnStateTest`. Emulator (`EditorSaverAndroidTest`, synthetic only): in-place Save with EXIF, XMP and byte-identical Revert after two Saves; Save as copy naming, folder and untouched source; HEIF `CopyRequired` and JPEG copy from a generated HEIF asset; Ultra HDR gainmap present and aligned with the base after rotate and crop; motion clip kept on rotate and dropped on a colour edit; a failed Save leaving no trace; journal roll-forward after a partial write; a shell-owned photo returning `NeedsWriteConsent` with nothing written (checked by the on-disk SHA-256, since Atlas's own read of it is redacted).

Not tested here: the consent dialog itself (system UI), how other apps display carried XMP or a kept clip (the test clip is a synthetic MP4 header, not playable video), Samsung motion-photo trailers, and real device HEIF/HDR captures (S24+ check in `BUDGETS.md`).
