# Native viewer filmstrip — reference implementations and benchmark plan

Date: 2026-10-14. Research/design only: no performance result or S24+ validation is claimed here.

## Off-main-thread local Search — 15 October 2026

Native Search previously filtered all media metadata directly inside a
Compose `remember` block on every typed query, scanning up to 150,000
filenames/albums and walking confirmed People photo paths on the UI thread.
The new `rememberAsyncGallerySearch` runs the **unchanged matching policy**
on `Dispatchers.Default`, supports cancellation checkpoints at most every
512 scanned records, and displays a temporary "Searching your library
locally…" state rather than old query results while a new result is pending.

Both sorted MediaStore inventory and imported confirmed People list are keyed
by **reference identity**, with the query and active Search tab. A stale
result can never appear under a newer query, MediaStore refresh, sort mode,
or People import. Blank Search retains the already sorted catalogue instantly
without an unnecessary worker. Filename and album match remain case-insensitive;
person-name matches include exact confirmed paths only (duplicate display names
remain separate person IDs). No embeddings, inferred identities or network.

JVM tests use 150,000 artificial files to check cancellation, exact-path
privacy and result identity. Android Compose tests exercise 15,000 fake files
with alternating typed terms, same-name reviewed People, an updated People
list and a new equal-content source generation. These are **functional
regressions**, not measured S24+ keyboard-input or frame-pacing results.
The native app on the real phone is unchanged pending signer-safe deployment.

## Background ordering for very large media catalogues — 15 October 2026

Native Photos previously called `sortMedia(media, sort, randomSeed)`
directly inside a Compose `remember` calculation. For archives near
150,000 entries, an O(N log N) operation on the main thread can delay
input, animation and display frames, even if the algorithm itself is fast.

The new `rememberAsyncSortedCatalog` moves that **same** deterministic
sort onto `Dispatchers.Default` using a keyed `produceState`. A result is
accepted only when the exact MediaStore-generation list object, sort
order and random seed match the current UI request. A sort being computed
for a previous key is canceled and cannot publish a stale ordering or
wrong album; the gallery shows a temporary 'Ordering library…' state
instead of showing incorrectly ordered media. Existing photo/People
membership, source URIs and sorting semantics are unchanged.

JVM tests reject stale snapshots even when a new source list happens to
contain equal metadata; instrumented synthetic Compose tests exercise
newest → oldest → seeded random → newest transitions across 15,000
fictional records. The prior 150,000-item deterministic sorting tests
remain mandatory. No photos, People, credentials or private data are
accessed by these tests.

**Performance caveat:** this avoids doing sorting in the Compose drawing
path, but is not yet an actual physical S24+ frame-time or latency
measurement. Large-library search/filtering on every keystroke is a
separate future profiling target; no unverified string-filter rewrite
was bundled into this change.

## Scrub coalescing and travel-aware neighboring previews — 15 October 2026

The viewer's bottom `LazyRow` can pass many centered thumbnails under the
fixed marker during one continuous gesture. `collectLatest` now cancels a
stale pager jump whenever a newer centered index arrives, rather than
queueing every intermediate `scrollToPage`. The final snapped item still
uses the existing page-to-strip recentering and Android Back behavior.

After motion settles for 120 ms, the shared Coil loader prewarms only up to
two neighboring photos on each side. The direction of the last *settled*
navigation determines request order: for backward browsing, previous-photo
neighbors come first; forward browsing retains forward-first priority.
The candidate does **not** increase the number or size of thumbnail/previews
requested, alter album sort, or create an unbounded decode queue.

Pure Kotlin/JVM tests cover forward/backward, equal/unknown origin, album
boundaries, and 150,000-item indexes with at most eight bounded neighbors.
Android emulator tests reverse filmstrip direction repeatedly and verify
that the selected thumbnail ends centered on the most recent navigation.

**Evidence limit:** Better cancellation/priority is a plausible efficiency
improvement, not a verified p95 latency improvement. Compare equivalent
cold/warm interactions, heap and frame timing on the physical S24+ only
after the signer-and-backup upgrade gate is passed.

