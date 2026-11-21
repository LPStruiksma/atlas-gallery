# FIND-6 · Stories scoped search with a Whole library handoff (design frames)

**Pre-build design evidence for [atlas-gallery #797](https://github.com/LPStruiksma/atlas-gallery/issues/797)** (parent [#175](https://github.com/LPStruiksma/atlas-gallery/issues/175); gap found by [FIND-4 PR #317](https://github.com/LPStruiksma/atlas-gallery/pull/317)). These are proposed ATLAS screens drawn to the source on `main` at `004e944` (2026-11-21). They are **not** device screenshots and **not** proof of an implementation. Everything shown is synthetic: the story titles are made up and the "photos" are abstract shapes. No personal media or faces were used.

Owner words: “all places where one can search, one has access to the full suite of search tools”; media-screen search defaults to that screen and may widen to the whole library ([#175 acceptance](https://github.com/LPStruiksma/atlas-gallery/issues/175#issuecomment-6893800838)).

- Contact sheets: [light](contact-sheet-light.png) · [dark](contact-sheet-dark.png)
- Frames: [`frames/`](frames/). 14 frames, each in light and dark. Every portrait frame is exactly 1080 × 2340 px; the landscape frame is 2340 × 1080 px.
- Generator: [`render/frames.html`](render/frames.html) and [`render/render.mjs`](render/render.mjs). Run `node docs/design/stories-search-175/render/render.mjs [outDir] [--clean] [--only=<frame>]`. `--clean` drops the orange annotations. Contact sheets: [`render/contact_sheet.py`](render/contact_sheet.py). The committed PNGs are reduced to 256 colours with Pillow to keep the repository small.

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

The Stories UI implementation ([#804](https://github.com/LPStruiksma/atlas-gallery/issues/804)) needs independent design preflight of these frames, a synthetic scope and navigation test (coordinate with test-only [#317](https://github.com/LPStruiksma/atlas-gallery/pull/317), whose Stories entry should then pass), real S24+ light and dark captures at the implementation head (insets, Samsung Keyboard height, popup bounds, risks 9 and 23), and exact-head CI. The S4 chip-row risk is a shared `AtlasSearch` follow-up, not part of the Stories unit.
