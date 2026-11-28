# Reference observations and proposed Lapis adaptations

External references are observations only. They do not make these drawings measured Samsung UI or grant ownership of OS surfaces.

| Reference observation | Source | Proposed Lapis adaptation (not an observed platform fact) |
|---|---|---|
| Android recommends touch targets of at least 48 × 48 dp and describes spacing when a visual element is smaller. | [Android accessibility: touch target size](https://support.google.com/accessibility/android/answer/7101858) | Give every row, sphere and slab action a 48 dp semantic hit region even when the visible sphere is smaller. |
| Android accessibility guidance asks authors to describe UI elements and expose meaningful labels/actions. | [Android Developers: make apps more accessible](https://developer.android.com/guide/topics/ui/accessibility/apps) | Announce ordered pick number, selection count, play state and consequences; retain named buttons for every gesture. |
| Android documents font scaling and recommends scalable pixels for text. | [Android Developers: support different pixel densities](https://developer.android.com/training/multiscreen/screendensities) | Use Funnel roles in sp at implementation time; the 100%/200% notes require wrapping/growing layouts and later device proof. |
| Android exposes a system animator-duration scale and accessibility settings can reduce or remove motion. | [Android Developers: `Settings.Global.ANIMATOR_DURATION_SCALE`](https://developer.android.com/reference/android/provider/Settings.Global#ANIMATOR_DURATION_SCALE) | Lapis Lift lasts 200 ms after deliberate picks at normal scale and switches state immediately when animations are removed. |
| Material guidance describes snackbars as brief feedback that may include an action. | [Material 3 snackbar overview](https://m3.material.io/components/snackbar/overview) | Use a count-specific Undo snackbar after eligible V06/V09/V10 operations; never imply reversal succeeded before the data operation completes. |
| Material guidance distinguishes dialogs as modal content requiring user acknowledgement or a decision. | [Material 3 dialog overview](https://m3.material.io/components/dialogs/overview) | Use confirmation only for consequential destructive/private actions, state the exact count/consequence, and focus the safe action first. |
| Samsung documents One UI as supporting one-handed use by separating viewing and interaction areas. | [Samsung Design: One UI](https://design.samsung.com/global/contents/one-ui/) | Place frequent actions in the annotated lower reach zone, while keeping ATLAS's own Quiet Frame hierarchy rather than copying Samsung component shapes. |
| Android owns IME insets and provides APIs for fitting content around system bars and the keyboard. | [Android Developers: edge-to-edge and insets](https://developer.android.com/develop/ui/views/layout/edge-to-edge) | Treat the drawn 72/488 px insets as explicit assumptions; implementation must query actual insets and keep album name/actions visible above IME. |

## Deliberate departures

- The approved Lapis palette, held sphere, Funnel hierarchy, 8/22/24 dp shapes, and centred slab come from Quiet Frame, not One UI or Material.
- Destructive state is conveyed through exact copy and confirmation here; no unapproved accent is introduced merely to imitate a platform sample.
- OS authentication, keyboard, status/navigation bars, TalkBack UI, Trash policy and notification/dialog internals remain platform-owned. The artwork proposes only ATLAS-owned content around them.
