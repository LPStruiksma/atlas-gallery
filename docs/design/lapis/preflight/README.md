# Lapis L4–L8 synthetic pre-build evidence (#753)

**Status:** five annotated sheets ready for independent Review and then a fresh design re-preflight on [L4 #657](https://github.com/LPStruiksma/atlas-gallery/issues/657), [L5 #658](https://github.com/LPStruiksma/atlas-gallery/issues/658), [L6 #659](https://github.com/LPStruiksma/atlas-gallery/issues/659), [L7 #660](https://github.com/LPStruiksma/atlas-gallery/issues/660) and [L8 #661](https://github.com/LPStruiksma/atlas-gallery/issues/661). These are **synthetic design storyboards**: not device screenshots, emulator captures, implementation, recordings or performance readings, and not an inspection of the private Round 3, Continuum or Lapis boards. Source of truth: [Quiet Frame v3](../../QUIET_FRAME.md) and the preflight receipts on each unit.

| Sheet | Unit | Device frames |
|---|---|---|
| [brand-l4-navigation.png](brand-l4-navigation.png) | L4 slab and held sphere | 20 |
| [brand-l4-fast-scroll.gif](brand-l4-fast-scroll.gif) | L4 synthetic fast-scroll recording (11 frames, 1.4 s) | 11 |
| [brand-l5-collections.png](brand-l5-collections.png) | L5 Collections rows | 13 |
| [brand-l6-atlas.png](brand-l6-atlas.png) | L6 atlas overlay | 18 |
| [brand-l7-search.png](brand-l7-search.png) | L7 search under the thumb | 18 |
| [brand-l8-justified.png](brand-l8-justified.png) | L8 six-stop day and overview densities; pinch storyboard, month/year signature and precision selection, repaired in [#836](https://github.com/LPStruiksma/atlas-gallery/issues/836) and corrected in [#855](https://github.com/LPStruiksma/atlas-gallery/issues/855) and [#864](https://github.com/LPStruiksma/atlas-gallery/issues/864) | 67 |

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
2. **L4 slab at 200 % font (resolved in [#816](https://github.com/LPStruiksma/atlas-gallery/issues/816)):** Pictures, Collections and the search sphere stay in ONE centred slab (about 360 of 384 dp wide), with three separate targets of at least 48 dp. The detached-sphere proposal is removed. Evidence and checks: [L4-GEOMETRY-AUDIT.md](L4-GEOMETRY-AUDIT.md).
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

## L8 six stops, pinch anchor and precision selection (#836, #855, #864)

The [blocking L8 preflight](https://github.com/LPStruiksma/atlas-gallery/issues/661#issuecomment-7056128733) found that the #770 frames claimed "same screen point" while the tile only kept its Y, and that month/year frames showed no selection sphere or thumb; [#840](https://github.com/LPStruiksma/atlas-gallery/pull/840) repaired both. The next review ([#661 comment](https://github.com/LPStruiksma/atlas-gallery/issues/661#issuecomment-7057281181)) found that the repaired sheet still drew *four* columns as "month" and *eight* as "year", which is not the approved ladder of [parity spec §2](../../../product/SAMSUNG_PARITY_SPEC.md) and [FLOW-2 #712](https://github.com/LPStruiksma/atlas-gallery/issues/712). #855 redraws the sheet on the approved six stops. No stop is added or removed.

| Stop | Layout | Size at 384 dp | Direct per-media tap |
|---|---|---|---|
| 1 ORIGINAL | 1 per row, real ratio | full width, tall portraits centred narrower | yes, ≥ 48 dp |
| 2 LARGE | ~3 per row (3.06 measured), real-ratio justified rows | row height target 118 dp | yes, ≥ 48 dp |
| 3 MEDIUM | ~4 per row (3.83 measured), real-ratio justified rows | row height target 86 dp | yes, ≥ 48 dp |
| 4 SMALL | 7 squares, month sections | 53.0 dp tiles | yes, ≥ 48 dp |
| 5 MONTH | 12 squares, month sections | 30.0 dp tiles | no: precision pathway |
| 6 YEAR | 28 squares, short month headings | 11.7 dp tiles | no: precision pathway |

Tile sizes use the 1080 px frame, (1080 px − gaps) / columns / 2.8125. YEAR is never drawn with 7 columns.

**Geometry is computed, not drawn by hand.** `tools/design/lapis_screens.py` builds one synthetic 5290-item library (November 268 and September 702 as in the L6 counts, plus six older synthetic months so the 28-column YEAR stop is longer than one screen), lays it out at all six stops, and `storyboard()` and `precision_flow()` derive every pinch step. The frames, their annotation cards and the measured table on the sheet print those numbers; `tools/design/tests/test_lapis_l8_pinch.py` asserts them.

**Pinch invariant drawn.** The same media key is the anchor. The new scroll offset restores its *relative* height under the focal point F wherever scroll bounds allow; X follows the destination grid's fixed column (a key's column is its position in the month modulo the column count). So the key slides to its column, and F may no longer be over it. The sheet prints the measured shift and which key is under F. It does not claim that a stationary pointer stays over the same tile, zero X drift, or a literal same-screen-point landing. At the top and bottom clamps the height residual is disclosed instead.

| Frames | What it shows | Measured |
|---|---|---|
| S1–S6 | Key 828 at each of the six stops | height under F restored at every step |
| C1–C5 | Pinch in LARGE → MEDIUM → SMALL → MONTH → YEAR | centre shift X +537 px, −926 px, +692 px, −750 px; F rests on keys 826, 830, 825, 839 |
| C7–C10 | Pinch out from YEAR, anchored on key 839 (the key under F in C5) | centre shift X +659 px, +19 px, −536 px, +109 px; the round trip ends on key 839, not on key 828 |
| C11–C14 | Interior key 825 (wide photo mid-row) | +188 px (SMALL), −200 px (MONTH), +491 px (YEAR); F rests on keys 824, 825, 813 |
| C15, C16 | Top and bottom of the library | scroll clamps at 0 / at the end; the key lands −90 px / +147 px from its focal height |
| P1–P6 | Precision selection from MONTH and YEAR (long-press entry) | see below |
| T1–T7 | Fast-scroll thumb label at MONTH, SMALL and YEAR, light and dark 200 % | label = month of the first visible key; Nov at the top, Sep after the last November row has left, Sep at SMALL and YEAR (#864) |
| Q1–Q17 | Select precisely with no prior target: MONTH (Q1–Q4, Q6–Q10) and YEAR (Q5, Q11–Q17), each with entry, focused SMALL, tap, return, prior selected keys and dark 200 %; TalkBack focus (Q8) | centre rule, no auto-select, return restores scroll and selection (#864) |
| B1–B5 | SMALL, MONTH and YEAR, light 100 % and dark 200 % | ordered spheres 1–3 with 2 dp white halo, video badge (SMALL), play glyph (MONTH), no video mark on YEAR's 12 dp tiles, 46 dp thumb in a 48 dp target whose label is the month of the first visible key |
| E3 | MONTH loading, dark 200 % | surface squares hold their place; selection, thumb and the precision pill already work |

**Measured constraint.** One stationary F can stay on the same key in two grids only when the key's two horizontal spans overlap. The key at zero-based month position *i* sits in column *i* mod 7, *i* mod 12 and *i* mod 28, so this depends on the key's index, not on the finer column alone (`shared_point_x()`). Over a full cycle of 84 month positions, SMALL → MONTH can share a point for 18 and MONTH → YEAR for 8 (`shared_point_positions()`). Example: month position 85 is column 1 in both SMALL and MONTH, spans of about 155–304 px and 90–175 px, so F at x = 160 px stays on it. For all other positions the key keeps its height and moves to its column.

**Precision selection from the dense overviews.** MONTH (30.0 dp) and YEAR (11.7 dp) tiles are below the 48 dp target of [Quiet Frame v3](../../QUIET_FRAME.md), so they are not drawn as direct per-media taps. Selection spheres and the fast-scroll thumb stay visible at every stop. A long-press on a dense tile selects that key and focuses the SMALL 7-column view on it at the same height, where every tap is a real 53.0 dp target (P1–P3). The 48 dp *Select precisely* pill focuses the same view without selecting anything (see below). The return pill (labelled with the column count of the overview it returns to: *12 columns* from MONTH, the YEAR count from YEAR) or system Back returns to the overview with the selection and anchor intact (P4); YEAR uses the same pathway (P5), and the pill grows at 200 % font (P6). The pathway is a proposal: if #661 cannot keep the parity behaviour with it, the gap returns to Strategy rather than a smaller target. The TalkBack equivalent is a requirement to verify on the S24+, not a measurement.

**Thumb label and pill-only entry (#864).** The independent preflight after #856 found two defects in the L8 proof: the fast-scroll thumb always read `Nov`, even on September keys, and `precision_flow()` always started at key 568, so the pill had no defined key when nothing was long-pressed. Both are now computed, not drawn by hand (`thumb_month()`, `focus_anchor()` and `pill_flow()` in `tools/design/lapis_screens.py`):

- **Thumb label.** The month of the *first visible key*: the first key in library order with a pixel below the top edge of the grid. A month heading belongs to the keys under it. Tie-break, shown in T2 and T3: at MONTH scroll 2122 px the last November row is still partly in view and the thumb reads Nov; at 2242 px it has left and the thumb reads Sep. T1–T7 cover MONTH, SMALL and YEAR, light 100 % and dark 200 %.
- **Pill with no prior target.** The pill focuses the SMALL 7-column view on the *visible key nearest the viewport centre* (distance to the tile centre; equal distance goes to the lower key, the earlier in library order). It does **not** select that key: selecting is a tap on the 53.0 dp tile (Q2, Q3). Prior selected keys never move the anchor and stay selected (Q6, Q7). Back or the return pill returns to the same overview, scroll position and selection (Q4).
- **Explicit focus.** A long-press or TalkBack focus key that is still visible is kept as the anchor (Q8, P1); a stale focus (scrolled out of view) is not eligible and the centre rule applies. Long-press keeps its selecting behaviour and is drawn separately (P1, P5).
- **Targets and order.** The pill, the 53.0 dp tile and the return pill are measured at ≥ 48 dp in light 100 % and dark 200 % (Q1–Q17). Proposed TalkBack order: the pill, then the focused tile, then the grid. This is a design proposal, not a device test.

Nothing here adds a zoom stop or a smaller target, selects automatically, or claims S24+, TalkBack or jank results; those remain post-build gates on [#661](https://github.com/LPStruiksma/atlas-gallery/issues/661).

**Still open or post-build.** Smooth cross-layer motion stays with [FLOW-2 #712](https://github.com/LPStruiksma/atlas-gallery/issues/712). S24+ frames, Android gestures, TalkBack and the < 1 % FrameMetrics fling gate on 150k synthetic media stay with the [#661](https://github.com/LPStruiksma/atlas-gallery/issues/661) build. These are synthetic frames, not device captures.
