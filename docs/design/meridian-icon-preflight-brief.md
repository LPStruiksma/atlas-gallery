# BRAND-3A — Meridian preflight source and raster contract

Unit: [atlas-gallery#645](https://github.com/LPStruiksma/atlas-gallery/issues/645). This draft is a Codex task staging PR, **not evidence of a completed preflight**. The outputs below are not present until a verified patch lands.

## Geometry (108 × 108 coordinate system)

- Base `#161B23`, ink `#EEF1F5`, amber `#FFB547`.
- Meridian paths `M54 29C44 39 39 56 36 75` and `M54 29C64 39 69 56 72 75`.
- Equator `M27 61H81`; pole at `(54,28)`.
- Ordinary 108 dp: meridian strokes 7 round caps, equator 3.5 round caps, amber dot radius 5.5.
- At 48 dp: meridian strokes 8, equator 4.5, dot radius 6.5.
- Themed monochrome: all strokes in one launcher-selected tint at width 7; dot radius 6.
- Overlay circle centre (54,54), radius 33 in at least one source/render. The conservative extrema are no farther than 32.5 dp, but this geometric bound is not pixel-level validation.

## Required artifacts

1. `docs/design/meridian-icon-preflight.svg`: inspectable exact paths and visual plate covering unmasked, round and squircle masks, themed light/dark and unthemed, actual 48px and 108px instances.
2. `docs/design/meridian-icon-preflight.png` or equivalent independently inspectable static raster output generated from the SVG, preserving the native 48px row. If a binary PNG cannot cross the Codex format-patch allowlist, supply a reproducible text-only generator and a self-contained static source/render representation; **do not call the PNG committed until it exists on the PR**.
3. Launcher placement samples on light, dark and synthetic busy-photo-like wallpapers, with an annotated 1080×2340 S24+ aspect mock; distinguish image-mock scale from Android density.
4. Light (background `#F5F3EE`) and dark (background `#0D1117`) cold-start splash proposals, with dark Meridian tile retaining visibility of light ink on light background. Label API 31+ platform splash and API 29–30 legacy static window distinctly.
5. No changes to Android source, resources, Gradle, manifest or shipping preview.

## Evidence boundary

All mock backgrounds must be synthetic. This is design proof, **not** emulator/runtime proof. Parent [#639](https://github.com/LPStruiksma/atlas-gallery/issues/639) separately needs a fresh independent pre-build Design review; actual emulator screenshots and regenerated shipping preview are still acceptance criteria before its implementation merges.

Platform references: [adaptive icon design](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive), [splash guidance](https://developer.android.com/develop/ui/views/launch/splash-screen).

Do not read, search or print `eval/held-out/**`. Do not include user media or credentials.