## Single-tap immersive photo viewing (candidate)

While viewing a photograph, a single tap can hide the fixed top
navigation header, guidance, bottom thumbnail strip and coarse seek
rail, allowing the image pager to occupy the available content area.
Another single tap restores the controls and recenters the exact
selected thumbnail. The photo/album selection, zoom, MediaStore list
and Android Back behavior are preserved; double-tap continues to
control zoom and is not mistaken for two single taps.

This initial implementation applies to **photos only**; Media3 video
playback retains the regular controls. System status/navigation bar
immersion is not claimed. Emulator interaction checks both hide/restore
and retained selection. Real S24+ usability and TalkBack need validation.

## Native coarse seek rail (candidate)

For a long album, the filmstrip must excel at **precision** (adjacent photos),
but cannot reasonably require tens of thousands of swipes to reach the other
end. The latest candidate adds a compact full-range Compose Slider beneath
the existing centered thumbnail filmstrip. The position is an exact index
derived from the fraction of the **current album's immutable viewer items**,
not a new filename search, remote request or MediaStore mutation.

During rail drag, `snapshotFlow` and `collectLatest` update the pager toward
only the newest selected index; full-resolution decode, neighbor prefetch
and ExoPlayer startup stay suspended. When released, the pager and thumbnail
center converge on that index. Ordinary filmstrip dragging, paging, zoom
and the album Back route keep their existing semantics. A one-item album
omits the rail.

Emulator synthetic tests: drag right and left across a 64-image album,
verify major position jumps and that the highlighted thumb returns to
the fixed center mark; retain existing thumbnail-swipe and Back tests.
Physical S24+ touch/jank, 10k–150k-item latency and reduced-motion
accessibility remain **unmeasured**; emulator green is not production proof.

## Mission and observed baseline

The UX target is Samsung Gallery-class interaction: while a thumb is dragged rapidly
through dozens of images, the full-screen viewer immediately tracks the centered
thumbnail; reverse-direction swipes, pinch-to-zoom, videos, Back, and 150k-item
catalogues must remain reliable. Sorting by size and seeded random remain first-class
ATLAS differentiators, not bolted-on hacks.

Current implementation: `GalleryViewer.kt` already combines Compose
`HorizontalPager` + `LazyRow`, synchronized with `snapshotFlow`; uses
`scrollToPage` without animation, skips large decodes during scrubbing, waits
110 ms before requesting a 2400 px photo, prewarms a couple 800 px neighbors
after 120 ms of rest, reuses Coil singleton, and uses Media3 ExoPlayer for
video. **Keep this architecture as baseline A.** It has not been benchmarked on
the user's Galaxy S24+ and lacks production image-tile subsampling.

## Open-source/reference study (read APIs and observe behavior before reimplementing)

