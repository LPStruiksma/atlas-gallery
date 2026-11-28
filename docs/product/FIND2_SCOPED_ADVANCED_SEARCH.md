# FIND-2 scoped advanced search — implementation contract

Issue: [#181](https://github.com/LPStruiksma/atlas-gallery/issues/181). Approved UI task/state design: [revised A–H](https://github.com/LPStruiksma/atlas-gallery/issues/181#issuecomment-7025216879), [independent design PASS](https://github.com/LPStruiksma/atlas-gallery/issues/181#issuecomment-7025312924).

**Status on PR #798: unfinished draft, not acceptance evidence.** The branch begins integrating the shared `AtlasSearch` tool slots with the existing face and visual-search routes, initially for Photos, album-detail and person-detail headers. Do not claim parity or successful emulator UI from this document.

## Search source and results

- The overview host supplies a current, authorized, sorted media source and the permission/publication epoch before an advanced mode begins. Source snapshots are transient and are discarded on route/permission changes. A reference picked later is **not** itself a result-source grant.
- `scopeRankedHits` intersects the existing engine's ranked hits with that source by media URI. In **Best matches** it preserves engine ordering, hit tiers and video-moment metadata; **Screen sort** reorders the same hits to the source display order. Neither option adds hits or silently broadens to the library.
- Scope and text filtering run off the Compose UI thread; a result viewer additionally checks the live grant and publication when opened. Empty and revoked states must never retain old pictures.
- The global Search tab continues to own whole-library exploration. An explicit **Whole library** choice leaves the current overview instead of treating it as the source of the same scoped query.
- The existing face and visual models own ranking, confidence labels and optional pose emphasis. FIND-2 does not introduce another model, index or content suppression rule.

## Albums and People lists (F)

- The Albums and People list headers offer **By face** and **By image** in the same AtlasSearch tool slots. The list's own name filter (and the Albums photo/video filter) decides which entries count; the chips are disabled when the list shows nothing.
- `albumsOverviewUnion` keeps the shown albums' media by exact volume and relative path; `peopleOverviewUnion` keeps imported reviewed people's confirmed paths through the person-album authorization and native people's host-resolved URIs. Both draw only from the current grant's sorted publication, in its order (so Screen sort is the gallery sort). Unnamed face groups never count.
- The union is built on a worker (native URIs on IO) and is used only if grant, gallery epoch, route, the sorted publication and (for People) the People sources are unchanged when it lands (`OverviewUnionOrigin`). Any later change closes the advanced search, as for the other headers.
- Results say "Matches in these albums/people" and offer a literal **Back to Albums/People**; the list's query and open tray survive the round trip (host-held, never saved state).

## Work still required

1. ~~Albums and People list unions~~ (above).
2. Connect **Filters** (date/person/folder) to the same immutable screen source and preserve search, filter and Screen sort membership. No fully functioning scoped facet panel has landed on this draft.
3. Offer an alternative face reference from **People**, photo reference from the phone picker (without widening result scope), and recoverable no-face/index-off/empty states. Review controls and callback contracts with the actual active grant.
4. Add synthetic JVM authorization/permission-epoch regressions, demonstrate ranking equals the engine's restricted query for both face and visual streams, and verify video moments and pose-sort membership.
5. Supply actual **exact-head** emulator A–H task/state frames: portrait and landscape; light/dark; IME; font scale 1.3; large People list; empty, index off, error, and revoked permission. The design-only diagrams in the issue are not substitutes.
6. Check every Overview entry against #175, treat Trash/Declutter as staged-only, and leave Same person? expanded-grid implementation to #626.

The current draft is intentionally not labelled `next:review` until these are complete, exact-head Android CI is green and evidence is attached. Use only synthetic or licensed test data in GitHub.
