# Samsung Gallery parity spec (atlas-native #509)

**Lukas, 2026-11-10: "effectively I want it all."** Full Samsung feature parity is the target; this spec orders it.

**Baseline:** Samsung Gallery 15.9 is the reference for **full feature coverage and in-feature behavior** on the surfaces Lukas uses, **plus sort by file size**. Every Samsung capability must have an obvious ATLAS home; ATLAS-only People, face search, Find similar, Photo DNA and declutter remain. **Quiet Frame v3 / Lapis Atlas** defines ATLAS information architecture, navigation, feature placement, visual identity, chrome and control shapes ([OWNER_INTENT](../OWNER_INTENT.md), [QUIET_FRAME](../design/QUIET_FRAME.md)). Samsung's screen layouts and styling are observations, not ATLAS layout requirements.

**How to read this spec:** Samsung screenshots and historical ATLAS before/after comparisons describe features, outcomes and provenance. They do not prescribe ATLAS navigation, placement, icons, typography or geometry. All specified gestures, actions, interactions, performance, accessibility, research IDs, removal protections and checklist commitments still apply; when a line mixes them with Samsung styling, Quiet Frame governs only the styling.

**Samsung behavior evidence:** Lukas's S24+ screenshots in the #509 threads (2026-11-09), then the private Samsung parity research archive (behavioural study of Samsung Gallery 15.9.00.45; finding IDs such as `VIEW:V20` below point into it). For ATLAS structure and appearance, [OWNER_INTENT](../OWNER_INTENT.md) and [QUIET_FRAME](../design/QUIET_FRAME.md) take precedence over these screenshots. Clean-room rule: describe and reimplement behaviour; never copy Samsung code or artwork (Material icons only).

**Rules for every UI PR**
1. Name the section(s) of this spec it implements, and tick them in the checklist at the bottom.
2. Keep core screens free of unapproved extras: if Samsung doesn't have a feature, ATLAS shows it only when this spec or Quiet Frame names a reason. The removal list below remains binding. Samsung features may live in different ATLAS locations.
3. Fast and snappy beats ceremony: no explanatory banners, no blocking screens, no confirmation steps beyond what Android itself enforces (for example the system trash dialog).
4. For UI-changing PRs, attach before/after emulator screenshots of the surface (the `saveUiEvidence` / `screenshot` helpers under `app/src/androidTest`). Review checks rendered visual design against Quiet Frame and functionality / in-feature behavior against this spec. A docs-only PR does not need new renders.

---

## 0. Feel (applies to every surface and every PR)
Lukas, 2026-11-10 00:39Z: "Samsung Gallery is relatively minimalistic, but in a sophisticated way ... that little premium feel is found in small things." Example: the filmstrip scrub has weight; a fling carries momentum and slowly settles. Reviews judge this responsive interaction feel, not Samsung's visual styling; Quiet Frame owns the visual identity.

