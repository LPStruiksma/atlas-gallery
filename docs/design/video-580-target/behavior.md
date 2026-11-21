# VIDEO-1T behavior and state tables

These are proposed contracts for future implementation, not observations of production behavior.

## Playback speed

The **Video speed** Details row has a ≥48dp semantic hit and announces `Video speed, 1×, button` (substitute the selected value). Its choice sheet title is `Video speed`; choices are presented without ranking, exactly: **0.25×, 0.5×, 0.75×, 1×, 1.25×, 1.5×, 2×, 3×, 4×**. TalkBack copy for each is `<value>, radio button, selected/not selected`; focus enters on the selected rate and returns to the Video speed row after selection/dismissal.

| Event | Normal rate | Presentation rate | Exit/restore |
|---|---:|---:|---|
| Choose rate | chosen value | chosen value | persists for current video session |
| Hold center while playing | unchanged | 2× transient | restore normal rate on up/cancel |
| Hold center while paused | unchanged | paused | no transient playback; announce `Video paused` |
| Pager changes item | reset to 1× | 1× | only after pager is settled for 180ms |
| Lifecycle interruption/error | retained for same item | stopped | never strand transient 2× |

Reduced-animation mode removes indicator scale/fade motion but preserves immediate state changes and the full 600ms readable indicator interval. Otherwise the transient may fade, without moving hit targets.

## Gesture arbitration

| Input / region | Owner and priority | Result |
|---|---|---|
| Existing seek hit / filmstrip band | seek or filmstrip wins before canvas | scrub time or media; never ±10s/zoom |
| Horizontal canvas drag | pager after touch slop | change media; commit only after 180ms settled |
| Pinch or moved double-tap | zoom wins | transform media; suppress seek zones |
| Double tap in left 35% | canvas after exclusions | clamp `max(0, t−10s)`; show `Rewind 10 seconds` for 600ms |
| Double tap in right 35% | canvas after exclusions | clamp `min(sourceDuration, t+10s)`; show `Forward 10 seconds` for 600ms |
| Double tap in center 30% | zoom | toggle fitted/zoomed; no seek |
| Press-and-hold center while playing | transient speed | 2× until up/cancel, then restore normal rate |
| Press-and-hold center while paused | paused hold | stay paused; do not silently start playback |

`sourceDuration` means the decoder/media source duration, not a rounded label. All seek results clamp to it. System back/mandatory gesture regions outrank the app; their true gesture-navigation insets remain **UNKNOWN (#627)** and must be measured rather than inferred. Exclusion requests, if later used, require independent platform review.

## Save current frame

The Details action label and exact TalkBack name are **`Save current frame`**, with role `button`. Activation freezes the presentation timestamp immediately; decoding and writing use that frozen timestamp even if playback continues or seeks. The output is a new, non-overwriting MediaStore image (collision-safe display name), never an edit of the video.

| State | UI / exact announcement | Required behavior |
|---|---|---|
| Ready | `Save current frame, button` | ≥48dp row; enabled only when a frame source exists |
| Pending | `Saving current frame` | one operation for frozen timestamp; prevent duplicate activation |
| Success | `Frame saved` | publish pending MediaStore row, expose resulting item, retain source |
| Decoder failure | `Couldn’t decode this frame` | remove pending row/temp data; retry remains possible |
| Permission failure | `Allow photo access to save this frame` | use Android permission route; no custom consent ceremony |
| Write failure | `Couldn’t save frame` | remove pending row/temp data; source is untouched |
| Cancellation/owner destroyed | no success announcement | close descriptors and decoder; remove pending row/temp data |
| Cleanup failure | `Couldn’t finish saving frame` | report failure; schedule scoped cleanup without overwriting media |

No success is announced until the MediaStore pending flag is cleared. A failure never reuses or overwrites an existing item. Decoder color/orientation correctness and permissions need runtime tests; this document is not proof.

## Responsive and accessibility fallback

At 200% font, row height may grow beyond 48dp, labels wrap, values remain adjacent in traversal order, and the sheet scrolls rather than clipping. In 832×384dp rotation, speed choices use three columns; in the #142 640×384dp narrow fallback, Details stays within the known safe boundary and may scroll. Do not shrink type or hits. Focus order is sheet title → Video speed → Save current frame (or, inside the speed sheet, title then choices in the exact listed order). Dismiss returns focus to the invoking row.

Light and dark proposals retain the same geometry. Icons/text require contrast validation against their sheet/scrim, not arbitrary video pixels. Indicators are accessibility announcements, not focusable duplicate controls. Exact spoken output remains unverified until a real TalkBack traversal is captured.