| Candidate | Useful mechanism | Adoption stance | Licensing / integration |
| --- | --- | --- | --- |
| [AndroidX Compose Pager](https://developer.android.com/develop/ui/compose/layouts/pager) | Lazy pager, `scrollToPage`, explicit offscreen budget, stable page keys | **Already used**; tune prefetch + synchronization instead of writing a custom pager | AndroidX / Android framework |
| [Coil 3](https://coil-kt.github.io/coil/) [cache guidance](https://github.com/coil-kt/coil/blob/main/docs/image_loaders.md), [preload FAQ](https://github.com/coil-kt/coil/blob/main/docs/faq.md) | Shared `ImageLoader`, memory cache, non-target preload, dynamic decode size, cancelability | **Already used**; instrument image request latency, cache hit rate and unneeded decode work | Apache-2.0 |
| [Telephoto](https://github.com/saket/telephoto) | Compose ZoomableImage and large-image subsampling rather than multiplying one big bitmap | **First library spike** for correct bounded zoom/pan; compare to current `graphicsLayer` transform | Apache-2.0; assess Coil/MediaStore compatibility |
| [Aves gallery](https://github.com/deckerst/aves) | Mature Android gallery navigation, mixed-media behavior, extensive metadata, large-library UX | **Behavioral reference**, not a direct Kotlin drop-in: built with Flutter | BSD-3-Clause; avoid porting without evidence |
| [PhotoPrism Android client](https://github.com/Radiokot/photoprism-android-client) | Chronological navigation, media-client edge cases, thumbnail strategies | **Behavioral reference** only, since oriented to remote PhotoPrism libraries | GPL-3.0: don't copy into private app without legal/licensing review |

Do not decompile, copy, or redistribute Samsung Gallery proprietary assets.
Its visible interactions are valid black-box behavioral benchmarks; implement
ATLAS with publicly documented Android APIs or compatible open source.

## Repeatable experiment matrix

Use the same ordered fixture list and identical device thermals/state for
baseline A and each variant B. Workloads: 1k, 25k, 150k indexed synthetic
catalog rows; 100/1,000 item consecutive photo traversals; alternating
direction (50 left/50 right); scrub far into a list; cold and warm thumbnail
cache; dense 4K JPEGs, HEIC where supported, large PNG, mixed videos,
missing/unreadable media, and instant Android Back. Synthetic fixture paths
only in CI. Never upload users' face data.

- **Correctness:** expected selected item vs displayed image, filmstrip center,
  stale-image flashes, stuck player, unexpected directory sort, lost scroll
  position, zoom carry-over, touch conflicts, and Back.
- **Responsiveness:** record input timestamp and selected-page/image-ready
  timestamp; p50/p95/p99, not just average. Separately report thumb-first
  preview vs full-resolution completion, initial open, 100-image fling.
- **Smoothness:** Android frame timeline/Perfetto: jank rate, p95 frame
  duration, dropped frames; distinguish 60 Hz and 120 Hz device modes.
- **Resource economy:** Java/native heap peak, decoded bitmap bytes, number
  of image decode requests while scrubbing, cache hit rate, battery/thermal
  sensitivity; verify bounded work as list length grows.
- **Accessibility/reliability:** videos pause/release across transitions;
  zoomed image handles pan without stealing strip gestures; Back never
  requires uninstall/relaunch; correct dynamic content descriptions.

**Do not use Linux GitHub emulators for definitive performance claims.**
They are a functional-regression gate; the physical Galaxy S24+ is the target
for timing, thermals, renderer/decoder behavior, and actual 120 Hz interaction.
The real-device experiment waits until a signer-compatible/data-preserving
ATLAS upgrade is proved. Avoid touching or replacing the installed app to run
these tests before that gate.

## First reversible development experiments

1. Add a debug-only, privacy-preserving performance trace for
   `strip center chosen -> pager index -> preview ready`, plus Coil decode
   counts (timestamps and anonymous indices, never paths or filenames).
   Keep it off production UI and avoid logcat with private media paths.
2. Measure baseline A before refactoring; use Macrobenchmark/Perfetto or a
   lightweight in-app trace where infrastructure permits. **Report actual
   n, percentiles, warm/cold state, OS/build and thermal conditions.**
3. Spike Telephoto ZoomableImage on an isolated feature flag. A/B test
   overscroll boundary, pan/zoom bounds, huge images, video transitions,
   gesture arbitration and page state disposal; retain existing implementation
   unless measurably better.
4. Compare Coil preload strategies: nearest two neighbors after idle versus
   velocity-aware directional prefetch, abort/cancel when scrubbing resumes.
   Constrain decoded pixel budgets and ensure no runaway queue.
5. Only if A/B data proves Compose's stock mechanisms inadequate, prototype
   a custom RecyclerView-backed thumbnail rail. Do **not** reinvent a pager,
   image cache, media player, or tile decoder as the default first step.

## Release evidence gates

- Green synthetic/Compose interaction CI.
- Validated safe APK upgrade preserving app-private People database and signer.
- Reproducible same-device A/B performance results with no regression in
  correctness, memory, scrolling, video, accessibility or gesture handling.
- Preserve media ordering, source identity, stable sorting and no false People
  links. No irreversible user-media writes.

This is a performance study plan, not a claim that ATLAS already meets a
Samsung Gallery-level numerical threshold.
