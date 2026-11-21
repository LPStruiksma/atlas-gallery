# FIND-6 · Stories scoped search with a Whole library handoff (design frames)

**Pre-build design evidence.** [#797](https://github.com/LPStruiksma/atlas-gallery/issues/797) drew the frames on **current `main`** (legacy four-tab pill, header-icon popup). The independent [Stories preflight](https://github.com/LPStruiksma/atlas-gallery/issues/804#issuecomment-7057393168) found three Design: BLOCKING problems; [#872](https://github.com/LPStruiksma/atlas-gallery/issues/872) repairs them with a clearly separate **proposed target**. Parent implementation: [#804](https://github.com/LPStruiksma/atlas-gallery/issues/804); goal [#175](https://github.com/LPStruiksma/atlas-gallery/issues/175). Everything is synthetic (made-up story titles, abstract shapes, no media, no faces). None of it is a device capture, an implementation, or a Design: PASS.

Owner words: “all places where one can search, one has access to the full suite of search tools. when search is selected in a media screen, it by default only searches within that screen, but the user can toggle a fuller search if wanted” ([#175 acceptance](https://github.com/LPStruiksma/atlas-gallery/issues/175#issuecomment-6893800838)).

| Set | What it is | Where |
|---|---|---|
| **Current main** | What ships today: legacy four-tab pill, header search icon, `AtlasSearch` popup. Each frame carries the label “CURRENT MAIN (legacy shell, not the target)”. Not an identity-compliant design. | [`frames/`](frames/) (14 frames × light/dark, 1.7× font in S4), contact sheets [light](contact-sheet-light.png) · [dark](contact-sheet-dark.png) |
| **Proposed target** | Quiet Frame v3 composition: two-place slab with search sphere (L4), search bar above the slab (L7), shared `AtlasSearch` scope chips. Each frame carries “PROPOSED TARGET”. Not on `main`; shipping it needs L4 #657 and L7 #660 first. | [`frames/target/`](frames/target/) (13 frames × 100 % / 200 % × light/dark = 52 PNGs, plus `metrics.json`), contact sheets [light](contact-sheet-target-light.png) · [dark](contact-sheet-target-dark.png) |

Frame size: every portrait frame is exactly 1080 × 2340 px (384 × 832 dp at 2.8125), landscape 2340 × 1080. Annotation colour: orange = numbered badges, dashed 48 dp touch targets and assumed insets; green = measured clearance; dark label = frame code; hatched dashed band = where the IME would be (when hidden).

## How the three blocking findings are repaired

| Preflight finding | Repair here | Evidence |
|---|---|---|
| **6 · 200 % scope control clipped** (`Whole library` cut off in a non-wrapping 360 dp row) | Target: the scope chips live in the L7 panel (352 dp inner width) and **wrap to a second row** when they do not fit; each chip is at least 48 dp tall, label never clipped, selected state kept. Measured: 1 row at 100 %, 2 rows at 200 %, zero clipped labels. Current-main S4 stays as the **documented defect** (still clipped at 1.7×), because `AtlasSearch` on `main` is unchanged. | `T2`, `T3`, `T4`, `T6` at 200 %; `metrics.json` `chipRows`, `chipLabelsClipped`; [S4](frames/S4-large-text-light.png) |
| **6 · IME hides the last match** | Measured finding below: with the IME up there is **no room** for a whole tile, so the repair is a rule, not padding alone: results get bottom content padding equal to the whole bottom stack, **and the first drag on the results hides the IME** (bar and query stay). `T3` shows the end of the results with the last tile wholly clear. | `T2` vs `T3`; `metrics.json` `resultRegionDp`, `lastTileClearAboveStackDp` |
| **8 · legacy shell contradicts Quiet Frame v3** | Target frames use the approved slab + sphere + bar composition; legacy frames are labelled as current main only. Hand-off semantics from #824 are kept. | All `T*` frames |

### IME capacity (measured, 384 × 832 dp, IME 324 dp + 16 dp gesture inset, assumed)

| Font | Bar panel | Slab | Free for results above the stack | One Story tile | Consequence |
|---|---|---|---|---|---|
| 100 % | 122 dp (one chip row) | 64 dp, rides the IME like L7 A1 | **195 dp** | 284 dp | A whole tile cannot fit; first row cover and title are visible (T2). |
| 200 % | 204 dp (two chip rows) | 71 dp | **98 dp** | 372 dp | Only the top of the first cover (T2). Back and the bar stay reachable; the panel does not cover the header (checked). |
| Landscape 100 % / 200 % | 68 / 84 dp (one row) | hidden | **44 / 20 dp** | n/a | Results are effectively behind the IME; they return when the IME hides (T7). |

So “the last matching tile above the IME” is delivered by the **IME-dismiss-on-drag rule** plus bottom content padding: at rest after a drag (IME hidden) the last tile is **8 dp above the bar** at both scales (T3: 100 % region 519 dp, 200 % region 422 dp). Tapping the field again raises the IME over the lower part; it does not move the bar, and the next drag hides it again. **Implementation prerequisite for #804, not asserted as done:** Stories must (1) set content padding to the measured stack height + 8 dp, (2) hide the IME on results drag, (3) keep the 8 dp padding when the IME is hidden by the Search key or Back.

### Adaptation decisions (source → observed/proposed)

| Source | Observed on `main` / in the approved design | Decision for Stories |
|---|---|---|
| Quiet Frame v3 §Structure 1, 4 and Components | Approved: slab with Pictures, Collections and a search sphere; tapping the sphere grows a full-width bar above the slab; keep the full `AtlasSearch` suite | Adopt for the target. Stories is a Collections place, so the slab shows Collections active. The header search icon disappears in the target; Back and title stay. |
| [L4 geometry audit (#859)](../lapis/preflight/L4-GEOMETRY-AUDIT.md) | One slab at every font scale: pills 123 × 55 and 160 × 55 dp at 200 %, slab 359 dp wide with the sphere, 12 dp above the gesture inset | Used as drawn (checked: 359 dp, 12 dp). Pill widths are copied from the audit (Funnel metrics); Inter is a stand-in. |
| L7 board and `search_bar` geometry | Bar 8 dp from the edges, 22 dp surface, chip rows wrap, slab rides the IME and loses the sphere while the bar is open | Same. Scope chips are the existing `This screen` / `Whole library`. |
| FIND-5 and [#824](https://github.com/LPStruiksma/atlas-gallery/issues/824) | **Whole library** closes scoped search, releases the IME, clears the local query and opens existing global Search with an empty query. L7's focus-on-open is distinct from this transition. | Kept exactly (T6 → T8). Stories exits (`storiesOpen = false`), which clears any pending playback restore; no dialog (nothing unsaved); the query is not carried. |
| `AtlasSearch.kt` on `main` | Non-wrapping scope `Row` in a 360 dp `DropdownMenu`; hard-coded “This screen” | Needs the shared panel (L7) to wrap. This is a **shared-component prerequisite** (L7 #660, which owns `AtlasSearch` layout), not a Stories-only change. |

### Behaviour table for the target (focus, Back, ownership)

| State | Frame | Query owner and value | Scope | Focus / IME | Back goes to | Kept or discarded |
|---|---|---|---|---|---|---|
| Collapsed | T1 | none | n/a | none | closes Stories (#457) | deck, scroll |
| Bar open, typing | T2 | Stories-local, “summer” | This screen | field focused, IME up | hides the IME | deck unchanged, never widened |
| After drag | T3 | same | This screen | field unfocused, IME hidden | closes the bar and clears the query | scroll position kept |
| No match, IME up | T4 | “beach” | This screen | IME up | hides the IME | the Whole library chip is the one-tap action |
| No match, IME hidden | T5 | “beach” | This screen | IME hidden (Search key) | closes the bar | button **Search whole library** repeats the chip |
| Whole library tapped | T6 | cleared | Library | IME released, focus leaves | n/a | Stories exits; query and bar discarded; playback restore cleared |
| Arrived in global Search | T8 | empty | Library | **IME not raised** (open point: if L7/#824 decide a handoff focuses the field, this frame follows) | Pictures (host rule `tab ≠ 0 → tab = 0`) | nothing from Stories |
| Back from Search | T9 | n/a | n/a | n/a | n/a | Stories is not reopened with a half-open bar |
| Grant or catalogue change | TS3 | cleared (source `remember` keys) | This screen | none | closes Stories | deck follows the new data; hidden or denied items never appear |
| No stories | TS1 | none | n/a | none | closes Stories | the sphere stays: Whole library is still useful (copy unchanged from `main`) |
| Restored slideshow, deck planning | TS2 | none | n/a | none | closes Stories | “Getting your stories ready…” (copy unchanged) |
| Playback closed with a query | TS4 | kept | This screen | none | closes the bar | the bar is **shown open** with the query, so a filtered grid is never silent (fixes the lost `expanded` state recorded in S6) |

TalkBack and keyboard order (T2): Back, bar field “Search”, Close search, This screen (selected), Whole library, then the results. Outside tap or Back closes in the order above; no state returns an expanded popup after the host handoff. Search never reads other screens' history (`SearchHistoryScope.STORIES`) and never reveals unavailable or hidden media: it filters only titles and captions of the current deck.

### Landscape (T7)
Slab hidden while the IME is up (it would take 76 of 200 free dp; L4 does not define landscape, so this is an assumption to confirm in L4/L7). One row: field, two chips, Close; all at least 48 dp. Back stays visible. Results are not visible until the IME hides.

### Known limits of the target frames
- At 200 % the two-column grid truncates story titles to one line, as `main` does. A one-column layout at large font is a Stories design question for #804, not drawn here.
- Titles are fitted to one line at 200 % (precedent L4–L8). Font is Inter, not Funnel.
- Insets (status 29/24 dp, gesture 16 dp, IME 324/184 dp) are assumed, not measured on the S24+. Android 14+ scales large text non-linearly; 2× is the worst case.
- Material 3 `SearchBar` remains framework guidance only, not a Samsung screenshot.

## Regenerate and check

```bash
node docs/design/stories-search-175/render/render.mjs              # all frames, writes frames/ and frames/target/ and metrics.json
node docs/design/stories-search-175/render/render.mjs --target     # target set only (--only=T3-bottom-results for one frame)
node docs/design/stories-search-175/render/render.mjs --check      # measures every target frame, asserts the rules, compares metrics.json
python3 docs/design/stories-search-175/render/contact_sheet_target.py docs/design/stories-search-175/frames/target light docs/design/stories-search-175/contact-sheet-target-light.png 0.25
```
`--check` asserts: every touch target at least 48 dp, no clipped chip label, chips inside the panel, panel never covers the header, slab inside the screen and 12 dp above the inset, slab 359 dp wide at 200 % (L4), chips wrap at 200 %, empty-state text fits, the last tile in T3 is wholly clear, and `metrics.json` equals a fresh measurement. Committed PNGs are reduced to 256 colours with Pillow.

---

## Badge legend for the target frames

| Badge | Frame | Meaning |
|---|---|---|
| 1 / 2 / 3 | T1 | Search sphere (48 dp, in the slab) / Back 48 dp / Stories grid unchanged |
| 4 / 5 | T2 | Scope chips (both 48 dp+) / field with Close, focused |
| 6 | T3 | Final matching tile is 8 dp above the bar (green band), IME hidden by the drag |
| 7 | T4 | No-match copy sits in the free region; chip is the action |
| 8 | T5 | **Search whole library** button, 48 dp |
| 9 | T6 | Tap on Whole library: the handoff |
| 10 / 11 | T7 | Back stays visible / one-row bar with green results strip |
| 12 | T8 | Existing global Search, library scope, empty query |
| 13 | T9 | Pictures after Back |
| 14–17 | TS1–TS4 | Sphere stays when empty or planning / grant change reset / bar reopened after playback |

# Frames drawn on current `main` (from #797)

Superseded for the **shell and the 1.7× and IME states** by the target set above; kept as the record of what ships today. The “Proposed adaptation” section below describes the #804 behaviour (handoff, no-match copy) that the target frames keep; its header-icon placement and the notes that defer L4/L7 are the part #872 replaces.

## What the source on `main` does today (facts)

| Fact | Source |
|---|---|
| Stories header: Back (48 dp, "Back to gallery"), title "Stories", then `AtlasSearch(query, onQueryChange, historyScope = STORIES)`. No `onScopeChange`, no `selectedScope`. | `AtlasStoriesScreen.kt` |
| Typing filters the current deck's cards by `title + " " + caption` (`scopedNamedMatches`). | `AtlasStoriesScreen.kt`, `ScopedSearch.kt` |
| **Gap:** tapping **Whole library** only flips the chip locally. Nothing navigates, and the grid keeps filtering the deck. FIND-4 #317 flags this as a missing explicit LIBRARY handoff. | `AtlasSearch.kt` (`selectScope` → no-op `onScopeChange`) |
| **Gap:** a query with no match shows the empty state "Your stories will appear here", which reads as if there are no stories at all. | `AtlasStoriesScreen.kt` (`cards.isEmpty()`) |
| The query is `remember(currentGrantEpoch, currentCatalogRevision)`: a permission or catalogue change clears it. | `AtlasStoriesScreen.kt` |
| Playback (`StoryPlayback`) replaces the whole grid screen, including the header and search. Back closes the player and returns to the grid. | `AtlasStoriesScreen.kt` |
| Every other overview uses one host handoff for Whole library: close the overlay, then `tab = 3` (Search) with an empty query, for example `onSearchWholeLibrary = { hideAlbumsOpen = false; tab = 3 }`. Back from Search goes to Photos (`tab != 0 → tab = 0`). | `MainActivity.kt` |
| The floating nav pill shows over Stories and Stories content stays above it (`drawUnderBar` is false while `storiesOpen`). | `MainActivity.kt` |

## Proposed adaptation (what the Stories UI unit #804 would build)

1. Stories passes `selectedScope` and `onScopeChange` to its existing `AtlasSearch`, and the host passes `onSearchWholeLibrary = { storiesOpen = false; tab = 3 }`, the same handoff as Hide albums, People and the result screens. `storiesOpen = false` already clears any pending playback restore through `ClearStoryPlaybackWhenHidden`.
2. No confirmation dialog. Unlike Declutter (FIND-5), Stories holds no staged or unsaved choices, so nothing is lost by leaving.
3. The Stories query is **not** carried into global Search, matching every existing handoff. Story titles are generated names, not library terms. Carrying the query would be a separate, cross-screen decision.
4. A no-match state: "No stories match “beach”." with one text button, **Search whole library**, which runs the same handoff. It uses the existing `AtlasEmptyState(title, actionLabel, onAction)`; no new component.
5. No new search panel, no Stories-specific search UI and no change to the shared `AtlasSearch` API.

## How the frames were made, and what is assumed

| Item | Value | Source or status |
|---|---|---|
| Viewport | 384 × 832 dp at density 2.8125 = 1080 × 2340 px | S24+ default FHD+ resolution |
| Colours | Quiet Frame v3 Lapis tokens (ground, mist, ink, ink-secondary, brand-fill, brand-text) | [`QUIET_FRAME.md`](../QUIET_FRAME.md). Error red is the Material 3 default (not a Quiet Frame token) |
| Stories layout | Header row 10 dp × 8 dp padding; two-column grid, 16 dp sides, 12 dp × 18 dp gaps; 220 dp covers with 18 dp radius and a 57% black "N moments" band; `titleMedium` title (1 line), `labelSmall` caption (2 lines), error-colour "N items unavailable" | `AtlasStoriesScreen.kt` |
| Search panel | `DropdownMenu` anchored to the header icon: field + 48 dp Close, then `This screen` / `Whole library` chips (48 dp rows), then "Recent searches" (Stories' own history) when the query is blank; max width 360 dp | `AtlasSearch.kt`, `SearchHistoryUi.kt` |
| Panel look | 22 dp radius, mist surface, fully rounded field and chips, brand-filled selected chip | Quiet Frame (secondary surface 22 dp). Shape only; the implementation takes its colours from the shipped theme |
| Landscape panel | FIND-5 single-row layout (`atlasSearchSingleRow`: window height above the IME < 280 dp): 240 dp field, chips, Close in one 72 dp row | `AtlasSearch.kt` |
| Status bar | 29 dp portrait, 24 dp landscape | **Assumed.** Measure on the S24+ |
| Gesture nav | 16 dp | **Assumed.** Measure on the S24+ |
| Nav pill | 64 dp pill, 8 dp above gesture nav; drawn as the current four-item pill (Photos, Albums, People, Search) | Schematic. BRAND-L4 will replace it with the slab; the frames do not depend on it |
| Keyboard height | 324 dp portrait (CI emulator test IME, atlas #437), 184 dp landscape | Landscape **assumed**; Samsung Keyboard **unverified** |
| Font | Inter / Inter Display stand in for Funnel Sans / Funnel Display | Render container only |

Orange hatching marks insets. Dashed orange boxes mark 48 dp touch targets. Numbered badges point to the legend. Red marks a risk to verify on device. The green band (frame L) marks clearance above the keyboard.

## Frames and legend (the user path is A → B → C1 → D1 → D2 → D3)

| Frame | What it shows |
|---|---|
| **A-collapsed** | **1** One search icon in the existing header slot, 48 dp, TalkBack "Search this screen". No new chrome; Stories stay the main content. **2** Back keeps "Back to gallery" and closes Stories (#457). **3** Grid unchanged. |
| **B-expanded** | **4** Field **not auto-focused** (`AtlasSearch` has no focus requester), so the keyboard stays down until the field is tapped. **5** `This screen` is selected by default and means *this deck only*. The label stays "This screen" because the shared component hard-codes it. **6** Close (48 dp, "Close search"), an outside tap or Back closes the panel and clears the query. **7** Recent searches are Stories' own (`SearchHistoryScope.STORIES`), never another screen's. |
| **C1-keyboard-typing** | **8** Typing "summer": only cards of the current deck whose title or caption match stay visible, with the match underlined. The deck itself is never widened and never re-planned. **9** *Risk (red line):* the Stories surface has no IME padding, so the keyboard covers the lower grid and the last match can sit behind it. Proposal: give the grid a bottom content padding of the IME height while the keyboard is up, so every match can scroll above it. The Search key hides the keyboard. |
| **C2-no-match** | **10** Proposed copy: "No stories match “beach”." replaces today's misleading "Your stories will appear here". **11** One text button, **Search whole library** (48 dp row), runs the same handoff as the chip. It sits between the panel and the keyboard. |
| **D1-whole-library** | **12** A deliberate tap on **Whole library** leaves Stories at once, with no dialog (nothing unsaved). The host sets `storiesOpen = false`, which also clears a pending playback restore, then `tab = 3`. The Stories query is dropped. Story playback state and the deck are untouched. |
| **D2-search-tab** | **13** The existing global Search (persistent inline `AtlasSearch` + `LocalSearchControls`) opens unchanged, with an empty query and the library scope. **14** The nav pill shows Search active. There is no standalone Stories search panel anywhere. |
| **D3-back** | **15** System Back on Search goes to Photos, as for every other library handoff (`tab != 0 → tab = 0`). Stories is not reopened with a half-open panel; it stays one tap away in Albums. *Open question for the preflight:* should Back return to Stories instead? That would change the shared host rule for all handoffs, so it is not proposed here. |
| **L-landscape-keyboard** | **16** With the keyboard up in landscape, the existing FIND-5 single-row panel applies (field, This screen, Whole library, Close), so neither chip sits under the keyboard. **17** Back (48 dp) stays visible. **18** About 48 dp stays clear above the IME (green). Only a strip of the grid is visible above the keyboard; it returns when the keyboard hides. TalkBack order: field, This screen (selected), Whole library, Close. |

## State variants

| Frame | State | Notes |
|---|---|---|
| **S1-no-stories** | Empty deck | **19** The search icon stays: This screen has nothing to filter, but Whole library is still a useful exit. The DESIGN-6 quiet state stays as is. |
| **S2-planning** | Restored slideshow waiting for its re-planned deck (#457) | **20** "Getting your stories ready…" as on `main`. Typing filters nothing until the deck arrives; Whole library works at once. |
| **S3-unavailable-items** | Error and permission change | **21** A story with missing items keeps its error-colour "3 items unavailable" line while filtered. Search never reveals unavailable or hidden media: it only reads titles and captions of the current deck. **22** A grant or catalogue change clears the query (source `remember` keys), and the grid follows the new deck. Whole library hands off to global Search, whose own grant and hidden-album rules apply. Stories adds no error state of its own; video errors stay in the player ("This video could not be played" / "Retry video"). |
| **S4-large-text** | Font scale ≈ 1.7×, long titles, a 142-moment story | **23** *Risk (red box):* the two chips no longer fit the 360 dp panel row and **Whole library is clipped**. This affects every screen using `AtlasSearch`, so it belongs to the shared component (allow the chip row to wrap), not to Stories. Measure on the S24+ at the largest font size. **24** Long titles stay on one line with an ellipsis and captions on two, as in the source; the moment count band grows with the text. |
| **S5-one-handed** | Reach | **25** The panel opens at the top beside the existing header icon, in the hard-reach zone, as on every overview screen. Results and the no-match action stay lower. Moving search to the bottom is Quiet Frame BRAND-L7 (search bar above the slab), a separate shared unit; Stories inherits it there. |
| **S6-playback** | Playback active | **26** No search while a story plays: the player replaces the grid, as on `main`. **27** Close or Back returns to the grid. *Proposal:* when the grid comes back with a non-empty query, show the panel open with that query, so a filtered grid is never shown without its query. Today the query is kept in `AtlasStoriesScreen` but the panel's `expanded` state is lost, so the grid stays silently filtered. |

## Accessibility and interaction notes (proposed; to verify on device)

- **Touch targets.** Back, search icon, Close, both chips and the no-match button are 48 dp rows.
- **TalkBack.** Icon "Search this screen", field "Search", Close "Close search". Chips read their selected state ("This screen, selected"). Whole library reads as a chip and moves focus to the Search tab's field area after the handoff.
- **Focus.** Opening the panel does not move focus into the field. Tapping the field raises the keyboard. Close returns focus to the icon.
- **Back order.** Keyboard up → hide the keyboard. Panel open → close the panel and clear the query. Panel closed → leave Stories (#457). On the Search tab after a handoff → Photos.
- **Contrast.** Quiet Frame lists ink-secondary at 6.26:1 (light) and 8.09:1 (dark), and brand-text at 8.36:1 / 8.25:1. The white "N moments" label sits on a 57% black band over a photo; check actual compositions on device.
- **Motion.** The panel uses the platform `DropdownMenu` transition; with "remove animations" it cuts.

## Reference provenance and differences

| Reference | What was used | Difference |
|---|---|---|
| [`AtlasStoriesScreen.kt`](../../../app/src/main/java/nl/atlas/gallery/featurestories/AtlasStoriesScreen.kt) | Header, grid, tile, empty, planning and playback layout | No-match copy and handoff are proposals (C2, D1) |
| [`AtlasSearch.kt`](../../../app/src/main/java/nl/atlas/gallery/featuresearch/AtlasSearch.kt) | Panel structure, chips, Close, single-row landscape rule | Unchanged; S4 records a shared risk |
| [Declutter preflight frames](../declutter-search-341/) (FIND-5, merged #650) | Shared icon, chip, keyboard and landscape pattern, annotation style | No confirm dialog here, because Stories has nothing to lose |
| [`QUIET_FRAME.md`](../QUIET_FRAME.md) | Lapis colour tokens, 22 dp surface radius, 48 dp targets | Funnel fonts replaced by Inter in the render only |
| [Material 3 SearchBar](https://developer.android.com/develop/ui/compose/components/search-bar) | Documented framework pattern: collapsed entry point, expanded search with results under it, Back collapses first | ATLAS keeps its shared anchored `AtlasSearch` panel instead of a full-screen `SearchBar`. This is a framework reference, **not** a claim about Samsung Gallery |

## Still pending

The Stories UI implementation ([#804](https://github.com/LPStruiksma/atlas-gallery/issues/804)) needs a fresh independent design preflight of the target frames above, a synthetic scope and navigation test (coordinate with test-only [#317](https://github.com/LPStruiksma/atlas-gallery/pull/317), whose Stories entry should then pass), real S24+ light and dark captures at the implementation head (insets, Samsung Keyboard height, popup bounds, risks 9 and 23), and exact-head CI. The S4 chip-row risk is a shared `AtlasSearch` follow-up (L7 #660), not part of the Stories unit. No #804 Kotlin until L4 #657 and L7 #660 are implemented and merged, unless Strategy re-plans with an owner-approved interim-shell exception (none granted).
