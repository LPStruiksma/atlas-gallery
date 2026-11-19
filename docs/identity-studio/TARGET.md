# Identity Studio target-face selection (IDS-7)

Unit: [#474](https://github.com/LPStruiksma/atlas-gallery/issues/474), goal [#467](https://github.com/LPStruiksma/atlas-gallery/issues/467). This is the expected contract; implementation and tests are pending.

The selected photo is decoded at bounded resolution with EXIF orientation applied. Use the shipped `OnnxFaceDetector` (YuNet) and map detected boxes and five landmarks back into full-resolution source-photo coordinates. Release detector resources after use. **Coordinate space:** every `TargetFace` returned by `TargetFaceDetection` and matched by `TargetFaceSelector.faceAtTap` is in raw stored pixels (EXIF orientation not applied), the space the identity engine decodes in. Detection runs on the upright frame and maps back with the photo's clockwise rotation (`TargetFaceSelector.uprightToRaw`); callers pass the same rotation to `detect` and `faceAtTap`. No new detector, identity recognition, network service, or Studio screen is added here.

The selector returns `NoFace` for an empty set; `TooSmall(face)` when no candidate meets the engine's minimum usable face size; `Auto(face)` when a single eligible subject clearly dominates; otherwise `Ask(faces ranked)` and let the user choose. Rank by face area, proximity to photo centre, sharpness, and detector confidence. Write explicit score weights, minimum quality thresholds, and the margin required to auto-select rather than guess among comparable people. Do not auto-select a face smaller than the engine's declared limit.

A tap in displayed-photo coordinates is transformed into source-photo coordinates including rotation and image-fit translation/scale. Map to the face containing the tap or the nearest candidate with a 48 dp-equivalent tolerance; ignore taps outside tolerance.

Acceptance on synthetic inputs: one face -> Auto; two similarly strong faces -> Ask; a crowd with an unambiguous dominant subject -> Auto; rotated tap mapping; TooSmall; NoFace. Run `./gradlew :app:testDebugUnitTest --tests 'nl.atlas.gallery.featureidentity.target.*'` in hosted exact-head CI. No local test run is implied by this document.
