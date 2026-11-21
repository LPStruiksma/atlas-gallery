# WIDGET-1 · photo widget preflight sheets

**Status:** synthetic design, not actual launcher/emulator capture. These sheets are pre-build design evidence only. They are not an Android screenshot, a measurement of a Samsung launcher, a design PASS or permission to build.
**Unit:** [#846](https://github.com/LPStruiksma/atlas-gallery/issues/846), for [WIDGET-1 #590](https://github.com/LPStruiksma/atlas-gallery/issues/590), following [design brief rev 1](https://github.com/LPStruiksma/atlas-gallery/issues/590#issuecomment-7057557272). It answers the [earlier design preflight](https://github.com/LPStruiksma/atlas-gallery/issues/590#issuecomment-7052555138), which stopped because no annotated frames existed.
**Brand:** [Quiet Frame v3 · Lapis Atlas](../QUIET_FRAME.md). **Goal:** [#97](https://github.com/LPStruiksma/atlas-gallery/issues/97).
**Media:** every photo is a generated vector landscape and every person tile is an abstract shape. The sheets contain no real photos, faces or names. Names such as “Ada Example” are made up.

## Open the sheets

- **[preflight.svg](preflight.svg)** is the whole sheet as an inspectable vector. Each frame is its own group (`A1` … `D1`), and each group has a `-design` layer and an `-annotations` layer. Magenta marks the annotation layer, which is not UI.
- **[preflight.png](preflight.png)** is the same sheet exported by Chromium at 1 px per unit.
- **[frames/](frames/)** holds each phone frame as its own 1080 × 2340 SVG and PNG (nominal 360 × 780 dp at 3 px/dp).

Fonts: the SVG asks for Funnel Display and Funnel Sans and falls back to Inter or Arial. The PNGs were rendered with Inter because Funnel isn't bundled yet (BRAND-L2), so glyph widths are approximate. The render check below measures the real boxes with the fallback font.

## Frame index

| # | Frame | Theme · type | What to inspect |
|---|---|---|---|
| A1 | [Launcher home](frames/A1.png) ([svg](frames/A1.svg)) | light · 100% | Busy synthetic wallpaper and icons around a 4×2 widget (Favourites, labelled) and a 2×2 widget (Person, no label) |
| A2 | [Launcher home](frames/A2.png) ([svg](frames/A2.svg)) | dark · 100% | The same layout in dark theme: label pill on the dark surface token |
| A3 | [Launcher, annotated](frames/A3.png) ([svg](frames/A3.svg)) | light · 100% | Host cell estimates (marked ASSUMPTION), 328 × 160 and 160 × 160 dp targets, 22 dp radius, 48 × 48 Next hit area, hatched photo-tap region, centre-crop guides for 3:2 and 3:4 originals, status and gesture insets |
| A4 | Widget anatomy at 2× (in sheet) | light + dark | Label pill (opaque surface, 14/20 sp 600, 32 dp), Next sphere (36 dp lapis, 2 dp white halo, 48 dp hit) |
| B1 | [Configuration](frames/B1.png) ([svg](frames/B1.svg)) | light · 100% | Preview 328 × 176 dp, four sources in brief order, Favourites selected by default, Add widget 328 × 48 dp anchored above the gesture inset, one-hand reach arc, TalkBack reading order 1 to 7 |
| B2 | [Configuration](frames/B2.png) ([svg](frames/B2.svg)) | dark · 100% | Dark tokens on the same layout |
| B3 | [Person picker](frames/B3.png) ([svg](frames/B3.svg)) | light · 100% | Search visible at once (214 people), image-backed rows of 64 dp or more, a long name that wraps, selected row, fast-scroll sphere |
| B4 | [Album picker](frames/B4.png) ([svg](frames/B4.svg)) | dark · 100% | 236 albums with real cover crops and a long album name |
| B5 | [Person picker, keyboard open](frames/B5.png) ([svg](frames/B5.svg)) | light · 100% | Title collapses to the section-title role, preview stays at 96 dp, filtered list scrolls, Add widget sits above the IME |
| B6 | [Album picker, keyboard open](frames/B6.png) ([svg](frames/B6.svg)) | dark · 100% | The same keyboard case in dark |
| B7 | [Configuration](frames/B7.png) ([svg](frames/B7.svg)) | light · 200% | Title wraps, rows grow, content scrolls under a fixed CTA that grows to 64 dp |
| B8 | [Configuration](frames/B8.png) ([svg](frames/B8.svg)) | dark · 200% | The same at 200% in dark |
| B9 | [Person picker](frames/B9.png) ([svg](frames/B9.svg)) | light · 200% | Rows at 200% type, search and CTA still reachable |
| B10 | [Person picker, no results](frames/B10.png) ([svg](frames/B10.svg)) | dark · 100% | “No people match ‘zzq’.” with a next step, Add widget disabled, Back available |
| B11 | [On this day selected](frames/B11.png) ([svg](frames/B11.svg)) | light · 100% | “Photos from 21 November in earlier years. Uses your phone's date.” No ages anywhere |
| C1 | Widget state matrix (in sheet) | light/dark × 100/200% | Ready, loading, empty, hidden-only, revoked, invalid source, decode failure: 28 widget cells |
| C2 | Resize, two widgets, removal (in sheet) | light 100% and dark 200% | 2×2, 3×2, 4×2 and 4×3 crops; label dropped at small sizes and at 200%; two widgets with independent sources; removed widget leaves nothing behind |
| D1 | Accessibility and contrast (in sheet) | n/a | TalkBack labels, reading order, motion, and the measured contrast table |

## Widget states and copy (proposal for Review)

| State | Widget shows | Next | Tap goes to |
|---|---|---|---|
| Ready | Chosen photo, centre crop, label only on wide size at 100% | yes | That photo in the Atlas viewer, after an eligibility recheck |
| New / loading | Surface plus the held sphere on its arc, “Loading photos” | no | The source in Atlas |
| Empty (Favourites, or On this day with no matches) | “No photos to show.” and “Tap to open Favourites.” | no | The source in Atlas |
| Hidden-only | Exactly the same as Empty, so the widget never reveals that hidden items exist | no | The source in Atlas |
| Permission revoked / media deleted | “Photos unavailable.” and “Tap to open Atlas.” | no | Atlas, which owns the Android permission flow |
| Source deleted / invalid URI | “Choose another source.” and “Tap to pick one.” | no | Reconfiguration |
| Decode or refresh failure, no eligible cached photo | “Could not show this photo.” and “Tap Next to try another.” | yes | Next fetches the next eligible item |

At 200% type the second line is dropped when it doesn't fit, as the C1 cells show for decode failure. The TalkBack label still carries the full action.

## Assumptions to verify on a real S24+

- **Host cell bounds.** 82 × 88 dp cells in a 4-column grid are mockup targets. The [Android responsive layouts guide](https://developer.android.com/develop/ui/views/appwidgets/layouts) gives a Pixel 4 example of `(73n − 16) × (118m − 16)` dp in portrait, which would make a 2×2 about 130 × 220 dp. Samsung One UI grids differ. The implementation must use host-reported sizes and responsive layouts, never these numbers.
- **Corner radius.** 22 dp is the brief's nominal radius. On Android 12 and later the host's system widget radius wins and the frame clamps to it. API 29–30 hosts clip differently. Not measured here.
- **Insets.** A 36 dp status bar, a 20 dp gesture inset and a 310 dp keyboard are drawn only to show the layout rule. The app reads real insets at runtime.
- **200% type.** Body and secondary text are doubled (the worst case). Titles use about 1.5× to model Android 14 non-linear font scaling.
- **Density.** Frames use 3 px/dp so the export is 1080 × 2340. The real S24+ density must come from the device.

## Evidence index

Accessed 2026-11-21 from a Claude cloud seat. Platform: Android. None of these sources measures an S24+ launcher, so every geometry value above stays a target, not a measurement.

| Source | Observed behavior | ATLAS adaptation | Deviation or unknown |
|---|---|---|---|
| [Android app widgets](https://developer.android.com/develop/ui/views/appwidgets) and [responsive layouts](https://developer.android.com/develop/ui/views/appwidgets/layouts) (fetched; page text checked) | Widgets are sized in grid cells, and Android 12 adds `targetCellWidth/Height` and `maxResizeWidth/Height`. Available dp sizes vary by device. The guide shows a Pixel 4 size table. | Two placement targets (2×2 and 4×2), a responsive crop at every size, and the source label only where it fits | Samsung host bounds are unknown until measured on the device |
| [Aves changelog](https://github.com/deckerst/aves/blob/develop/CHANGELOG.md) (fetched via raw file) | Widget entries include “photo frame”, “tap action setting”, “option to open collection on tap”, “option to update image on tap”, “more shape options”, and a fix for “sizing widgets with some launchers on Android >=12” | Fixed behavior instead of settings: photo tap opens the photo, and a separate 48 dp **Next photo** control advances. One Quiet Frame shape. | The current Aves layout and geometry were not inspected, so no equivalence is claimed. The launcher-sizing fix supports measuring on real hosts. |
| [Ente Home Widgets help](https://ente.com/help/photos/features/utilities/home-widgets) | Per brief rev 1: add through launcher long-press → Widgets; Albums, Memories and People widgets; tap opens the content | One widget with a four-way source choice at placement time, and exactly one person or album in v1 | The page was **not reachable from this seat** (the network proxy refused ente.com). This row relies on the Strategy brief's 2026-11-21 reading, so Review should recheck it. No Ente UI is copied. |
| [Quiet Frame v3](../QUIET_FRAME.md) | Tokens, type roles, 22 dp secondary-surface radius, held sphere for selection and loading, no ambient motion, 48 dp targets | Used directly: surface label pill, lapis Next sphere with 2 dp white halo, selection sphere on rows, loading sphere only for a real load | Collapsing the title to the section-title role when the keyboard is open is a layout choice for Review to confirm |

## Accessibility

- **Reading order.** On the widget: photo (open) first, then Next photo. In configuration: title, preview, four sources, Add widget. B1 numbers them.
- **TalkBack labels** (proposed): “Open photo in Atlas. Favourites.”, “Next photo”, “Favourites, radio button, checked, 1 of 4”, “Add widget, button”. D1 has the full list.
- **Motion.** The widget has no ambient animation, so “remove animations” looks identical. The loading sphere appears only during a real load.
- **Contrast** of every product text pair drawn in the sheet, measured with WCAG 2.1 relative luminance on opaque backgrounds. Text never sits directly on photo pixels.

| Foreground | Background | Ratio | Example |
|---|---|---:|---|
| `#5A5F7A` | `#F2F3F8` | 5.65:1 | secondary on light surface |
| `#5A5F7A` | `#FFFFFF` | 6.26:1 | secondary on light ground |
| `#FFFFFF` | `#3448E0` | 6.67:1 | Add widget, dark |
| `#9AA0B8` | `#14152A` | 6.92:1 | secondary on dark surface, disabled CTA |
| `#9AA0B8` | `#000000` | 8.09:1 | secondary on dark ground |
| `#8C9BFF` | `#000000` | 8.25:1 | title, dark |
| `#2639C9` | `#FFFFFF` | 8.36:1 | title, light |
| `#FFFFFF` | `#2639C9` | 8.36:1 | Add widget, light |
| `#F2F3F8` | `#14152A` | 16.19:1 | ink on dark surface |
| `#0E1030` | `#F2F3F8` | 16.69:1 | ink on light surface (label pill) |
| `#0E1030` | `#FFFFFF` | 18.49:1 | ink on light ground |
| `#F2F3F8` | `#000000` | 18.95:1 | ink on dark ground |

## How it was made and checked

`build.mjs` draws every frame from one geometry model and writes the SVGs (`--png` also exports PNGs through Playwright and Chromium). `check.test.mjs` asserts against that same model:

- the committed SVGs match the generator byte for byte;
- every frame and panel carries the synthetic caption, and no SVG contains a raster, script or external reference;
- phone frames are 1080 × 2340;
- all 144 interactive targets are at least 48 × 48 dp;
- all 262 product text pairs reach 4.5:1, and the Next and radio indicators reach 3:1;
- launcher widgets are 160 × 160 and 328 × 160 dp with a 22 dp radius and Next at the bottom right;
- every state is drawn in light and dark at 100% and 200%, and hidden-only copy equals empty copy;
- the four sources appear in brief order with Favourites as the default;
- the CTA stays above the gesture inset and the IME, and the keyboard case keeps a preview of at least 96 dp;
- in Chromium, every text element stays inside its layout box (measured with `getBBox`);
- the PNGs are valid and the right size.

```sh
node docs/design/widget-1/build.mjs --png
node --test docs/design/widget-1/check.test.mjs
```

This unit only touches `docs/design/widget-1/**`, so the repository CI doesn't discover this test. The PR records the local run.

## Not covered here (later obligations for #590)

Exact-head launcher and emulator screenshots at 1080 × 2340 in light and dark at 100% and 200% type, TalkBack and keyboard passes, hide-toggle invalidation, revoked permissions, two widgets, resize and removal journeys with synthetic media, and JVM tests for source order, eligibility and cache purge. A mockup cannot satisfy any of these.
