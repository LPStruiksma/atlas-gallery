# SAVED-1 synthetic storyboard · design evidence only

This is the **in-progress input brief** for [unit #779](https://github.com/LPStruiksma/atlas-gallery/issues/779), parent [SAVED-1 #578](https://github.com/LPStruiksma/atlas-gallery/issues/578), goal [#175](https://github.com/LPStruiksma/atlas-gallery/issues/175). It is **not** a production screen, implementation, emulator capture or approved design preflight. This initial document is a Codex handoff input; the finished board, manifest and generator are still required.

## Canonical design and reference constraints

- [Quiet Frame v3](../QUIET_FRAME.md) and [Owner intent](../../OWNER_INTENT.md) govern identity, navigation and feature parity. The one search sphere expands immediately above the bottom Pictures/Collections lapis slab; a saved search adds no tab. Preserve full AtlasSearch rather than replacing it.
- Compare the **merged, synthetic** [L5 Collections](../lapis/preflight/brand-l5-collections.png) and [L7 Search](../lapis/preflight/brand-l7-search.png) boards. Follow their real-shape photo strips, held-sphere state, two-place slab and approved lapis light/dark palette. No private Samsung or Continuum screens were inspected for this evidence.
- Source [#578 design-brief rev 1](https://github.com/LPStruiksma/atlas-gallery/issues/578#issuecomment-7055692729) for the saved-search behavior and panel specifics; [#779](https://github.com/LPStruiksma/atlas-gallery/issues/779) is the acceptance contract.
- Synthetic media only: draw fictional photographic **shapes/silhouettes with distinct real aspect ratios**, no personal photos, stock photos, externally retrieved media, copied reference graphics or map tiles.

## Geometry and styling (proposals, not device measurements)

The S24+ portrait design canvas is **1080 × 2340 px**, modelled at **384 × 832 dp**, scale **2.8125 px/dp**. Status bar **29 dp**, gesture region **16 dp**, and IME **324 dp** are explicit **assumptions to verify on device**. Every tap/overflow/chip has a >=48 dp effective target. Use 2 dp grid gap, 8 dp photo-strip corner radii, 24 dp slab radius, a white active-place pill and a sphere for loading or selection.

| Token | Light | Dark |
|---|---|---|
| Ground | #FFFFFF | #000000 |
| Mist/surface | #F2F3F8 | #14152A |
| Ink | #0E1030 | #F2F3F8 |
| Secondary ink | #5A5F7A | #9AA0B8 |
| Lapis fill | #2639C9 | #3448E0 |
| Lapis text | #2639C9 | #8C9BFF |
| On-brand | #FFFFFF | #FFFFFF |

Funnel Display for titles, Funnel Sans for controls; document source font paths, licensing and actual font SHA-256 in the manifest. Use on-brand contrast and no second accent. Out-of-palette markup may only be annotation ink, distinct from proposed UI.

## Required panels and behaviors

Render labelled **light/dark pairs** for all A–G, with at least **two 1080 × 2340 px frames at 100% light and 200% dark**, and smaller labelled comparisons with declared scale and annotation rectangles. Keep media next to visible actions, not just isolated text.

| Panel | Contract |
|---|---|
| A | Existing photo grid, bottom slab/sphere, expanded search with typed positive chips, **Not: Screenshots**, scope This screen/Whole library and reachable Save as album in keyboard-open overflow. Back once dismisses IME but keeps query. |
| B | Save sheet shows readable current query summary, editable name, Cancel/Save and duplicate-name plus failed-save states. No lost query; Back not trapped. |
| C | Collections → Albums latest-photo real-shape strips, **Saved searches** named row/count and persistent **Unsorted** row. Illustrate 0 and 1–2 assets without made-up photos or empty placeholders. |
| D | Saved-album row opens standard photo grid with Sort/shared Search; edit query, rename and remove-saved-album confirmation **without deleting media**. |
| E | Facet menu Include/Exclude; selecting Exclude makes a visible **Not:** chip. Contradictory filters show zero matches with **Clear exclusion**. Semantics: OR within positive family, AND across positive families, subtract union of excluded sets. |
| F | Unsorted = media in **no user-managed album**, regardless of camera folder. Adding to a manual album removes the result from virtual Unsorted without moving the file. |
| G | Concrete state strips: indexing with held sphere/count, permission or index unavailable with Retry, 10k+ count, 200% text/long name, TalkBack reading order, RTL, reduced-motion. |

## Observed references versus Atlas proposals (decision record, 2026-11-21)

| Source | Source evidence (not a rendering claim) | Atlas-specific proposal / deliberate deviation |
|---|---|---|
| [Aves dynamic album request](https://github.com/deckerst/aves/issues/1370) | Community issue discusses dynamic albums from filters. | Persist structured live-query facets; no assertion about Aves visual placement. |
| [Aves filtered album discussion](https://github.com/deckerst/aves/discussions/1864) | Discussion about creating a filtered collection as an album. | Save from the existing search overflow over the bottom slab, not a copied Aves toolbar. |
| [Immich no-album search](https://github.com/immich-app/immich/issues/21001) | Community issue requests “Not in any album.” | Persistent virtual Unsorted photo-led row; precise user-managed membership rule instead of a copied search filter. |
| [Material 3 SearchBar](https://developer.android.com/develop/ui/compose/components/search-bar) | Documented expandable search/suggestion pattern; version-dependent APIs. | Keep Quiet Frame search immediately above slab, not Material's conventional top position. |
| [Merged L5/L7 preflight](../lapis/preflight/README.md) | Synthetic layout grammar for photo-led Collections and expandable AtlasSearch; not runtime screenshots. | Preserve single-sphere search, white active pill, and lapis light/dark identity. |

## Deliverable and validation ledger

- `docs/design/saved-1/saved-1-search-albums.png`: generated storyboard; capture dimensions and SHA-256 in manifest.
- `docs/design/saved-1/manifest.json`: deterministic generator, font paths/licences and hashes, full panel label/scale/rectangle inventory, all assumptions and final board digest.
- `tools/design/saved1_*.py`: isolated generator and minimal unittest/check; no shared L5/L7 generator changes.
- Reproduce from declared Python/Pillow versions, verify every panel present and dimensions/digests, validate 48 dp minimum targets and state semantics. CI must run on the **exact applied PR head** before Review.

Generate and finalize the committed artifact ledger from the repository root with
`python3 tools/design/saved1_board.py --finalize`, then validate using
`python3 tools/design/saved1_check.py`. The validator fails if the PNG is missing,
its digest/byte count differ, the source fonts or Pillow version differ, or the
regenerated bytes differ. Commit the generated PNG and manifest **together**.
The final image must also be inspected visually for actual A–G interaction states;
passing copy/geometry assertions alone does not establish design-preflight fidelity. The board labels its fallback font, geometry
assumptions, target rectangles, synthetic real-shape media, and design-evidence
status. The manifest is the machine-readable inventory and digest ledger. The PNG
is intentionally excluded from the Codex text patch and must be committed and
inspected separately before #779 can be considered satisfied.

**Codex apply limitation:** the installed bounded apply workflow accepts only normal UTF-8 text additions/edits and refuses PNG/binary hunks, deletes or protected paths. The Python generator and documentation can be applied automatically; the generated PNG must be materialized and committed separately by the Dev relay, followed by exact-head CI. A patch-applied receipt **alone is not an artifact or a Review pass**.
