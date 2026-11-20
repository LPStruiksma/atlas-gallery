# DESIGN-8M #609 — viewer baseline capture contract

This is a **measurement harness**, not a Quiet Frame redesign or a physical
Galaxy S24+ validation. The hosted workflow uses an API 35 x86_64 `pixel_6`
emulator and `S24DisplayRule` forces the **1080 × 2340 px, 450 dpi**
display setting for each measured test. The test reads back `wm size`,
`wm density`, model, rotation, window bounds, density and navigation mode;
these numbers must be checked in the **runtime JSON**, not assumed from
the profile or the setting command.

## Exact-head evidence location

The `build-apk.yml` **emulator-ui-shard** jobs run the complete Android
instrumentation suite on the PR head after the PR is ready and carries
`next:review`. The two generated reports are uploaded in the **individual**
`ATLAS-android-UI-evidence-shard-0` or `-1` artifacts, under:

- `ui-evidence/viewer-609-photo.json`
- `ui-evidence/viewer-609-video.json`

The reports' `frames[]` entries provide unique
`photo/<fixture>/<theme>/<orientation>/<chrome>` and
`video/short/<theme>/<orientation>/<state>` keys, image path, capture
status, Compose and Android insets (px and dp), density, real display and
decor window extents, device/emulator identity, actual rotation, navigation
mode, display-cutout rectangles and per-control semantic bounds.
`safe_edge_offsets_px` gives distance from the Compose safeDrawing edge;
negative values are recorded, **not suppressed**. The report also computes
`at_least_44dp` for each semantic click area and
`video_controls_to_filmstrip_gap_dp` for overlapping visible video controls.

The `ATLAS-android-UI-evidence` merged artifact contains the PNG captures
(`ui-evidence/*.png`). It does **not** merge the per-shard JSON; use the
individual shard artifact for geometry. Both artifact names and source commit
must be linked in the final #609 and parent #142 evidence receipts. Before an
exact-head green run, no dimensions, hit targets or screenshots are certified.

## Fixture/state matrix

| Fixture | Themes | Orientation | Actual states | Screenshot path |
| --- | --- | --- | --- | --- |
| Invented bright PNG | Light, dark | Portrait | Chrome shown, hidden | `viewer-609-photo-bright-<theme>-portrait-<state>.png` |
| Invented dark PNG | Light, dark | Portrait | Chrome shown, hidden | `viewer-609-photo-dark-<theme>-portrait-<state>.png` |
| Invented bright/dark PNG | Light, dark | Rotated landscape via `UiAutomation.setRotation` | Chrome shown | `viewer-609-photo-<fixture>-<theme>-landscape-shown.png` |
| Unmaterialized synthetic image | Light | Portrait | Current unavailable-media appearance | `viewer-609-photo-unavailable-light-portrait-shown.png` |
| Invented four-second H.264 clip | Light, dark | Portrait | Still, playing, paused, held seek preview | Existing `viewer-video-<theme>-{still,playing,paused,seek}.png` |
| Invented four-second H.264 clip | Light, dark | Portrait | Chrome hidden, semantic seek start/end | `viewer-609-video-<theme>-portrait-hidden.png`, `viewer-609-video-<theme>-seek-{start,end}.png` |
| Invented four-second H.264 clip | Light, dark | Rotated landscape via `UiAutomation.setRotation` | Chrome shown | Existing `viewer-142-video-landscape-cutout-<theme>.png` |
| 30-entry fictional filmstrip | Light, dark | Both | Virtualized long `LazyRow`; two materialized PNGs, remaining absent synthetic URIs | Included in photo frames |

No owner's private photos or Samsung image assets are ever involved.
MediaStore items created by the photo test are invented PNGs and removed in
`finally`. The video fixture is generated in the app cache and deleted by
the existing test. Empty catalogue safe exit is an *assertion*, not a screenshot.

## Unknowns and measurement limits

- **Semantic action bounds are not painted icon/pill geometry.** The rectangle
  is `boundsInWindow` of the tagged semantics node, and `semantic_click_action`
  distinguishes action nodes; native Media3 `AndroidView` touch hit regions
  and underlying pill painting cannot be inferred from it.
- **Cutout support is empirical.** A zero-inset emulator does not prove a
  cutout-safe layout. Inspect per-frame `cutout_supported`, insets and
  rectangles. Landscape must be evidenced by actual rotation and width > height.
- **Baseline comparison only:** the corrected #374 proposal
  `videoControlsBottom <= filmstripTop - 8dp` is reported as a measured
  boolean, not asserted. Similarly 44dp is a diagnostic flag; no existing
  visual mismatch alone fails this baseline.
- **NOT_CAPTURED:** long-duration video, unavailable codec/error/empty-video
  playback, an independently distinguishable loading/error photo state,
  physical-device navigation/gestures, and spoken TalkBack traversal. A
  semantics description is not a recording of spoken TalkBack output.
- **Exact-head verification required:** the code and tables here specify what
  to capture, not evidence that hosted CI actually did so. Review must open
  the generated artifacts and identify missing or failing states, rather than
  checking a declaration as a successful capture.

## Source of truth

- Production geometry: `app/src/main/java/nl/atlas/gallery/GalleryViewer.kt`
  (read-only for this unit).
- Runtime measurement implementation:
  `app/src/androidTest/java/nl/atlas/gallery/Viewer609Measurements.kt`.
- Synthetic photo matrix: `Viewer609PhotoBaselineTest.kt`.
- Synthetic video media and player states: `ViewerVideoChromeJourneyTest.kt`.
- Host emulation and rotation: `S24DisplayRule.kt`,
  `ViewerLightSystemBarsTest.kt`.
- Capture files and artifact upload: `scripts/ui-evidence.d/609-viewer-measurements.txt`,
  `scripts/ci_ui_evidence.sh`, `.github/workflows/build-apk.yml`.
- Parent Quiet Frame preflight work: [viewer #142](https://github.com/LPStruiksma/atlas-gallery/issues/142).
