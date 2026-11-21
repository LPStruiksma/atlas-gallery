# BRAND-VE1 — V01–V05 synthetic pre-build design evidence

> **Draft work area, not approved design evidence.** This index sets the evidence contract for [unit #806](https://github.com/LPStruiksma/atlas-gallery/issues/806). Screen panels, states and visual verification are pending. Do not release a child for implementation on this scaffold.

Source of truth: [Quiet Frame v3](../../../QUIET_FRAME.md), [Lapis coverage audit](../../../LAPIS_COVERAGE.md), [design preflight index](../README.md). Owner intent: a coherent visual identity throughout ATLAS, without new features, Android-code changes or personal-media access.

## Requested artifact set

All examples are synthetic. Target phone: Samsung Galaxy S24+ **1080 × 2340 px portrait**, annotated assumed density/insets. Every **V01–V05** row needs inspectable **light and dark** primary-task frames, plus the named states and interaction/accessibility matrix. The ten expected frames are `v01-light.svg` / `v01-dark.svg`, through `v05-light.svg` / `v05-dark.svg`. An additional `references-decisions.md` must distinguish cited Android/One UI/Material conventions from ATLAS proposals. No frame here represents a runtime screenshot or measured Samsung behavior.

| Child | Primary task | Required distinct states / proofs |
| --- | --- | --- |
| V01 | Viewer actions and filmstrip | Current item with 2 dp white ring and small #8C9BFF held sphere; ordered multi-selection and accessible order announcement; gesture arrows |
| V02 | Viewer details sheet | Open details sheet, photo/exif captions and dismiss flow; full-height/one-handed/large-text treatment |
| V03 | People home / sort / progress | No people; scan in progress with sphere climbing an arc and plain count; 68+ people with search and sort; no arbitrary circular portraits |
| V04 | Person page / cover chooser | Real-shape photo strip, selected cover, name editing with keyboard open; 200% font and inset behavior |
| V05 | Identity review | Same-person choice, unknown-face review and explicit split confirmation; non-colour-only decision and undo/confirmation |

## Presentation and inspection contract

- Each SVG is a complete **1080 × 2340** synthetic phone panel, not an Android implementation. Provide meaningful image geometry with synthetic photos/faces (abstract illustrations are acceptable if clearly marked), real-shape strip proportions, and explanatory captions outside/alongside the primary device surface where possible.
- Use **Funnel Display 38/44 sp 700** for screen titles, **Funnel Sans** for body and nav; if fonts are unavailable, mark substitutions as design approximations rather than asserting exact glyph metrics.
- Palette must follow approved v3: light ground #FFFFFF, mist #F2F3F8, ink #0E1030, secondary #5A5F7A, lapis fill/text #2639C9; dark ground #000000, mist #14152A, ink #F2F3F8, secondary #9AA0B8, fill #3448E0, text #8C9BFF. Viewer ground is black in both modes. Use a **solid** lapis accent, never an accent outline.
- Radii: **8 dp** for real-shape photo strips, **22 dp** for secondary surfaces, **24 dp** for short centred action/nav slab, circular held sphere. Use a **2 dp white halo** for selection, numbered when ordered. No permanent banners or decorative extra chrome.
- Every screen documents **100% vs 200% font**, status/nav/IME inset assumptions, **48 dp minimum touch bounds**, one-handed reach, gesture/touch flow arrows, TalkBack labels, focus order and accessible state changes. Where a full-size 200% scenario cannot fit, supply a clearly labelled inset/alternate state panel and explain the overflow or scrolling.
- State diagrams may be schematic; *never* claim animations were captured. Annotate Lift as a **200 ms** deliberate transition with immediate cut when Android Remove animations is enabled.
- Clearly mark each observation of OS/One UI/Material convention with a source and which pieces are proposed v3 adaptations. Do not treat a speculative layout as observed Android behavior.
- Link every child to its primary paired frames, and include a state-by-state matrix so an independent pre-build reviewer can score rubric dimensions **#1–#6 and #8**, with precise follow-up gaps.
- Evidence release requires independent review and merge; subsequently each child must get the evidence link and its own independent design preflight. Hosted CI checks and later runtime screenshots remain separate proof.

## Status

| Child | Light | Dark | State matrix | Design preflight |
| --- | --- | --- | --- | --- |
| V01 | pending | pending | pending | not requested |
| V02 | pending | pending | pending | not requested |
| V03 | pending | pending | pending | not requested |
| V04 | pending | pending | pending | not requested |
| V05 | pending | pending | pending | not requested |

**Do not interpret any blank or pending panel as a verified design PASS.**
