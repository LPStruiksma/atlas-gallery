---
name: atlas-ui
description: Use for ATLAS Jetpack Compose UI, screen, theme, navigation, or gesture changes; check approved design, feature coverage, and rendered evidence.
---
# ATLAS UI
1. Read `docs/OWNER_INTENT.md`, `docs/product/SAMSUNG_PARITY_SPEC.md`, the current owner-approved design brief, and later owner decisions before changing Compose UI; live [Dev rules](https://github.com/LPStruiksma/autodev/blob/main/roles/dev.md) take priority.
2. Samsung Gallery is a **feature coverage reference**, not a mandate for ATLAS layout, internal structure, visual styling, control placement, or gestures. Preserve feature outcomes; let the approved design determine presentation ([review evidence](https://github.com/LPStruiksma/atlas-gallery/pull/651#issuecomment-7028308819)).
3. Never hard-code a paused or unapproved brand concept. Follow current approved colour, type, spacing, components, and interaction decisions; if repository docs conflict with newer owner decisions, route to Strategy to reconcile them.
4. For a `UI: yes` unit, require the current `Design brief:` and independent `Design: PASS` preflight before building ([Dev rules](https://github.com/LPStruiksma/autodev/blob/main/roles/dev.md)); missing or stale PASS routes to Strategy.
5. Pre-build proof is an annotated S24+ 1080 × 2340 design with navigation, copy, and required states ([design rubric](https://github.com/LPStruiksma/autodev/blob/main/docs/design/rubric.md)).
6. At PR, name affected feature-parity sections, explain deliberate design deviations, and link actual exact-head light **and** dark renders plus a state matrix; a wireframe is not rendered proof.
7. Cover empty, loading, error, huge-library, one-handed, keyboard, and inset states where relevant; missing evidence is not a pass.
8. Use only synthetic or licensed media for public evidence; do not expose private photos, faces, or personal records.
9. This helper does not bypass independent Review, exact-head CI, or owner-gated paths.
