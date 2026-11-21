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
