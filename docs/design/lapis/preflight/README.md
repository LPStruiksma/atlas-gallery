# Lapis L4–L8 synthetic pre-build evidence (#753)

**Status:** five annotated sheets ready for independent Review and then a fresh design re-preflight on [L4 #657](https://github.com/LPStruiksma/atlas-gallery/issues/657), [L5 #658](https://github.com/LPStruiksma/atlas-gallery/issues/658), [L6 #659](https://github.com/LPStruiksma/atlas-gallery/issues/659), [L7 #660](https://github.com/LPStruiksma/atlas-gallery/issues/660) and [L8 #661](https://github.com/LPStruiksma/atlas-gallery/issues/661). These are **synthetic design storyboards**: not device screenshots, emulator captures, implementation, recordings or performance readings, and not an inspection of the private Round 3, Continuum or Lapis boards. Source of truth: [Quiet Frame v3](../../QUIET_FRAME.md) and the preflight receipts on each unit.

| Sheet | Unit | Device frames |
|---|---|---|
| [brand-l4-navigation.png](brand-l4-navigation.png) | L4 slab and held sphere | 20 |
| [brand-l5-collections.png](brand-l5-collections.png) | L5 Collections rows | 13 |
| [brand-l6-atlas.png](brand-l6-atlas.png) | L6 atlas overlay | 18 |
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

## COLLECT-1 utility collections (#776)

[COLLECT-1 #776](https://github.com/LPStruiksma/atlas-gallery/issues/776) extends the [#576 rev-2 design brief](https://github.com/LPStruiksma/atlas-gallery/issues/576#issuecomment-7055670165) without changing app code. The text-only change supplies the deterministic generator for `collect-1-utilities.png`; the Dev driver must render the omitted binary with Funnel fonts, record its SHA-256 and exact dimensions in the manifest, and commit it before this becomes review evidence.

The board preserves the merged L5 row and L7 search contracts and covers, in order:

* **A — Collections media-type rows:** between Favourites and later system rows, the fixed order is Videos, Screenshots, Recently added, Selfies, Panoramas, Slow motion, Large files. Videos/Screenshots are their existing L5 destinations, never duplicated. Only confirmed non-empty categories render; an all-empty grouping disappears. Real-ratio strips contain only available synthetic items, including genuine 1–3-item short strips. Light 100% and dark 200% panels illustrate different scroll positions.
* **B — category destination:** Screenshots opens its normal square grid; sort and a typed query remain scoped to that category.
* **C — search:** the search sphere expands immediately above the slab with **This screen** selected and **Whole library** available. The assumed 324 dp IME opens; first Back hides it and keeps the query, second Back closes search.
* **D — pending, error and empty:** the held sphere accompanies plain progress; error offers one 48 dp retry; no text matches prompts a clear-search action, while confirmed-zero categories are omitted from Collections.
* **E — stress and accessibility:** long labels at 200%, a schematic 250,128-item lazy grid (layout intent, not measured performance), assumed system insets, merged row semantics, visual-order TalkBack traversal and 48 dp targets.

Two hero frames are annotated native **1080 × 2340 px** S24+ frames: light at 100% and dark at 200%. Every A–E panel is built from the same full-size frame before the board scales it to 50%. The 29 dp status inset, 16 dp gesture inset and 324 dp keyboard are explicitly **assumptions**, not device measurements. All pictures are generated gradients and shapes; no image file, MediaStore item, network resource or private board is read.

The dated decision table compares Apple Photos Media Types, Apple Photos Utilities, Google Photos Recently Added, and Android Material 3 SearchBar as **observed convention → Atlas proposal/deviation**. It does not credit or infer behaviour from private design material.

### Regenerate and validate COLLECT-1

```bash
python3 tools/design/collect_1_utilities.py --font-dir <dir with funnel_display.ttf and funnel_sans.ttf>
python3 tools/design/collect_1_utilities.py --check
python3 -m unittest tools.design.tests.test_collect_1_utilities
```

`--check` renders without writing the PNG and validates the 1080 × 2340 device frames, fixed A–E panel/category order, board bounds and the generator's no-private-asset rule. The fallback font is allowed only for this programmatic check; the review PNG must use Funnel Display and Funnel Sans. The generator uses Quiet Frame v3 tokens and shares the existing Lapis drawing kit, so L5/L7 behaviour stays unchanged.
