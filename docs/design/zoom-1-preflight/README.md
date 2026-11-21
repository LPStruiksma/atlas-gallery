# ZOOM-1E preflight: one-finger quick-scale on photos (#845, parent #588)

**Status: evidence unit, partly UNVERIFIED.** Written from the cloud seat with no Samsung S24+ in reach. Every Samsung hold/drag result below is **UNKNOWN** until the physical test in section 3 is run. Nothing here changes app code. Storyboard: [`quick-scale-storyboard.svg`](quick-scale-storyboard.svg) (simulated frames, not device captures).

Legend: **CODE** = read from `GalleryViewer.kt` / `ViewerMotionMath.kt` at main `dacd4a0`. **PROPOSAL** = new, untested, tunable on device. **UNKNOWN** = not observed.

## 1. Gesture timeline and state table (S24+ profile: 1080×2340 px, density 3.0, touch slop 8 dp = 24 px)

| State | Entry | Actual numbers | Behaviour | Source |
|---|---|---|---|---|
| Idle | no pointer | - | Pager owns horizontal; zoom loop inert at scale 1 | CODE |
| First tap | down+up #1 | up within 24 px slop | `onTap` is deferred until the 300 ms double-tap timeout | CODE (detectTapGestures); timeout 300 ms is the Android default, verify |
| Second down | down #2 inside timeout | e.g. (552,1212) vs #1 (540,1200): 17 px | Today: wait for up. PROPOSAL: arm quick-scale, no visible change | CODE / PROPOSAL |
| Held within slop | #2 down, travel < 24 px | `|d| < 24 px` | Inert; release here = plain double-tap (160 ms zoom to 2.5, or back to fit) | CODE |
| Quick-scale active | travel ≥ 24 px, `|dy| > 1.4·|dx|` (same ratio as swipe code) | scale = s0·2^(dy/585 px), pivot = tap point | PROPOSAL; down = zoom in, up = out (Maps convention), rate untested |
| Dragging down / up | active | clamp 1.0 to 5.0 (16.0 with tiles, `ViewerTileZoom`) | Continuous; tile path engages as for pinch | PROPOSAL, limits CODE |
| Finger up | #2 up | - | Keep level, no fling, re-clamp pose; pager stays locked while scale > 1.01 | PROPOSAL |
| Short accidental travel | 24 to ~60 px then up | - | PROPOSAL: still activates (no dead band after slop); release keeps the small change. Needs device feel test | PROPOSAL |
| Cancel / takeover | second finger, ACTION_CANCEL, focus or page change | - | Keep current pose, hand over to the pinch loop (`viewerGestureOwned`); a new touch also cancels the 160 ms animation (FLOW-4) | CODE + PROPOSAL |
| Focal clamp / image edge | tap near edge or corner | e.g. (1050,2300) | Pose clamped to `bound = (fitted·s - viewport)/2`; the point under the finger drifts at the edge (blended in, as `viewerDoubleTapFrame`) | CODE |
| Tiles | scale above base | tiled max 16.0 | Same `maxZoom()`; no extra allocation per event is a hard requirement (PERF-14 #221) | CODE |
| 200 % font | system font scale | - | Gesture has no text and draws nothing; chrome text unchanged | PROPOSAL (nothing drawn) |
| Non-gesture fallback | TalkBack / switch access | - | Existing semantics `onClick` toggles chrome; **no zoom action exists today**. PROPOSAL: add custom accessibility actions "Zoom in" / "Zoom out" with the new unit | CODE gap |
| Reduced motion | animations disabled | - | Plain double-tap already cuts to end pose; quick-scale is 1:1 with the finger, so no animation | CODE + PROPOSAL |
| Light/dark, gesture-nav insets | - | viewer ground is black; gesture bar ignored by this gesture | Gesture area is the full photo box; side back-gesture zones (system) win at the left/right edge. Measured gesture-nav numbers are NOT CAPTURED (#627 `GESTURE_NAV.md`) | CODE / UNKNOWN |

Finding to settle in the build unit: the parity spec says double-tap ×2.31 (`VIEW:V20-21`) but `viewerDoubleTapZoom` uses **2.5** (`ViewerMotionMath.kt`). Not a ZOOM-1 change, noted for Strategy.

## 2. Priority and event-consumption matrix (photos only; video out of scope)

Three handlers touch a photo today: (A) outer vertical-drag tracker (`GalleryViewer.kt` ~648, Main pass, **does not check `isConsumed` on moves**; it stops only on extra pointers or zoom > 1.01), (B) pinch/pan loop (~1540), (C) `detectTapGestures` (~1604), plus the pager's own horizontal drag.

| Situation | Owner | Must not also happen |
|---|---|---|
| Single tap, no second down in 300 ms | C: toggle chrome | zoom, details |
| Second tap released inside slop | C: double-tap zoom (unchanged) | chrome toggle, quick-scale |
| Second down held, travel ≥ 24 px, vertical-dominant | **Quick-scale (new, B-family)** | A starting a close/details drag, pager paging, chrome toggle, details open |
| Same, but diagonal/horizontal-dominant (≤ 1.4 ratio) | Unresolved: PROPOSAL quick-scale does not claim; falls to existing paths. Needs device decision (see 4) | silent compromise |
| Scale > 1.01 and one finger | B pan (unchanged) | A (already disabled by `latestZoom > 1.01f`) |
| Two fingers | B pinch (unchanged) | quick-scale |
| Single finger, first down, vertical > slop | A (unchanged) | quick-scale |

Guarantee required of the build: because A ignores consumption, quick-scale must set an explicit shared flag (or run in the Initial pass before A) that A checks on every move and on release; otherwise a vertical second-tap drag also moves the photo/details surface and can close or open Details on release. A JVM state-machine test must assert: second-tap drag never calls `finishViewerDrag`, `settleDetails`, `latestToggleChrome` or the pager. Also verify per Compose version whether a second press that leaves slop still fires the first tap's `onTap`; this is UNKNOWN and would toggle chrome.

Cross-checks: Quiet Frame v3 draws nothing for this gesture (consistent with "no extra chrome"); FLOW-4 #713 160 ms cancellable animation applies only to the released plain double-tap; VIDEO-1 #580 double-tap seek is a different surface (`atlas-viewer-video`, tap-only layer) and stays untouched; DESIGN-8 #142 / #221 also edit `GalleryViewer.kt`, so ownership must be exclusive in the build unit.

## 3. Samsung comparison

| Behaviour | Result | Setup |
|---|---|---|
| Plain double-tap (zoom in/out, point, duration) | Documented in parity `VIEW:V20-21` (private archive); **not re-observed here: UNKNOWN for this unit** | - |
| Double-tap then hold and drag, up and down | **UNKNOWN. Not attempted.** No archive-silence inference | needs device |
| Arbitration with swipe up (details) / down (close) | **UNKNOWN** | needs device |
| Aves v1.3.0 quick scale (release behaviour, sensitivity) | **UNKNOWN**: only the changelog line exists; behaviour not observed | needs device with Aves |

Physical test script (for whoever holds the S24+; use a synthetic non-personal image, record device, One UI version, Gallery version): (1) double-tap once, release, note scale and pivot; (2) double-tap, keep second touch down, drag down 600 px slowly, note any zoom, then release; (3) same drag up; (4) repeat vertical drags starting 200 ms and 500 ms after a single tap; (5) record whether details/close fire; (6) screen-record at 60 fps. Mark each observed / absent / inconclusive. Do not commit Samsung images or recordings.

## 4. Collision and routing

No collision can be claimed or ruled out: Samsung hold/drag behaviour is unverified. Concrete safe fallback if the device shows Samsung uses second-touch drag for something else: skip quick-scale, or keep it off by default behind a Settings toggle (needs a design decision). Routing: **#588 stays parked and returns to Strategy** for the live S24+ check; if disjoint, #588 can go to independent `Type: design-preflight`.

## 5. Proof status

Storyboard renders (checked in a headless browser). Frames and numbers are **simulated**; the only measured quantities are code constants. No physical-device measurement, no emulator claim, no Android implementation.
