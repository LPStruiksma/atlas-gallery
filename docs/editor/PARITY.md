# EDIT-0 — Samsung Gallery / Atlas editor parity ledger

> Status: research baseline for [Goal #629](https://github.com/LPStruiksma/atlas-gallery/issues/629), unit [#630](https://github.com/LPStruiksma/atlas-gallery/issues/630). **Not** a live S24+ observation. One UI, Samsung Gallery, Photo Editor and Studio versions must be written down at canary time before declaring exact parity. Latitude: **fidelity** (functional/layout target) and clean-room implementation only.

## Scope, acceptance, and reading this ledger

The Atlas editor replaces the viewer pencil with **one** photo/video editor; AI Edit and Face Swap live inside its tool bar and return results to the same undo/save system. The old external [Samsung editor handoff](../SAMSUNG_EDITOR_HANDOFF.md) becomes a **More → Open in Samsung editor** fallback. Every functional row gets a tool, control/gesture and known range **or explicitly D**, source, Atlas route and delivery milestone. M1 = core/architecture/shell/AI-tool contract, M2 = geometry, M3 = color/filters, M4 = decoration, M5 = own AI tools, M6 = video. Milestones are **Atlas commitments**, not proof of Samsung behavior.

Rows sourced to S2/S4 describe older or multi-device Samsung documentation, not certified current S24+ UI. Missing range = **needs device observation**; do not invent min/max. Accepted Samsung-specific exclusions are repeated below. This ledger is a planning **superset**: D-only candidates must be verified, removed as non-Samsung (while remaining an Atlas feature if desired), or documented as a release-specific alternative. On every change, add a row rather than silently dropping a control. No Samsung source code, decompiled method, proprietary asset, gallery media or sticker art belongs in Atlas.

## Source key and evidence grading

- **S1 (Samsung, current general help):** [Edit photos or videos on your Galaxy phone or tablet](https://www.samsung.com/us/support/answer/ANS10003229/), sections **Filters**, **Color mix**, **Additional photo and video editing tools** and save/revert instructions. This page covers several Galaxy generations; it does **not** certify an exact S24+ / One UI build.
- **S2 (Samsung, older general help):** [How do I use the Gallery app?](https://www.samsung.com/uk/support/mobile-devices/how-do-i-use-the-gallery-app/), sections **Editing a photo** and **Editing a video**. These contain detailed controls but are **not** a frozen S24+ UI snapshot.
- **S3 (Samsung, One UI 6):** [One UI 6 new features](https://www.samsung.com/us/apps/one-ui/features/), **Photo Editor** and **Studio (Video Editor)** sections; includes editor undo/redo, decoration re-editing, timeline, per-section speed and draft projects.
- **S4 (Samsung, video):** [How to use Samsung Video editor](https://www.samsung.com/uk/support/mobile-devices/how-to-use-samsung-video-editor/), updated 2023; older behavior, not guaranteed on S24+.
- **S5 (Samsung, Galaxy S24):** [How to use Generative photo editing on the Galaxy S24](https://www.samsung.com/uk/support/mobile-devices/how-to-use-generative-photo-editing-on-the-galaxy-s24/); public Galaxy AI workflow, **not** a portable implementation.
- **S6 (Samsung, Object eraser):** [Edit your photos like a pro with Object eraser](https://www.samsung.com/au/members/whats-new/how-to-edit-your-photos-like-a-pro-with-object-eraser/); features/menu vary by software release.
- **R1 (static REA, reference only):** archived [Samsung Gallery findings v2](https://github.com/LPStruiksma/atlas-native/blob/main/docs/research/samsung-rea/report/samsung-gallery-findings-v2.md), **DATA:DI16–DI17**, **VIEW:V19/V29** and **DATA:DI22**; analyzed Gallery 15.9.00.45, not a running photo-editor UI. No decompiled code or assets reproduced here.
- **R2 (static REA, reference only):** archived [declutter deep dive](https://github.com/LPStruiksma/atlas-native/blob/main/docs/research/samsung-rea/report/deep/declutter.md), findings **22–23**, effect provenance and filter catalogue (an implementation-level intensity 0–100 is **not** evidence of an on-screen slider range).
- **D:** **needs device observation** on an S24+ with One UI and Gallery/Photo Editor/Studio versions recorded. A row marked D is a parity hypothesis or committed Atlas requirement, **not** a claim that Samsung currently displays it. Exact ranges, coordinates, tool visibility, modal order, accessibility and HDR/HEIF export behavior remain unverified where D appears.

All Samsung features, order, placements and enabled states depend on installed versions, media type and device. The list below is a **checkable, version-scoped inventory and target**, not a false assertion that a static APK study enumerated the proprietary Photo Editor. The first canary must resolve every D row and add newly discovered tools; no implementation unit may silently count D as observed.

## Photo editing — tool and control matrix

| Tab / area | Tool | Controls, bounds and gestures | Source | Atlas route / ships |
| --- | --- | --- | --- | --- |
| Transform | Crop/freeform | Drag corner/edge bounds; pinch zoom/pan within crop; numerical limits D | S2 (crop drag); D (gesture limits) | M2 |
| Transform | Aspect ratio | Preset ratios + free orientation; exact presets D | S1; S2; D | M2 |
| Transform | Rotate 90° | Tap for quarter-turn; clockwise direction D | S1; S2; D | M2 |
| Transform | Flip | Horizontal mirrored image; vertical flip availability D | S1; S2; D | M2 |
| Transform | Straighten | Angle dial/slider; precise degrees/min/max D | S3; D | M2 |
| Transform | Auto-straighten | Auto suggestion, accept/reject; availability D | D | M2 |
| Transform | Perspective horizontal | Distortion/axis slider; bounds D | S1; S3; D | M2 |
| Transform | Perspective vertical | Distortion/axis slider; bounds D | S1; S3; D | M2 |
| Transform | Perspective/free corners | Four-corner drag; availability D | D | M2 |
| Transform | Lasso/freeform crop | Finger-drawn outline to keep region | S1; S2 | M2 |
| Transform | Lasso/shape crop | Select shaped boundary; shapes/ranges D | S1; D | M2 |
| Transform | Two-finger rotation | Rotate image within crop; pivot/snap behavior D | D | M2 |
| Filters | Preset filter list | Horizontal thumbnail scroll; Samsung looks are proprietary | S1; S2 | M3 Atlas LUT substitutes; Samsung exact catalogue excluded |
| Filters | Filter strength | Slider/dial; displayed min/max and step D (REA engine 0–100 is not UI evidence) | S1; S2; R2; D | M3 |
| Filters | Custom filter from photo | Choose reference photo, create/rename filter; copy visual mapping, not Samsung asset | S1 | M3 |
| Filters | Copy/paste filter and tone | Samsung provenance/re-application suggested by Gallery static analysis; UI menu D | R2; D | M3 Atlas recipe copy/paste |
| Tone | Auto adjust | One-tap automatic tone; comparison and strength D | S2; D | M3 |
| Tone | Brightness | Slider; exact numeric bounds and neutral D | S1; S2; D | M3 |
| Tone | Exposure | Slider; EV units/bounds D | S1; S2; D | M3 |
| Tone | Contrast | Slider; exact bounds D | S1; S2; D | M3 |
| Tone | Highlights | Slider; exact bounds D | S1; D | M3 |
| Tone | Shadows | Slider; exact bounds D | D | M3 |
| Tone | Saturation | Slider; exact bounds D | S2; D | M3 |
| Tone | Hue | Slider; exact bounds D | S2; D | M3 |
| Tone | White balance | Color temperature/tint control configuration/ranges D | S2; D | M3 |
| Tone | Light balance | Balance slider/options D | S1; D | M3 |
| Tone | Temperature | Warm/cool slider; whether separately exposed D | D | M3 |
| Tone | Tint | Green/magenta slider; whether separately exposed D | D | M3 |
| Tone | Sharpness / definition | Potential detail controls; exact Samsung availability D | D | M3; only if canary finds it |
| Tone | Curves | RGB/component curve points; Samsung availability and constraints D | D | M3 Atlas feature |
| Tools | Color mix / hue | Select target color, hue slider; limits D | S1 | M3 |
| Tools | Color mix / saturation | Select target color, saturation slider; limits D | S1 | M3 |
| Tools | Color mix / luminance | Select target color, luminance slider; limits D | S1 | M3 |
| Tools | Color mix eyedropper | Sample source color; Samsung help says at most 3 custom colors | S1 | M3 |
| Tools | Spot color / add color | Greyscale except touched hues; tap or sample to reveal | S1; S2 | M3 |
| Tools | Spot color / remove color | Sample a hue to suppress it | S1; S2 | M3 |
| Tools | Spot color / brush erase | Brush size slider and finger erase; range D | S1; S2; D | M3 |
| Tools | Resize image | If installed Tools menu exposes resize; ratios/options D | D | M2 |
| Tools | Style / artistic look | Older editor lists multiple art styles; Samsung originals excluded | S2; D (S24+ presence) | Exclusion: Samsung proprietary styles |
| Tools | Face effects / retouch | Skin/eye/face controls and limits D | D | M5 optional substitute; Samsung face effects excluded until scoped |
| Tools | Object eraser | Tap object or draw outline, erase, undo/redo | S6 | M5 LaMa erase |
| Tools | Erase shadows | Availability, triggering, strength D | D | M5 |
| Tools | Erase reflections | Availability, triggering, strength D | D | M5 |
| Decorations | Draw / pen | Finger strokes, selectable pens/colors/width; exact presets D | S1; S2; D | M4 |
| Decorations | Highlighter | Translucent strokes, width/colors D | D | M4 |
| Decorations | Eraser | Remove strokes; size/behavior D | S2; D | M4 |
| Decorations | Shapes | Line/shape insert or snapping, handles; Samsung presence D | D | M4 |
| Decorations | Text | Edit text, keyboard; font, size and color controls D | S1; S2; D | M4 |
| Decorations | Text alignment | Left/center/right | S2 | M4 |
| Decorations | Text background/style | Background color and styles; exact catalogue D | S2; S3; D | M4 |
| Decorations | Move/resize/rotate layer | Drag and transform decorations; gesture constraints D | D | M4 |
| Decorations | Emoji | Editable OS emoji layer; picker/render D | D | M4 Atlas emoji |
| Decorations | Stickers | Samsung packs/personal stickers; Atlas user-made and open-license alternatives | S1; S2; S3 | M4 substitute; Samsung packs excluded |
| Decorations | Re-edit decoration after save | Adjust drawings/text/stickers after previous save | S3 | M4 (with M1/M3 recipe/save) |
| AI / Photo assist | Generative move, resize, remove | Tap or draw selection, generate; may require Samsung AI service | S5 | Exclusion: Samsung Galaxy AI; Atlas AI Edit M5 |
| AI / Photo assist | Sketch to image / generative fill | Availability and entry D | D | Exclusion: Samsung Galaxy AI; Atlas AI Edit M5 substitute |
| AI / suggestions | Suggested enhancement / remaster | Availability/processing rules D | D | M5 own models where feasible; Samsung AI excluded |
| AI / cutout | Subject clipping / sticker creation | Select foreground, save as sticker; selection refinement D | S3; D | M5 mask & M4 layer |
| AI / portrait | Portrait blur / background effects | Portrait effect strength and variations D | D | M5 |
| AI / restore | Upscale / face restoration | Not established as Samsung Photo Editor tools; availability D | D | M5 Atlas own tools only |
| AI / central | AI Edit | Atlas existing product tool, NOT a Samsung-native name/control | Goal #629 (Atlas-only) | M1 EDIT-5 contract; M5 tool |
| AI / central | Face Swap / Identity Studio | Atlas existing product tool, NOT a Samsung-native name/control | Goal #629 (Atlas-only) | M1 EDIT-5 contract; M5 tool |


## Video editing — tool and control matrix

**Distinction:** Gallery's single-video Edit and One UI 6 **Studio** project-based editor are distinct Samsung surfaces; the latter's multi-clip tools are included for parity planning, but must **not** be passed off as present in the single-video pencil screen without canary proof.

| Tab / area | Tool | Controls, bounds and gestures | Source | Atlas route / ships |
| --- | --- | --- | --- | --- |
| Video / trim | Trim in/out | Timeline thumbstrip, left/right handle drag, playback check; frame step D | S2; S4 | M6 |
| Video / timeline | Split at playhead | Samsung Studio multi-clip timeline; specific split control D | S3; D | M6 |
| Video / speed | Change clip speed | Older guide: 0.5×/1×/2×; One UI 6: entire clip or selected section; S24+ choices D | S2; S3; D | M6 |
| Video / transform | Rotate | Quarter-turn icon in older help; present UI D | S2; D | M6 |
| Video / transform | Crop/ratio | Bounds, aspect presets; availability D | D | M6 |
| Video / transform | Flip / straighten | Whether exposed for video D | D | M6 if observed |
| Video / looks | Filter presets | Select filter; exact Samsung looks excluded | S2; S4 | M6 Atlas LUT substitutes |
| Video / looks | Filter intensity / adjustments | Slider and tone controls; available parameters and ranges D | S4; D | M6 |
| Video / audio | Mute / source volume | Speaker/volume slider; 0% mute, max D | S1; S2 | M6 |
| Video / audio | Background music | Choose soundtrack, mix with source volume; licence source D | S1; S4 | M6 |
| Video / audio | Audio eraser | Samsung Galaxy AI service, not local editor parity | S1 | Exclusion: Galaxy AI |
| Video / captions | Text / captions | Font, color, alignment, background, drag placement; exact choices D | S2 | M6 |
| Video / stickers | Sticker overlay | Packs or source cutouts, position/timing; Samsung packs excluded | S2; S3 | M6 Atlas own stickers |
| Video / drawing | Freehand / live drawing | Live vs static drawing, pen/erase, undo/redo; S24+ support D | S2; D | M6 |
| Video / clips | Add video/photo clips | Pick media from device and order multiple clips | S2; S3 | M6 |
| Video / clips | Transitions | Older options dissolve/slide/fade; current presets D | S2; D | M6 |
| Video / clips | Timeline layer timing | Place clips, subtitles and stickers, edit lengths | S3 | M6 |
| Video / studio | Save project / reopen | Draft project persistence; share project between Samsung devices excluded | S3 | M6 Atlas project; Samsung cross-device format excluded |
| Video / output | Save / export movie | Output location, overwrite/copy, codec/resolution bitrates D | S2; S4; D | M6 |
| Video / output | Save still frame | S24+ access path / full-resolution support D | D | M6 |
| Video / output | GIF export | S24+ availability, loop/fps/size/clip controls D | D | M6 |
| Video / output | HDR video preservation | Codec/profile/colorspace/hardware support D; no silent SDR downgrade | D | M6 |


## History, save and media-format matrix

| Area | Action / contract | Behavior and unknowns | Source | Atlas route / ships |
| --- | --- | --- | --- | --- |
| Editor shell | Viewer pencil → photo or video editor | Single focused URI, no gallery-wide enumeration | Goal #629; legacy handoff doc | M1 EDIT-4 |
| Editor shell | Top-bar undo | Inverse most recent recipe operation; enabled state D | S2; S3; D | M1 EDIT-1/4 |
| Editor shell | Top-bar redo | Redo after undo; branch invalidation D | S2; S3; D | M1 EDIT-1/4 |
| Editor shell | Press-and-hold compare | Shows unedited original while pressed; Samsung exact trigger/priority D | D | M1 EDIT-4 |
| Editor shell | Pinch zoom/pan | Preview transformation without committed edit; exact caps D | D | M1 EDIT-4 |
| Editor shell | Cancel / Back | Leave or discard provisional edit; confirmation UI D | D | M1 EDIT-4 |
| Editor shell | Revert original | Explicit action restoring original (Samsung's private original store is excluded) | S1; S2; R1 | M1 EDIT-3; Atlas byte-for-byte |
| Editor shell | Save | Commit to single safe save path; Samsung menu confirmations differ by release | S1; R1; D | M1 EDIT-3 |
| Editor shell | Save as copy | Keep original alongside new edited item; exact return/selection D | S1; R1; D | M1 EDIT-3 |
| Media / format | EXIF and location | What Samsung retains across transformations D; Atlas preserves location per current policy | D | M1 EDIT-2/3 |
| Media / format | Ultra HDR still / gainmap | Samsung editing effects on HDR gainmap D; Atlas keep gainmap through geometry/color | R1 (viewer HDR only); D | M1 EDIT-2/3 |
| Media / format | Motion photo / clip | Samsung edited still+clip treatment D; Atlas preserve or clearly disclose clip drop | R1 (VIEW:V19, DATA:DI22); D | M1 EDIT-2/3 |
| Media / format | HEIF / HEIC | Samsung decoder and edited MIME/EXIF/metadata handling D; do not assume JPEG fallback | R1 (decoder is private); D | M1 EDIT-2/3 |
| Media / format | HDR video | Per-codec HDR support and metadata after export D | D | M6 |
| Media / provenance | Edited-filter/tone flags | Gallery may store SEF PE_RE_EDIT_DATA (not portable as an Atlas store) | R2 | Exclusion: Samsung SEF/revert store; Atlas recipe |


### Exact Atlas behavior and critical differences

- The **recipe/undo model** (EDIT-1) records reversible edits and distinguishes temporary preview gestures from committed operations. Undo/redo must cross tool and AI candidate boundaries; a newer edit after undo invalidates the redo branch. Samsung's exact granularity and edge-case UI are **D**.
- Atlas **compare** is a preview press-and-hold of the unedited original; holding never writes pixels. Samsung's UI trigger is **D**. Zoom/crop manipulation and two-finger rotation are required Atlas interactions; Samsung's precise thresholds remain **D**.
- **Save** uses EDIT-3's single path. **Save as copy** makes a new MediaStore item while retaining the original. **Revert** recovers the original **byte-for-byte**, even after an Atlas edit was saved. A Samsung proprietary hidden original under its private storage and cross-app revert/provenance do **not** transfer. **No source photo is changed without explicit Save.**
- **EXIF:** retain orientation/time/location consistent with existing Atlas policy; after a geometric change update orientation coherently, without silently stripping unrelated metadata. **Ultra HDR:** color and geometric changes preserve a valid gainmap; test decoding AND tone-mapped/non-HDR viewing. **HEIF:** preserve/encode where supported; never silently transcode or claim gainmap retention without output inspection. **Motion photos:** preserve still+clip, or explicitly warn/confirm if the clip must be dropped; don't claim the Samsung-specific trailer is standard. **Video HDR:** preserve where device codec supports, otherwise warn and offer a safe choice. All Samsung-specific behavior here remains **D** until verified.
- Galaxy AI feature names vary with software/service/region: Samsung's cloud/privileged implementation is **excluded**, but Atlas's own **AI Edit**, **Face Swap**, LaMa erase, segmentation, cutout and restoration are supported from the central editor without adopting proprietary Samsung models.

## M1 shell wireframe — target dimensions, not a Samsung measurement

**Proposed portrait content viewport:** 360 × 800 **dp** (reference only; *not* an S24+ screenshot or measured bounds). All bounds relative to the window, excluding display cutout effects; final dimensions and RTL/accessibility behavior require independent design review and a canary ruler capture. Layout proportions follow Samsung's public top-preview-tools paradigm; exact order, icons, safe area and spacing are **D**.

```text
┌────────────────────────────────────┐ 0 dp
│ status/safe inset (0–24)           │
├────────────────────────────────────┤ 24
│ Back    Undo   Redo   Compare  Save│ top bar 24–80 (56 dp)
├────────────────────────────────────┤ 80
│                                    │
│       PHOTO / VIDEO PREVIEW        │ image fit; pinch/drag
│       (centered, letterbox)        │ 80–540 (460 dp flexible)
│                                    │
├────────────────────────────────────┤ 540
│ [crop handles / timeline if video] │ contextual UI
│ tool name  [−─●────+]   Done       │ sub-tool controls 540–668
├────────────────────────────────────┤ 668
│ Transform  Filter  Tone  Tools     │ scrollable primary tabs
│ Decorate  AI Edit  Face Swap       │ 668–728 (60 dp)
├────────────────────────────────────┤ 728
│ gesture/system navigation inset    │ 728–800
└────────────────────────────────────┘ 800
```

- **Geometry tokens:** top bar min 56 dp, primary tab touch targets ≥48 dp, lower tool panel adapts between 96–160 dp, 16 dp side inset for labels and controls, ≥24 dp safe window inset **only as an example** (system insets are measured at runtime, never hard-coded to 24 dp). Preview consumes the remaining space; in landscape/IME state the strip collapses or scrolls before compressing preview to zero.
- **Photo** substrip: active tool title, visual picker/slider/handle labels and Done/Cancel affordances. **Video** swaps a part of the lower substrip for playhead, frame strip and trim/split handles. Don't falsely claim Studio and Gallery single-video UI are identical.
- **State matrix to draw in EDIT-4 design gate:** blank/loading; standard 12 MP image; zoom+crop gesture; neutral and moved slider; compare held; undone/redone; save pending/success/failure; HEIF/HDR unsupported; motion-clip warning; video timeline; AI loading/error/result; keyboard/text placement; light/dark; talkback focus and landscape.
- **Tool order to capture on S24+:** primary tabs, every subtool and overflow, labels/icons, top-bar behavior, status/nav insets, gesture timing and accessibility names. The arrangement above is an **Atlas candidate**, not a claim of Samsung's measured pixel-perfect layout.

## Accepted exclusions (record explicitly, never report as shipped)

1. **Samsung Galaxy AI and proprietary Samsung processing** (including Samsung's Generative Edit, AI-specific suggestions, sketch generation and Audio eraser): use independent Atlas models where available; not Samsung API parity.
2. **Samsung filter catalogue/looks, proprietary artistic styles, Samsung sticker packs and AR Emoji**: Atlas LUTs, open-license/user-created stickers and native emoji are alternatives, not copies.
3. **Samsung-private nondestructive original store, SEF re-edit service, cross-app revert and inter-device Studio project exchange**: Atlas maintains **its own** safe recipe and originals; never impersonates Samsung's store.
4. **Samsung-specific private decoders, hardware boost and privileged Gallery integrations** identified in R1: Atlas can match visible outputs where public APIs permit, not proprietary integrations.

## S24+ canary: one batched, later observation request

Use **disposable synthetic test media**, never personal photos: 12 MP JPEG, 12 MP HEIF, Ultra HDR JPEG/gainmap, motion photo (purpose-captured disposable media or licensed fixture), 10 s SDR/HDR video and project with two clips. Record **device model, One UI, Android, Gallery, Photo Editor and Studio versions, resolution/display refresh and export settings**. For each **D** row, capture tab/order, label, existence, enabled/disabled state by file type, slider **neutral/min/max/step**, long press/2-finger pinch/rotate, compare, undo/redraw, overflow and Save/Save copy/Revert result. In addition measure warm/cold opening, frame trace and export with [BUDGETS.md](BUDGETS.md). Save only typed observations, anonymized bounds and timing, not image assets. If recording any screenshot, use a synthetic image, crop out notifications/IDs, and store it only in an approved ephemeral proof channel. A missing control must be marked **not present on tested build**, not universally absent.

### Review sampling checklist

Independent Review should sample **at least 10 different source-backed rows**, including: freeform crop (S2), flip/rotate (S1), custom filter (S1), filter strength (S1), Color mix HSL (S1), text alignment (S2), tool undo/redo (S3), non-destructive revert (R1), object erase (S6), Studio timeline/speed (S3), photo Save as copy (S1) and motion metadata limitation (R1). State which source and section was consulted and whether only **D** was available. **Do not** turn D into PASS for Samsung UI parity without device proof.
