# PERF-3: viewer open latency (#124, goal #119)

**Target:** tap to the first visible image in the viewer at ≤ 150 ms p50, with full resolution following without a visible jump.

## What was slow

The viewer page asked for its own 800 px preview. The grid tile the user just tapped was already in Coil's memory cache, but under a different presentation key (its own request size), so it was never used. The first frame therefore waited for a fresh 800 px decode of the full photo. Until then the page was black. The 2400 px base started only after a further 110 ms settle delay. That delay is meant to protect scrubbing and swipes, but the page the viewer opens on has had no motion yet.

## Change

- `ViewerPhotoPreview` (GalleryViewer.kt) gives the 800 px request a `placeholderMemoryCacheKey`: the sharpest smaller copy of the same photo already in memory. That copy is usually the grid tile, a filmstrip, People or search thumbnail, or the shared-transition copy. The copy appears on the first frame and is replaced in the same `ContentScale.Fit` geometry by the 800 px preview and then the 2400 px base.
- `viewerPlaceholderKey` (ViewerInstantPreview.kt) borrows only exact presentation keys. They must match on URI, provider version, generation, geometry, publication epoch and editor revision, and may differ in request size alone. An edited, re-saved, rotated or re-authorized photo never shows an old bitmap, and a photo with no known revision borrows nothing. The search builds at most 32 keys and stops at the first hit. An editor return (`forceFresh`) never borrows.
- The opening page starts its 2400 px base at once. Every later page keeps the 110 ms settle.
- PERF-32's tile path is unchanged. Tiles still start from the 2400 px base after the first zoom.

## Proof

- JVM: `ViewerCacheKeyReuseTest` has 7 tests. They cover reuse of the exact grid key; that every `gridPreviewRequestPixels` size (cells 1 to 2000 px) is a candidate; that the sharpest copy wins; that no copy is borrowed across revision, version, geometry, epoch or edit; that unknown revisions borrow nothing; that the search is bounded; and the delay rule.
- Emulator: `ViewerOpenLatencyTest` opens a generated 12 MP JPEG from its warmed 352 px grid tile, 5 times per arm, without the change (arm 0) and with it (arm 1). It logs tap→first image and tap→sharp p50 to `ATLAS_SCALE`, and asserts first image ≤ 150 ms p50 with the change. The UI evidence `viewer-open-first-frame.png` is the first idle frame with the change.

Synthetic pixels only. The emulator numbers do not stand in for S24+ numbers.
