# BRAND-VE2 · V06–V10 synthetic pre-build preflight

> **Status: proposed design evidence, not a design PASS.** These inspectable text SVGs and matrices are synthetic pre-build decisions pending independent review. They are not screenshots, runtime verification, or implementation proof.

Unit: [#807](https://github.com/LPStruiksma/atlas-gallery/issues/807) · Goal: [#628](https://github.com/LPStruiksma/atlas-gallery/issues/628)

Source of authority: [Quiet Frame v3](../../../QUIET_FRAME.md), [Lapis surface audit](../../../LAPIS_COVERAGE.md), and the unit issue. All media is abstract synthetic colour geometry made for these sheets; there are no private photos, faces, places, embeddings, or production data.

## Evidence index

Each contact sheet is `2160 × 2340` with separately bounded light and dark `1080 × 2340` panels.

| Child | Surface | Light | Dark | Named states |
|---|---|---|---|---|
| V06 | Declutter and Photo DNA | [left panel](v06-declutter-photo-dna.svg) | [right panel](v06-declutter-photo-dna.svg) | [destructive confirmation, undo, keep/discard](STATE_MATRIX.md#state-transitions) |
| V07 | Settings and What's new | [left panel](v07-settings-whats-new.svg) | [right panel](v07-settings-whats-new.svg) | [long list, people/privacy, current release](STATE_MATRIX.md#state-transitions) |
| V08 | Stories browse and playback | [left panel](v08-stories.svg) | [right panel](v08-stories.svg) | [playing, paused, gesture, empty](STATE_MATRIX.md#state-transitions) |
| V09 | Album picker, hidden albums, cover chooser | [left panel](v09-albums.svg) | [right panel](v09-albums.svg) | [keyboard create, private/hidden, active cover](STATE_MATRIX.md#state-transitions) |
| V10 | Multi-select action slab and feedback | [left panel](v10-selection.svg) | [right panel](v10-selection.svg) | [100 picks, undo, ordered selection](STATE_MATRIX.md#state-transitions) |

Supporting evidence: [state, type, reach, TalkBack and motion matrix](STATE_MATRIX.md) · [OS / One UI / Material observations separated from proposals](REFERENCES.md).

## Reading the sheets

The SVG text calls out the synthetic S24+ design viewport, assumed status/navigation/IME insets, media provenance, minimum 48 dp targets, focus/gesture arrows and lower one-hand reach zone. The matrix specifies 100%/200% text behavior, TalkBack names/actions, 200 ms deliberate Lift, and immediate reduced-motion transitions. Real-shape strips have 8 dp corners and 2 dp gaps; mist surfaces use 22 dp; the lapis slab uses 24 dp; ordered held spheres use a 2 dp white halo.

## Explicit gaps

No real device or app was run. Actual density/insets, font rendering/reflow, contrast over media, target bounds, focus order, gesture behavior, authentication, story playback, state persistence, transactionality and undo correctness remain uncovered until implementation evidence exists. The matrices mark further bounded gaps; none is silently treated as passing.
