# SORT-1 · design preflight revision 3 (SORT-1D)

**Status: synthetic proposals only.** These drawings are **not** current Android UI, S24+ device screenshots, Samsung captures, permission to implement, or evidence of a running emulator. [Repair #869](https://github.com/LPStruiksma/atlas-gallery/issues/869) addresses findings [#4, #6, #8](https://github.com/LPStruiksma/atlas-gallery/issues/177#issuecomment-7056795151) on [parent design preflight #177](https://github.com/LPStruiksma/atlas-gallery/issues/177) under [goal #175](https://github.com/LPStruiksma/atlas-gallery/issues/175). No app or protected paths changed.

## Exact contract and distinction from revision 2

Live-main [`MediaSortSheet`](../../app/src/main/java/nl/atlas/gallery/PhotosHeader.kt) has **five** standard full-row radio choices, each `heightIn(min = 52.dp)`: Date created, Date modified, File name, Size, Random. Search results and Find similar (including face results) prepend **Relevance** as the initial **sixth** key. Direction-supporting date/name/size choices render two **vertical** selectable 52dp radio rows, labelled **Ascending** and **Descending**. Relevance has **no direction or shuffle**; Random has a **Shuffle again** 52dp button, not direction. The 52dp selectable **region** is each entire row, not a small label. Revision 2's repaired sixth-row clipping and >=48dp hit areas remain preserved; its erroneous schematic horizontal Newest/Oldest buttons are superseded.

The sheet appears over a still-visible scoped grid, without losing selected media or the existing search query. Sort persists independently per host, query changes reset only Search/Find similar to Relevance, and Back/drag/scrim dismiss without applying another choice. A new key or direction updates only that scope's order; stable ties and Random seeds are implementation obligations, not observed behavior.

## 24 inspectable light/dark drawings

Actual linked SVG **pictures**, not descriptions-only. They declare 1080×2340 px with illustrative 360×780 dp viewBox (3 illustrative px per dp, **not** measured density). Every depicted screen title uses **Funnel Display 700, 38/44 sp, −0.03em, brand-text**; other copy Funnel Sans. These browser SVGs cannot embed protected/personal fonts and use a sans-serif fallback when Funnel is missing; **no pixel-exact text metrics** are asserted.

| Host or state | Light | Dark | Visual contract |
| --- | --- | --- | --- |
| Album · Date created / Descending | [Open](evidence/sort1-rev3/album-date-light.svg) | [Open](evidence/sort1-rev3/album-date-dark.svg) | Five full key rows and two stacked directions |
| Folder · File name / Ascending | [Open](evidence/sort1-rev3/folder-name-light.svg) | [Open](evidence/sort1-rev3/folder-name-dark.svg) | Independent filename choice |
| Person · Size / Descending | [Open](evidence/sort1-rev3/person-size-light.svg) | [Open](evidence/sort1-rev3/person-size-dark.svg) | Person scope and selected direction |
| Search results · Relevance | [Open](evidence/sort1-rev3/search-relevance-light.svg) | [Open](evidence/sort1-rev3/search-relevance-dark.svg) | Six complete keys, query retained, no direction |
| Find similar · Relevance | [Open](evidence/sort1-rev3/similar-relevance-light.svg) | [Open](evidence/sort1-rev3/similar-relevance-dark.svg) | Results-only initial choice, context retained |
| Stories · Random | [Open](evidence/sort1-rev3/stories-random-light.svg) | [Open](evidence/sort1-rev3/stories-random-dark.svg) | Shuffle again without direction |
| Trash/Declutter · Size | [Open](evidence/sort1-rev3/trash-size-light.svg) | [Open](evidence/sort1-rev3/trash-size-dark.svg) | Current trash selection/actions unchanged |
| Search empty | [Open](evidence/sort1-rev3/search-empty-light.svg) | [Open](evidence/sort1-rev3/search-empty-dark.svg) | Empty results and query retained |
| Four missing states | [Open](evidence/sort1-rev3/state-matrix-light.svg) | [Open](evidence/sort1-rev3/state-matrix-dark.svg) | **Pictured** loading sphere/progress, error/retry, revoked permission, progressive 250k grid |
| Search IME sequence | [Open](evidence/sort1-rev3/ime-transition-light.svg) | [Open](evidence/sort1-rev3/ime-transition-dark.svg) | **Pictured** keyboard visible → dismissed → Sort/Relevance, query `canal` retained |
| 200% terminal direction | [Open](evidence/sort1-rev3/font200-direction-light.svg) | [Open](evidence/sort1-rev3/font200-direction-dark.svg) | Last keys, both 72dp direction rows after scrolling |
| 200% terminal shuffle | [Open](evidence/sort1-rev3/font200-shuffle-light.svg) | [Open](evidence/sort1-rev3/font200-shuffle-dark.svg) | Last Random row, reachable 72dp Shuffle again |

All media thumbnails are generated neutral shapes, never actual photos. The held sphere represents **media selection/loading**; the sort choices use conventional radio indicators, not an invented held-sphere control.

## Geometry and accessibility proposal

| Item | Declared geometry and intent | Pending Android validation |
| --- | --- | --- |
| Canvas / insets | 360×780 illustrative dp; top 24dp and gesture y757–780 (23dp), not runtime insets | Real `WindowInsets`, density, keyboard overlap, landscape |
| Sort and normal rows | Sort 52×52dp. Five keys y310–570, 52dp each; vertical direction rows y616–720, 52dp each | Compose click bounds, focused radio semantics, Back and scrim |
| Results-only sixth row | Relevance plus five options y364–676, each complete and non-overlapping | TalkBack focus, long labels/RTL and scroll after IME |
| 200% scrolled terminal | `data-scroll-offset-dp=240`; all in-app text doubles its declared baseline (38→76 title, 13→26 subtitle, 14→28 Sort, 20→40 sheet heading, 12→24 scroll hint, 17→34 Direction, 15→30 options/shuffle). Wider 116×72dp Sort entry; end-scrolled 72dp direction/Shuffle controls stay fully above the gesture inset | Real fontScale=2.0, fallback glyph dimensions, swipe/focus order, one-hand reach |
| Fallible states | Pictured loading with count/held sphere, actionable retry/permission, incremental 250k grid | Permission re-grant, actual large library and error states |

The 200% frames show **after scrolling**, not an impossible claim that the whole sheet fits without scrolling. The Python audit now rejects any undoubled text labels and checks text baselines against enlarged controls, the scroll-hint clearance and the final action's safe-area position; it does not measure font glyph extents or prove Android font scaling. The IME frames show keyboard **dismissed before sheet opening**, not both overlaid. Large translations, RTL, high contrast, rotation and focus testing remain implementation-stage work.

## Per-host adaptation versus reference

| Existing host | Proposed placement, content retention and persistence | Dismissal / accessibility |
| --- | --- | --- |
| Album, Folder, Person | One 52dp Sort action in the current scoped header; grid stays visible; distinct saved choice per host | Back/scrim/drag dismiss, selected radio announcement, five keys then direction |
| Search, Find similar, face search | Sort in the **results context**, preserving Quiet Frame search sphere and query; no duplicate global search control | IME dismisses before sort; six results-only options; new query resets only that result scope |
| Stories | Sort affects overview order, not playback | Random offers Shuffle again; selection stays |
| Trash / Declutter | Sort only affects presentation, not recover/delete/permission actions | Pending selection and Back behavior retained |
| Same person? [#170](https://github.com/LPStruiksma/atlas-gallery/issues/170) | **Conditional after expanded grid exists**; no fabricated new host frame | Keep Yes/No/Undo and add its design proof later |

Official [Android ModalBottomSheet guidance](https://developer.android.com/develop/ui/compose/components/bottom-sheets) describes modal and dismissal conventions; official [SearchBar guidance](https://developer.android.com/develop/ui/compose/components/search-bar) describes query/IME interaction. Neither is an observed Samsung device screenshot. [Quiet Frame v3](QUIET_FRAME.md) is the visual authority: white/black ground, mist #F2F3F8/#14152A, lapis solid fill #2639C9/#3448E0, brand-text #2639C9/#8C9BFF. A static Samsung 15.9.00.45 research report does **not** establish exact Samsung placement. These are documented ATLAS adaptations, not claims of a copied or observed screen.

**Traceability:** finding #4 → selected date/name/size, Random and Relevance key-conditioned frames; #6 → empty/fallback, keyboard sequence and both scrolled 200% end states; #8 → title attributes and fallback limitations on every SVG. [Generator](evidence/sort1-rev3/generate.mjs) and [Python audit](evidence/sort1-rev3/audit.py) check structural geometry and coverage, **not** device UX. The evidence PR needs exact-head CI and separate Review. Then #177 needs an **independent issue-level Design PASS against merged evidence** before any production implementation can start.
