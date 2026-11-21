# Lapis L4–L8 synthetic pre-build evidence (#753)

**Status:** the L6 2 × 6 accessibility repair source is staged on #817, but its PNG and hash manifest must be regenerated before the L6 pre-build review can pass. The other four sheets remain the original reviewed synthetic reference. The baseline had five annotated sheets for independent Review and then a fresh design re-preflight on [L4 #657](https://github.com/LPStruiksma/atlas-gallery/issues/657), [L5 #658](https://github.com/LPStruiksma/atlas-gallery/issues/658), [L6 #659](https://github.com/LPStruiksma/atlas-gallery/issues/659), [L7 #660](https://github.com/LPStruiksma/atlas-gallery/issues/660) and [L8 #661](https://github.com/LPStruiksma/atlas-gallery/issues/661). These are **synthetic design storyboards**: not device screenshots, emulator captures, implementation, recordings or performance readings, and not an inspection of the private Round 3, Continuum or Lapis boards. Source of truth: [Quiet Frame v3](../../QUIET_FRAME.md) and the preflight receipts on each unit.

| Sheet | Unit | Device frames |
|---|---|---|
| [brand-l4-navigation.png](brand-l4-navigation.png) | L4 slab and held sphere | 20 |
| [brand-l5-collections.png](brand-l5-collections.png) | L5 Collections rows | 13 |
| [brand-l6-atlas.png](brand-l6-atlas.png) | L6 atlas overlay; old 18-frame baseline until #817 render | 18 baseline; 21 after repair |
| [brand-l7-search.png](brand-l7-search.png) | L7 search under the thumb | 18 |
| [brand-l8-justified.png](brand-l8-justified.png) | L8 justified day rows | 16 |

Each sheet opens with two full-scale 1080 × 2340 frames (light 100 % and dark 200 %), then labelled state panels (the same full frames at 50 %), then tables: a dated **source → observed convention → Atlas adaptation/deviation** row set, and the unit's copy, TalkBack, tap-path, gesture or layout rules. [manifest.json](manifest.json) records every sheet's size, SHA-256, panel codes, font hashes and Pillow version.

## What is assumed

| Item | Value | Status |
|---|---|---|
| Viewport | 384 × 832 dp at density 2.8125 = 1080 × 2340 px | S24+ FHD+ default, same as [FIND-5](../../declutter-search-341/README.md) |
| Status bar / gesture nav | 29 dp / 16 dp | **Assumed** (FIND-5 values); measure on the S24+ |
| Keyboard | 324 dp | CI test IME (#437); Samsung Keyboard height unverified |
| Font scale 200 % | Linear 2× | Worst case; Android 14+ scales large text non-linearly. One-word titles that would overflow are fitted to the width and marked |
| Fonts | Funnel Display and Funnel Sans, variable weight | The BRAND-L2 files from PR [#720](https://github.com/LPStruiksma/atlas-gallery/pull/720) (head `91ccdeb`, not yet on `main`) |
| Pictures, names, places | Generated gradients, fictional names, schematic dots | No photos, people or geodata; no map tiles, network or permissions |

The annotation layer is magenta `#E0007A`, outside the palette: dashed box = effective touch target with its size, numbered dot = TalkBack order, tinted band = system inset, ring and arrow = illustrative gesture.

## Open design questions the sheets surface

1. **L6 accessible month layout selected** in [#817](https://github.com/LPStruiksma/atlas-gallery/issues/817): two rows of six, each 58.7 × 80 dp effective target. The old 12 × 29 dp primary sheet is rejected by the independent [blocking preflight](https://github.com/LPStruiksma/atlas-gallery/issues/659#issuecomment-7055978690); regenerate the PNG before it may be cited as the chosen primary layout.
2. **L4 slab at 200 % font:** the pills widen instead of truncating, so the search sphere no longer fits and detaches above the slab's right end (A2). A proposal, not part of Quiet Frame v3 yet.
3. **L7 auto-focus:** the bar opens with the keyboard (Quiet Frame v3), which differs from FIND-5's non-focused field. The two-step Back and the This screen / Whole library chips are kept.
4. **L5 two-tap reach:** recent people and albums get their own rows; any of 500 people is Collections, then search (2 taps plus typing) or 3 taps via People and pets.

## L6 synthetic pull-motion proof (#817)

[View the six-second animated SVG](brand-l6-pull-motion.svg). It is a **synthetic vector storyboard**, not an Android, emulator, touch capture, video recording or performance measurement. The authored timeline shows closed → finger-tracked 50% pull → release past halfway → open, then finger-tracked push-up → close, a cancelled short pull before the threshold, and the remove-animations cut (direct closed/open frame with no tween). The UI drawings are illustrative; the generator's still frames and the independent pre-build Review remain authoritative for geometry. A reviewer must explicitly decide whether the inspectable timed animation meets the brief's motion-proof gate.

For the selected static layout: all 12 month targets are 58.7 × 80 dp with no area overlap, the current-month sphere stays on its counted bar, and visible place dots receive separate effective 48 × 48 dp hit targets. The primary light/100% and dark/200% atlas and the dark/200% empty-year and filtered-grid states are rendered from the same selected layout. TalkBack chronological order is January–December before Places, shown across two scrolled synthetic list panels. Inset assumptions remain status 29 dp and gesture navigation 16 dp. No real picture, place coordinate, tile, permission or network fetch is used.

**Publishing gate:** on a Python/Pillow renderer with the bundled Funnel files, regenerate `--only l6`, commit the resulting `brand-l6-atlas.png` together with the updated `manifest.json`, then run `--check` and the L6 invariants against those committed bytes. The generator preserves L4/L5/L7/L8 output when only L6 is regenerated. Until this is done, the PNG is the old 29 dp baseline; the repair is not design-approved or ready for implementation.

## Regenerate

```bash
pip install Pillow  # tested with Pillow 12.3.0
python3 tools/design/lapis_preflight.py --only l6 --font-dir <dir with funnel_display.ttf and funnel_sans.ttf>  # repair L6 only
python3 tools/design/lapis_preflight.py --font-dir <dir with funnel_display.ttf and funnel_sans.ttf>  # all five
python3 tools/design/lapis_preflight.py --check      # sizes and SHA-256 against manifest.json
python3 -m unittest discover -s tools/design/tests   # layout invariants; uses a fallback font
```

Once BRAND-L2 lands, the generator finds the fonts in `app/src/main/res/font/` without `--font-dir`. Two renders with the same fonts and Pillow version produce byte-identical PNGs. Details: [tools/design/README.md](../../../../tools/design/README.md).
