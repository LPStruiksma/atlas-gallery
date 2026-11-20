# DESIGN-8T #627 — proposed Lapis Atlas viewer target (pre-build evidence)

**What this is:** the dimensioned **proposed target** for the viewer
([#142](https://github.com/LPStruiksma/atlas-gallery/issues/142)) in the
**Lapis Atlas** look (Quiet Frame v3, [BRAND-L1 #654](https://github.com/LPStruiksma/atlas-gallery/issues/654),
authority PR [#665](https://github.com/LPStruiksma/atlas-gallery/pull/665)), anchored to the
**measured current implementation** from DESIGN-8M
([#609](https://github.com/LPStruiksma/atlas-gallery/issues/609), merged
[#616](https://github.com/LPStruiksma/atlas-gallery/pull/616)). Gesture
navigation stays **UNKNOWN / preflight-gated** ([GESTURE_NAV.md](GESTURE_NAV.md)).
It exists so the independent issue design preflight on #142 can judge geometry and
states before anyone builds.

**Product rule it follows (#654, Lukas 2026-11-20 02:13Z/02:22Z):** Samsung Gallery
**feature** parity stays (every viewer feature of parity §1/§6 has a home here), but
structure and look are Atlas's own: "the way that the various UI items look should
not be limited by it." So no Samsung layout value below is mandatory; each value is
either a v3 token or a named Atlas choice. If #665 changes a v3 value, change the
constant in `generate_target.py` and regenerate.

**What this is not:** a viewer implementation, a runtime capture of the target, a
physical Galaxy S24+ measurement, or acceptance of baseline defects. Every diagram is
a **target concept**: insets and cutout are copied from a named measured frame,
every other rectangle is **proposed**. No `app/src/main/**` change.

## Sources

| Kind | Where | Used for |
|---|---|---|
| MEASURED, three-button | [run 44627923073](https://github.com/LPStruiksma/atlas-gallery/actions/runs/44627923073), artifact `ATLAS-android-UI-evidence-shard-0`, `ui-evidence/viewer-609-photo.json` (13 frames) and `viewer-609-video.json` (16 frames); PNGs in `ATLAS-android-UI-evidence` | Window, density, insets, cutout and current semantic bounds |
| NOT MEASURED, gesture navigation | Attempted test-only capture could not stay isolated ([GESTURE_NAV.md](GESTURE_NAV.md)) | Gesture rules below are computed and **UNKNOWN / preflight-gated** |
| COPIED extract | [`measured-baseline.json`](measured-baseline.json), written by [`extract_baseline.py`](extract_baseline.py) | The only measured input of the generator |
| PROPOSED | [`generate_target.py`](generate_target.py) → [`target-geometry.json`](target-geometry.json), [`tables.md`](tables.md), [`svg/`](svg/) | Every target rectangle, check and diagram |
| Rules | #654 / #665 Quiet Frame v3 (look, structure), [Samsung parity §1/§6](../../product/SAMSUNG_PARITY_SPEC.md) (**features and behaviour only**), [OWNER_INTENT](../../OWNER_INTENT.md) | Constants below |

Host for all measurements: GitHub API 35 x86_64 `pixel_6` emulator with
`S24DisplayRule` (1080×2340 px, 450 dpi → **2.8125 px/dp**, observed per frame). Not Lukas's phone.

Regenerate everything (Python 3, no dependencies):

```bash
python3 docs/design/viewer-142-target/extract_baseline.py <dir with viewer-609-*.json> [gesture report.json <run id> <head sha>]
python3 docs/design/viewer-142-target/generate_target.py
```

## Measured current implementation (summary)

Full numbers: [`measured-baseline.json`](measured-baseline.json); per viewport: [tables.md T1/T2](tables.md).

| | Portrait 1080×2340 px = 384×832 dp | Rotated landscape 2340×1080 px = 832×384 dp |
|---|---|---|
| Frame key | `photo/bright/dark/portrait/shown` | `photo/bright/dark/landscape/shown` (rotation 1, width > height) |
| Navigation | three-button (`navigation_mode=0`) | three-button |
| safeDrawing L/T/R/B | 0 / 125 / 0 / 135 px = 0 / 44.4 / 0 / 48 dp | 125 / 68 / 135 / 0 px = 44.4 / 24.2 / 48 / 0 dp |
| Status / navigation bars | top 44.4 dp / bottom 48 dp | top 24.2 dp / right 48 dp |
| Cutout | 480,0 → 595,125 px (top centre) | 0,485 → 125,600 px (left edge) |
| Current back, details, favourite…delete, play/pause | 40.2×40.2 dp semantic, `at_least_44dp=false` | same |
| Current thumbnails | centre 37.3×50.2 dp, neighbour 29.9×40.2 dp | same |
| Current video controls → filmstrip | 87.8 dp (one 4 s clip, 100% font, portrait) | not measured as a gap |
| Hidden chrome | safeDrawing 0 / 44.4 / 0 / 0 dp (cutout only; bars hidden) | not captured |

## Proposed target model

dp, origin top-left, `S = safeDrawing`. **v3** = value from #654; **Atlas choice** = proposed here (v3 silent).

| Rule | Value | Source |
|---|---|---|
| Touch target for every control | **48 × 48** semantic hit (visible shape + 4 each side) | v3 "touch targets ≥ 48 dp" |
| Viewer ground | `#000000` both themes | v3 |
| Action slab | favourite · edit · share · delete; four 48 hits, 8 gaps, 4 padding → **224 × 56**, radius **24**, solid lapis **`#3448E0`**, white icons; centred on safe width, bottom `H−S.b−12` | v3 viewer slab; layout Atlas choice. Edit opens the one central editor (AI edit and Face Swap inside it) |
| Filmstrip | tiles **44 × 56**, radius **8**, **4** gaps (48 pitch), current item **not** enlarged; **2 dp white ring** + small **`#8C9BFF` sphere (8 dp) 4 dp above** the current tile | v3 sphere + ring + tile radius; sizes Atlas choice (replaces Samsung 30×40 / 2 dp) |
| Filmstrip touch band | 76 tall (4 + sphere 8 + 4 + tile 56 + 4), full safe width, bottom 8 above the slab; one **48 × 76** cell per tile, contiguous, non-overlapping | Atlas choice |
| Back | 40 circle at `S.l+12, S.t+8`, hit 48; fill mist `#14152A` at 80% | Atlas choice |
| Top-right group | one pill, N = 1–3 contiguous 48 hits (Find this person only with indexed faces, Find similar, Details), right edge `W−S.r−12` | parity features; Atlas placement |
| Date, time, place | "20 November 2026 at 14:32 in Utrecht" (no middle dots); Funnel Sans body 15/22, place secondary 14/20 | v3 type + "no middle-dot meta strings" |
| Metadata reflow F1 (portrait) | 1 line in the row → 2 lines in the row → below the row (`S.t+56`), full safe width − 24, ≤ 2 lines, ellipsized | Atlas choice |
| Metadata reflow F2 (landscape) | never a second row; one centred line, place ellipsized first; full text in Details | Atlas choice |
| Missing date/place | omit; no placeholder, no empty row | v2/v3 removal list |
| Top scrim | 35% black, 0 → lowest top element + 16 | v2 carried (v3 silent), see C2 |
| Video row | 48 tall, bottom **12** above the strip band (rule ≥ 8); play 48 hit left; elapsed/duration secondary 14/20 sized for the longest value; seek hit between; track 4 (played part `#8C9BFF`), thumb 16, inset 8 so the thumb stays inside the hit at 0% and 100% | parity §6 features; Atlas layout |
| Video reflow F3 | seek hit < 120, or labels taller than 48 → labels above the bar | Atlas choice |
| Motion | **200 ms Lift**; chrome and system bars together; "remove animations" cuts | v3 |
| Icon artwork | 22 dp (v2 value carried until L2 sets icons) | v3 silent |

## Geometry results

Every viewport × 10 scenarios: [tables.md T3](tables.md). Across **all 30**: 0 hit collisions,
0 hits outside safeDrawing, 0 hits over the cutout, **0 hits under 44 dp** (all ≥ 48,
including filmstrip cells), video row → strip gap **12 dp** in every video scenario
(computed for this target, not imported from the 87.8 dp baseline).

| Viewport | Top chrome bottom | Strip band top | Free media band (photo) | Worst free media band |
|---|---|---|---|---|
| portrait-3button (measured insets) | 122.4 (metadata below row) | 632.0 | 493.6 | 327.6 (long video, 200% font) |
| landscape-3button (measured insets, rotation 1) | 76.2 (metadata inline) | 232.0 | 139.8 | 79.8 (long video) |
| landscape-narrow-computed (640×360, same insets) | 76.2 | 208.0 | 115.8 | 55.8 (long video) |

Visible chrome keeps 12 dp from the side and bottom safe edges (back left, group
right, slab bottom) and 8 dp below the safe top.

## Diagrams (target concepts, inspectable SVG)

| File | Shows |
|---|---|
| [`svg/portrait-3button--photo.svg`](svg/portrait-3button--photo.svg) | Portrait, photo, measured insets/cutout, every target box with numbers |
| [`svg/portrait-3button--video-long-font200.svg`](svg/portrait-3button--video-long-font200.svg) | Portrait, long video at 200% font (F1 + F3), gap annotation |
| [`svg/portrait-3button--photo-font200-longplace.svg`](svg/portrait-3button--photo-font200-longplace.svg) | Metadata below the row, 2 lines, ellipsized |
| [`svg/landscape-3button--*.svg`](svg/) | Physically rotated landscape (rotation 1, cutout left, nav right) |
| [`svg/landscape-narrow-computed--*.svg`](svg/) | Narrow 640×360 landscape stress case (computed) |
| [`svg/*--states.svg`](svg/) | 16-state target matrix per viewport |
| [`svg/*--interaction.svg`](svg/) | Gesture priority bands, exclusion rects and TalkBack order |

Legend: teal dashed = measured safeDrawing; blue = measured bars; red = measured
cutout; pink dashed = proposed semantic hit; orange dashed = proposed strip band;
yellow = proposed text box (width is an estimate).

## Target states

| State | Target treatment | Evidence here | Status |
|---|---|---|---|
| Light / dark theme | Viewer is black in both; slab `#3448E0`, sphere `#8C9BFF`, white ring in both | states.svg 1–2, T4 | proposed |
| Bright / dark synthetic photo | Slab is solid, so its icons are 6.67:1 on any photo; scrim, sphere and ring see T4 | states.svg 2–3, T4 | proposed; **C2/C3** |
| Photo only / video | Video adds the 48 row above the strip | layout SVGs, T3 | proposed |
| Chrome + system bars shown / hidden | One media tap; 200 ms Lift; hidden = media only | states.svg 4, measured hidden frames | proposed |
| Short (0:04) / long (1:23:45) video | Labels sized for the longest value; seek width in T3 | states.svg 8–9, T3 | proposed |
| Seek start / end | Thumb fully inside the 48 hit (8 inset) | states.svg 10–11 | proposed |
| Long filmstrip (30+) | Virtualized; band ends fade 24 dp; centre item = pager item | states.svg 15 | proposed |
| 200% font | F1 / F2 / F3 | T3, font200 SVGs | proposed; text widths estimated |
| Missing date / place | Omit | states.svg 5–6, T3 | proposed |
| Pending (decode not ready) | Cached strip thumbnail as placeholder, chrome shown, no text | states.svg 13 | proposed |
| Unavailable / error | 22 dp neutral glyph, no text; back, Details, delete live; favourite/edit/share disabled | states.svg 14 | proposed; error vs loading **NOT_CAPTURED** in baseline |
| Empty catalogue | Immediate safe exit, viewer never shown | measured assertion (`emptyCatalogueUsesSafeExitWithoutShowingViewer`) | measured (current) |
| One-handed reach | Slab, play, seek and strip in the bottom third; back/top group in the top third | states.svg 16, T5 | proposed; **C4** |
| TalkBack / action order | interaction.svg 1–13 | interaction.svg | proposed; spoken output **NOT_CAPTURED** |
| Gesture navigation | Layout follows safeDrawing; strip/seek drags request exclusion; no hit in the mandatory home zone | [GESTURE_NAV.md](GESTURE_NAV.md) | **UNKNOWN / preflight-gated** |
| Rotation 270 (cutout right) | Inset-driven model mirrors | not drawn | **UNKNOWN** |

## Gesture priority and TalkBack order

See [`svg/portrait-3button--interaction.svg`](svg/portrait-3button--interaction.svg) (its red side bands are a 24 dp placeholder, not a measurement).

1. **System edge zones** (`systemGestures`, not yet measured) stay system-owned, except
   the strip band (76) and the seek band (48), which request
   `setSystemGestureExclusionRects` while chrome is shown: 124 dp per edge, inside
   Android's 200 dp cap. The bottom mandatory home zone is never excluded.
2. **Seek drag** consumes its gesture; no chrome toggle or pager swipe. A frame-thumbnail
   seek strip while held (parity VIEW:V16–V18 feature, **UNVERIFIED**) would reuse the strip band.
3. **Strip drag / tap**: horizontal slop scrubs with platform fling; tap selects that
   tile (48 cells, no ambiguity). Never toggles chrome.
4. **Buttons**: 48 hits; sliding off cancels.
5. **Media**: single tap toggles chrome after the double-tap timeout; double-tap zoom;
   pinch; horizontal pager; swipe up = Details, swipe down = close (unzoomed).

TalkBack: back → date, time and place (one node) → shown top actions → media
("Photo N of M: title") → play/pause → seek (adjustable) → filmstrip (collection,
current selected, previous/next actions) → favourite → edit → share → delete.

## Conflicts and choices for the preflight

| # | Item | Fact | Proposed resolution |
|---|---|---|---|
| C1 | Filmstrip touch size | Resolved by #654: leaving Samsung's 30×40 / 2 dp lets cells be 48 × 76 | 44×56 tiles at 4 dp gaps (fewer tiles visible: 7 portrait, 15 landscape) |
| C2 | 35% top scrim vs 4.5:1 text | Ink `#F2F3F8` on 35% black over white = **2.20:1** (T4) | **A:** deepen to 60% behind the metadata box only (5.18:1). **B:** drop the top scrim and put metadata in a mist pill like back |
| C3 | White ring and `#8C9BFF` sphere on bright content | Ring 1.0:1, sphere 2.55:1 on white | 1 dp black keyline outside ring and sphere (21:1); v3 says lapis is never a thin outline, the keyline is black |
| C4 | Back in the top third | Centre y 72.4 of 832 | Keep; swipe-down close and system back are reachable exits. v3 frees a move to the slab if the preflight prefers |
| C5 | Icon size | v3 silent | 22 dp until BRAND-L2 sets icons |

## Unknown or unverified (do not read as passing)

- Physical S24+ panel, One UI gesture settings: **UNKNOWN**.
- Gesture-navigation insets: **NOT CAPTURED**.
- VIEW:V16–V18 seek frame strip behaviour: **UNVERIFIED**.
- Text widths use a 0.58 em estimate for Funnel Sans; the implementation must measure real text.
- Rotation 270, split-screen, foldables: not captured.
- Spoken TalkBack, long-duration video, codec-error video, loading vs error photo: **NOT_CAPTURED** (as in #609).

## Capture manifest required at implementation review

The production viewer PR (only after a preflight PASS) must attach exact-head
emulator captures and a measurement report under the same schema for: portrait and
rotated landscape × light/dark × bright/dark photo × shown/hidden; video short and
long × playing/paused × seek start/end; 200% font portrait and landscape; missing
place and date; unavailable media; gesture navigation portrait and landscape (needs
an isolated run); and report `at_least_44dp=true` for every control including
filmstrip cells and `video_controls_to_filmstrip_gap_dp ≥ 8` in each video frame.
