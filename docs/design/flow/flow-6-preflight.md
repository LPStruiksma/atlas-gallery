# FLOW-6 preflight visual evidence — draft index

> **Status: awaiting synthetic graphical panels.** This is a scaffold for the Codex-authored, GitHub-renderable evidence. This file alone is **not** a Design PASS and is **not** Android/emulator evidence.

## Decision and constraints

- Evidence unit: [#825](https://github.com/LPStruiksma/atlas-gallery/issues/825); pre-build Design Review is [#715](https://github.com/LPStruiksma/atlas-gallery/issues/715).
- Motion authority: [FLOW-6/brief-v2](https://github.com/LPStruiksma/atlas-gallery/issues/715#issuecomment-7055644601), plus [preflight finding](https://github.com/LPStruiksma/atlas-gallery/issues/715#issuecomment-7056114689).
- Design authority: [Quiet Frame v3](../QUIET_FRAME.md). Samsung VIEW V10/V17 are *behavior* references only.
- Required final artifact: [flow-6-preflight.svg](flow-6-preflight.svg), an inspectable 1080 × 2340 synthetic S24+ light/dark graphical storyboard with photo P0–P5, video V0–V4, and an edge/state companion board. **Do not count this link as rendered until the file actually exists.**
- No Android/Kotlin or production UI edits; no proprietary screenshots, no real personal media, and no claims of measured status/nav insets.

## To complete before Review

1. Replace this scaffold with an indexed rendered-panel matrix mapping **every** SVG panel to P/V states, layer owners/alphas, Fit rectangle R and shared transform, URI + revision, cancellation, theme, and the specific #715 acceptance criterion.
2. Show light/dark, portrait and panorama, a video poster/first frame, cached and cold loading, errors, stale editor revision, rapid swipes, 100%/200% font with unchanged slab, empty/large-library applicability, accessibility, and ready-only reduced-motion cuts.
3. Distinguish baseline warm-cache placeholder / 800 px preview / 2400 px delayed HQ / 700 px poster from proposed no-blink and ≤120 ms photo or ≤350 ms video transition behavior.
4. Add graphical links and a screenshot/preview after verifying SVG renders. Document that real Media3 PlayerView/SurfaceView stacking remains **unverified on Android**.

The independent Design preflight remains blocked until inspectable panels exist; implementation of #715 also waits for #593.
