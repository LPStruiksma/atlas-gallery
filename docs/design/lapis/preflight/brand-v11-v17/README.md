# BRAND-VE3 · V11–V17 synthetic design preflight

This pack is **synthetic design evidence**, not an Android runtime capture, implementation report, or preflight PASS. The SVGs use generated abstract scenes and invented names only. Their system-font fallback is a typography approximation; Funnel Display/Sans rendering remains an implementation evidence requirement.

## Manifest

| Group | Paired 1080 × 2340 light/dark primary frame | Inspectable named states |
|---|---|---|
| V11 AI Edit | [AI Edit canvas](v11-ai-edit.svg) | Provider chooser; result selected; provider error |
| V12 Swap person | [Chooser and task flow](v12-swap-person.svg) | Person chooser; candidate studio; compare results |
| V13 Results | [Visual and face results](v13-search-results.svg) | Visual results; face results; no matches |
| V14 Android entry | [Atlas-owned entry surfaces](v14-android-entry.svg) | Single picker; multi picker; viewer/share/editor handoff |
| V15 Shared states | [Reusable state surfaces](v15-shared-states.svg) | Empty; loading; search: no matches |
| V16 Album detail | [Long album](v16-album-detail.svg) | Long album; album error; permission grant |
| V17 Model install | [Notification handoff](v17-model-install.svg) | Notification progress; notification error; retry action |

Every SVG labels its two full-size frames, 24 dp side and safe-inset assumptions, a 48 dp minimum target envelope, a one-handed action, 100% body/title roles, a 200% wrapping specimen, TalkBack-style control semantics, and directional touch arrows. The panels are supporting state studies; they are not a claim that Android draws the shown notification, editor, sharesheet, permission dialog, or picker chrome.

## Reading and provenance

- Palette, sphere/halo, 8/22/24 dp shapes, short slab, type roles, and motion rules come from [Quiet Frame v3](../../../QUIET_FRAME.md).
- Scope comes from [the V11–V17 coverage audit](../../../LAPIS_COVERAGE.md) and [task brief](TASK_BRIEF.md).
- Photos are SVG-only geometric synthetic scenes created for this pack; there are no external assets, owner media, embedded raster files, or real people.
- Dimensions are explicit SVG user units and are intended for direct browser inspection at 100%; the artboard also includes state panels below the two phone frames.
- See [accessible states matrix](STATES_MATRIX.md) and [references and decisions](REFERENCES_DECISIONS.md).

## Honest evidence gaps

Exact absent frames are: V11 provider-settings keyboard-open and remove-animations; V12 reference-photo sheet, face-choice sheet, comparison at 200% with keyboard, and remove-animations; V13 face-reference keyboard-open, loading, and error; V14 external-view failure, editor-return failure, OS sharesheet, OS editor, and OS picker; V15 permission and generic error; V16 rotated long album, missing-media dialog, and scoped-media partial grant; V17 actual Android notification-template progress/error and notification-permission denial. These drawings also do not prove contrast after Android composition, physical reach, gesture dispatch, focus order, live-region timing, runtime font metrics, or behavior on a device.
