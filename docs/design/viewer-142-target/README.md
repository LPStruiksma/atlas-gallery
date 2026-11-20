# DESIGN-8T #627 — proposed Quiet Frame viewer target (pre-build evidence)

**What this is:** the dimensioned **proposed target** for the Quiet Frame viewer
([#142](https://github.com/LPStruiksma/atlas-gallery/issues/142)), anchored to the
**measured current implementation** from DESIGN-8M
([#609](https://github.com/LPStruiksma/atlas-gallery/issues/609), merged
[#616](https://github.com/LPStruiksma/atlas-gallery/pull/616)). Gesture
navigation stays **UNKNOWN / preflight-gated** ([GESTURE_NAV.md](GESTURE_NAV.md)). It exists so the independent
issue design preflight on #142 can judge geometry and states before anyone builds.

**What this is not:** a viewer implementation, a runtime capture of the target, a
physical Galaxy S24+ measurement, or acceptance of baseline defects. Every
diagram here is a **target concept**; its insets and cutout are copied from a
named measured frame and every other rectangle is **proposed**. No production
code (`app/src/main/**`) changes in this unit.

## Sources

| Kind | Where | Used for |
|---|---|---|
| MEASURED, three-button | [run 44627923073](https://github.com/LPStruiksma/atlas-gallery/actions/runs/44627923073), artifact `ATLAS-android-UI-evidence-shard-0`, `ui-evidence/viewer-609-photo.json` (13 frames) and `viewer-609-video.json` (16 frames); PNGs in `ATLAS-android-UI-evidence` | Window, density, insets, cutout and current semantic bounds |
| NOT MEASURED, gesture navigation | Attempted test-only capture failed to stay isolated (see [GESTURE_NAV.md](GESTURE_NAV.md)) | Gesture rules below are computed and **UNKNOWN / preflight-gated** |
| COPIED extract | [`measured-baseline.json`](measured-baseline.json), written by [`extract_baseline.py`](extract_baseline.py) from the reports above | The only measured input of the generator |
| PROPOSED | [`generate_target.py`](generate_target.py) → [`target-geometry.json`](target-geometry.json), [`tables.md`](tables.md), [`svg/`](svg/) | Every target rectangle, check and diagram |
| Rules | [Quiet Frame](../QUIET_FRAME.md) (look), [Samsung parity §0/§1/§6](../../product/SAMSUNG_PARITY_SPEC.md) (order and behaviour), [OWNER_INTENT](../../OWNER_INTENT.md) | Constants below |

Host for all measurements: GitHub API 35 x86_64 `pixel_6` emulator with
`S24DisplayRule` (1080×2340 px override, 450 dpi → **2.8125 px/dp**, observed in
each frame). Not Lukas's phone.

Regenerate everything (Python 3, no dependencies):

```bash
python3 docs/design/viewer-142-target/extract_baseline.py <dir with viewer-609-*.json> [gesture report.json <run id> <head sha>]
python3 docs/design/viewer-142-target/generate_target.py
```

## Measured current implementation (summary)

Full numbers per frame: [`measured-baseline.json`](measured-baseline.json); per viewport: [tables.md T1/T2](tables.md).

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

Gesture navigation: **not captured** ([GESTURE_NAV.md](GESTURE_NAV.md)). Every #616 frame is three-button.

## Proposed target model

All values are dp, origin top-left, `S = safeDrawing` of the viewport being laid out. These are **targets**.

| Rule | Value | Source |
|---|---|---|
| Visible pill edge to safe edge | 12 | parity §0 floating chrome |
| Icon artwork | 22, 1.6 stroke | Quiet Frame icons (wins over parity's 24) |
| Semantic hit for every icon action | **48 × 48** (visible pill + 4 on each side) | Quiet Frame ≥ 44 touch |
| Back | visible 40 circle at `S.l+12, S.t+8`; hit `S.l+8 … S.l+56`, `S.t+4 … S.t+52` | parity §1 top-left pill |
| Top-right group | one pill, N = 1–3 contiguous 48 hits (Find this person only with indexed faces, Find similar, Details), right edge `W−S.r−12` | parity §1, conditional |
| Date · time · place | centred on the window between back and group hits (8 gap each side); Label 13/18 sp, place may be Meta 12/16 sp | Quiet Frame viewer |
| Metadata reflow (F1, portrait) | 1 line in the row → 2 lines in the row → below the row (`S.t+56`), full safe width minus 24, ≤ 2 lines, ellipsized | this model |
| Metadata reflow (F2, landscape) | never a second row; one centred line, place ellipsized first | this model (height-starved) |
| Missing date/place | omit; no placeholder, no empty row | Quiet Frame |
| Top scrim | 35% black from 0 to lowest top element + 16 | Quiet Frame |
| Action pill | favourite · edit · share · delete; four 48 hits, 8 gaps, 4 padding → **224 × 56** visible, centred on safe width, bottom `H−S.b−12` | parity §1 order |
| Filmstrip touch band | 56 tall, full safe width, bottom 8 above the action pill | this model |
| Tiles | 30×40, 2 gaps, centre ×1.25 = 37.5×50 (**VIEW:V15 UNVERIFIED**), 4 radius, 2 accent ring on current | parity §1, Quiet Frame |
| Tile hit cells | contiguous, non-overlapping: visible width + 1 each side × band height 56 (centre 39.5×56, others 32×56) | conflict **C1** |
| Video row | 48 tall, bottom **12** above the strip band (rule ≥ 8); play 48 hit at left; elapsed/duration labels Meta 12/16 sp sized for the longest value; seek hit between labels; 4 track, 16 thumb, track inset 8 so the thumb stays inside the hit at 0% and 100% | parity §6 |
| Video reflow (F3) | seek hit < 120, or labels taller than 48 → labels move above the bar | this model |
| Fade | 250 ms standard easing, system bars with chrome; remove-animations respected | Quiet Frame (wins over 200 ms) |
| Pill fill | `#23262E` at 72% in both themes (viewer ground is black in both) | Quiet Frame raised + parity translucent pills |

## Geometry results

Generated, every viewport × scenario: [tables.md T3](tables.md). Headline numbers
(photo, chrome shown, 100% font, 3 top actions):

| Viewport | Top chrome bottom | Strip band top | Free media band | Hit collisions | Hits outside safe / over cutout | Hits < 44 dp |
|---|---|---|---|---|---|---|
| portrait-3button (measured insets) | 118.4 (metadata below row) | 652.0 | 517.6 | 0 | 0 / 0 | tile cells only (C1) |
| landscape-3button (measured insets, rotation 1) | 76.2 (metadata inline) | 252.0 | 159.8 | 0 | 0 / 0 | tile cells only (C1) |
| landscape-narrow-computed (640×360, same insets) | 76.2 | 228.0 | 135.8 | 0 | 0 / 0 | tile cells only (C1) |

Worst case in the matrix: narrow landscape, long video (1:23:45), 200% font → free
media band **75.8 dp**, video gap to strip **12 dp**, no collisions. In every
video scenario `videoControlsBottom ≤ filmstripTop − 8` holds with **12 dp**
(T3 column), computed from this target, not imported from the 87.8 dp baseline.
Visible pills keep exactly 12 dp from the side and bottom safe edges they sit
against (back left, group right, action pill bottom) and 8 dp below the safe top,
where the status bar inset already separates them from the screen edge.

## Diagrams (target concepts, inspectable SVG)

| File | Shows |
|---|---|
| [`svg/portrait-3button--photo.svg`](svg/portrait-3button--photo.svg) | Portrait, photo, measured insets/cutout, every target box with numbers |
| [`svg/portrait-3button--video-long-font200.svg`](svg/portrait-3button--video-long-font200.svg) | Portrait, long video at 200% font (F1 + F3 reflow), gap annotation |
| [`svg/portrait-3button--photo-font200-longplace.svg`](svg/portrait-3button--photo-font200-longplace.svg) | Metadata below the row, 2 lines, ellipsized |
| [`svg/landscape-3button--*.svg`](svg/) | Physically rotated landscape (rotation 1, cutout left, nav right) |
| [`svg/landscape-narrow-computed--*.svg`](svg/) | Narrow 640×360 landscape stress case (computed) |
| [`svg/*--states.svg`](svg/) | 16-state target matrix per viewport |
| [`svg/*--interaction.svg`](svg/) | Gesture priority bands, exclusion rects and TalkBack order |

Legend in every layout SVG: teal dashed = measured safeDrawing; blue = measured
bars; red = measured cutout; pink dashed = proposed semantic hit; orange dashed =
proposed strip band; yellow = proposed text box (width is an estimate).

## Target states

| State | Target treatment | Evidence here | Status |
|---|---|---|---|
| Light / dark theme | Viewer ground black in both; only the accent ring colour differs (`#2A62D4` / `#7FA8FF`) | states.svg 1–2, T4 | proposed |
| Bright / dark synthetic photo | Same geometry; legibility from scrim and pill (T4) | states.svg 2–3, T4 | proposed; **C2/C3** |
| Photo only / video | Video adds the 48 row above the strip | layout SVGs, T3 | proposed |
| Chrome + system bars shown / hidden | One media tap fades both (250 ms); hidden = media only, cutout inset only | states.svg 4, measured hidden frames | proposed |
| Short (0:04) / long (1:23:45) video | Label width sized for the longest value; seek width in T3 | states.svg 8–9, T3 | proposed |
| Seek start / end | Thumb centre at track ends, 16 thumb fully inside the 48 hit (8 inset) | states.svg 10–11 | proposed |
| Long filmstrip (30+) | Virtualized; band ends fade 24 dp; centre item = pager item | states.svg 15 | proposed |
| 200% font | F1 (portrait) / F2 (landscape) / F3 (video labels) | T3, font200 SVGs | proposed; text widths are estimates |
| Missing date / place | Omit the line or the place; no placeholder | states.svg 5–6, T3 | proposed |
| Pending (decode not ready) | Strip's cached thumbnail stretched as placeholder, chrome shown, no spinner text | states.svg 13 | proposed |
| Unavailable / error | 22 dp neutral glyph, no text; back, Details and delete live; favourite/edit/share disabled (semantics disabled) | states.svg 14 | proposed; error vs loading **NOT_CAPTURED** in baseline |
| Empty catalogue | Immediate safe exit, viewer never shown | measured assertion (`emptyCatalogueUsesSafeExitWithoutShowingViewer`) | measured (current) |
| One-handed reach | Actions, play, seek, strip in the bottom third; back/top group in the top third (mitigation: swipe-down close, system back) | states.svg 16, T5 | proposed; **C4** |
| TalkBack / action order | interaction.svg order 1–13 | interaction.svg | proposed; spoken output **NOT_CAPTURED** |
| Gesture navigation | Layout follows safeDrawing (which includes the gesture bar); drags in strip/seek bands request exclusion; no hit in the mandatory home zone | [GESTURE_NAV.md](GESTURE_NAV.md), interaction SVGs | **UNKNOWN / preflight-gated**: insets not captured |
| Rotation 270 (cutout right) | Mirror of rotation 1: insets swap sides; model is inset-driven, centring on safe width | not drawn | **UNKNOWN**, not captured |

## Gesture priority and TalkBack order

See [`svg/portrait-3button--interaction.svg`](svg/portrait-3button--interaction.svg) (its red side bands are a 24 dp placeholder, not a measurement).

1. **System edge zones** (`systemGestures`, not yet measured) stay system-owned, except the
   strip band and the seek band, which request `setSystemGestureExclusionRects` while
   chrome is shown: 56 + 48 = 104 dp per edge, inside Android's 200 dp cap. The
   bottom mandatory home zone is never excluded and no hit rect enters it.
2. **Seek drag**: touch-down in the seek hit consumes the gesture; no chrome toggle or
   pager swipe. Holding swaps the strip band for frame thumbnails (VIEW:V16–V18, **UNVERIFIED**).
3. **Strip drag / tap**: horizontal slop scrubs with platform fling; tap selects the
   nearest tile centre. Consumed; never toggles chrome.
4. **Buttons**: 48 hits; sliding off cancels.
5. **Media**: single tap toggles chrome and bars after the double-tap timeout;
   double-tap ×2.31; pinch; horizontal pager; swipe up = Details, swipe down = close (unzoomed).

TalkBack: back → date · time · place (one node) → shown top actions → media
("Photo N of M: title", state shown/hidden) → play/pause → seek (adjustable) →
filmstrip (collection, current selected, previous/next custom actions) →
favourite → edit → share → delete. With chrome hidden only the media node and
system back remain.

## Conflicts for the preflight to rule on

| # | Conflict | Measured / computed fact | Proposed resolution (recommended first) |
|---|---|---|---|
| C1 | Parity §1 30×40 tiles at 2 dp gaps (32 dp pitch) vs Quiet Frame ≥ 44 dp touch | Non-overlapping cells cannot exceed the 32 dp pitch; a 44 dp width would overlap neighbours | **A:** contiguous 32×56 cells (centre 39.5×56), no dead gaps, tap = nearest centre, strip also scrubbable and TalkBack previous/next. **B:** 44 dp pitch (14 dp gaps), breaks parity's 2 dp look. **C:** larger tiles, breaks parity. |
| C2 | Quiet Frame 35% top scrim vs its own 4.5:1 text rule | Ink `#ECEDF0` on 35% black over a white photo = **2.08:1** (T4) | **A:** keep the 35% scrim across the top and deepen to 60% only behind the metadata box (4.90:1). **B:** text shadow only (not measurable as contrast). |
| C3 | Dark-theme accent ring `#7FA8FF` on bright content | 2.35:1 against white (< 3:1 non-text) | 1 dp black keyline outside the 2 dp ring (21:1 vs white); light-theme ring already 5.53:1 |
| C4 | Parity top-left back vs one-handed reach | Back centre y 72.4 dp of 832 (top third) | Keep parity position; swipe-down close and system back are the reachable exits |
| C5 | Parity 24 dp icons, 200 ms fades vs Quiet Frame 22 dp, 250 ms | — | Quiet Frame wins (OWNER_INTENT) |
| C6 | Dark pill on a dark photo | Pill vs black 1.22:1 | Accepted: the icon carries contrast (14.7:1); pill edge is decorative |

## Unknown or unverified (do not read as passing)

- Physical S24+ panel, One UI gesture settings and Samsung OEM offsets: **UNKNOWN**.
- VIEW:V15 (centre ×1.25), VIEW:V16–V18 (seek frame strip): **UNVERIFIED**.
- Text widths use a 0.58 em estimate; the implementation PR must measure real text.
- Rotation 270, split-screen and foldables: not captured.
- Spoken TalkBack, long-duration video, codec-error video, loading-vs-error photo: **NOT_CAPTURED** (as in #609).

## Capture manifest required at implementation review

The production viewer PR (only after a preflight PASS) must attach exact-head
emulator captures and a measurement report under the same schema for: portrait and
rotated landscape × light/dark × bright/dark photo × shown/hidden; video short and
long × playing/paused × seek start/end; 200% font portrait and landscape; missing
place and missing date; unavailable media; gesture navigation portrait and
landscape; and it must report `at_least_44dp=true` for every icon action and the
`video_controls_to_filmstrip_gap_dp ≥ 8` boolean in each video frame.
