# ATLAS Declutter Studio — first native implementation

## Goal

Review the biggest space consumers inside **one exact physical folder**
without sacrificing the gallery's read-only browsing model.

1. In Native Photos, select a physical folder using the album chips.
2. Choose **Declutter**. ATLAS makes a frozen, size-descending snapshot
   of only that exact `MediaRecord.relativePath` folder; subdirectories
   are not implicitly included. Videos and images are mixed by bytes.
3. Swipe **left** or tap **Stage Trash** to put the selected file into a
   temporary in-memory review queue. Swipe **right** or tap **Keep** to
   move past it without modifying anything. Undo reverses the last
   choice. The counter shows reviewed count, staged count and approximate
   recoverable space using recorded MediaStore sizes. **Rapid video
   swipes display an existing poster thumbnail; Media3 ExoPlayer starts
   only after the video has remained selected for 180 ms.** A skipped
   video does not need a new player. The settled video remains playable
   with the normal media controls.
4. Open the review list and unstage individual files; photos/videos
   appear with thumbnails and byte sizes.
5. Confirm the final action in ATLAS. Before Android's prompt,
   ATLAS **re-queries all staged MediaStore rows on a background thread**
   to check the exact ID, name, physical folder, size, nonzero
   DATE_MODIFIED timestamp and not-already-trashed status against the
   review snapshot. It fails closed on missing permissions, moved or
   altered files, missing timestamps, duplicate IDs and Android 10.
   If anything differs, the user must exit and start a fresh review.
   Nothing is deleted during this check.
6. Then approve Android's *separate* `MediaStore.createTrashRequest`
   system dialog. Only then does Android mark those specific media items
   as trashed. This is **recoverable Android Trash**, not a permanent
   delete call. Canceling either confirmation leaves the files untouched.
7. On OS success, leave the frozen deck and rebuild the MediaStore
   catalogue; on cancellation, retain the review queue.

## Guards

- No filesystem deletion, permanent `ContentResolver.delete`, raw file
  access, root/Shizuku, new all-files permission or background cleanup.
- No filename/size-based matching for destructive actions: only the exact
  content URI returned by MediaStore can be approved.
- An OS trash request uses 1..200 staged distinct URIs in this MVP. Users
  must split larger trash queues into smaller review sessions. This is
  a conservative prompt/batch cap, **not** a claim about the Android API.
- Android 10 cannot use `createTrashRequest`; review/keep still work, but
  final trash is disabled with clear feedback.
- Reviewer state is **in-memory only**. An Android process restart or
  leaving the session discards pending choices, not photo originals.
- The OS may refuse some images due to scoped/partial permissions, or
  items may have been removed since the snapshot. Fail without deleting
  anything directly; the OS remains the authority.
- Preflight is **metadata-based**, not a cryptographic proof that pixels
  are unchanged: Android DATE_MODIFIED has one-second granularity, and
  external applications could conceivably preserve size and timestamp
  while replacing contents. The conservative OS confirmation remains
  necessary, and stronger content-integrity checks require an explicit
  throughput/CPU/privacy budget before ever shipping.
- While verifying a pending batch, the review screen disables swipes,
  changes to the staged queue and confirmation controls. If the user
  exits or switches sessions before a background check finishes, ATLAS
  refuses to launch a stale system Trash prompt.
- "Space potentially recovered" is a *metadata estimate*, not a verified
  post-trash free-space increase; Android may retain Trash items until
  its retention policy clears them.

## Performance and acceptance

Pure JVM synthetic cases cover mixed images/video, duplicate URI rejection,
strict folder scope, safe undo/unmark, and a 150,000-item catalogue. Compose
Android tests cover staging, undo, review, zero immediate actions, cancel,
and explicit final confirmation *without requesting actual OS Trash*.

Future physical S24+ acceptance: cold/warm 100-file swiping, video player
lifecycle and thermal/heap checks, temporary grant expiry, non-MediaStore
items, 200-item boundary, native Back behavior, and actual OS Trash
confirmation **only on disposable test media after the owner approves a
signing-safe APK upgrade and backup**.

## Future enhancements

- Review-queue save/restore as a carefully scoped encrypted private draft
  with explicit expiry (not automatic deletion).
- Smart shortlist by estimated recoverable MB vs media quality, burst
  similarity and trustworthy duplicate-family evidence; never auto-delete.
- Non-destructive image comparison and best-of-burst suggestions.
- "Revisit later" queue and batch-aware content prefetch, including
  video decoding and S24+ input-frame trace benchmarks.
