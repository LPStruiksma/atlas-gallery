# FIND-5 · Declutter search with a Whole library handoff (design frames)

**Pre-build design evidence for [atlas-gallery #341](https://github.com/LPStruiksma/atlas-gallery/issues/341).** These are proposed ATLAS screens drawn to the source on `main` (c715f85, 2026-11-18). They are **not** device screenshots and **not** proof of an implementation. Everything shown is synthetic: the file names are made up, and the "photos" are abstract shapes drawn from the `AtlasTokens.SyntheticArt` palettes. No personal media was used.

- Contact sheets: [light](contact-sheet-light.png) · [dark](contact-sheet-dark.png)
- Frames: [`frames/`](frames/). There are 13 frames, each in light and dark. Every frame is exactly 1080 × 2340 px (landscape is 2340 × 1080).
- Generator: [`render/frames.html`](render/frames.html) and [`render/render.mjs`](render/render.mjs). Run `node docs/design/declutter-search-341/render/render.mjs [outDir] [--clean] [--only=<frame>]`. `--clean` drops the orange annotations; `--only` re-renders one frame. The committed PNGs are then reduced to 256 colours with Pillow to keep the repository small.

## How the frames were made, and what is assumed

| Item | Value | Source or status |
|---|---|---|
| Viewport | 384 × 832 dp at density 2.8125 = 1080 × 2340 px | S24+ default FHD+ resolution |
| Colours | Light and dark `lightGalleryColors` / `darkGalleryColors`; page = `surfaceContainerLowest` | `AtlasTokens.kt`, `AtlasGalleryDesign.kt` |
| Declutter layout | `safeDrawing` padding plus 12 dp horizontal and 4 dp vertical, 8 dp gaps, 56 dp header, list rows with 64 dp thumbnails, `Keep` text buttons, `Return to swiping`, full-width Trash button | `DeclutterScreen.kt` |
| Search panel | `AtlasSearch` icon → `DropdownMenu`: 280 dp text field plus 48 dp close button, then the `This screen` / `Whole library` filter chips (48 dp touch rows) | `AtlasSearch.kt`; M3 TextField minimum width 280 dp |
| Status bar | 29 dp | **Assumed.** To measure on the S24+ |
| Gesture nav | 16 dp | **Assumed.** To measure on the S24+ |
| Keyboard height | 324 dp (912 px) portrait, 184 dp landscape | The portrait value is the test IME measured on the CI emulator (atlas #437). The landscape value is assumed. The Samsung Keyboard height is **unverified** |
| Font | Inter standing in for Roboto | Render container only |
| Popup position | Right edge of the panel aligned to the search icon, top edge at the icon's bottom | M3 `DropdownMenu` default. Exact device bounds are **unverified** |

Orange hatching marks insets. Dashed orange boxes mark 48 dp touch targets. Numbered badges point to the legend below. The green band (frame L) marks the clearance between the panel and the keyboard.

**Colours.** Frame L was revised on 2026-11-18 after Quiet Frame ([`QUIET_FRAME.md`](../QUIET_FRAME.md)) was approved, so it uses the Quiet Frame tokens: ground, surface, ink and accent colours, fully rounded chips and field, and a 20 dp panel radius. The other frames still use the earlier palette. The frames decide layout and behaviour; the implementation takes its colours from Quiet Frame.

## Frames and legend

| Frame | What it shows |
|---|---|
| **A-swipe-collapsed** | Swiping state as it is on `main`. **1** The search icon sits in the existing header action slot, with a 48 dp target and the TalkBack label "Search this screen". No new chrome. **2** Back keeps its label "Back to Gallery" and its behaviour (`closeDeclutter`). **3** The card, Stage/Keep and Undo/Review are unchanged. |
| **A-review-collapsed** | Review staged files, search collapsed. **4** The staged rows stay the main content. |
| **B-expanded** | The icon is tapped and the shared panel opens. **5** The query field is **not auto-focused**, because `AtlasSearch` has no focus requester, so the keyboard stays down until the field is tapped. This keeps the source behaviour. **6** `This screen` is selected by default. The chip label stays "This screen", because the shared component hard-codes it; "This cleanup" would need a shared API change that affects other screens, so it is not proposed. **7** Close (48 dp, "Close search") clears the query and collapses the panel. Tapping outside or pressing Back does the same through `onDismissRequest = close`. The staged rows stay visible below the panel. |
| **C-keyboard** | Typing "VID". **8** The field is focused and the filter is live. Only the staged list is filtered, by name and album (`scopedNamedMatches`). **9** Because the Declutter column pads for `safeDrawing`, which includes the IME, `Return to swiping` and the Trash button move up above the keyboard. **The Trash count stays at the full 12.** Search never changes what gets trashed. The Search key hides the keyboard. |
| **D-confirm-leave** | **10** The user picks Whole library while choices are staged. **Fact:** leaving Declutter (`closeDeclutter`) drops the staged session today, and Back does so silently. **Proposal:** when `stagedCount > 0`, show a dialog: "Search whole library?" / "This leaves Declutter. Your 12 staged choices will be cleared. Nothing is moved to Trash." with the buttons **Stay** and **Search library**. Stay sets the scope back to This screen and keeps everything. With nothing staged, the app goes straight to D2. |
| **D-search-tab** | **11** The host runs `closeDeclutter()`, sets `overviewQuery = ""` and `tab = 3`. This copies the existing album/person handoff in `MainActivity` (the `onScopeChange` LIBRARY branch). The global Search tab opens with an empty query. Declutter's query never reaches Search. **12** The Search tab itself is unchanged and drawn here as a schematic. From there, Back follows the Search tab's normal back stack. Declutter does not reopen with a popup half open. |
| **E-swipe-expanded** | Search while swiping. **13** A note in the panel says "Searches your 12 staged files." (using the existing `suggestionsTool` slot) above a **Show staged files** button, which sets `reviewing = true`. The deck, the current card and `session.position` are never changed, and `Return to swiping` comes back to the same card. Nothing is filtered silently. |
| **S1-empty** | **14** Nothing is staged. The list says "No staged files yet" / "Swipe left on a photo to stage it." Search stays usable and Whole library goes straight to D2, with no dialog. The Trash button is disabled (source: `pending.isNotEmpty()`). |
| **S2-no-match** | **15** "No matching staged files" / "Clear the search to see all 12." **16** The Trash button still says 12, because the filter is only a view. |
| **S3-verifying** | **17** The existing `atlas-declutter-verifying` progress bar shows. The `Keep` buttons and the Trash button are disabled, as on `main`. Search cannot enable any of them. |
| **S4-error** | **18** This is the existing host error: a Toast from `MainActivity`, "Couldn't verify selected files. Nothing was trashed." Retrying means pressing Move again. Search adds no error state of its own, because it is a local in-memory filter with no IO. The Toast is drawn here; Android draws the real one. |
| **S5-long** | **19** 214 staged files. The list is a `LazyColumn` with 160 px thumbnails and no eager decode. The existing max-200 guard disables Move and shows its message. A filter does not get around the guard, because it counts all staged files, not just the matches. |
| **L-landscape-keyboard** | *Revised 2026-11-18 after preflight [#6988128847](https://github.com/LPStruiksma/atlas-gallery/issues/341#issuecomment-6988128847) found the chip row under the keyboard.* **20** **Inset-aware single-row panel.** When the window has less than about 280 dp of height above the IME (landscape with the keyboard up), `AtlasSearch` lays out the query field (240 dp), **This screen**, **Whole library** and **Close** in one row instead of two. The panel is 72 dp tall (8 + 56 + 8), so its bottom sits at about 152 dp, against a keyboard top at about 200 dp (184 dp landscape IME, assumed). Portrait keeps the two-row panel (frames B and C). Proposed implementation: the shared component measures `WindowInsets.ime` and the window height, switches to the row layout below that threshold, and relaxes its 360 dp width cap only in that layout. No new component and no change to the swipe deck. **21** **Back** (header, 48 dp) stays visible above the keyboard. **22** About 48 dp stays clear above the IME (green band). The Move button stays above the keyboard through `safeDrawing`, and its count stays at 12. **Search does not need the keyboard dismissed first:** the field keeps focus and the cursor while either chip is tapped. Tapping **Whole library** goes straight to the D1 dialog (or D2 when nothing is staged), and the keyboard closes as the panel closes. **Focus and Back:** system Back with the keyboard up hides the keyboard first, leaves the panel open and keeps the query. A second Back closes the panel and clears the query. A third Back leaves Declutter, as on `main`. While the panel is open, a tap on the header Back button first closes the panel (a `DropdownMenu` popup takes outside taps), as in portrait. Close (48 dp) clears the query and collapses the panel. TalkBack order: field, This screen (selected), Whole library, Close. **Still true:** in landscape with the keyboard up, the `safeDrawing` column leaves no staged rows visible. They come back when the keyboard hides. Measure the real Samsung Keyboard height and popup bounds on the S24+ before merge. |

## Accessibility and interaction notes (proposed; to verify on device)

- **Touch targets.** Back, the search icon, close and the chip rows are 48 dp. The dialog buttons are text buttons with 48 dp rows.
- **TalkBack.** The icon reads "Search this screen", the field "Search", close "Close search". FilterChip reads its selected state, for example "This screen, selected". Whole library reads as a chip and opens the D1 dialog or the D2 Search tab.
- **Focus.** Opening the panel does not move focus into the field (source behaviour). Tapping the field raises the keyboard. Close returns focus to the icon. The dialog focuses its title. Stay returns focus to the panel.
- **Back order.** Keyboard up → hide the keyboard. Panel open → close the panel and clear the query. Panel closed → leave Declutter (unchanged).
- **Contrast.** Text and chip colours come from the shipped colour schemes. Measured contrast is a check for the pre-merge render on the device.
- **One-handed reach.** The panel sits at the top, next to the existing header icon, as on other overview screens. `Return to swiping` and Move stay at the bottom, above the keyboard. Moving the search to the bottom would mean new chrome, which the brief rules out.

## Still pending

Pre-merge evidence: real S24+ screenshots at the implementation head (insets, Samsung Keyboard height, popup bounds, landscape risk 20), a synthetic scope and navigation regression test (coordinate with the draft test-only [#317](https://github.com/LPStruiksma/atlas-gallery/pull/317)), and exact-head CI.
