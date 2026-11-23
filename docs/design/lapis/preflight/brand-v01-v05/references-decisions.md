# BRAND-VE1 reference observations and proposed Lapis decisions

> Sources below document **general Android guidance**. They do not prove Samsung Galaxy S24+ dimensions, One UI layout placement, insets, or installed-app behaviour. All screenshots/frames here are synthetic SVG drawings, not screenshots.

| Documented observation | Source (external documentation) | Proposed ATLAS v3 adaptation — explicitly **not** a platform observation | Outstanding verification |
| --- | --- | --- | --- |
| Android accessibility guidance describes touch targets of at least 48 × 48 dp, including larger accessible targets around small visuals. | [Android accessibility touch target guidance](https://support.google.com/accessibility/android/answer/7101858) | A small held sphere may look under 48 dp but its tappable and semantic region must be ≥48 × 48 dp; source geometry is not proof of a runtime hit target. | Measure actual Compose semantics and hit bounds, including viewer filmstrip. |
| Android encourages meaningful accessibility descriptions and action semantics for UI elements. | [Android Developers: make apps more accessible](https://developer.android.com/guide/topics/ui/accessibility/apps) | Announce "Current photo 3 of 5", ordered selection, same-person choices, progress phase and undo. Swipe-only actions have named accessible alternatives. | Capture TalkBack focus order and state announcements on the app. |
| Scalable pixels and font scaling respond to user text size preferences; screenshots alone cannot establish behaviour at 200%. | [Android Developers: support different pixel densities](https://developer.android.com/training/multiscreen/screendensities) | Funnel Display 38/44 sp titles and Funnel Sans body roles at 100%, with overflow, scroll, wrapping and IME reflow specified at 200%. The SVGs approximate type using fallback fonts. | Render actual 100% / 200% states with bundled Funnel fonts and measured insets. |
| Android exposes an animator duration scale used when adapting motion. | [Android API: Settings.Global.ANIMATOR_DURATION_SCALE](https://developer.android.com/reference/android/provider/Settings.Global#ANIMATOR_DURATION_SCALE) | Lapis Lift is 200 ms only after a user pick; with Remove animations, switch chosen state immediately. Progress is a semantic count, not an SVG animation. | Verify motion-disabled state and duration in Compose. |
| Android provides guidance for gesture navigation, insets and IME window coordination; device values differ across configurations. | [Android Developers: edge-to-edge displays](https://developer.android.com/develop/ui/views/layout/edge-to-edge) | The 76 px status, ~84 px nav and 408 px V04 IME are labelled layout assumptions; do not treat them as S24+ measurements. Place active edit field and Save above actual IME; preserve swipe-up sheet alternatives. | Record insets on target device with keyboard closed/open and system font enlarged. |
| Material guidelines describe sheets and modal destinations, not a required Samsung Gallery screen layout. | [Material Design 3: bottom sheets](https://m3.material.io/components/bottom-sheets/overview) | V02 shows a 22 dp soft secondary sheet with explicit Close and Back; V03 sort alternatives use a similar surface. These shapes and navigation belong to Quiet Frame v3, not an observed One UI convention. | Test swipe-down, Back, TalkBack modal semantics and long content. |

## Decisions taken from the approved ATLAS spec

| v3 source decision | Applied proposed evidence |
| --- | --- |
| [Quiet Frame v3](../../../QUIET_FRAME.md): one lapis token with two theme values | Light `#2639C9` fill and title; dark `#3448E0` fill / `#8C9BFF` title; no second accent. Viewer remains black; slab uses dark fill. |
| Filmstrip current treatment | A small `#8C9BFF` sphere above current tile and 2 dp white ring; ordered selection is numbered lapis sphere with white halo and accessible count. |
| People row design | Rectangular 8 dp real-shape latest-photo strips, name, count, date; no circular UI portrait crops. |
| Progress and motion | Held sphere on an arc next to a plain count; deliberate Lift 200 ms; instant state change with Remove animations. |
| Interaction/shape/spacing | 22 dp secondary surfaces, 24 dp action slab reference, touch target ≥48 dp. Exact hit-testing and layout scaling are implementation obligations, not verifiable from source SVG. |
| Funnel roles | Display 38/44 sp title and Funnel Sans text hierarchy. Text in SVG uses Funnel family names and documented Arial fallback; font metrics require device render. |

## Limitations and follow-up before design release

1. These textual SVGs need *independent rendered visual inspection*. Source syntax and palette literals alone cannot certify contrast at every overlap, clipping, legibility or exact font appearance.
2. Alternate states in the same frame are **concept panels**, not proof that the Android screen can show them simultaneously; 200% layout, keyboard resizing, selection toggles, animation-off and state transitions require independent implementation captures later.
3. All faces and media are synthetic decorative shapes. Do not infer owner photos, gallery counts, EXIF contents or model accuracy from examples; test accessibility labels and parity on synthetic runtime fixtures.
4. The child design-preflight PASS is separate from approval of this shared reference pack. Do not release V01–V05 implementation holds merely because this pack exists.
