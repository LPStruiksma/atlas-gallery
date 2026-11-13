# Samsung Gallery parity spec (atlas-native #509)

**Lukas, 2026-11-10: "effectively I want it all."** Full Samsung feature parity is the target; this spec orders it.

**Baseline:** ATLAS looks, moves and behaves like Samsung Gallery 15.9 on every surface Lukas uses daily, **plus sort by file size**. ATLAS-only features (People, face search, Find similar, Photo DNA, declutter) stay, but sit where Samsung would put such a feature (top action pill, overflow menu, Search, People tab), never as extra chrome on a core screen.

**Sources of truth, in order:** Lukas's S24+ screenshots in the #509 threads (2026-11-09), then the private Samsung parity research archive (behavioural study of Samsung Gallery 15.9.00.45; finding IDs such as `VIEW:V20` below point into it). Clean-room rule: describe and reimplement behaviour; never copy Samsung code or artwork (Material icons only).

**Rules for every UI PR**
1. Name the section(s) of this spec it implements, and tick them in the checklist at the bottom.
2. If Samsung doesn't have it, ATLAS doesn't show it, unless this spec names a reason (the removal list below is binding).
3. Fast and snappy beats ceremony: no explanatory banners, no blocking screens, no confirmation steps beyond what Android itself enforces (for example the system trash dialog).
4. Attach before/after emulator screenshots of the surface (the `saveUiEvidence` / `screenshot` helpers under `app/src/androidTest`); Review checks them against this spec.

---

## 0. Feel (applies to every surface and every PR)
Lukas, 2026-11-10 00:39Z: "Samsung Gallery is relatively minimalistic, but in a sophisticated way ... that little premium feel is found in small things." Example: the filmstrip scrub has weight; a fling carries momentum and slowly settles. Reviews judge feel, not only function.

