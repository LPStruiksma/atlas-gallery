# DESIGN-0 — Samsung-faithful polish checklist and screen/action scorecard

**Status:** audit template; **not a visual sign-off**. Owner's target is Samsung Gallery 15.9 on S24+, with ATLAS additions only in Samsung-like entry points. This document does not replace [OWNER_INTENT](../OWNER_INTENT.md) or [SAMSUNG_PARITY_SPEC](SAMSUNG_PARITY_SPEC.md). Sources: [design inventory #120](https://github.com/LPStruiksma/atlas-gallery/issues/120), [DESIGN-0 #130](https://github.com/LPStruiksma/atlas-gallery/issues/130), [parity contract](../../scripts/parity-matrix.tsv). Do **not** read a screenshot filename as proof its pixels passed inspection.

## Evaluation contract

For each applicable checklist cell score **0** = visible mismatch/failure against the cited Samsung reference, **1** = partly matching or visually inconsistent, **2** = visually matching and interaction verified in **both** system themes. **?** = not inspected or no credible reference, **—** = not applicable with reason. Never turn **?** into zero or two. A row is *signed off* only when all applicable cells are 2, and its light/dark **actual captured PNGs** have been inspected against the owner's Samsung reference with an exact-head manifest. For each scored 0/1 cell write a concrete finding with evidence link, reference/finding ID, component, and desired delta. Record the tester, timestamp, commit SHA, capture manifests and fixture; no invented Samsung metrics.

The S24+ evidence target is **1080 × 2340 px at density 450** via `S24DisplayRule`. Synthetic MediaStore images only, never personal photos. Existing `saveUiEvidence` instrumentation, `scripts/ci_ui_evidence.sh verify` and `scripts/verify_parity_matrix.py` supply real PNG bytes, SHA-256s, per-head manifest, and explicit `NOT_CAPTURED` rows. Review downloads the aggregate emulator artifact from the **PR head** and inspects the light/dark pairs. A generated diagram, a Compose-only static mock, or a file declaration is not proof. `NOT_CAPTURED` means evidence absent. Capture the action **state**, not just its parent destination, before awarding a score.

## Checklist (nine equally weighted cells)

| ID | Check (0/1/2 per applicable screen/action) | Samsung/owner anchor |
|---|---|---|
| **T** | Type hierarchy: large light title where Samsung uses one, medium labels, restrained weights, readable dynamic text; no bold all-caps, oversized captions or filenames in viewer chrome | Spec §0, §1, §2; `VIEW:V20` |
| **S** | Spacing: consistent compact grid/gutters, **2 dp** photo/filmstrip gaps, no gratuitous status bars or explanatory banners; controls breathe at Samsung density | Spec §0–§3; `GRID:G1-G7`, `GRID:T1-T2` |
| **R** | Corners: square photo tiles, rounded album covers, full rounded floating pills/dialog or sheet corners that match reference | Spec §0, §1, §3 |
| **C** | Colors/contrast in light **and** dark: shared app tokens, legible glyphs, dark full-bleed viewer, ~70%-opacity dark pill with blur on API 31+ if evidenced; no baseline Material purple | Spec §0–§1 and OWNER_INTENT removal list |
| **I** | Insets/system bars: safe tap area under cutout/navigation, edge-to-edge viewer, single tap toggles viewer chrome and bars together | Spec §1; `VIEW:V20` |
| **G** | Glyph set: consistent clean-room Material outlined 24 dp icons, Samsung-like active/inactive treatment, no copy of Samsung artwork, correct order and accessibility labels | Spec §0–§5; `PPL:15-26` |
| **M** | Motion: 200–350 ms sine/emphasized easing and no pop/bounce, grid↔viewer ~200 ms, pinch settle ~350 ms, double tap ~160 ms; full spline fling momentum, soft end snap, appropriate light haptics | Spec §0; `VIEW:V20,V23`, `GRID:G3-G4`, `PERF:S11` |
| **E** | Empty/loading/error and user feedback: background refresh, cached content stays visible, subtle inline error only; no blocker, explanation banner or extra confirmation. Include dialogs/snackbars/selection in applicable rows | Spec §5, §7; OWNER_INTENT |
| **H** | Hit targets/gestures: **at least 48 dp** interactive target (even when glyph 24 dp); discoverable Samsung-equivalent drag, long-press, swipe and TalkBack action. Test edge gestures and touch-size geometry | Issue #130; Spec §1, §2, §5; `GRID:S1-S4` |

