# References and decisions

Reviewed 2026-11-21. “Observed” below means documented platform guidance was read; it does **not** mean an Atlas build or Samsung device was observed. Quiet Frame v3 remains the visual authority where Atlas owns the surface.

| Source-cited platform convention | Scope and decision for this pack |
|---|---|
| [Android accessibility principles](https://developer.android.com/guide/topics/ui/accessibility/principles) describe meaningful labels, actionable elements, and minimum touch target guidance. | Propose explicit control names, roles/states, non-colour selection, and 48 dp envelopes. Runtime traversal, announcements, and physical size remain unverified. |
| [Android support for different font sizes](https://developer.android.com/training/multiscreen/screendensities#TaskUseWrapContent) advises scalable text and layouts that can expand. | Include a 200% wrapping specimen in each primary frame. Actual Funnel metrics, clipping, keyboard coexistence, and device font scaling remain absent. |
| [Android window insets guidance](https://developer.android.com/develop/ui/compose/system/insets) documents system-bar and IME inset handling. | Label status, bottom-safe, and side assumptions rather than drawing controls under system regions. No claimed S24+ measurement or runtime inset observation. |
| [Android photo picker](https://developer.android.com/training/data-storage/shared/photopicker) defines a system picker contract and single/multiple selection. | V14 studies Atlas-owned request/entry feedback only. Do not recolour or recreate the system picker; exact OS picker frames are intentionally absent. |
| [Android Sharesheet](https://developer.android.com/training/sharing/send) documents launching the system chooser for outbound content. | Show the Atlas share affordance/handoff only. The sharesheet is OS-owned and no synthetic panel is evidence of its appearance. |
| [Android notifications](https://developer.android.com/develop/ui/views/notifications) explains system templates, progress, actions, and notification behavior. | V17 proposes truthful title/progress/error/action wording. Android owns template typography and chrome; actual template and permission-denial frames are absent. |
| [Material accessibility guidance](https://m3.material.io/foundations/accessible-design/overview) treats accessibility as visual, interaction, and semantic work. | Pair text, shape, and spoken state instead of relying on lapis alone. This is a proposed v3 adaptation, not a Material conformance claim. |

## V3 decisions applied

- Use only the approved light/dark ground, mist, ink, secondary, lapis fill/text, and on-brand values; no second brand accent.
- Use a filled held sphere plus 2 dp white halo for selection, a short 24 dp-radius slab for primary actions, 22 dp state surfaces, and 8 dp synthetic photo tiles.
- Use plain, truthful counts and one next step. Do not infer Android behavior, performance, progress, or visual layout from the drawings.
- Represent people and imagery with abstract synthetic scenes. Names and counts are invented examples and contain no owner data.
- The SVG fallback is Arial because no font files are embedded. Funnel verification is explicitly deferred to implementation/runtime evidence.

## Evidence gap caused by source access

The local `TASK_BRIEF.md` identifies issue #809 and contains its bounded contract. The issue body itself could not be retrieved in this environment (GitHub CLI had no authentication; unauthenticated API access returned HTTP 403). No additional issue-only claims are made here.
