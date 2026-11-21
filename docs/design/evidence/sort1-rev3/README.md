# SORT-1D revision 3 working evidence

This directory is the **proposed synthetic design evidence** workspace for [SORT-1D #869](https://github.com/LPStruiksma/atlas-gallery/issues/869). It is **not** a device capture, Android implementation, or approved design preflight.

Source of truth:
- [Issue #869 repair brief](https://github.com/LPStruiksma/atlas-gallery/issues/869) (preflight findings #4, #6, #8).
- `docs/design/SORT1_PREFLIGHT.md` revision 2 and `docs/design/evidence/sort1-rev2/`: retain the repaired six-key / 48 dp minimum geometry, replace schematic direction controls.
- `app/src/main/java/nl/atlas/gallery/PhotosHeader.kt` `MediaSortSheet` on `main`: options are `heightIn(min = 52.dp)` radio rows; direction-selected keys expose two vertically stacked >=52 dp radio rows labelled Ascending and Descending; Relevance hides direction and Random exposes Shuffle again.
- `docs/design/QUIET_FRAME.md` v3: screen titles Funnel Display 700, 38/44 sp, -0.03em in brand-text; other copy Funnel Sans.

Expected committed output: the revised preflight page, directly inspectable light/dark SVG proposals and a deterministic coordinate/coverage audit. Any claim about visual fidelity must distinguish illustrative dp viewBox layout from sp typography and actual measured Android output. No fonts, personal images, Kotlin, app resources or permission changes.
