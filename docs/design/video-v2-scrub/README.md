# VIDEO-V2P #979: proposed video frame-thumbnail seeker (pre-build evidence for #961)

**What this is:** dimensioned, annotated **proposed** overlays, a state and gesture arbitration matrix, a timing and seek diagram, and timestamp quantization limits for the VIDEO-V2 seeker of [#961](https://github.com/LPStruiksma/atlas-gallery/issues/961): hold the centred video tile in the viewer filmstrip, scrub its frames, and release at a stable position. It answers the BLOCKING [design preflight](https://github.com/LPStruiksma/atlas-gallery/issues/961#issuecomment-7075734028) (failures #1, #3, #5, #6 and #8) so a fresh `Type: design-preflight` can judge the design before anyone builds it.

**What this is not:** an implementation, a runtime capture, a physical Galaxy S24+ measurement, or a claim about Samsung behaviour beyond the cited findings. No `app/**` file changes and no `GalleryViewer.kt` edits. All media is synthetic (a generated sun-and-boat scene with a burned-in frame counter); all artwork is clean-room (no Samsung assets).

**Base layer:** the proposed Lapis Atlas viewer target of DESIGN-8T, [`viewer-142-target`](../viewer-142-target/README.md). The generator imports that model, so the measured insets and cutouts and the proposed strip band, video row and slab come from one source. If #142's target changes, run the generator again. Note that `main` still draws the older 30×40 dp strip (`GalleryViewer.kt` `STRIP_TILE_*`). This design assumes the #142 44×56 dp strip lands first, or ships with it.

Regenerate (Python 3, no dependencies):

```bash
python3 docs/design/video-v2-scrub/generate.py
```

## Evidence status: observed, proposed, unknown

Samsung behaviour comes from the **private Samsung parity research archive** (`LPStruiksma/atlas-native`, `docs/research/samsung-rea/**`, read-only through the owner's authorized access on 2026-11-22). That archive is a **static analysis of Samsung Gallery 15.9.00.45**, not a runtime measurement. Behaviour below is paraphrased, with no Samsung code, identifiers or artwork.

| ID | OBSERVED in the archive (static analysis) | How this design uses it |
|---|---|---|
| `VIEW:V16` | Pressing the **centred** video in the filmstrip for **180 ms** expands it into a frame-thumbnail "seeker" | Hold threshold 180 ms (adopted) |
| `VIEW:V16` | The seeker shows **max(3, 7·log10(duration in s))** frame tiles (4 for a motion photo) | Frame count N (adopted; **rounding not observed**, floor proposed) |
| `VIEW:V16` | Frames are taken at even offsets **(d − 100 ms)/n**, the first at the nearest sync frame; frames that come out identical are fetched again at exact time; frames are scaled when extracted and cached under a key that includes count, size and modification | Tile times t_i = i·(d − 100)/N; cache key and duplicate re-fetch adopted |
| `VIEW:V16` | Dragging the strip seeks the video as begin → visual seek → end | Seek session model (adopted) |
| `VIEW:V16` | While playing, the strip position **leads playback by 200 ms** | Follow mode lead (adopted) |
| `VIEW:V17` | Position remembered per item for the viewer session; pause on audio-focus loss and headphone unplug; on pause the current frame becomes the page image; elapsed text redraws only when the second changes | Lifecycle rows; elapsed-label update rule |
| `VIEW:V18` | Double-tap left/right seeks ∓/± 10 s, clamped | Arbitration (canvas, not strip) |
| `VIEW:V15` | Photo filmstrip with enlarged centre item, fling velocity ×1.0 | Fling physics reused; Quiet Frame replaces the enlarged centre with ring + sphere (#142) |

**The two 180 ms values are different things.** The Samsung 180 ms is the **hold threshold** that opens the seeker (`VIEW:V16`). The ATLAS 180 ms in #961/#580 is the **settle** before expensive work. This design uses both and labels each.

**Not in the archive, so PROPOSED here** (the reviewer should treat each as an ATLAS choice): seeker tile size and spacing, how the expansion animates, whether playback pauses during a scrub, fling and snap inside the seeker, end-clamp behaviour, haptic ticks, when the seeker collapses, preview pipeline timings (60 ms / 180 ms), TalkBack path, reduced-motion treatment, error and unavailable visuals. The archive also *recommends* Media3 `CLOSEST_SYNC` while dragging and `EXACT` on release. That is the researchers' recommendation, **not** an observation of Samsung, and is adopted here as a proposal.

**UNKNOWN:** live Samsung timings and visuals, gesture-navigation insets (as in #142 [GESTURE_NAV.md](../viewer-142-target/GESTURE_NAV.md)), rotation 270, foldables and split-screen, real codec frame tables on Lukas's library.

## Proposed seeker

| Rule | Value | Source |
|---|---|---|
| Entry | Finger down on the **centred** video tile and held **180 ms** within touch slop, with the pager and strip idle for ≥ 180 ms (#580 settle). Moving beyond slop first = ordinary strip drag | V16 threshold; arming rule proposed |
| Expansion | The video tile becomes **N contiguous 44×56 dp frame tiles** (one rounded 8 dp object, no inner gaps); neighbours slide outward and keep the 4 dp gap. Animated with the 200 ms Lift; cut when animations are removed | V16 count; geometry proposed (QF v3 tile size and radius) |
| Marks | 2 dp white ring with a 1 dp black keyline around the **whole** expanded video (the current item); the 8 dp `#8C9BFF` sphere stays **fixed at the playhead** (safe-width centre), 4 dp above the tiles. No label, no centre line, no time bubble | QF v3 marks; removal list |
| Time axis | Continuous: playhead time T = (playhead x − span left)/span width · d, clamped to [0, d − 1 frame]. Tiles are a visual index, not snap points | Proposed |
| Drag | 1:1 with the finger across the whole 76 dp band (one drag target, full safe width). Playback pauses on entry and resumes after release if it was playing | Proposed |
| Fling | Platform spline decay ×1.0 inside the span, clamped at both ends without bounce, **no snap** | V15 fling ×1.0 reused; clamp proposed |
| Ends | At 0:00 and at d − 1 frame further drag does nothing. No pager hand-off while expanded | Proposed |
| Haptic | One light tick on expansion, then one when the playhead crosses a frame-tile boundary (≤ 1 per vsync); never per pixel | Parity §0 tick rule applied to frames |
| Video row | Elapsed label shows T and redraws only when the second changes; the seek bar thumb mirrors T; play glyph shows paused | V17 label rule; #142 row |
| Collapse | On page change, chrome hide, viewer exit, lifecycle stop or a tap on a neighbour tile (which also selects that item). While the same video stays current and chrome is shown, the seeker stays expanded and, while playing, follows position + 200 ms | V16 lead; persistence proposed |
| Short video | d < 300 ms: no seeker; the hold does nothing (no tick) | Proposed |

## Overlays (annotated, inspectable SVG)

Every board shows the measured safeDrawing (teal), system bars (blue), cutout (red), the proposed semantic hits (pink dashed), the playhead and the preview (orange), plus an annotation panel with the numbers. The viewer is `#000` in both themes (QF v3), so **light and dark boards differ only in the media**: light boards use bright synthetic frames and dark boards dark frames, which are the real contrast extremes for the ring and sphere.

| Viewport | safeDrawing L/T/R/B dp (MEASURED, three-button) | Boards |
|---|---|---|
| S24+ portrait 1080×2340 px = 384×832 dp, rotation 0, cutout top centre | 0 / 44.44 / 0 / 48 | [light held](svg/portrait-3button--light--held.svg) · [light dragging](svg/portrait-3button--light--dragging.svg) · [dark held](svg/portrait-3button--dark--held.svg) · [dark dragging](svg/portrait-3button--dark--dragging.svg) · [dark unavailable frames](svg/portrait-3button--dark--unavailable.svg) · [dark 1:23:45 at 200% font](svg/portrait-3button--dark--long-font200.svg) · [states](svg/portrait-3button--states.svg) |
| Physically rotated 2340×1080 px = 832×384 dp, rotation 1, cutout left, nav bar right | 44.44 / 24.18 / 48 / 0 | [light held](svg/landscape-3button--light--held.svg) · [light dragging](svg/landscape-3button--light--dragging.svg) · [dark held](svg/landscape-3button--dark--held.svg) · [dark dragging](svg/landscape-3button--dark--dragging.svg) · [dark unavailable](svg/landscape-3button--dark--unavailable.svg) · [dark long 200%](svg/landscape-3button--dark--long-font200.svg) · [states](svg/landscape-3button--states.svg) |
| Narrow landscape 640×360 dp (COMPUTED from the rotated insets) | 44.44 / 24.18 / 48 / 0 | [light held](svg/landscape-narrow-computed--light--held.svg) · [light dragging](svg/landscape-narrow-computed--light--dragging.svg) · [dark held](svg/landscape-narrow-computed--dark--held.svg) · [dark dragging](svg/landscape-narrow-computed--dark--dragging.svg) · [dark unavailable](svg/landscape-narrow-computed--dark--unavailable.svg) · [dark long 200%](svg/landscape-narrow-computed--dark--long-font200.svg) · [states](svg/landscape-narrow-computed--states.svg) |

PNG renders of every SVG are in [`png/`](png/) (headless Chromium at 2 px per dp, quantized to 128 colours to keep the repository small; the SVGs are the source). Geometry checks for all 18 boards are in [tables.md G1](tables.md): the drag target is ≥ 48 dp in both directions, inside safeDrawing, off the cutout, with no collision with any other hit; the video row sits 12 dp above the band and the band 8 dp above the slab on every board; the gesture-exclusion rectangle is the 76 dp band (under Android's 200 dp per-edge cap). The mandatory bottom gesture zone is never excluded.

## State and gesture arbitration matrix

Precedence, highest first. "Owner" is who consumes the pointer stream from its first down until up or cancel; a stream never changes owner except where a row says so.

| # | Situation | Owner and outcome |
|---|---|---|
| 1 | System edge or mandatory gesture zone (back swipe, home bar) | System. The band requests exclusion only while chrome is shown; the mandatory zone is never excluded. A back swipe during a scrub arrives as ACTION_CANCEL → row 14 |
| 2 | TalkBack exploration on | Accessibility path (see below); the hold gesture is never required |
| 3 | Finger down on the band, centred video tile, held 180 ms within slop, pager and strip idle ≥ 180 ms | **Seeker** opens. Second pointers anywhere are ignored until up |
| 4 | Finger down on the band and moved past slop before 180 ms | Ordinary strip scrub (photo rules, #142); the seeker never opens on that stream |
| 5 | Finger down on the band while a fling or the 260 ms tap ease is running | Stops the motion (V14 rule); a fresh 180 ms hold is needed to open the seeker |
| 6 | Seeker open: horizontal drag / fling on the band | Seek (T moves); no pager, no chrome toggle, no zoom |
| 7 | Seeker open: finger down during a seeker fling | Stops the span where it is; that point becomes the drag origin; no seek committed until release |
| 8 | Seeker open: tap on a neighbour tile outside the span | Selects that item (48×76 cell), collapses the seeker, keeps the video's position per item (V17) |
| 9 | Video canvas hold while playing (#580 2× hold) | Canvas. Never opens the seeker; the strip hold never triggers 2×. If 2× is active, a finger on the strip is a second pointer and is ignored |
| 10 | Video canvas double-tap (#580 brief: left 35% −10 s, right 35% +10 s, centre zoom) | Canvas; V18 step. If the seeker is open it stays open and its span jumps to the new T without animation |
| 11 | Canvas horizontal swipe (pager) | Pager. Collapses the seeker after the page settles; the old video's position is remembered |
| 12 | Canvas pinch / pan while zoomed | Canvas zoom; the seeker stays as it is and does not move |
| 13 | Single tap on canvas | Toggles chrome after the double-tap timeout; hiding chrome collapses the seeker (position kept) |
| 14 | ACTION_CANCEL, lifecycle pause/stop, audio-focus loss or headphone unplug during a scrub | Commit the **last previewed T** (never revert, never jump), stay paused, collapse. V17 pause rules apply |
| 15 | Orientation change while open | Seeker state (open, T, was-playing) is saved; it reopens at the same T in the new geometry with no animation |
| 16 | Frame tile not yet extracted or extraction failed | Pending: video poster under a 45% black veil; failed: mist `#14152A` tile, no glyph or text. Seeking still works through the player |
| 17 | Decoder/player error before the hold | Hold does not open the seeker; the page shows #142's error state; strip acts as a photo strip |
| 18 | Decoder error mid-scrub | Main view freezes on the last good image; the seeker collapses on release; #142 error state; position kept |
| 19 | Rapid navigation (pager or strip moving, or < 180 ms since rest) | Seeker cannot arm; no player work starts for pass-through videos (#580 settle rule) |
| 20 | Reduced motion (remove animations) | Expansion, collapse and preview cross-fades cut instantly. Drag and fling stay, because they are direct manipulation; seek itself is never removed |
| 21 | 200% font | No text in the strip. Only the video row reflows (#142 F3, labels above the bar); band geometry is unchanged ([long 200% boards](svg/portrait-3button--dark--long-font200.svg)) |

The per-state visuals for 19 of these rows are on each viewport's states board, for example [portrait states](svg/portrait-3button--states.svg).

## Timing and seek pipeline

![timing](png/timing.png)

Source: [svg/timing.svg](svg/timing.svg). Rules:

1. **Moving** (finger or fling): every vsync maps the span offset to T. The main view shows the **nearest cached frame tile** scaled up (low-res, at most half a tile interval away in time). The player receives at most one `CLOSEST_SYNC` seek in flight; a newer target replaces the queued one. No full-screen recomposition: T is read only by the overlay image, the seek-bar thumb and the elapsed label.
2. **60 ms still:** the main view switches to the decoder frame from a `CLOSEST_SYNC` seek to T. It is sharp, but it is the nearest **sync** frame, so it can be up to half a GOP away from T (for example ±0.5 s for a 1 s GOP). The elapsed label keeps showing T.
3. **180 ms still:** an `EXACT` seek to T decodes the frame at T. Expensive work (audio preparation, neighbour prefetch) is allowed only from here.
4. **Cancellation:** any new movement cancels the pending 60 ms and 180 ms timers. A new seek supersedes an in-flight one. Collapsing, a page change or leaving the viewer cancels the frame-extraction jobs and evicts their pending keys.
5. **Release invariance:** at ACTION_UP (velocity below the minimum fling) the strip offset x_r, T_r = clamp(map(x_r), 0, d − 1 frame) and the elapsed label are frozen. Commit = `EXACT` seek to T_r, then resume if it was playing. Nothing in the strip moves on release; only the preview image may sharpen to the exact frame at T_r. A fling ends with the same rule at its rest offset.
6. **TalkBack:** one polite announcement of the position, for example "0:19 of 0:30, paused", 180 ms after the playhead comes to rest. No per-frame or per-tick speech.

## Timestamp quantization and decoder limits

Full table: [tables.md Q1](tables.md). Drag resolution is span px ÷ duration on the 2.8125 px/dp target.

| Duration | N | Span | ms per px | Practical limit |
|---|---|---|---|---|
| 1 s | 3 | 132 dp | 2.7 | Decoder frames (one px < one 60 fps frame) |
| 4 s | 4 | 176 dp | 8.1 | Decoder frames |
| 30 s | 10 | 440 dp | 24.2 | Both (between one 60 fps and one 30 fps frame per px) |
| 2 min | 14 | 616 dp | 69.3 | Strip pixels (one px skips ~2 frames at 30 fps) |
| 1:23:45 | 25 | 1100 dp | 1,624 | Strip pixels (one px ≈ 1.6 s) |

What the design promises, and what it does not:

- **Frame-accurate** means the decoded frame whose presentation timestamp is the one Media3 `EXACT` selects for T_r. It does not mean any requested millisecond: the result can be off by up to one frame interval (33 ms at 30 fps, 17 ms at 60 fps, variable for VFR video). Dev must measure this in the emulator journey (below) rather than assume it.
- During movement and the 60 ms preview, accuracy is limited to the frame-tile interval and the GOP respectively, as stated in the timing rules.
- For long videos, drag alone cannot select single frames (one px ≈ 1.6 s at 1:23:45). The seek bar and the TalkBack adjustable step are the finer paths; the design does not claim per-frame drag on long videos.
- Tile times come from `getScaledFrameAtTime` at the tile size (124×158 px); tile 0 is taken at the nearest sync frame (V16). If two tiles decode identical, the second is fetched again at exact time (V16).

## Accessibility

- **TalkBack:** the video tile in the strip exposes a custom action "Seek through frames". It opens the seeker as an adjustable node: swipe up or down steps one frame tile (d/N) and the node reads "Frame 4 of 10, 0:12 of 0:30". The existing seek bar stays adjustable for fine steps. Play state is announced only when it changes. TalkBack order stays as in #142 (video row play → seek → filmstrip).
- **48 dp:** the seeker's single drag target is the full band (≥ 384×76 dp portrait); neighbour tiles keep their 48×76 cells.
- **Reduced motion and 200% font:** see matrix rows 20 and 21.
- **Contrast:** the ring and sphere use #142's keylined marks (≥ 21:1 and ≥ 8.25:1 against white or black frames, per #142 T4).

## Proof Dev still owes (pre-merge, not this unit)

- JVM: frame count and tile times for d = 0, 99, 100, 300, 1 s, 4 s, 30 s, 1:23:45; T clamp at both ends; offset → time mapping; cancellation of the 60/180 ms timers; release invariance (offset, T and label unchanged by the commit); arbitration rows 3–14 as gesture-state tests.
- Emulator: a generated synthetic video with a burned-in frame counter (as drawn here); hold 180 ms → assert N tiles; drag to a target → release → assert the decoded frame's checksum or counter and `currentPosition` within one frame interval of T_r; light/dark exact-head captures in portrait and rotated landscape, including 200% font and an unavailable-frame fixture.

## Choices for the preflight reviewer

| # | Choice | Proposed | Alternative |
|---|---|---|---|
| P1 | Time axis inside the seeker | Continuous, no snap | Snap to frame tiles (coarser, more Samsung-strip-like, but quantizes T to d/N) |
| P2 | Playback during a scrub | Pause, resume after release | Keep playing at the scrubbed position |
| P3 | Seeker after release | Stays open while the same video is current and chrome is shown; follows playback with a 200 ms lead | Collapse on release |
| P4 | Fling under reduced motion | Keep (direct manipulation) | Drop momentum entirely |
| P5 | Frame rounding of 7·log10(d) | Floor | Round to nearest (one more tile for some durations) |
