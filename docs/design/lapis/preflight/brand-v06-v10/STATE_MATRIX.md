# V06–V10 state, accessibility and geometry matrix

**Status:** synthetic design proposal, not live UI. Each [paired frame](README.md#evidence-index) has two separately bounded 1080 × 2340 designs in a 2160 × 2340 SVG. A/B/C are alternative moments in the task; placement in one inspection sheet is a documentary comparison, not a runtime stack. Insets: top/nav 72 px each assumed; V09 keyboard 488 px assumed. SVG px != Android dp or sp; independent render and implementation checks are necessary.

## Shared design contract

| Concern | Proposed contract / later check |
|---|---|
| Identity | Quiet Frame v3 approved light/dark palette, Funnel Display title and Funnel Sans body (font fallback in SVG if unavailable). 8 dp real-shape media, 2 dp intended gaps, 22 dp mist surfaces and 24 dp lapis slab are design tokens, not a verified pixel mapping. No second accent. |
| Text sizes | Titles should implement 38/44 sp, section 18/24 sp and body 15/22 sp. Mock 100%/200% examples show wrapping/growing rows, never a real font-scale measurement. Test actual OEM font at both sizes and with long translations. |
| Touch and reach | All interactive concepts require 48 × 48 dp semantic touch targets. Lower slabs and dialogs place their actions in the one-handed reach region; no gesture-only action. Static SVG control sizes do not prove installed hit bounds. |
| Sphere and motion | Filled lapis numbered sphere, 2 dp white halo on selected item; order is spoken. Lift happens only on a deliberate selection for 200 ms; Remove animations switches to immediate visual state, never to lost functionality. SVG is static and cannot prove animation behavior. |
| TalkBack | Verb-first named actions and selected/playing/private state in accessibility labels. Traversal follows visual reading order; confirm starts at safe Cancel; count announcement after mutation success; gesture has an explicit button alternative. Verify with a device. |
| Geometry | 1080-wide S24+-sized conceptual viewport, assumed status/nav insets; V09 IME begins at y=1780, album Create/Cancel buttons at y=1496–1588 and remain clear. Keyboard is a synthetic placeholder, not copied Android UI. |

## State transitions and visible spatial evidence

| Unit | State A: entry / choice | State B: alternative / confirm | State C: feedback and fallback | Accessibility / edge handling |
|---|---|---|---|---|
| **V06** | [Comparison images](v06-declutter-photo-dna.svg) at y=330–682 show suggested keep vs review-for-Trash; a numbered sphere marks the kept candidate. Separate Keep 12 / Review 26 controls at y=822–914. | Modal at y=1019–1385 asks “Discard 26 photos?” and separates **Cancel** from **Move to Trash**. | Snackbar at y=1495–1633 says 26 moved and visibly offers **Undo**. Lower 100%/200% panels show text wrapping. | Announce suggestion and ordered selection (“Photo 1, suggested keep, 1 of 38”); focus Cancel first. Undo should restore exact prior membership; failed/expired undo must be reported accurately. |
| **V07** | [Settings list](v07-settings-whats-new.svg) has five full rows, People On and Privacy Off switches plus a visible scroll marker. | What's new is its own mist sheet at y=1240–1776 with actual sample release-note lines and a Close button, not a generic text caption. | 200% example grows a hidden-album row and preserves long-list scroll. Release notes absent → plain unavailable message plus close. | Names include On/Off. Keep keyboard/gesture reach, 48 dp row targets and non-clipped summary at 200%; sample copy is not proof of a shipped release. |
| **V08** | [Story viewer](v08-stories.svg) at y=332–1129 draws media, progress 00:18/01:02, Previous/Pause/Next. | Paused viewer at y=1252–1725 separately draws paused timing and Play control. | Media-unavailable/finished panel at y=1761–2017 has a next action; controls remain available without swipe. | “Autumn walks, playing/paused, elapsed 18 seconds”, accessible Previous/Next/Play/Pause; Pause persists through navigation; Remove animations does not remove media controls. |
| **V09** | [Create album](v09-albums.svg) has visible input at y=397–528, three cover images y=614–908 (Cover B chosen with sphere), Hidden switch y=954–1143, and large-type panel. | Focused input has Cancel and Create y=1496–1588 **above** drawn IME y=1780–2268; empty/duplicate validation must render inline and disable Create until valid. | Cover-changed Undo alternative at y=1625–1734; Cancel must close IME without creating. Hidden/private chooser reports visibility consequences, OS owns authentication. | Name field first, cover choices with selected state, hidden switch spoken, actions above actual queried IME, 48 dp targets, no private thumbnails in recents/previews. |
| **V10** | [Ordered multi-select grid](v10-selection.svg) at y=340–1000 shows twelve thumbnails representing 100 selected (1–8, 97–100), all marked with numbered spheres. | Modal at y=1135–1494 asks “Move 100 photos?” with destination, safe Cancel and Move 100. | Success snackbar y=1577–1702 shows **Moved 100 photos · Undo**; separately framed partial failure reports **98 moved, 2 failed** at y=1730–1974. | Announce each ordered pick and count. Preserve selection order on eligible Undo; partial failure must never claim total success, and named Share/Move/Delete controls replace gesture-only access. |

## Explicitly unproven / deferred to implementation

- Hardware density, cutout, system bars, alternate navigation modes, IME height and interaction with gesture navigation, landscape and fold state.
- Actual Funnel font delivery, 200% font reflow, screen-reader traversal/live-region timing, target bounds, contrast over real media and OEM accessibility.
- Destructive operation transactionality, Trash policy, Undo expiry, 100-item partial rollback, hidden/private authentication, cover persistence and story playback.
- Whether 200 ms Lift, Remove animations and the proposed one-hand position behave correctly on device.
- Actual rendered visual quality of these SVGs; source inspection alone is not a rendered or runtime review.
