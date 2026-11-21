# Lapis L4–L8 synthetic pre-build evidence (#753)

**Status:** five annotated sheets ready for independent Review and then a fresh design re-preflight on [L4 #657](https://github.com/LPStruiksma/atlas-gallery/issues/657), [L5 #658](https://github.com/LPStruiksma/atlas-gallery/issues/658), [L6 #659](https://github.com/LPStruiksma/atlas-gallery/issues/659), [L7 #660](https://github.com/LPStruiksma/atlas-gallery/issues/660) and [L8 #661](https://github.com/LPStruiksma/atlas-gallery/issues/661). These are **synthetic design storyboards**: not device screenshots, emulator captures, implementation, recordings or performance readings, and not an inspection of the private Round 3, Continuum or Lapis boards. Source of truth: [Quiet Frame v3](../../QUIET_FRAME.md) and the preflight receipts on each unit.

| Sheet | Unit | Device frames |
|---|---|---|
| [brand-l4-navigation.png](brand-l4-navigation.png) | L4 slab and held sphere | 20 |
| [brand-l5-collections.png](brand-l5-collections.png) | L5 Collections rows | 13 |
| [brand-l6-atlas.png](brand-l6-atlas.png) | L6 atlas overlay | 18 |
| [brand-l7-search.png](brand-l7-search.png) | L7 search under the thumb | 18 |
| [brand-l8-justified.png](brand-l8-justified.png) | L8 justified day rows; pinch storyboard and month/year signature repaired in [#836](https://github.com/LPStruiksma/atlas-gallery/issues/836) | 26 |

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

1. **L6 month bars are 29 dp wide** in one row of twelve (flagged `BELOW 48 dp`). Panel C6 shows a 2 × 6 alternative with 58 × 80 dp targets that touch but do not overlap (unit-tested); the sheet recommends it. Decide at re-preflight.
2. **L4 slab at 200 % font:** the pills widen instead of truncating, so the search sphere no longer fits and detaches above the slab's right end (A2). A proposal, not part of Quiet Frame v3 yet.
3. **L7 auto-focus:** the bar opens with the keyboard (Quiet Frame v3), which differs from FIND-5's non-focused field. The two-step Back and the This screen / Whole library chips are kept.
4. **L5 two-tap reach:** recent people and albums get their own rows; any of 500 people is Collections, then search (2 taps plus typing) or 3 taps via People and pets.

## Regenerate

```bash
pip install Pillow  # tested with Pillow 12.3.0
python3 tools/design/lapis_preflight.py --font-dir <dir with funnel_display.ttf and funnel_sans.ttf>
python3 tools/design/lapis_preflight.py --check      # sizes and SHA-256 against manifest.json
python3 -m unittest discover -s tools/design/tests   # layout invariants; uses a fallback font
```

Once BRAND-L2 lands, the generator finds the fonts in `app/src/main/res/font/` without `--font-dir`. Two renders with the same fonts and Pillow version produce byte-identical PNGs. Details: [tools/design/README.md](../../../../tools/design/README.md).

## L8 pinch anchor and month/year signature (#836)

The [blocking L8 preflight](https://github.com/LPStruiksma/atlas-gallery/issues/661#issuecomment-7056128733) found that the #770 frames C1–C4 claimed "same screen point" while the tile only kept its Y, and that month/year frames showed no selection sphere or thumb. The repaired sheet replaces both.

**Geometry is computed, not drawn by hand.** `tools/design/lapis_screens.py` builds one synthetic 970-item library (November 268, September 702, as in the L6 counts), lays it out as justified day rows and edge-aligned 4- and 8-column square grids, and `storyboard()` derives every pinch step. The frames, their annotation cards and the measured table on the sheet print those numbers; `tools/design/tests/test_lapis_l8_pinch.py` asserts them.

**Rule drawn:** the pinch keeps the media key under the focal point F (the two-finger centroid) and restores the same relative height inside it by choosing the new scroll offset, clamped to the content. A key's column in a square grid is its position in the month modulo the column count, so its horizontal position is quantized.

| Frames | What it shows | Measured |
|---|---|---|
| C1–C4 | Pinch in day → month → year on key 88 | same key under F at every step; height residual 0 px; centre shift X −144 px (month), −68 px (year) |
| C5–C7 | Pinch out year → month → day, F unchanged | key under F is still 88; day scroll returns to exactly 8354 px |
| C8–C10 | Interior key 83 (wide photo mid-row) | height kept, but the fixed column moves it +388 px (month) and −475 px (year): F then rests on keys 82 and 84 |
| C11, C12 | Top and bottom of the library | scroll clamps at 0 / at the end; the key lands −31 px / +53 px from its focal height |
| B1–B4 | Month and year, light 100 % and dark 200 % | ordered spheres 1–3 with 2 dp white halo, video badge (month) or play glyph (year), 46 dp "Nov" thumb in a 48 dp target, scaled headings |
| E3 | Month loading, dark 200 % | surface squares hold their place; selection and thumb already work |

**Measured constraint.** 4- and 8-column tile centres sit at odd/8 and odd/16 of the width and never coincide. One stationary F can stay on the same key in both month and year only for keys in year columns 0 and 7 (`same_point_columns()`); elsewhere the key keeps its height and slides to its column. No frame or caption claims a 0 px centre shift.

**Open for Strategy before the re-preflight:** (1) accept the invariant "same photo at the same height under the fingers; it slides to its column" for #661, or choose another; (2) year tiles are 46.25 dp, below 48 dp when a tile tap toggles selection (flagged in B3/B4): keep 8 columns or use 7 (52.6 dp). Runtime motion stays with [FLOW-2 #712](https://github.com/LPStruiksma/atlas-gallery/issues/712); S24+ frames, TalkBack and the 150k FrameMetrics fling gate stay with the #661 build.
