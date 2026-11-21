# V06–V10 state and measurement matrix

These are proposed, synthetic pre-build decisions, not app screenshots or implementation verification. Every SVG has two separately bounded `1080 × 2340` panels in a `2160 × 2340` contact sheet. Insets are design assumptions (72 px status and navigation; V09 also assumes a 488 px IME), not device measurements.

## Shared contract

| Concern | Proposed adaptation and inspectable check |
|---|---|
| Type | Funnel Display 38/44 sp title and Funnel Sans elsewhere. At 100% use the shown roles; at 200%, wrap rather than truncate, allow rows/dialogs to grow, and keep the focused control above IME. Contact sheets are spatial studies, not a claim that SVG px equal Android sp. |
| Shape/colour | Only approved light/dark ground, mist, ink, `brand-fill`, `brand-text`, on-brand, and viewer black. Actual-ratio synthetic strips use 8 dp corners/2 dp gaps; secondary surfaces 22 dp; slab 24 dp. Destructive meaning must use text and confirmation, not an invented second brand accent. |
| Targets/reach | Every depicted row, slab action, sphere, and dialog action maps to at least 48 × 48 dp. Primary actions sit in the lower one-hand reach zone; critical confirmation is not gesture-only. |
| Sphere/motion | Selected/ordered items use filled lapis spheres, 2 dp white halo, and spoken number. Deliberate selection uses Lift for 200 ms; system Remove animations makes the same state change immediate. No autonomous SVG animation. |
| TalkBack/gesture | Focus order follows the arrows, visible state is included in the accessible name, and actions have verbs. Swipe/story gestures have equivalent named controls; announcements are polite and do not steal focus. |

## State transitions

| Unit | Entry / focus | Action → state | Confirmation / feedback | Undo and edge handling |
|---|---|---|---|---|
| V06 | Review opens on proposed best item; TalkBack says “Photo 1, suggested keep, selected, 1 of 38.” | Keep/discard changes sphere state; Compare edits exposes provenance text without colour-only coding. | “Discard 26 photos?” modal names count and Trash consequence; focus starts on safe cancel, then destructive action. | On confirm: “26 photos moved to Trash. Undo.” Undo restores membership and prior ordered selection; expiry/offline failure is stated, never a false success. |
| V07 | Settings title, then 48 dp rows in document order. | Rows expand/navigate without replacing the screen title; People/privacy state is text plus control state. | What’s new shows current release content as a 22 dp mist sheet, with close labelled. | Long list preserves scroll position. At 200% rows grow; no clipped summaries. Missing release notes are an explicit unavailable state. |
| V08 | Story title then play/pause; TalkBack says playing or paused and elapsed time. | Tap toggles playing ↔ paused; swipe arrows move story, but Previous/Next controls remain available. | End, empty, and media-unavailable states provide one sentence and one next action. | Pause is immediate and persistent. Back leaves playback stopped. Remove animations cuts visual transitions, not playback controls. |
| V09 | Album picker focuses search/create. “Create album” opens field and IME while actions remain above it. | Valid name enables Create; private/hidden status is written and announced. Cover chooser uses a filled sphere + halo on real-shape media. | Duplicate/empty names show inline text tied to field. Hiding requires a consequence statement; authentication remains OS-owned. | Cancel closes IME without creating. Cover change offers Undo and restores exact prior cover. No private thumbnail appears in recents/previews in the proposal. |
| V10 | Long press selects first photo and announces “1 selected.” | Each deliberate pick lifts 200 ms and gets numbered sphere; the 100th says “100 selected.” Actions remain in 24 dp slab. | Move/delete dialogs name 100 items and destination/consequence. Completion snackbar says exact count. | Undo restores all 100 and their order when operation permits. Partial failure reports succeeded/failed counts and does not claim rollback of items not restored. |

## Uncovered / requires implementation evidence

- Real S24+ density, cutout, gesture/three-button navigation, landscape/folded layouts, keyboard height, and OEM font rendering are not measured here.
- Contrast tokens are specified by Quiet Frame, but composited media overlays and disabled/error semantics still require implementation contrast checks.
- TalkBack traversal, live-region timing, switch semantics, authentication, Trash retention, and undo transactionality require executable UI/device tests.
- These sheets do not prove text reflow at 200%, 48 dp hit geometry, Lift timing, reduced-motion behavior, story playback, persistence, or data safety.
