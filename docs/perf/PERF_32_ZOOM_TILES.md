# PERF-32: full-resolution zoom tiles in the photo viewer (#334, inventory A13)

**Before.** The viewer zoomed one Coil decode capped at 2400 px through `graphicsLayer`, up to 5x. A 12 to 200 MP photo went soft as soon as the base was magnified, and `planViewerTiles` (PERF-28) had no production caller.

**Change.**
- The 2400 px decode stays as the base layer, so the first frame, swipes and flings are unchanged.
- On the first zoom of a focused, settled photo, `ViewerRegionSource` opens up to two `BitmapRegionDecoder`s off the main thread. Each has its own file descriptor. It reads EXIF orientation with the framework `ExifInterface`, falling back to MediaStore rotation. It refuses the source (base only) when the format has no region decoder (GIF, some DNG), the file is unreadable, or the oriented size disagrees with the base decode.
- Once the base is magnified past 1.5 screen px per base px (`viewerTilesWanted`), each pose change plans with the previous plan reused (`viewerTilePlanForPose`). Requests go through `viewerTileRequests`, and `viewerTileWork` cancels decodes that left the view and never starts a tile twice. Decodes run on `Dispatchers.IO.limitedParallelism(2)`. Rotated or mirrored tiles are turned upright with the same transform as `viewerTilePixelTransform`.
- Tiles publish only through `ViewerTileResidentCache`, keyed to a per-layer epoch, so nothing survives a page change or dispose. The cap is one eighth of the heap limit, between 4 and 32 MB, next to PERF-2's thumbnail cap (#147). The plan budget is never above it, so a full viewport always fits.
- Tiles draw inside the photo's zoom layer at float `ContentScale.Fit` positions (no rounding seams) and fade in over 150 ms over the base, which stays underneath.
- With a tiled source the pinch limit rises from 5x to two screen px per source px, capped at 16x (about 15x for 50 MP on a 1080 px phone). Without one it stays 5x.
- Evicted tiles are dropped, not recycled. A recorded frame may still hold the Bitmap, and the GC frees its native pixels.

**Proof.**
- `ViewerTileZoomTest` (JVM) covers: thresholds; the zoom limit; the pose-to-plan mapping equal to the planner, with every on-screen photo pixel in exactly one drawn tile for all 8 EXIF orientations and 25 poses; a 60-frame fling that cancels stale decodes and never starts a tile twice, then completes the viewport; the cache bound across heap sizes with a whole viewport always resident; and the pixel transform against the geometry for all 8 orientations.
- `ViewerFullResZoomTest` (emulator, `next:review`) uses a generated 8160 x 6120 checkerboard PNG, written row by row with no full-frame bitmap. It zooms to 8x with the base only and then with tiles, and compares centre sharpness. It asserts zero main-thread decodes, records a real-time 12x pan frame trace and heap peak for both arms, and logs to `ATLAS_SCALE` as "PERF-32 ...". Screenshots: `viewer-zoom-8x-base`, `viewer-zoom-8x-tiles`.

**Limits.**
- HEIC goes through the same path. Its orientation is checked only by the aspect guard, so a 180 degree container rotation that EXIF does not report would not be caught. HEIC is not device-verified here.
- In the Compose test harness, tile scheduling advances on the test clock, so the real-time pan trace measures UI-thread frames, not decode completion.
