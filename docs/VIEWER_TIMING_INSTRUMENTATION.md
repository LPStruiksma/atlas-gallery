# Native filmstrip timing probe — first measured baseline

**Status:** opt-in instrumentation, not a verified S24+ performance result.

## Why

Subjective smoothness is not enough for a world-class gallery. The existing
Compose `HorizontalPager` + `LazyRow` scrubbing system already defers expensive
photo decode; benchmark this baseline before migrating to a different stack.

`ViewerTimingProbe` is an **in-memory**, explicitly supplied option on
`GalleryViewer(..., timingProbe = probe)`. The normal app passes `null`
by default and does not log to disk, analytics, network or Logcat.

The probe records only 0-based **temporary page indices** plus elapsed
durations from: filmstrip center/click index selected → pager selection →
Coil's initial 800px preview-load success callback. It does not store URI,
image path, file/name, face IDs, device user identity or media content.
Samples are bounded (default latest 256); a rapid scrub supersedes its pending
selection, so late preview callbacks don't get misattributed.

`aggregate()` returns **n, superseded, p50/p95 page-selection and
preview-delivery latency**, with missing data represented by zero samples.
This is **not** finger-down latency, GPU-present latency, full-resolution
image decode time or jank rate. An asynchronously preloaded image can have a
very quick first-preview callback. Always distinguish hot vs cold caches and
match test conditions.

## Reproducibility and test isolation

- Plain JVM `ViewerTimingProbeTest.kt` controls a fake monotonic clock,
  verifies correct event association, out-of-order preview callbacks,
  cancellation after rapid scrub and bounded storage.
- `ViewerInteractionTest.kt` uses locally generated synthetic files, taps
  an adjacent thumbnail and waits for an actual Coil success callback to
  complete one timing sample. The existing photo/filmstrip/back/screenshots
  tests continue unchanged.
- Numeric emulator measurements must be labeled **CI-functional only**.
  Hardware-accelerated emulator measurements aren't representative of
  the physical Samsung S24+ or its 60/120 Hz displays.
- To compare alternate viewers, use the *same* 150k MediaStore inventory,
  identical video/HEIC/4K fixtures, thermal state, cold/warm cache and
  repeated trials. Complement this with Perfetto frame timeline,
  Macrobenchmark and bitmap/heap allocations, as specified in
  [the reuse benchmark](GALLERY_FILMSTRIP_BENCHMARK_AND_REUSE.md).

## Release/experiment boundary

The performance probe does not enable a new gallery feature, mutate photos,
change People data, or alter phone app signing. The existing installed v0.2
on the Galaxy S24+ should **not** be replaced while the signed upgrade
and backup/migration compatibility checks remain open. An experimental
Telephoto subsampling implementation should be integrated only after a
controlled A/B test, and should never displace the original filmstrip
without correctness and memory evidence.
