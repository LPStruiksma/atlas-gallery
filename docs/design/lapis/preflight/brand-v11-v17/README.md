# BRAND-VE3 · V11–V17 synthetic design preflight

This pack is **synthetic proposal, not Android runtime capture, implementation proof or Design: PASS**. All faces, photos and labels are invented geometric examples; no owner media. Arial is a type fallback, not the actual Funnel font.

## Manifest

| Group | Paired 1080 × 2340 light/dark primary concepts | Three named states, each with separate light + dark state artwork |
|---|---|---|
| V11 | [AI Edit canvas](v11-ai-edit.svg) | Provider chooser; Result selected; Provider error |
| V12 | [Swap person studio](v12-swap-person.svg) | Person chooser; Candidate studio; Compare results |
| V13 | [Visual/face results](v13-search-results.svg) | Visual results; Face results; No matches |
| V14 | [Atlas entry/handoff](v14-android-entry.svg) | Single picker; Multi picker; Viewer / share / edit handoff |
| V15 | [Shared states](v15-shared-states.svg) | Empty; Loading; Search: no matches |
| V16 | [Album detail](v16-album-detail.svg) | Long album; Album error; Permission grant |
| V17 | [Model install handoff](v17-model-install.svg) | Notification progress; Notification error; Retry action |

The paired full-size primary frames remain independent 1080 × 2340 design examples. Each of the 21 named state studies now shows **different illustrations and copy**, a separate light/dark preview, an appropriate next action and proposed TalkBack semantics. Miniature state panels are not full-size screenshots or verified touch measurements.

## Reading and provenance

- [Quiet Frame v3](../../../QUIET_FRAME.md) defines the light/dark Lapis palette, 8 dp photo tiles, short action slab, 22 dp secondary surfaces, filled selection sphere with white halo and deliberately triggered 200 ms Lift. See the [coverage audit](../../../LAPIS_COVERAGE.md), [task brief](TASK_BRIEF.md), [state matrix](STATES_MATRIX.md) and [reference decisions](REFERENCES_DECISIONS.md).
- Original primary frames annotate proposed 24 dp sides, 48 dp status top, 72 dp bottom safe inset, 48 dp target intent, 100% and 200% text specimen and lower one-hand action. These annotations are **assumptions**, not measured S24+ viewport results; actual Funnel type metrics and 200% keyboard coexistence are still missing.
- V11 chooser / selected result / unavailable provider have different semantics and recovery. V12 chooser / candidate studio / before-after have different geometry. V13 no matches has no similarity score.
- V14 has *Atlas-owned* single (1 selected), multi (2 selected) and viewer/share/editor handoff studies. None recreates OS Picker, Sharesheet, editor or permission chrome.
- V15 empty / loading-known-count (7,140 of 12,305 *synthetic sample*) / no-match are separate and do not carry stale progress copy. V16 long album / album error / access-needed offer distinct actions. V17 progress (68% example), failure and ready-to-retry **do not conflate progress and error**; those are Atlas handoff concepts, not actual OS notification frames.

## Exact evidence gaps

V11 provider-settings keyboard and remove-animations; V12 reference-photo/face-choice sheets, comparison at actual 200%/IME; V13 face-reference keyboard, loading and error; V14 actual OS Picker, Sharesheet, editor, external-view failure and editor-return failure; V15 permission and generic error; V16 long-album rotation, missing-media dialog and partial-grant actual OS dialog; V17 Android notification template progress/failure and notification-permission denial.

**Remaining checks:** render every state at readable viewport, visually inspect light/dark contrast and clipping, check real Funnel 100%/200% layouts, safe insets, physical 48 dp targets, keyboard, focus/TalkBack order, gestures, one-handed reach, actual platform UI, 200 ms Lift and remove-animations. Source inspection alone does not close these gaps. Hold V11–V17 implementation children until merged evidence and independent pre-build verdict.
