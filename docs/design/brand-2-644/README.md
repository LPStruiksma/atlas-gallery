# BRAND-2 · Meridian pre-build targets (evidence for #638)

**Pre-build design evidence for [BRAND-2 #638](https://github.com/LPStruiksma/atlas-gallery/issues/638), made under [#644](https://github.com/LPStruiksma/atlas-gallery/issues/644).** These are **proposed targets**, drawn from the merged Meridian spec ([`QUIET_FRAME.md`](../QUIET_FRAME.md), BRAND-1 PR #641) and the source on `main` (a0bb86c, 2026-11-20). They are **not** emulator or device renders and they prove nothing about an implementation. The paired before/after renders at the implementation head stay #638's pre-merge proof.

Everything is synthetic: the "photos" and portraits are abstract gradients and circles generated from a fixed seed, and the names (Mira, Joost, …) are made up. No photograph, face, library content or owner data is used, and nothing was copied from the board's own artwork.

- Sheets: [targets light](targets-sheet-light.png) · [targets dark](targets-sheet-dark.png) · [states light](states-sheet-light.png) · [states dark](states-sheet-dark.png)
- Annotated targets at 100% and 200% font: [`targets/`](targets/). The same frames without annotations at 100%: [`targets/clean/`](targets/clean/). State frames at 100%: [`states/`](states/).
- Every frame is exactly 1080 × 2340 px (384 × 832 dp at density 2.8125, the S24+ default FHD+).
- Generator: [`render/frames.html`](render/frames.html) and [`render/render.mjs`](render/render.mjs): `node docs/design/brand-2-644/render/render.mjs [outDir] [--only=<frame>]`. It needs Playwright and Google Fonts, and fails if Instrument Sans or IBM Plex Mono did not load. The committed PNGs were reduced to 256 colours with Pillow.
- Contrast: [`render/contrast.mjs`](render/contrast.mjs) checks every text/background pair the frames use (table below).

## Source and how it was read

| Item | Value |
|---|---|
| Board | Private Claude Design canvas "Atlas identity concepts", artboards `project/Meridian-identity.dc.html` ("1 Meridian: identity sheet") and `project/Meridian-screens.dc.html` ("1 Meridian: Pictures, viewer, People"), version 1795136516-5596. A Claude seat on the owner's account read both files in full on 2026-11-20; no access problem. |
| Observed pattern | Identity sheet: mark, `ATLAS` wordmark in IBM Plex Mono, the colour swatches, Instrument Sans + Plex Mono type pairing, meridian rail, numbered selection, hairline loading, chart-grid empty state, Plot motion. Screens: three 390 × 844 dark/light concept phones (Pictures grid with fast-scroll rail, viewer with filmstrip pole dot, People with a "Same person?" card). |
| Spec | `QUIET_FRAME.md` v2 colour and type tables, used verbatim. |
| Layout source | `main`: `AtlasGalleryHeader` (56 dp row, title + count line, search, ⋮), `AtlasGalleryNavigation` (60 dp floating pill, 12 dp edges, Photos · Albums · People · Search, labels capped at 1.3× by #413), `GalleryViewer` (back pill; Find this person, Find similar, ⋮, Details pill; 30 × 40 dp filmstrip, centre ×1.25; favourite · edit · share · delete pill), `PersonPageScreen` (132 dp portrait, name, count, `SamePersonCard` with No / Yes), `AtlasSettingsScreen`, `PersonNameDialog`, `PeopleScanProgress`, the first-scan error in `MainActivity`. |

## Assumptions

| Item | Value | Status |
|---|---|---|
| Status bar | 29 dp | **Assumed** (as in the #341 frames). Measure on the S24+. |
| Gesture nav | 16 dp | **Assumed.** Measure on the S24+. |
| Keyboard | 324 dp | CI test IME (atlas #437). Samsung Keyboard height **unverified**. The keyboard is drawn as a neutral schematic: it is Android's, not ATLAS's. |
| 200% font | Android 14+ non-linear font scaling, AOSP `FontScaleConverterFactory` table for 2.0 (8→16, 10→20, 12→24, 14→26, 18→30, 20→32, 24→36, 30→40, 100→100 sp, interpolated; sizes and line heights both converted, as Compose does) | From the AOSP table; confirm against a 200% device render. |
| Icons | Schematic Material-style glyphs | BRAND-2 changes no icon glyphs. |

Magenta (`#D6249F`, deliberately outside the Meridian palette) is annotation only: hatching marks insets, dashed boxes mark ≥ 44/48 dp touch targets, numbered badges point to the legends below, and the top tag says what each frame is.

## Targets: legend (100% and 200%)

| Frame | 100% | 200% |
|---|---|---|
| **T1-photos** | **1** `Photos`, screen title, Instrument Sans 600 34/40, −0.02em, on `ground`. **2** Count line `12,305 photos & videos` in IBM Plex Mono 11/16 `ink-faint`; copy unchanged (case unchanged, no upper-casing). **3** Date header `19 Nov` in Instrument Sans 600 15/20; the board's `Today · 19.11 · AMSTERDAM` is **not** adopted, because place names in headers would be new copy. **4** Square tiles, 2 dp gap, 4 columns: photos stay the dominant area. **5** Existing floating pill, recoloured: `raised` at 80% (dark) / `surface` at 90% with its shadow (light); selected tab `ink` with the filled glyph, other tabs `ink-muted`. | Title 34 → ~43 sp, header row grows from 56 dp; the grid keeps its tile size, so photo priority holds and only fewer rows fit. The count line wraps to two lines at ≥ 150% instead of ellipsizing (**proposed fallback**, same element, no new chrome; it still folds away on scroll per #641). Nav labels stay capped at 1.3× (existing #413 fallback), so the 60 dp pill and its tab targets keep their size. |
| **T2-viewer** | Black `viewer ground` in both themes; chrome uses the dark tokens. **1** Back pill. **2** Action pill: Find this person, Find similar, ⋮, Details, each a 48 dp target; icons `#EEF1F5` (`ink` dark) instead of pure white. **3** Photo full-bleed, untouched. **4** Filmstrip as on `main`: 30 × 40 dp, 2 dp gaps, centre ×1.25; track colour `FilmstripTrack #2A2A2A` → `line`. **5** Bottom pill favourite · edit · share · delete; favourite uses `accent` (`Viewer.Favourite #FF4D5E` → accent, per #638 Done-when 1). | Identical: viewer chrome has no visible text, so font scale changes nothing on screen; icons stay 24 dp and targets 48 dp. TalkBack labels unchanged. |
| **T3-same-person** | People → person page. **1** Name, Instrument Sans 600 28/34. **2** `412 photos` as a Plex Mono meta line. **3** The existing `SamePersonCard` on `surface` (20 dp radius per spec): portrait, `Same person?` (16/22, 600), `38 more faces` as mono meta. **4** `No` and `Yes` text buttons in `accent` (on `surface`, 5.43:1 light / 9.84:1 dark), 48 dp tall. **5** Nav pill, People selected. | **Explicit fallback:** at ≥ 150% the card puts No / Yes on a second row, right-aligned, under portrait + text, because one row would need ~412 dp of 352 dp. Same component, same copy, same actions; no new chrome. Name and count grow; the photo grid starts lower but keeps its tile size. |

**Not in BRAND-2 (left out on purpose).** The board's meridian rail, the 5 dp nav dot with an accent label, the filmstrip pole dot and amber ring, the chart-grid empty state and the hairline icon set are BRAND-4 or #120 screen-unit work per `QUIET_FRAME.md` "Order of landing" and #638 "Not this". Radii stay as on `main` except where noted. The frames show only what tokens and type change.

**Intentional deviations from the board** (the board was a concept at 390 × 844; parity and copy win):

| Board | Target | Why |
|---|---|---|
| Tabs Pictures · Albums · Stories · People | Photos · Albums · People · Search | `AtlasDestination` on `main`, parity spec §2 |
| "Pictures" title | "Photos" | `MainActivity` header title |
| Viewer top: centred mono date, time and coordinates | No date line; Samsung-parity pills only | Viewer chrome on `main` has no date line; adding one is new chrome and copy (OWNER_INTENT removal list) |
| People home card "2 groups · 38 photos" + amber `Review` pill | Person-page `SamePersonCard` with No / Yes text buttons | That is the existing component and copy (#580); a primary pill would be a new component |
| Fast-scroll month label in `accent` on `raised` | Label in `ink` on `surface` | Light `accent` on `raised` is **4.44:1**, below 4.5:1 (review flag). Rule used throughout: amber text only on `ground`, `surface` or black |
| Upper-case mono meta (`12.305 PHOTOS`) | Case as in the app | Spec permits upper case; copy unchanged keeps it out |

## State sheet: legend (100%, light and dark)

| Frame | Notes |
|---|---|
| **S1-settings** | **1** `Settings` screen title with Back (48 dp). **2** Group titles in the Section role (20/26); rows on `surface` cards with `line` hairlines, values in `ink-muted`, row copy as on `main`. Safe exit: Back. |
| **S2-dialog** | `PersonNameDialog` ("Add name") over a scrim. **1** Dialog on `surface`, field focused with a 2 dp `accent` outline and cursor. **2** `Cancel` / `Save` text buttons in `accent` on `surface`. **3** Keyboard up; the dialog sits above it. Safe exit: Cancel, Back or a tap outside. |
| **S3-empty-album** | **1** `No photos here yet` (existing copy) in `ink-muted`; no banner, no new button. **2** `0 items` as mono meta. Safe exit: Back. The chart-grid backdrop is BRAND-4/#136, not drawn. |
| **S4-loading** | Face scan running. **1** Existing status line `Finding people · 7,140 of 12,305` in mono meta, with the existing 2 dp progress bar: `accent-fill` on a `line` track. Not blocking: the People grid stays usable. |
| **S5-large** | 148,212 synthetic items at the 7-column level. **1** Count with grouping separators in mono. **2** Existing fast-scroll thumb (`accent-fill`) and bubble `March 2019`: `ink` mono on `surface`. No eager decode is implied; tiles are placeholders. |
| **S6-search-keyboard** | **1** Search field on `raised`, `ink` text, 2 dp `accent` focus ring. **2** `Recent searches` (label, `ink-muted`) with `Clear` in `accent` on `ground` (4.89:1 light). **3** Keyboard up, the nav pill is under it. Safe exit: Back hides the keyboard first. |
| **S7-error** | **1** First-scan failure: `Couldn't load your photos.` with `Retry` (existing copy). **Open question for preflight:** Meridian has no error colour and amber is the only saturated chrome colour, so the target sets the message in `ink`, not red. If an error token is wanted, that is a spec change for Strategy, not this unit. |

## Contrast (`node docs/design/brand-2-644/render/contrast.mjs`)

| Theme | Text | On | Ratio |
|---|---|---|---|
| dark | ink `#EEF1F5` | ground `#0D1117` | 16.70:1 |
| dark | ink-muted `#A9B1BD` | ground | 8.75:1 |
| dark | ink-faint `#8B94A1` | ground | 6.17:1 |
| dark | accent `#FFB547` | ground | 10.77:1 |
| dark | ink / ink-muted / ink-faint / accent | surface `#161B23` | 15.25 / 7.99 / 5.64 / 9.84:1 |
| dark | ink | raised `#212833` | 13.09:1 |
| dark | ink / ink-muted | nav pill (`raised` 80% on ground) | 13.93 / 7.30:1 |
| light | ink `#12161C` | ground `#F5F3EE` | 16.36:1 |
| light | ink-muted `#4A515C` | ground | 7.22:1 |
| light | ink-faint `#5E6672` | ground | 5.23:1 |
| light | accent `#9A5B00` | ground | 4.89:1 |
| light | ink / ink-muted / ink-faint / accent | surface `#FFFFFF` | 18.15 / 8.01 / 5.80 / 5.43:1 |
| light | ink | raised `#ECE8DF` | 14.84:1 |
| light | ink / ink-muted | nav pill (`surface` 90% on ground) | 17.98 / 7.93:1 |
| both | on-accent `#1A1206` | accent-fill `#FFB547` | 10.55:1 |
| viewer | `#EEF1F5` icons / `#FFB547` favourite | viewer pill on black | 15.86 / 10.22:1 |
| light | accent | raised | **4.44:1, so never used** |

## Still pending (for #638, not this unit)

A fresh, independent `Type: design-preflight` on #638 against these targets; then, at the implementation head, real S24+ light/dark renders at 100% and 200% (insets, Samsung Keyboard height, the non-linear font curve), the paired before/after captures via `scripts/compare_parity_renders.py`, and exact-head CI.
