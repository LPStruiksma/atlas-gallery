# BRAND-L3 synthetic launcher and splash preflight (#717)

**Status:** inspectable synthetic design evidence ready for independent documentation Review. This is **not** an independent design-preflight PASS, a device capture, or approval to implement BRAND-L3.

**Visual evidence:** [Open the annotated SVG board](lapis-l3-preflight.svg) · **Approved identity:** [Quiet Frame v3](QUIET_FRAME.md) · **Source unit:** [#717](https://github.com/LPStruiksma/atlas-gallery/issues/717) · **Implementation gate:** [#656](https://github.com/LPStruiksma/atlas-gallery/issues/656).

## What the board shows

1. **Launcher recognition (board 01):** a visibly busy, fictional **1080 × 2340 px** launcher composition with surrounding fictional app icons. It illustrates a **48 dp** Atlas launcher cell as **144 px at an explicitly simulated 3× density**. The visible phone frame, wallpaper, labels and UI are **synthetic artwork, not an actual S24+, emulator or device screenshot**.
2. **Variant matrix (board 02):** all **16** intersections of round/squircle mask × colour/monochrome asset × themed icons off/on × light/dark launcher context. These are individually labelled design concepts. When themed icons are **off**, the monochrome column explicitly shows the isolated asset layer, *not a claimed launcher output*. When themed icons are **on**, the tinted monochrome examples are illustrative only; Android OEM and launcher output can differ.
3. **Geometry (board 03):** 108 dp adaptive canvas and exact protected r=33 dp circle, with the complete foreground mark scaled by 0.77 about (54,54), **including stroke**. Both 48 dp mask views retain the complete mark.
4. **Splash (board 04):** four labelled **static synthetic** panels: API 29–30 `windowBackground` layer-list light/dark and API 31+ `windowSplashScreen*` light/dark. Each has the identical Lapis ground, centred mark, **no wordmark and no animation**, and an explicit handoff to the **existing** post-launch app theme. These do not assert installed splash resources or real platform behaviour.

## Launcher matrix

| Launcher context | Round, colour | Squircle, colour | Round, mono | Squircle, mono |
| --- | --- | --- | --- | --- |
| Light, themed off | Colour source | Colour source | Mono layer preview | Mono layer preview |
| Light, themed on | Colour source | Colour source | Tinted concept | Tinted concept |
| Dark, themed off | Colour source | Colour source | Mono layer preview | Mono layer preview |
| Dark, themed on | Colour source | Colour source | Tinted concept | Tinted concept |

The 108 dp adaptive mask/source illustration is distinct from the 48 dp displayed-cell illustrations. Colour-source entries under themed-on depict the unthemed source asset for comparison, **not** the actual OS-selected icon. No real launcher rendering is claimed.

## Source mark and numeric bounds

- Ground: solid **Lapis `#2639C9`**, unaffected by foreground scaling.
- Sphere: `(54,40)`, radius `15 dp`; after transform centre `(54,43.22)`, radius `11.55 dp`.
- Chevron: white path `M30 84L54 60L78 84`, `8 dp` stroke, round caps/joins; `translate(54 54) scale(.77) translate(-54 -54)` applied to **both sphere and chevron**, producing a `6.16 dp` stroke.
- Safe circle: `r=33 dp` about `(54,54)`. Farthest stroked endpoint: `(sqrt(24² + 30²) + 4) × .77 ≈ 32.66 dp < 33 dp`.
- Post-scale stroked chevron bounds: `x=32.44…75.56`, `y=55.54…80.18` dp. Combined sphere + chevron bounds: `x=32.44…75.56`, `y=31.67…80.18` dp.
- At **48 dp**: multiply source units by `48/108`. The safe-circle radius becomes **14.67 dp**, the farthest stroked point **≈14.52 dp**, chevron stroke **≈2.74 dp**, and sphere radius **≈5.13 dp**. The small clearance is intentional; the actual icon mask/OEM output still requires testing.

## Raster inspection performed

The SVG source was produced locally and rasterized with **CairoSVG 2.8.2** using `cairosvg.svg2png(url='/mnt/data/lapis-l3-preflight.svg', write_to='/mnt/data/lapis-l3-preflight-preview.png', output_width=1330, output_height=1990)`. The generated **1330 × 1990 RGB PNG** was opened and visually inspected: the synthetic launcher, 16 labelled mask/theme variants, safe-zone geometry and four splash panels are present and readable. The PNG was an **ephemeral local preview outside this repository**, not a screenshot or committed asset. No Android build, emulator/device run, OS launcher, or exact-head CI was executed locally.

## Limits and next gate

This preparatory PR changes **design documentation only**: the [SVG board](lapis-l3-preflight.svg) and this index. It does not change `app/**`, Android manifest, icon/splash resources, `docs/design/app-icon-preview.png`, signing, packages, dependencies, workflows or store assets. **This page does not constitute an independent design preflight PASS.** A different Review seat must inspect and merge this documentation on exact-head hosted CI. Afterward, #656 must seek a **fresh independent design PASS** before runtime implementation. Its later implementation PR must include **actual exact-head light/dark emulator/device launcher and cold-start captures on both API families**; the synthetic evidence here cannot replace those captures.
