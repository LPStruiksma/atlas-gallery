# BRAND-VE2 · V06–V10 synthetic pre-build design evidence

> **Proposed visual studies only.** Five distinct paired light/dark SVGs show task-specific controls and alternate state panels. They are not screenshots, a Design PASS, implementation, or device measurements. Review against Quiet Frame v3 is still required.

Unit: [#807](https://github.com/LPStruiksma/atlas-gallery/issues/807) · Goal: [#628](https://github.com/LPStruiksma/atlas-gallery/issues/628) · Repair findings: [PR #818 review](https://github.com/LPStruiksma/atlas-gallery/pull/818#issuecomment-7063209993)

Authority: [Quiet Frame v3](../../../QUIET_FRAME.md) · [Lapis coverage audit](../../../LAPIS_COVERAGE.md). The panels use original, abstract synthetic colour geometry only; no personal photographs or real user data.

## Evidence index

Each SVG is a **2160 × 2340 contact sheet** with a **light 1080 × 2340 panel on the left and dark 1080 × 2340 panel on the right**. States A/B/C and text scaling examples are simultaneously visible *alternatives*, not a claim that dialogs or snackbars coexist in a running app.

| Child | Paired light/dark artifact | Actual spatial state panels and visible controls |
|---|---|---|
| V06 Declutter / Photo DNA | [v06-declutter-photo-dna.svg](v06-declutter-photo-dna.svg) | **A** side-by-side best-kept versus discarded photo comparison, numbered held sphere and Keep/Review controls; **B** 26-photo Trash confirmation with distinct Cancel and Move actions; **C** successful Trash snackbar and Undo; 100%/200% reflow alternatives |
| V07 Settings / What's new | [v07-settings-whats-new.svg](v07-settings-whats-new.svg) | **A** scrollable Settings rows with explicit People and Privacy switch states; **B** separate What's new release-note sheet with Close action and *sample* content; 200% taller-row example |
| V08 Stories | [v08-stories.svg](v08-stories.svg) | **A** playing story viewer with elapsed/progress and Previous/Pause/Next controls; **B** separate paused frame with Play control; end/media-unavailable alternative |
| V09 Albums | [v09-albums.svg](v09-albums.svg) | **A** focused album name, three real-shape synthetic cover options with selected sphere, Hidden switch, 200% field/description, Cancel/Create actions **above** a drawn 488 px synthetic IME; alternate cover-change Undo |
| V10 Selection | [v10-selection.svg](v10-selection.svg) | **A** 12 representative selected media tiles numbered 1–8 and 97–100 to show ordering of 100 picks; **B** 100-item move confirmation; **C** count-specific snackbar/Undo; alternative 98-success/2-failure state and Share/Move/Delete slab |

See the [state and accessibility matrix](STATE_MATRIX.md) for exact per-child focus, large type, navigation, confirmation, undo and failure rules. The [reference-decision sheet](REFERENCES.md) separates observed OS/One UI/Material guidance from ATLAS proposals.

## Reading the geometry

- Coordinate frame: **1080 × 2340 design pixels per theme**, with **72 px assumed status** and **72 px assumed navigation** insets. V09 uses an additional **488 px assumed IME between y=1780 and y=2268**. Its Create and Cancel controls occupy y=1496–1588; neither is underneath the keyboard. These values are deliberate examples, **not S24+ measurements** or a proven px-to-dp scale.
- Only approved Quiet Frame light/dark ground, mist, ink, secondary, lapis fill and text values are used for UI chrome. Synthetic media geometry uses those colours too. The layouts distinguish 8 dp real-shape media, 22 dp secondary surfaces, and the 24 dp intent for the lapis slab; SVG coordinate pixels are *not* asserted to equal dp.
- Filled numbered lapis spheres with white halos mark ordered selection, not arbitrary decoration. Panels identify action-specific copy and count-specific consequences rather than relying solely on colour.
- The panels depict control regions generously, but **≥48 dp touch bounds, 100%/200% text reflow, contrast over media, TalkBack traversal and 200 ms Lift/Remove animations need implementation and device validation**. The static SVGs do not establish functional hit targets or animation timing.
- The mock What's new copy is explicitly labelled **sample**, not a claim about an actual app release.

## Uncovered

Real device fonts/density/insets, TalkBack semantics/focus, OS authentication and keyboard appearance, story playback, 100-item operation correctness, undo expiration and data persistence, error handling, edge-to-edge/fold/landscape and contrast compositing are not proven. Rendered SVG inspection by independent Review is still required; source-level spatial checks alone cannot substitute for an acceptance render. After this evidence is merged, issue #807 requires links on each V06–V10 child and typed-hold release **before** their independent design preflight; no child implementation may skip its own PASS.