| Area | Rule | Source |
|---|---|---|
| Fling | Full platform spline decay (velocity ×1.0); never cut momentum short. Snap only at the end, with a soft critically damped settle (no bounce, no quick snap) | observed fling (×1.0) + `LinearSnapHelper`; `PERF:S11` |
| Frame budget | Nothing recomposes the whole screen per frame during a scroll, scrub or pinch: per-frame state is read only in the smallest scope that draws it | ATLAS rule (#520) |
| Haptics | Light tick when a new photo reaches the strip centre while scrubbing, and on pinch level changes and selection start | Samsung behaviour on S24+ |
| Motion | Transitions 200–350 ms with sine in-out or emphasized easing: grid↔viewer shared element 200 ms, pinch level settle 350 ms, double-tap zoom 160 ms; chrome fades rather than pops | parity research `VIEW:V20`, `V23`, `GRID:G3-G4` |
| Floating chrome | Translucent dark pills (about 70% opacity, blur behind on API 31+), fully rounded, 12 dp from screen edges; icons outlined, 24 dp, white | Owner screenshots |
| Type and spacing | One type scale (One UI-like: large light screen titles, medium labels); 2 dp photo gaps; no bold all-caps labels | Owner screenshots |
| Corners | Photos in grids square-cornered; albums and pills rounded | Owner screenshots |

## 1. Viewer (PR 1)
| Samsung (screenshot `004231_Gallery`; parity research App. C) | ATLAS before | ATLAS after |
|---|---|---|
| Photo full-bleed on black; chrome floats over it | Opaque title bar + label + strip + slider stacked; the photo squeezed in between | Photo fills the screen; all chrome is an overlay |
| Top-left: round back pill (`<`) | Arrow + filename + "N of M" | Round dark pill with a back chevron. No filename, no counter (counter stays for TalkBack only) |
| Top-right: one rounded pill of actions (motion, view, rotate, ⋮) | Row of 5 loose icons | One pill: Find this person (only with indexed faces), Find similar, Details |
| Bottom: rounded pill (favourite · edit · AI · share · delete) | none | Pill: favourite · edit · share · delete, in Samsung order. Favourite uses `MediaStore.createFavoriteRequest`, Delete uses `createTrashRequest` (Android's consent dialog); after Trash the viewer moves to the neighbour. No AI button. Top and bottom chrome fade in and out over 200 ms |
| Thin filmstrip of small portrait tiles, 2 dp gaps, centre tile drawn larger, no label | 56 dp rounded squares, "SCRUBBING" / "DRAG THUMBNAILS TO SCRUB" label, a marker line, a thick progress slider below | 30×40 dp tiles, 2 dp gaps, centre tile ×1.25 (`VIEW:V15`), nothing else. Label, marker line and slider removed |
| Single tap hides all chrome and the system bars | Chrome hidden, system bars stay | Chrome and status/navigation bars hide together; swipe from the edge peeks them |
| Swiping pages has a black gutter (`VIEW:V31`) | none | 12 dp page spacing |
| Scrubbing: the big photo always equals the strip's centre item; low-res while moving, sharp when it stops; nothing jumps on release (`PERF:S1`, `VIEW:V11`) | Pager chased the strip page by page, fell several photos behind on a fast scrub, then one of the two jumped on release | While the strip moves, an overlay shows the centre item every frame from the strip's own cached thumbnail, sharpened to the 800 px preview after a 60 ms pause. On release the pager lands once on that same item and the overlay stays until it has. Pager never animates through intermediate pages |
| Double tap ×2.31 around the tap point, pan bounded, edge hands off to pager (`VIEW:V20-21`) | Done in earlier units | Kept |
| Swipe up shows the details panel (Lukas 2026-11-10: "sorely needed"; `VIEW:V26-V27`) | Only the ⓘ button | Swipe up on an unzoomed photo opens Details (viewer PR 2) |
| Drag down to close (`VIEW:V22`) | missing | Swipe down on an unzoomed photo closes (viewer PR 2) |
| Close returns the grid to the photo you ended on (`VIEW:V23`) | Done (`onExitSettledUri`) | Kept; shared-element return later |

## 2. Pictures grid and app shell (PR 2)
- **Header:** Samsung shows a large "Pictures" title with the count that scrolls away with the grid, and a compact bar (search, ⋮) that stays. ATLAS: the header must scroll away (#506 folded in here), no permanent second line.
- **Bottom navigation:** Samsung uses a floating pill tab bar (Pictures · Albums · Stories · Menu). ATLAS keeps its own tabs (Photos · Albums · People · Search) but in the same compact floating style; no Material selection blob.
- **Pinch levels** (`GRID:G1-G7`): phone portrait 1 (real ratio) · 3 · 4 (default) · 7 · 12 month · 28 year. Real ratio = justified rows that fill the width with no bars (#504 / PR #510). Anchor under the fingers; no decode mid-pinch; 350 ms sine settle (#507 folded in here).
- **Date headers** (`GRID:T1-T2`): day headers at 3/4, month at 7+, year at 28; sparse single days merge.
- **Fast scroll:** a thin handle on the right that appears while scrolling, with a bubble. **The bubble and the track follow the active sort** (Lukas 2026-11-09 23:51Z): dates for Newest/Oldest, file-size buckets for Largest (and Smallest), letters for Name, plain position for Random. Exact maths, jump on long moves (`PERF:S4-S6`).
- **Sort menu:** Samsung's sort sheet plus **Largest/Smallest** (ATLAS's addition; Samsung has no size sort).

## 3. Albums (PR 3)
Grid of albums, newest-item cover, name and count under the cover; Camera, Screenshots and "Black and White" pinned first; sort by name (natural), count, custom (`GRID:A1-A6`). No chips, no explanatory text.

## 4. People and Search entry points (PR 4)
People tab = Samsung's "People" album style: round face crops in a grid, names under them. Search = one search field with recent searches and suggestion chips (`PPL:15-26`). Stories and Photo DNA rows leave the Albums screen and move to the Menu/overflow.

## 5. Selection, menus, settings (PR 5)
Long-press + drag range selection with edge auto-scroll, header checkboxes (`GRID:S1-S4, T6`); a bottom action pill in selection mode (share, delete, more). Overflow menus use Samsung's order and wording.

## 6. Video
Same chrome as photos; Media3 controls replaced by a Samsung-style play button and seek bar inside the bottom area; frame-thumbnail seek strip when the centred video is held (`VIEW:V16-V18`). After the selection PR.

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
- [ ] 0 Feel: pill blur, grid↔viewer shared element, grid fling
- [x] 1 Viewer: swipe up for details, swipe down to close (viewer PR 2)
- [x] 1 Viewer: favourite, delete
- [ ] 2 Grid shell, header, levels, headers, sort-aware fast scroll
- [ ] 3 Albums
- [ ] 4 People and Search
- [ ] 5 Selection and menus
- [ ] 6 Video
- [ ] 7 Access and loading (#505)
