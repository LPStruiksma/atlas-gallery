# VIDEO-1T behavior, copy and state contracts (PROPOSED)

These are proposed contracts for the future #580 implementation, following the Strategy [design brief](https://github.com/LPStruiksma/atlas-gallery/issues/580#issuecomment-7055689019). They are not observations of production behavior. Geometry for every state below is in [tables.md](tables.md) and [geometry.json](geometry.json).

## Homes and exact copy

No new permanent viewer button. The existing top-right Details action opens the existing Details surface (bottom surface, 42% of viewer height, black Quiet Frame ground, vertical scroll). For a video, two rows are added directly below the `Details` header, above the existing rows, so they are visible when the surface opens in portrait:

| Element | Visible text | TalkBack semantics | Role / size |
|---|---|---|---|
| Playback speed row | label `Playback speed`, value = selected rate (e.g. `1×`) | `Playback speed, 1×, button` | button, full row, ≥48dp, opens the Playback speed sheet |
| Save current frame row | `Save current frame` | `Save current frame, button` | button, full row, ≥48dp |
| Playback speed sheet title | `Playback speed` | heading | modal single-choice sheet |
| Rate option | rate value, radio indicator | `<rate>, radio button, selected` / `not selected` | ≥48dp cell |

The choices are exactly, in this order and without ranking or a recommended badge: **0.25×, 0.5×, 0.75×, 1×, 1.25×, 1.5×, 2×, 3×, 4×**. Portrait shows one column; rotated and narrow landscape show three columns read row by row, so visual reading order equals the listed order (verified by `verify.py`). Speed is applied as Media3 PlaybackParameters with pitch fixed at 1.0. If the controller is unavailable, the row is disabled with the reason `Playback speed unavailable for this video`, never a dead tap.

## Named transitions (testable cases)

`normal rate` = the user's chosen rate for the current video; `presentation rate` = the rate actually playing.

| ID | State before | Trigger | Normal rate after | Presentation after | Required result |
|---|---|---|---|---|---|
| T01 | any, 1× | choose a rate in the Playback speed sheet | chosen | chosen | sheet closes; same video, position and playing/paused state; focus returns to the Playback speed row |
| T02 | Playback speed sheet open | dismiss (scrim tap, Back, TalkBack dismiss) | unchanged | unchanged | no change; focus returns to the Playback speed row |
| T03 | playing, unzoomed, chrome shown or hidden | one-finger hold on non-control video canvas | unchanged | 2× | transient `2×` indicator; normal rate is never overwritten |
| T04 | playing 2× hold | release (ACTION_UP) | unchanged | normal rate | indicator fades (or disappears with reduced animation) |
| T05 | playing 2× hold | ACTION_CANCEL | unchanged | normal rate | no other gesture applied |
| T06 | playing 2× hold | seek bar or filmstrip scrub/seek starts | unchanged | normal rate | seek owns the input; hold ends |
| T07 | playing 2× hold | second finger / pinch zoom | unchanged | normal rate | zoom owns the input; no 2× while zoomed |
| T08 | playing 2× hold | pager or filmstrip page change | reset to 1× on the different video | 1× | hold cancelled before the item changes; never applied to the next video |
| T09 | playing 2× hold | lifecycle loss (background, screen off, window focus loss) | unchanged | paused at normal rate | never strand transient 2× |
| T10 | playing 2× hold | Details or a sheet opens (sheet entry) | unchanged | normal rate | hold cancelled first |
| T11 | playing 2× hold | playback ends | unchanged | normal rate | stays ended; does not restart |
| T12 | paused, unzoomed | one-finger hold on video canvas (paused hold) | unchanged | paused | enters selection, never autoplay; announce selection state |
| T13 | playing (2× hold owns the pointer) | user wants selection | unchanged | unchanged | selection is reached from the filmstrip long-press or the existing selection affordance, not from this pointer |
| T14 | any | swipe to a different video (pager settles ≥180ms) | reset to 1× | 1× | new player activates only after the 180ms settle; interrupted pager keeps the old video and its rate |
| T15 | any | player recreation (config change that rebuilds the player, process restore) | reset to 1× | 1× | position restored per existing rules |
| T16 | any | viewer exit and re-entry | reset to 1× | 1× | no persisted speed preference |
| T17 | unzoomed | double tap in left 35% of the safe video content | unchanged | unchanged | seek to `max(0, t − 10 s)`; indicator `−10 s` for 600ms; announce `Rewind 10 seconds` |
| T18 | unzoomed | double tap in right 35% | unchanged | unchanged | seek to `min(sourceDuration, t + 10 s)`; indicator `+10 s` for 600ms; announce `Forward 10 seconds` |
| T19 | unzoomed | double tap in centre 30% | unchanged | unchanged | toggle zoom; no seek |
| T20 | zoomed | double tap anywhere | unchanged | unchanged | unzoom; no seek, no 2× hold while zoomed |
| T21 | near 0 s or near end (short or long video) | double tap ±10 s | unchanged | unchanged | clamp to `[0, sourceDuration]`; at the end, seeking forward stays at the end and does not restart |
| T22 | photo item | double tap anywhere | n/a | n/a | photo double-tap zoom unchanged |
| T23 | chrome shown | single tap not consumed above | unchanged | unchanged | toggle chrome and system bars together; a scrub or seek never toggles chrome |
| T24 | Details open | Save current frame | unchanged | unchanged | capture flow below with the timestamp frozen at the tap |

