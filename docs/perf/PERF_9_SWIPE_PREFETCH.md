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

At most two extra bases are held, one per side. A worst-case base is 2400 × 2400 × 4 B = 23 MB, and a 4:3 photo is 17 MB. How many neighbours get their base depends on Coil's memory cache size (`viewerNeighbourBaseBudget`):

| Cache | Bases warmed | Example |
|---|---|---|
| at least 6 worst-case bases (138 MB) | both sides | S24+ (256 MB) |
| at least 4 (92 MB) | the base ahead only | 6 GB phone (128 MB) |
| less | none, previews only | 4 GB or low-RAM phone, the CI emulator |

A cache too small for the shown base and two neighbours evicts a warmed base before the swipe reaches it. The first emulator run showed this: with both sides forced into a small cache, warming the base behind evicted the base ahead, and only 3 of 5 swipes landed on a cached base. Region tiles (PERF-32) are unaffected and still released off-page.

## Proof

- JVM: `ViewerSwipePrefetchTest` covers the window, direction, edges, skipped videos, the memory budget, the base key and the skipped settle.
- Emulator: `ViewerSwipeSharpnessTest` swipes forward through six synthetic 12 MP JPEGs at one swipe per 1.5 s, with base prefetch off (arm 0) and the base ahead forced on (arm 1), since the emulator's cache is below the real budget. It logs settle-to-sharp p50 and the share of swipes that are sharp within 100 ms to `ATLAS_SCALE`, and asserts that the p50 is at most 100 ms with prefetch on. The numbers are on the PR.
