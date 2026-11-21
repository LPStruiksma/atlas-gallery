# Lapis L4–L8 synthetic pre-build evidence (#753)

This directory is the output location for five **synthetic-only** annotated S24+ 1080×2340 PNG boards. These are design-preflight storyboards, **not** device screenshots, implementation, performance readings, or inspection of the private Round 3/Continuum boards. The source of truth is [Quiet Frame v3](../../QUIET_FRAME.md) and the independent preflight receipts on [L4 #657](https://github.com/LPStruiksma/atlas-gallery/issues/657), [L5 #658](https://github.com/LPStruiksma/atlas-gallery/issues/658), [L6 #659](https://github.com/LPStruiksma/atlas-gallery/issues/659), [L7 #660](https://github.com/LPStruiksma/atlas-gallery/issues/660) and [L8 #661](https://github.com/LPStruiksma/atlas-gallery/issues/661).

## Expected reproducible deliverables

A single generator under `tools/design/` should create the following five boards. Each board may compose multiple labelled scale-preserving panels, but must include at least one complete 1080×2340 device frame with measured 48 dp effective touch areas, status/navigation/IME insets, light and dark appearances and 100%/200% font handling. Annotate focus/TalkBack and distinguish illustrative gestures from runtime captures.

| Board | Mandatory visual/storyboard evidence |
| --- | --- |
| `brand-l4-navigation.png` | Pictures and Collections active white pills, legacy Albums/Stories/People entry, 46 dp month sphere in ≥48 dp target, 3 fast-scroll frames with thumb position and gesture, three ordered selections with 2 dp white halo, full AtlasSearch handoff + IME/Back, viewer filmstrip/current 2 dp ring/action slab, loading arc/count, empty/error/large library, reduced motion on/off |
| `brand-l5-collections.png` | Four differently shaped photo-strip thumbnails without circular people crops, rows with count/date and partial/empty variants, navigation tap-count to individual person/album/place, 500-people sort/scoped search/keyboard, Trash/Hidden, long labels, loading/error and TalkBack traversal |
| `brand-l6-atlas.png` | Pictures with closed, finger-followed 50% and open overlay; current-month sphere/year bar counts; month/place tap to grid, reverse gesture, no-geotags and dense-dot-map cases, TalkBack month/place lists, reduced-motion cuts; map MUST be offline schematic dots, never real tiles, network or location data |
| `brand-l7-search.png` | Search sphere/rest, expanding full-width bar directly above slab, IME/history/autocomplete/scope toggle/results/no-results/loading/error; preserve natural-language, face, image, metadata and history entry; scoped vs whole-library, query preservation, IME Back then collapse Back, focus traversal and reduced motion |
| `brand-l8-justified.png` | Day justified real-shape rows with panoramas, portrait, short final row, mixed video and 2 dp gaps; square month/year comparison, 3-step anchored pinch forward/back, selection and held sphere, empty/loading/error/150k schematic library, font-scale cases; plan FrameMetrics post-build gate without inventing measurements |

Every board should have a **source → observed convention → Atlas adaptation/deviation** row comparing two *independently documented public* interaction/layout patterns. Do not claim independent inspection of the private boards. Use only approved colour and type tokens: light brand fill `#2639C9`, dark `#3448E0`, Funnel Display/Sans, neutral ground/surface/ink from Quiet Frame v3. Use generated shapes/gradient placeholders, never real pictures, people or geodata.

## Acceptance and provenance

- Generator should be deterministic, documented (command and dependencies), render all five PNGs, and validate exact dimensions for every full-device base frame; no private input or network required at generation time.
- The five **PNG binaries must actually be committed** before review, not merely named in a manifest.
- The installed Codex patch-apply guard accepts only regular **text** files (max 12 changed files, no binary), so a Codex-generated text patch can deliver the generator and associated text annotations, but **cannot by itself land the PNG binaries**. A subsequent Dev action must generate and commit them through a binary-capable path, then validate exact-head CI.
- L2 #720 gates *runtime implementation*, not these pre-build boards. When evidence actually exists and has independent Review, re-request the separate pre-build design preflight on all five units.
