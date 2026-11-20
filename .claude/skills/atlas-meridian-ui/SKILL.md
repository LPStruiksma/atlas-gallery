---
name: atlas-meridian-ui
description: Use for any ATLAS Jetpack Compose UI, screen, theme, navigation, or gesture change; load Meridian visuals and Samsung parity before editing.
---
# ATLAS Meridian UI
1. Read `docs/design/QUIET_FRAME.md`, `docs/OWNER_INTENT.md`, and `docs/product/SAMSUNG_PARITY_SPEC.md` before changing Compose UI; live root rules retain authority.
2. Quiet Frame governs appearance: approved colour/type tokens, meridian rail, amber pole dot, spacing, shape, contrast, and motion. Do not invent a palette or extra chrome.
3. `OWNER_INTENT.md` controls the removal list; Samsung parity controls layout, navigation, gestures, feature placement, and behaviour. Quiet Frame never authorizes moving controls.
4. For a `UI: yes` unit, require the current `Design brief:` and independent `Design: PASS` preflight before building (`autodev/roles/dev.md`); missing or stale PASS routes to Strategy.
5. Pre-build proof is an annotated S24+ 1080 × 2340 design with navigation, copy, and required states (`autodev/docs/design/rubric.md`).
6. At PR, name affected parity sections and link actual exact-head light **and** dark renders plus a state matrix; a wireframe is not rendered proof.
7. Cover empty, loading, error, huge-library, one-handed, keyboard, and inset states where relevant; missing evidence is not a pass.
8. Use only synthetic or licensed media for public evidence; do not expose private photos, faces, or personal records.
9. This skill points to existing contracts; it does not bypass independent Review, CI, or owner-gated paths.
