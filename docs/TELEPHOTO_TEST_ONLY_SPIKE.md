# Telephoto Coil 3 spike — isolated test-only comparison

**2026-10-14. Research prototype only; not enabled in ATLAS native APK.**

We studied [Saket's Telephoto](https://saket.github.io/telephoto/zoomableimage/),
[its upstream implementation](https://github.com/saket/telephoto) and
[subsampling approach](https://saket.github.io/telephoto/zoomableimage/sub-sampling/).
The available 0.19.0 `zoomable-image-coil3` artifact is Apache-2.0 and
provides pinch and double-tap zoom, controlled panning and automatic tiled
subsampling where supported. This is a stronger starting point than
inventing custom bitmap-region decoders or unbounded canvas transforms.

**Isolation:** the dependency is declared *only* as
`androidTestImplementation("me.saket.telephoto:zoomable-image-coil3:0.19.0")`.
The shipping `GalleryViewer.kt`, main gallery UI, People storage and APK
runtime dependencies are unchanged. The synthetic
`TelephotoPrototypeTest.kt` generates two local JPEGs in emulator app
cache, displays them with `ZoomableAsyncImage`, checks that decoding finishes,
uses a real double-tap gesture to verify zoom state, then switches away
and back and checks that zoom doesn't leak between photos.

These two small synthetic files are **not** proof of multi-gigapixel
subsampling, HEIC, extreme panoramas, HDR, filmstrip gesture arbitration,
video playback or real-S24+ scrolling performance. No user photos, identity
records, Android keystores or device state were accessed. Android emulator
timings, if later collected, do not substitute physical Galaxy results.

## Promotion criteria

1. Keep baseline Compose Pager + LazyRow, Coil 3, and Media3 ExoPlayer.
2. With a signer-safe installation, run repeatable real-device A/B sessions
   for the current viewer versus Telephoto using comparable local media,
   testing cold/warm caches, 4K+/HEIC/huge panoramic photos, 120 Hz
   frame timeline, p50/p95/p99 ready time, decode count, heap,
   zoom/pan bounds, double-tap, screenshot artifacts, Back and video.
3. Integrate Telephoto in main source only if it offers **measured**
   improvement without sacrificing rapid scrubbing and no stale photos,
   and only after per-page lifecycle, pager-swiping gesture arbitration
   and memory pressure pass independent Android tests.
4. Maintain release gate: never uninstall/reinstall the installed phone
   app to work around uncertain signing; preserve native People private DB.

This spike is intentionally cheap to revert (remove test source and
androidTest-only dependency) and keeps all user-visible gallery behavior
unchanged.