**Scoring denominator:** for a fully applicable row, 9 cells × 2 = 18. Track score as earned/(2 × applicable); do not calculate a percentage while any applicable cell is **?**. `failed items` counts *verified* 0/1 cells only, not unknowns; keep a separate unknown count. The prioritization metric is `F × verified failed items`; with missing renders it is a **lower bound, not a complete ranking**. Never claim zero visible gaps because nobody inspected a row.

## D1–D19 inventory, evaluation ledger

All nine cells start at **?**, intentionally; that is an honest baseline, not nineteen asserted visual defects. `F` comes from #120. **Pair** refers to an existing *declared* light/dark source in `scripts/parity-matrix.tsv`, not to verified PR attachments; `missing` flags action-state-specific pairs that the matrix does not attest. The rank can be filled only after checking actual pixels and behaviour. Capture priority is frequency × missing-evidence leverage, not a fabricated visual gap score.

| ID | Screen / action | F | T | S | R | C | I | G | M | E | H | Declared paired evidence / remaining action-state proof |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| D1 | Pictures grid, zoom levels, dates, floating fast-scroll | 5 | ? | ? | ? | ? | ? | ? | ? | ? | ? | `pictures-default`, `pictures-pinch-compact`, `pictures-header-away`; FLOW-1 (#711) adds paired light/dark pinch frames at 25/50/75 %, mid-settle and settled (`flow1-*`, timeline and plain grid), a 1 px anchor handover assertion and per-kind pinch frame time, which M can now be scored from; **missing** paired original-ratio and fast-scroll bubble |
| D2 | Viewer bars, show/hide, insets, video controls | 5 | ? | ? | ? | ? | ? | ? | ? | ? | ? | `viewer-photo`, `viewer-filmstrip`; **missing** paired video-controls and hidden-chrome states |
| D3 | Grid ↔ viewer enter/return transition | 5 | ? | ? | ? | ? | ? | ? | ? | ? | ? | **Missing** paired transition frames/recording (still frames alone cannot score M) |
| D4 | Bottom bar and tab switching | 4 | ? | ? | ? | ? | ? | ? | ? | ? | ? | `pictures-default`, `albums-overview`; **missing** explicit paired in-flight tab-switch state |
| D5 | Albums list, album detail | 4 | ? | ? | ? | ? | ? | ? | ? | ? | ? | `albums-overview`, `albums-detail` |
| D6 | People tab | 3 | ? | ? | ? | ? | ? | ? | ? | ? | ? | `people-overview`, `people-hidden` |
| D7 | Search input, suggestions, chips, results | 3 | ? | ? | ? | ? | ? | ? | ? | ? | ? | `search-overview`; **missing** paired result/chip state and `search-person` |
| D8 | Person page | 3 | ? | ? | ? | ? | ? | ? | ? | ? | ? | **Missing** paired `people-named` (matrix says NOT_CAPTURED) |
| D9 | Selection, action bar, share/delete/move | 3 | ? | ? | ? | ? | ? | ? | ? | ? | ? | `selection-more`; **missing** paired range-selection and each action state |
| D10 | Confirm dialogs, undo snackbar, toast | 3 | ? | ? | ? | ? | ? | ? | ? | ? | ? | `dialog-photo-details` is *not* the full confirm/undo set; **missing** paired snackbar/toast/confirm |
| D11 | Empty/loading/error across routes | 3 | ? | ? | ? | ? | ? | ? | ? | ? | ? | **Missing** explicit paired empty/loading/failure for each route |
| D12 | Sheet, selection and press motion | 3 | ? | ? | ? | ? | ? | ? | ? | ? | ? | **Missing** timed paired animations; static PNG cannot validate M |
| D13 | People review: name, merge, same-person, split | 2 | ? | ? | ? | ? | ? | ? | ? | ? | ? | **Missing** paired complete review actions; dark-only dialog examples are insufficient |
| D14 | Details sheet | 2 | ? | ? | ? | ? | ? | ? | ? | ? | ? | `viewer-details-people` **NOT_CAPTURED** light/dark; detail-dialog is distinct |
| D15 | Stories | 2 | ? | ? | ? | ? | ? | ? | ? | ? | ? | **Missing** Stories destination pair (some older single-theme tests exist) |
| D16 | Full-screen video player | 2 | ? | ? | ? | ? | ? | ? | ? | ? | ? | **Missing** paired full-screen seek/video overlay |
| D17 | Settings | 1 | ? | ? | ? | ? | ? | ? | ? | ? | ? | `settings`; advanced submenu needs action-state assessment |
| D18 | Trash and Photo DNA review | 1 | ? | ? | ? | ? | ? | ? | ? | ? | ? | `photo-dna`, `declutter`; **missing** paired Trash and review interactions |
| D19 | Sort sheets | 1 | ? | ? | ? | ? | ? | ? | ? | ? | ? | **Missing** paired sort sheet (album sort has an existing unpaired capture) |

**Current computed audit totals:** 19/19 rows unscored; 0/171 checklist cells inspected; 171 unknown. Verified 0/1 gaps = **undetermined**, not zero. No visually measured rank is asserted here. The existing parity contract covers 20 *named surfaces*, not the 19 action rows above; do not conflate these denominators.

## How to finish a row and rank candidate units

1. Run the existing **API 35 emulator** screenshot pipeline at the PR's exact head with synthetic fixtures. Preserve the produced `ui-evidence/parity-matrix/manifest.tsv`, `coverage.txt`, original PNGs and job URL. `CAPTURED` is necessary, **not sufficient**: inspect the 1080×2340 PNGs in **both** themes. Add new action-state screenshot tests and a `scripts/ui-evidence.d/<unit>.txt` manifest only when needed; do not declare shots that no test writes.
2. Compare actual frames to Lukas's authorized Samsung references; cite spec finding IDs. For M also inspect a video/frame sequence plus durations; for H inspect semantics hit rectangles and gestures; neither can be inferred from a still.
3. Replace the corresponding nine `?` cells with 0/1/2/— as supported; write `Gap: Dn / cell / observed discrepancy / desired Samsung behaviour / screenshot link / test head`. Count 0/1 as failed cells, compute F × failed count, sort descending, ties higher F then inventory order. Keep unknown rows separate, never place them last as if healthy. Re-run on new heads because an earlier image is not current-head proof.

## Candidate units — provisional capture-first queue (NOT measured visual findings)

These units address **missing verification** first. Each is a proposed scoped unit, not a diagnosis that its UI fails. Listed by frequency F and missing action proof; when new pixel evidence exists, replace with actual measured-gap units and their score.

| Candidate unit title | Row / screen | Checklist cells requiring proof | F | Verified gap score |
|---|---|---|---:|---|
| Capture paired fast-scroll and original-ratio states (grid zoom frames: #711) | D1 Pictures | S, M, H | 5 | unknown |
| Capture paired viewer hidden chrome/video controls | D2 Viewer | I, G, M | 5 | unknown |
| Record grid↔viewer frame-timing on synthetic photos | D3 Transition | M, I | 5 | unknown |
| Capture tab-switch motion and active icons | D4 Bottom navigation | G, M, H | 4 | unknown |
| Verify album list/detail paired pixels against Samsung | D5 Albums | T, S, R, C | 4 | unknown |
| Capture search chips and populated results in two themes | D7 Search | T, S, E | 3 | unknown |
| Capture person page with synthetic identity in two themes | D8 Person | T, S, C | 3 | unknown |
| Capture selection/range and action outcomes | D9 Selection | G, M, H | 3 | unknown |
| Capture confirmation/undo/toast states | D10 Feedback | R, C, E | 3 | unknown |
| Capture cross-route empty/loading/error without blocking | D11 Loading | C, E | 3 | unknown |
| Record sheets and press-feedback timing | D12 Motion | M, H | 3 | unknown |
| Verify People tab paired face-crop grid | D6 People | S, R, C | 3 | unknown |
| Capture People name/merge/split review states | D13 People review | G, E, H | 2 | unknown |
| Capture real viewer details sheet light/dark | D14 Details | I, C, E | 2 | unknown |
| Capture Stories overview and cards light/dark | D15 Stories | T, S, R | 2 | unknown |
| Capture full-screen video playback/seek | D16 Video | G, M, H | 2 | unknown |
| Verify Photo DNA and Trash review interactions | D18 Review | C, E, H | 1 | unknown |
| Capture sort options and size-order confirmation | D19 Sort sheets | T, C, H | 1 | unknown |
| Verify settings and expanded menu two-theme pixels | D17 Settings | T, S, E | 1 | unknown |

**Important:** "failed items" here means *visually evidenced* sub-2 cells. No evidence inspection was performed in this documentation-only change; claiming a complete ranked gap list or all 19 light/dark action pairs would be inaccurate. The pending captures are explicit acceptance gaps for #130 and must be supplied before calling DESIGN-0 done.
