# BRAND-VE2 · V06–V10 synthetic pre-build preflight

> **Status: input scaffold only.** No design frames or design PASS are claimed until the linked, inspectable artwork and state matrix are committed and independently reviewed.

Unit: [#807](https://github.com/LPStruiksma/atlas-gallery/issues/807) · Goal: [#628](https://github.com/LPStruiksma/atlas-gallery/issues/628)

Source of authority: [Quiet Frame v3](../../../QUIET_FRAME.md) (Lapis Atlas), [Lapis surface audit](../../../LAPIS_COVERAGE.md), and the unit issue. These are **proposed synthetic illustrations** for design preflight, not actual app screenshots, Android behavior observations, accessibility-test results, or runtime verification. Never use private media.

## Required evidence index (not yet produced)

| Child | Surface | Light | Dark | Named states |
|---|---|---|---|---|
| V06 | Declutter and Photo DNA | Pending | Pending | destructive confirm, undo, keep/discard |
| V07 | Settings and What's new | Pending | Pending | long list, people/privacy, current release |
| V08 | Stories browse and playback | Pending | Pending | playing, paused, gesture, empty |
| V09 | Album picker, hidden albums, cover chooser | Pending | Pending | keyboard create, private/hidden, active cover |
| V10 | Multi-select action slab and feedback | Pending | Pending | 100 picks, undo, ordered selection |

**Target frame:** synthetic Samsung S24+ design viewport 1080 × 2340 px, corresponding dp density and status/navigation/IME insets stated **on each frame**. Annotate 100% and 200% font scale, inset and keyboard variants, minimum 48 dp touch regions, one-handed reach, TalkBack focus/name/action semantics and gesture arrows. Use a separate stated assumptions sheet where real-device metrics cannot be proven.

**Required v3 features:** Funnel Display title 38/44 sp, Funnel Sans roles; approved light/dark palettes; actual-ratio photo strips with 8 dp corners and 2 dp gaps; 22 dp mist surfaces; 24 dp lapis action slab; filled lapis held sphere with 2 dp white halo and pick number (not checkbox or a hollow outline). Annotate Lift = deliberate 200 ms, remove-animations = immediate switch. Do not animate an illustrative SVG or claim measured motion.

**Reference decisions:** include a separate observed-vs-proposed table citing actual documented Android/One UI/Material guidance and why v3 borrows or intentionally diverges. No uncited claim that a proposed layout is Samsung's measured behavior.

Deliver final artefacts *under this directory only* as inspectable vector SVG (regular UTF-8 text suitable for the bounded Codex patch allowlist) plus Markdown state/measurement matrices; replace all Pending entries with real relative links. If any requirement cannot be evidenced, mark it missing rather than passing it.
