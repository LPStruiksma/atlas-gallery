# PERF-9: swipe to a sharp photo (#138, goal #119)

**Target:** after a swipe settles, the next or previous photo is fully sharp within 100 ms (p50), with no dropped frames during the swipe.

## What was slow

A viewer page shows an 800 px preview and, once it is focused and settled, a 2400 px base. Before PERF-9, every swipe waited for:

1. the 110 ms settle, which keeps a scrub or a run of swipes from starting decodes it would cancel; then
2. a full 2400 px decode of the new photo (a few hundred ms for a 12 MP JPEG).

The neighbour prewarm did not help. It decoded 800 px under Coil's default key, while the page reads its preview under the presentation key, so the prewarmed bitmap was never shown. Nothing warmed the base.

## Change

- `viewerSwipePrefetchPlan` (`ViewerSwipePrefetch.kt`): once navigation settles (120 ms), warm the 2400 px base one step away on each side and the 800 px preview two steps away, in the direction of travel first. Videos are skipped, because their pages show a poster frame of their own.
- `viewerBaseRequestOrNull` builds the base the same way for the page and for the prefetch, so both use one memory key: the photo's presentation key at 2400 px (the same revision rules as PERF-3), or the editor-return key. A photo without a known revision keeps Coil's own key, as before, and is not prefetched.
- A page whose base is already in memory skips the 110 ms settle (`viewerBaseDelayMs`). Showing a cached bitmap starts no decode, so there is nothing to protect.
- A swipe or a scrub cancels the prefetch effect and every request still running, so fast swipes build no backlog.

## Memory

At most two extra bases are held, one per side. A worst-case base is 2400 × 2400 × 4 B = 23 MB, and a 4:3 photo is 17 MB. Base prefetch runs only when Coil's memory cache can hold six worst-case bases (138 MB, `viewerBasePrefetchAllowed`). The S24+ cache is 256 MB. A smaller cache, such as a low-RAM phone's or the CI emulator's, gets the preview prefetch only, so the grid's tiles are not pushed out. Region tiles (PERF-32) are unaffected and still released off-page.

## Proof

- JVM: `ViewerSwipePrefetchTest` covers the window, direction, edges, skipped videos, the memory gate, the base key and the skipped settle.
- Emulator: `ViewerSwipeSharpnessTest` swipes forward through six synthetic 12 MP JPEGs at one swipe per 1.5 s, with base prefetch off (arm 0) and forced on (arm 1). The gate is forced because the emulator's cache is below it. It logs settle-to-sharp p50 and the share of swipes that are sharp within 100 ms to `ATLAS_SCALE`, and asserts that the p50 is at most 100 ms with prefetch on. The numbers are on the PR.
