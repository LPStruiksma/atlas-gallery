# DESIGN-8 · S24+ viewer-chrome design-evidence sheet (pre-build)

Status: **schematic proposal, not emulator evidence and not an independently approved design**. Issue: [#142](https://github.com/LPStruiksma/atlas-gallery/issues/142). Existing implementation baseline: [#359](https://github.com/LPStruiksma/atlas-gallery/pull/359) and `app/src/main/java/nl/atlas/gallery/GalleryViewer.kt` on main at `04569f4e87cc97e4d57c3dfcd13a3874b9f3d507`. Source of intended style: [SAMSUNG_PARITY_SPEC §0, §1, §6](../product/SAMSUNG_PARITY_SPEC.md). No Samsung artwork or private screenshots included.

## Coordinate contract (do not turn pixels into invented dp)

The 1080×2340 portrait and 2340×1080 landscape canvases in the linked SVGs use **device pixels**, representing the test capture resolution; their graphical control placements are **illustrative**, not measured from an emulator. The runtime device density `D` in px/dp, the Android `safeDrawing` inset tuple `(L,T,R,B)` in dp, cutout position/extent, status/navigation bar geometries and rotated landscape dimensions remain **UNKNOWN until recorded from actual S24+ or matching API-35 emulator**. Treat every dp-valued layout below as a *local safe-area constraint*, never as a literal SVG pixel offset.

Inset-relative layout system, portrait or truly rotated landscape:
- viewport dp width `W = viewportPxWidth/D`; height `H = viewportPxHeight/D`. Safe rectangle `[L, W-R] × [T,H-B]`.
- Top chrome starts `x=L+12dp`, `y=T+8dp`; back pill left, variable-length existing action group right at `x=W-R-12dp`. Keep at least 8dp between groups or move lower-priority top actions into a single overflow when they would collide. Do not clip at the cutout. Existing `IconButton` uses Material touch sizing (nominal 48dp); icon art intent 24dp. Left back has an additional internal 6dp start padding in main.
- Bottom existing chrome is anchored above `B`. Its bottom action pill has 10dp of bottom padding; above it there is 10dp from the 56dp-high filmstrip. The filmstrip thumbnails are 30×40dp with 2dp gaps and a 1.25× selected scale (37.5×50dp visual). The filmstrip’s 56dp container must never be treated as a 56dp touch height for every tile: its 30×40dp clickable cells need separate accessibility/hit-target evaluation.
- Current bottom action `IconButton` modifiers each add 10dp horizontal padding, giving a nominal 68dp per enabled action (48dp touch + 20dp spacing) inside a pill with 4dp per side; with all four present, **approximately 280dp total**. On a safe-width below this plus margins, compress/gap-adjust or expose an overflow rather than horizontally clipping controls. Do not assert that compression is already implemented.
- Video `VideoPage` controls: `safeDrawing` bottom/horizontal inset + 14dp outer horizontal margins + 164dp bottom spacing; one 54dp-high full-width pill; play, timecode, Material `Slider`, duration. Seeking shows a 176×110dp preview and 8dp separator. A provisional non-overlap target is `videoControlsBottom <= filmstripTop - 8dp` (coordinates increase downward). Resolve the actual positions from a composable capture, not these cartoons.
- Screenshot diagrams show full-bleed synthetic media with no filename, picture counter, AI chrome, permanent progress rail, centre marker or redundant scrub label. Android status/nav regions are drawn only to illustrate *reserved/gesture* surfaces. Chrome and system bars disappear together on media tap, and edge-swipe transient bars must not steal control targets.

## Linked inspectable schematic artifacts

- [Portrait 1080×2340 diagram](viewer-chrome-142-portrait.svg), visually distinguishes safe-area guides, top grouping, 56dp strip, actions, and overlay video controls.
- [Rotated landscape 2340×1080 diagram](viewer-chrome-142-landscape.svg), with the cutout edge and same independent safe-rectangle logic. It is a **layout sketch with transposed pixel canvas, not a captured physical rotation**.
- [State sheet](viewer-chrome-142-states.md), including photo/video, shown/hidden bars, seek endpoints, long strip, accessibility, themes and failure states.

## Current implementation vs proposed fidelity/unknowns

| Element | Actual baseline observed in source | Target / not yet validated |
|---|---|---|
| Full-bleed image | `Box(fillMaxSize().background(Black))`; overlaid rows | Keep image primary, preserve photo/video gesture model |
| Top | `safeDrawing(Top+Horizontal)`, 12dp horizontal + 8dp vertical; 50%-rounded pill, `Color(0xB3202124)`; back plus conditional face/search/AI-overflow/details | 12dp inset-relative desired; Samsung’s precise pill footprint, icon paths and ordering **UNKNOWN** |
| Bottom | `safeDrawing(Bottom+Horizontal)`, 56dp strip, 10dp gap, action pill with 10dp bottom; four conditional actions with 10dp extra horizontal per icon | Four actions in favourite/edit/share/delete order; verify narrow landscape and one-hand reach |
| Filmstrip | 30×40dp tile, 2dp gap, selected scale 1.25; each cell individually clickable | Check minimum 48dp-equivalent accessible hit area in rendered build; parity VIEW:V15 **UNKNOWN** |
| Video | 54dp row, 14dp outer horizontal, 164dp above safe bottom, Material Slider, 176×110dp drag preview | Confirm non-overlap with strip, and Samsung-like seek visuals; VIEW:V16–18 **UNKNOWN** |
| Scrim | Top `0xB8000000`; bottom `0xCC000000` translucent gradients (#359); chrome pill `0xB3202124` | Bright photo proof still needed; #351 may move literals into tokens without changing values |
| Animation | `fadeIn(tween(200))` / `fadeOut(tween(200))`, system bars via `WindowInsetsControllerCompat` | Verify synced shown/hidden captures and transient edge swipe |
| Interaction | `MediaStore.createFavoriteRequest`, `createTrashRequest`, Android share, external edit; player scrub lifecycle | Preserve handoffs; seek pointer must not toggle chrome |
| Android rotation | #359 test uses `UiAutomation.setRotation` | Evidence for proposed **future UI implementation** must run actual rotation and report measured insets and safe target bounds |

## Required measured proof before approving the implementation plan

1. Record `D` and actual `WindowInsets.safeDrawing` pixel/dp values at both orientations, then overlay measured control bounds on a **1080×2340 real app capture** (or document if actual capture size differs), plus a physically rotated landscape capture. A sketch alone cannot prove clipping safety.
2. Inspect contrast and top/bottom gradient on a bright synthetic photo, dark photo and synthetic video in light/dark system themes, for both shown and hidden chrome. Capture transitions where applicable.
3. Inspect all action target rectangles and accessibility labels, especially 30×40dp filmstrip cell targets, interactive seek region, long-strip pointer conflict and landscape action-group collision. Record if an overflow/hit target redesign is required.
4. Verify video short/long duration endpoints, pause/play, preview-on-held-thumb, progress unavailable, pending/player error, and safe Back exit. No claim of actual Samsung exact seek appearance without cited owner reference.
5. Independent Review reruns `Type: design-preflight` on issue #142 against this brief and these linked artifacts. **PASS is not assumed**; only later code-bearing PR must supply exact-head emulator screenshots, manifest and CI.

Nothing in these files modifies runtime behavior or performance/decode paths owned by #124.