| Area | Rule | Source |
|---|---|---|
| Fling | Full platform spline decay (velocity ×1.0); never cut momentum short. Snap only at the end, with a soft critically damped settle (no bounce, no quick snap) | observed fling (×1.0) + `LinearSnapHelper`; `PERF:S11` |
| Frame budget | Nothing recomposes the whole screen per frame during a scroll, scrub or pinch: per-frame state is read only in the smallest scope that draws it | ATLAS rule (#520) |
| Haptics | Light tick when a new photo reaches the strip centre while scrubbing, and on pinch level changes and selection start | Samsung behaviour on S24+ |
| Motion | Transitions 200–350 ms with sine in-out or emphasized easing: grid↔viewer shared element 200 ms, pinch level settle 350 ms, double-tap zoom 160 ms; chrome fades rather than pops | parity research `VIEW:V20`, `V23`, `GRID:G3-G4` |
| Chrome geometry | Quiet Frame defines the slab/sphere/chrome. Samsung's translucent ~70%-opacity dark pills, API 31+ blur, 12 dp edge offset and outlined white 24 dp icons are nonbinding visual observations | Owner screenshots; [Quiet Frame](../design/QUIET_FRAME.md) |
| Type and spacing | Quiet Frame defines ATLAS typography and layout spacing; preserve 2 dp photo gaps where this spec specifies them, a legible hierarchy and no bold all-caps labels | Owner screenshots; [Quiet Frame](../design/QUIET_FRAME.md) |
| Photo shape | Quiet Frame: real-shape justified day rows without letterboxing or square crops; square grid tiles only from month zoom outward. Collections use photo-strip rows rather than prescribed Samsung album cards, circles or pills | Owner screenshots; [Quiet Frame](../design/QUIET_FRAME.md) |

## 1. Viewer (PR 1)
| Samsung reference (screenshot `004231_Gallery`; parity research App. C) | ATLAS before (historical) | ATLAS feature / in-feature behavior contract (visual treatment per Quiet Frame) |
|---|---|---|
| Photo full-bleed on black; chrome floats over it | Opaque title bar + label + strip + slider stacked; the photo squeezed in between | Photo fills the viewer on black; Quiet Frame defines the viewer slab and chrome treatment, rather than Samsung's floating overlays |
| Top-left: round back pill (`<`) | Arrow + filename + "N of M" | Back chevron returns from the viewer. No visible filename or counter (`N of M` remains available to TalkBack); Quiet Frame determines the control's location and shape |
| Top-right: one rounded pill of actions (motion, view, rotate, ⋮) | Row of 5 loose icons | Provide Find this person (only with indexed faces), Find similar and Details in Quiet Frame's approved viewer homes; Samsung's rounded grouping is a nonbinding example |
| Bottom: rounded pill (favourite · edit · AI · share · delete) | none | Favourite, edit, share and delete stay discoverable in Quiet Frame's viewer slab; Samsung's pill and action order are reference examples, not requirements. Favourite uses `MediaStore.createFavoriteRequest`, Delete uses `createTrashRequest` (Android's consent dialog); after Trash the viewer moves to the neighbour. No redundant standalone AI button; approved AI edit remains in the viewer slab. Top and bottom chrome fade in and out over 200 ms |
| Thin filmstrip of small portrait tiles, 2 dp gaps, centre tile drawn larger, no label | 56 dp rounded squares, "SCRUBBING" / "DRAG THUMBNAILS TO SCRUB" label, a marker line, a thick progress slider below | Filmstrip reference tiles 30×40 dp, 2 dp gaps, centre tile ×1.25 (`VIEW:V15`); label, centre marker line and coarse slider removed. Quiet Frame's small sphere and 2 dp white ring mark the current item instead |
| Filmstrip follows fractional pager swipe; thumbnail tap instantly changes the photo and eases the strip (`VIEW:V14`) | Existing pager tracking already used the fractional offset; thumbnail taps teleported the strip | Keep pager-driven strip tracking on each fractional swipe frame. Only thumbnail taps move the strip from its rendered offset to the selected item in 260 ms using cubic-bezier (0.22, 0.25, 0, 1), an ATLAS-chosen duration, not a Samsung measurement. The photo and selected semantics change immediately; a new tap restarts from the visible strip offset, and finger-down on strip, pager interaction, scrub/fling or dataset change cancels the ease. Scrub completion and edge page selection remain immediate. System remove-animations cuts directly to the destination, with no new chrome or per-frame photo decode |
| Single tap hides all chrome and the system bars | Chrome hidden, system bars stay | Chrome and status/navigation bars hide together; swipe from the edge peeks them |
| Swiping pages has a black gutter (`VIEW:V31`) | none | 12 dp page spacing |
| Scrubbing: the big photo always equals the strip's centre item; low-res while moving, sharp when it stops; nothing jumps on release (`PERF:S1`, `VIEW:V11`) | Pager chased the strip page by page, fell several photos behind on a fast scrub, then one of the two jumped on release | While the strip moves, an overlay shows the centre item every frame from the strip's own cached thumbnail, sharpened to the 800 px preview after a 60 ms pause. On release the pager lands once on that same item and the overlay stays until it has. Pager never animates through intermediate pages |
| Double tap ×2.31 around the tap point, pan bounded, edge hands off to pager (`VIEW:V20-21`) | Done in earlier units | Kept |
| Swipe up shows the details panel (Lukas 2026-11-10: "sorely needed"; `VIEW:V26-V27`) | Only the ⓘ button | Swipe up on an unzoomed photo opens Details (viewer PR 2) |
| Drag down to close (`VIEW:V22`) | missing | Swipe down on an unzoomed photo closes (viewer PR 2); FLOW-3 guards ownership until downward intent and cancels an owned drag on reversal. Authorized grid reveal is wired to gesture scale; runtime evidence remains pending |
| Close returns the grid to the photo you ended on (`VIEW:V23`) | Done (`onExitSettledUri`) | FLOW-3 A prepares and measures the settled tile before exit; runtime proof pending |

## 2. Pictures grid and app shell (PR 2)
- **Header:** Preserve the scroll-away Pictures heading (#506 folded in here), a compact search/overflow entry and no permanent duplicate header line. The title/chrome placement follows Quiet Frame; Samsung's large title and fixed compact bar are observations, not layout directives.
- **Bottom navigation:** Samsung's four-tab floating pill (Pictures · Albums · Stories · Menu) is reference only, **not** a requirement. ATLAS uses Quiet Frame's centred slab (Pictures · Collections) and search sphere, **not** a four-tab pill or Material selection blob. Albums, People, Stories and Search retain their functions through Quiet Frame's Collections and Search homes.
- **Pinch levels** (`GRID:G1-G7`): preserve the documented portrait density sequence 1 (real ratio) · 3 · 4 (default) · 7 · 12 month · 28 year within Quiet Frame's day → month → year zoom model. At day zoom, show real-shape justified rows without letterboxing/square crops; square tiles begin at month zoom. Anchor under the fingers; no decode mid-pinch; 350 ms sine settle (#507 folded in here). Both layouts are live during the gesture (FLOW-1 #711): the level being entered is drawn from cached tiles, one shared anchor rectangle moves between its sizes in the two levels, the layers crossfade with the dominant one opaque underneath, release commits past 20 % (80 % when pinching back) and the new grid opens already scrolled to the anchor.
- **Date headers** (`GRID:T1-T2`): day headers at 3/4, month at 7+, year at 28; sparse single days merge.
- **Fast scroll:** Provide Quiet Frame's held-sphere scroll thumb while scrolling. **Its bubble and track follow the active sort** (Lukas 2026-11-09 23:51Z): dates for Newest/Oldest, file-size buckets for Largest (and Smallest), letters for Name, plain position for Random. Preserve exact maths and jumps on long moves (`PERF:S4-S6`); Samsung's thin right-edge handle is a visual reference only.
- **Sort menu:** Keep Samsung's sorting capabilities plus **Largest/Smallest** (ATLAS's addition; Samsung has no size sort); menu presentation follows Quiet Frame rather than Samsung's sheet styling.

## 3. Albums (PR 3)
Collections presents albums as Quiet Frame rows with strips of latest photos at their real shapes, not a mandatory Samsung album-cover grid. Preserve newest-item previews, album names and counts; Camera, Screenshots and "Black and White" remain prioritised. Preserve natural-name, count and custom sorting (`GRID:A1-A6`). No unapproved chips or explanatory text.

## 4. People and Search entry points (PR 4)
People belong in Quiet Frame's Collections as rows with latest photos, name, count and latest date, not required round face crops or a standalone People tab. Search opens from the sphere into a full-width field above the slab, retaining recent searches and suggestion chips (`PPL:15-26`). Stories and Photo DNA rows remain removed from the Albums screen; the features remain available through Quiet Frame's Collections / cleanup homes rather than a prescribed Samsung menu.

## 5. Selection, menus, settings (PR 5)
Long-press + drag range selection with edge auto-scroll and header checkboxes (`GRID:S1-S4, T6`) remains required. Selection mode exposes share, delete and more actions in Quiet Frame's approved controls rather than a Samsung bottom pill. Overflow menus retain the feature actions and behavior; Samsung ordering/wording is an example, not a binding ATLAS layout or copy rule.

## 6. Video
Video shares Quiet Frame's photo-viewer chrome; retain play/pause and seeking controls in the viewer, replacing the raw Media3 presentation. Preserve the frame-thumbnail seek strip when the centred video is held (`VIEW:V16-V18`). The Samsung-style play-button/seek-bar look is a reference, not a compulsory shape or placement. After the selection PR.

## 7. Access and loading
No blocking "Updating your allowed photos" screen (#505): keep showing the last gallery while a changed grant is re-checked; an access problem shows inline, once.

---

## Removal list (binding)
| Item | Where | Status |
|---|---|---|
| "SCRUBBING" / "DRAG THUMBNAILS TO SCRUB" label | `GalleryViewer.kt` | removed in PR 1 |
| Coarse position slider under the strip | `GalleryViewer.kt` | removed in PR 1 |
| Strip centre marker line | `GalleryViewer.kt` | removed in PR 1 |
| Filename + "N of M" title in the viewer | `GalleryViewer.kt` | removed in PR 1 (TalkBack keeps "N of M") |
| Opaque viewer top bar | `GalleryViewer.kt` | removed in PR 1 |
| Letterbox bars in real-ratio rows | `featuregrid/` | #504 / PR #510 |
| "Updating your allowed photos…" blocking screen | `MainActivity.kt` | #505 |
| Tall fixed Photos header | `PhotosHeader.kt`, `MainActivity.kt` | PR 2 |
| Stories / Photo DNA rows on Albums | `StoriesHost.kt`, `PhotoDnaHost.kt` | PR 4 |
| Any new explanatory banner, status line or label | everywhere | never add one |

## Checklist
- [x] 1 Viewer: chrome, filmstrip, scrub sync, system bars, page gap
- [x] 0 Feel: weighted filmstrip fling, per-frame recomposition removed, scrub haptics (#520)
- [x] 0 Feel: chrome fades
- [ ] 0 Feel: pill blur (legacy Samsung reference; apply Quiet Frame slab material instead), grid↔viewer shared element, grid fling
- [x] 1 Viewer: swipe up for details, swipe down to close (viewer PR 2)
- [x] 1 Viewer: favourite, delete
- [ ] 2 Grid shell, header, levels, headers, sort-aware fast scroll
- [ ] 3 Albums
- [ ] 4 People and Search
- [ ] 5 Selection and menus
- [ ] 6 Video
- [ ] 7 Access and loading (#505)

FLOW-3 checkpoint A (#593): prepare the settled item's authorized grid and seek before the exit, measure its current tile rather than reusing the opening rectangle, and use a bounded no-target fallback. Checkpoint B1 adds focused below-fit pinch ownership and reversible focal scaling, with a provisional .90 fit commit threshold. This is implementation evidence only; B2a transfers the drawn down-drag pose into an eligible below-fit pinch and preserves centroid travel across pointer changes. B2b makes a cancelling down-drag interruptible at the current drawn pose and requires fresh downward intent before committing. B2c starts prepositioning the authorized grid after an idle settled URI, independently of commit; B2d makes the authorized, measured settled grid visible as drag/pinch scale decreases and restores backdrop/chrome opacity on reversal. B2e prevents release of an inherited pinch pose from closing without fresh net contraction; the provisional 2% gate needs runtime verification. B2f freezes an eligible returning pinch at its drawn pose on single-pointer regrab and prevents a competing parent drag; release without fresh two-pointer contraction cancels. B2g fades a non-grid return to the black viewer ground before restoring its existing route (no stale tile; immediate cut on revoked access or disabled motion). Runtime gesture arbitration and fallback appearance are not yet verified. B2h uses the gesture's decoded oriented fitted rectangle as the return overlay's starting frame and avoids a new black layer over a revealed grid; unknown geometry falls back to fade. B2i derives the committed gesture return clip from interpolated centered normalized crop and uniform scale. Preview alignment, one-copy handoff, Back/entry crop, paired runtime frames and timing comparison are still required.

FLOW-3 C1 extends the existing light/dark S24+ synthetic journey with mixed-aspect fixtures, held/reversed/committed drag and measured Back after paging. Assertions and capture targets are not rendered proof; hosted execution and inspection, pinch/ratio/offscreen/fallback cases and paired timing remain owed.

FLOW-3 C2 adds below-fit two-pointer held/reversed/committed return capture targets in the same light/dark journey. The reversal must keep the current photo; commit must start a measured overlay and return to the original tile. Actual pinch scale, pointer ownership and visual continuity still require hosted capture inspection.

FLOW-3 C3 adds a dedicated light/dark dated-catalogue offscreen case: prove the synthetic target is absent initially, choose its filmstrip position, capture measured Back midpoint and assert that exact target tile is displayed after closing. Hosted runtime inspection remains pending; no missing-target fade or timing result is claimed.

FLOW-3 C4 targets Back during an active entry animation, verifies no coexisting entry/exit semantics, closes to the opening tile, then reopens and returns to a different visible synthetic tile. Paired captures are targets only; actual pixel continuity and quick-close latency remain unmeasured.

FLOW-3 C5 uses the measured click-origin handoff on original-proportion rows and adds light/dark non-square synthetic photo entry, measured Back midpoint and same-ratio-tile return capture targets. No runtime crop or timing result is claimed.

FLOW-3 C6 applies the centered crop interpolation to ordinary entry and Back using oriented catalogue dimensions (no extra decode wait), retains the frozen decoded pose for gestures, and fades when fit geometry is unknown. The JVM geometry cases cover landscape, portrait, offset viewport and invalid dimensions. Metadata may differ from actual decoded geometry; paired runtime inspection and timing remain required.

FLOW-3 C7 adds a synthetic real-host Stories journey to the paired light/dark suite. It targets the non-grid no-return-tile fade and verifies the same story moment after Back; capture targets only, not a measured grid timeout or a runtime result.

FLOW-3 C8 registers every paired motion capture in the strict CI manifest and adds a real MainActivity grid-to-viewer timing probe using synthetic 4032×3024 PNG, first and repeated opens, PERF-3 callback stamps and Android Window frame-total p90. Hosted measurements, comparison and visual acceptance remain outstanding.

FLOW-3 C9 extracts the existing committed grid measurement wait without changing its 1500 ms deadline, and adds coroutine JVM cases for timeout (short injected deadline), successful measurement and cancellation of obsolete preparation. The host still clears pending seeks and uses a null return origin on timeout; this is a focused wait regression, not a rendered real-grid timeout proof.
