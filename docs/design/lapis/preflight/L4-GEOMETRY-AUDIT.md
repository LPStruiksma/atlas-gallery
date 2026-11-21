# BRAND-L4-E2 · slab geometry and fast-scroll evidence

Scope: [#816](https://github.com/LPStruiksma/atlas-gallery/issues/816), preflight request [#657](https://github.com/LPStruiksma/atlas-gallery/issues/657). Quiet Frame v3 tokens and spec are unchanged. Everything here is **synthetic**: generated gradients, fictional labels, assumed insets (status 29 dp, gesture 16 dp, keyboard 324 dp, viewport 384 × 832 dp at 1080 × 2340 px). Not device proof and not a Design: PASS.

## What changed

- **One slab at every font scale.** `Screen.slab` (API unchanged) now uses 20 dp pill padding (was 28 dp). At 200 % the pills are 123 × 55 dp and 160 × 55 dp and the sphere is 48 × 48 dp, all inside one slab 359 dp wide, centred, 12 dp above the gesture inset. If a longer label ever runs out of width, the sphere moves to a second row **inside the same slab**; it never detaches. `slab_targets` exposes each effective target for tests.
- **A2 detached-sphere proposal removed** from the sheet captions, the label-truncation row and the README open questions.
- **Shared geometry:** the pill padding also changes the 200 % slab on the L5–L8 sheets, so those four sheets were regenerated with the same generator (only the slab differs). #827 (L5 routes) keeps its own scope and should rebase on this geometry.
- **Fast-scroll recording:** [brand-l4-fast-scroll.gif](brand-l4-fast-scroll.gif), 360 × 780 px, 11 distinct frames: ten 100 ms drag steps (thumb 8 % → 82 %, November → August → March) and one 400 ms settled frame with the drag released. Deterministic, from `tools/design/lapis_fastscroll.py`. It is a storyboard, not a screen recording.
- Observed-reference decision kept: Samsung's tab positions do not bind; the one-slab arrangement is a deliberate deviation (L4 sheet "Reference decisions").

## Evidence

| Evidence | Where | Status |
|---|---|---|
| L4 light/dark 100 %/200 % sheet | [brand-l4-navigation.png](brand-l4-navigation.png) A1, A2, B1, B2 | rendered and inspected (A2 slab cropped at full scale: three boxes, 123 × 55, 160 × 55, 48 × 48 dp, inside the slab) |
| Animated fast-scroll recording | [brand-l4-fast-scroll.gif](brand-l4-fast-scroll.gif) | rendered; frames 0, 3, 6, 9 and 10 inspected |
| Effective regions and insets | `tools/design/tests/test_lapis_l4_slab.py` | 8 theme/scale cases × 2 active tabs, wrap fallback, keyboard and gesture inset, no overlap, all ≥ 48 dp |
| Manifest and hashes | `manifest.json` (`boards`, `recordings.l4_fast_scroll`) | `python3 tools/design/lapis_preflight.py --check` passes |
| Hosted exact-head CI | PR checks | see the PR |
| Independent design preflight | next:review | pending |

## Regenerate

```bash
python3 tools/design/lapis_preflight.py --font-dir <dir with funnel_display.ttf and funnel_sans.ttf>
python3 tools/design/lapis_preflight.py --check
python3 -m unittest discover -s tools/design/tests
```

The Funnel files used are the BRAND-L2 files from PR #720 (SHA-256 recorded in the manifest, unchanged).

## BRAND-L4-E3 · F1 filmstrip hit regions ([#889](https://github.com/LPStruiksma/atlas-gallery/issues/889))

Finding (design preflight on #657): the F1 viewer drew seven 40 dp tiles with 4 dp gaps (44 dp pitch), and `Screen.target()` pads every target to 48 dp, so neighbouring effective targets overlapped by 4 dp. Synthetic only; not device proof, not a Design: PASS.

- **Fix:** gap 8 dp (`filmstrip_gap()` in `tools/design/lapis_screens.py`), tiles stay 40 dp wide, so the centre pitch is exactly 48 dp (135 px at 2.8125). The strip is 328 dp wide and re-centred on the 384 dp screen (effective targets 336 dp, x 67–1012 px). Still seven visible items; slab, white 2 dp ring, held sphere and viewer actions are untouched; no token changed.
- **Effective targets:** seven boxes of 48 × 52 dp (135 × 146 px), adjacent boxes share an edge (0 px overlap, checked in whole pixels, so rounding cannot overlap them), all inside the viewport and clear of the action slab.
- **Evidence:** full-resolution [brand-l4-f1-viewer-48dp.png](brand-l4-f1-viewer-48dp.png) (1080 × 2340, targets dashed and labelled in the annotation colour); regenerated L4 sheet and manifest hash (`python3 tools/design/lapis_preflight.py --check` passes).
- **Test:** `tools/design/tests/test_lapis_l4_filmstrip.py`, light/dark × 100 %/200 %: seven targets, each ≥ 48 dp, pairwise non-overlapping, pitch 48 dp, inside the viewport, centred, clear of actions; the current-item ring and sphere sit on the fourth tile and no other tile has a ring. It fails on the old 4 dp gap (4 subtests).
- **Other sheets:** only the L4 sheet renders `viewer()`. The L5–L8 sheets and the GIF were not regenerated; their manifest hashes are unchanged.

Regenerate the full-resolution view:

```bash
cd tools/design && python3 - <<'PY'
import sys; sys.path.insert(0, ".")
from lapis_kit import Fonts, Screen
from lapis_preflight import find_fonts, REPO
import lapis_screens as L
s = Screen(Fonts(find_fonts("<font dir>", REPO, False)), theme="light", font_scale=1.0, viewer=True)
L.viewer(s); s.overlay_annotations()
s.img.convert("RGB").save("../../docs/design/lapis/preflight/brand-l4-f1-viewer-48dp.png", optimize=True)
PY
```