`sourceDuration` is the decoder/media source duration, not a rounded label.

## Gesture arbitration (highest priority first)

1. System mandatory back edges and insets, TalkBack exploration. True gesture-navigation insets are **UNKNOWN (#627)**: only three-button navigation was measured, so the zones in [tables.md](tables.md) subtract no invented gesture insets and implementation must subtract the measured `systemGestures`/`mandatorySystemGestures` once #627 captures them.
2. Visible 48dp controls, Details surface and sheets, the player seek bar and the filmstrip (scroll, selection, held-thumbnail seek per parity VIEW:V16–V18). Filmstrip-held seek outranks the canvas 2× hold.
3. Pager swipe/fling and pinch/pan outrank tap and hold; a page change needs the 180ms settle before any new hold, seek or capture.
4. Canvas gestures on the transient zones: double tap left 35% / centre 30% / right 35%, and hold anywhere on the canvas while playing (T03–T21). The zones are the safe video canvas minus top chrome, the video row, filmstrip and action slab, with 8dp clearance; with letterboxed video they are intersected with the displayed video rect and the 35/30/35 split applies to that width.
5. Single tap toggles chrome (T23).

Any in-flight gesture is cancelled on item change, drag, pinch, lifecycle loss, ACTION_CANCEL or sheet entry, and is never applied to a different video.

## Save current frame

Activation freezes the presentation timestamp immediately, before asynchronous extraction, so time spent with Details open or playback continuing never moves the capture. Decoding uses the source frame size when the decoder allows and never upscales. The output is a new MediaStore image in the video's relative directory with a collision-safe name; the source video and other files are never overwritten.

| State | Visible / announced text | Required behavior |
|---|---|---|
| Ready | `Save current frame` · `Save current frame, button` | enabled only when a decodable frame source exists |
| Unavailable | `Save current frame` + supporting `Frame capture unavailable for this video` | disabled with the reason (unprepared, DRM, no decoder) |
| Permission needed | `Save current frame` + supporting `Allow photo access to save this frame` | Android permission route, no custom consent screen; shown wrapped at 200% font in the narrow fallback |
| Pending | `Saving current frame` | one operation for the frozen timestamp; duplicate activation ignored |
| Success | `Frame saved` (optional Open action) | pending flag cleared before the announcement; resulting item exposed |
| Decoder failure | `Couldn’t decode this frame` | pending row and temp data removed; retry possible |
| Write failure | `Couldn’t save frame` | pending row removed; viewer stays usable |
| Cancelled / owner destroyed | no success announcement | descriptors and decoder closed, pending row removed, no hidden write |
| Cleanup failure | `Couldn’t finish saving frame` | failure reported; scoped cleanup scheduled without overwriting media |

## 200% font, rotation and narrow fallback

At 200% font the type doubles (Details label 24sp, value 28sp; sheet title 36sp, options 28sp), rows grow beyond 48dp, labels and supporting text wrap, and surfaces scroll rather than clip or shrink type or hits. In rotated and narrow landscape the Details surface is only 161dp / 151dp tall, so at 200% it scrolls; the evidenced offset shows Save current frame fully and the Playback speed row remains reachable by scrolling (see `tables.md` V3). Rows are inset to safeDrawing + 24dp, so nothing sits in the landscape cutout.

## Focus order, theme and reduced animation

TalkBack order: viewer play/seek → Details action → inside Details: `Details` header → Playback speed → Save current frame → existing rows. Inside the speed sheet: title, then the nine choices in listed order; focus enters on the selected rate. Dismiss returns focus to the invoking row. Indicators are announcements, not focusable duplicate controls. Exact spoken output stays unverified until a real TalkBack traversal is captured.

Light and dark keep identical geometry; contrast is checked against the sheet or indicator scrim, not arbitrary video pixels. With reduced animation, indicators appear and disappear without scale or fade but stay readable for the full 600ms, and hit targets never move.
