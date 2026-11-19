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
| Software | `ATLAS AI Edit` |
| ImageDescription | Bounded `provider/model` identifiers; identity output uses `ATLAS AI identity edit` |
| Camera serials, MakerNote, UserComment, other personal EXIF | Not copied |
| Prompt/settings/source lineage | Private SQLite only, not embedded or uploaded |
| Authentication tokens | Never accepted by this API, never written |

`createSanitizedUploadCopy(original)` returns a JPEG in app cache whose
metadata is **entirely stripped**, including capture dates and GPS. A remote
adapter must only transmit this copy and delete the file in a `finally` block.
It does not initiate network transfer, and does not silently upload originals.

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
