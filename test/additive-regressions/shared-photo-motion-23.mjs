import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// #23: a transition must use the clicked tile's *measured* bounds, rather than
// a guessed grid index (date-header slots and far scrubs invalidate indexes).
const main = readFileSync(new URL("../../app/src/main/java/nl/atlas/gallery/MainActivity.kt", import.meta.url), "utf8");
const motion = readFileSync(new URL("../../app/src/main/java/nl/atlas/gallery/SharedPhotoTransition.kt", import.meta.url), "utf8");
const grid = readFileSync(new URL("../../app/src/main/java/nl/atlas/gallery/featuregrid/AtlasFeatureGrid.kt", import.meta.url), "utf8");
const timeline = readFileSync(new URL("../../app/src/main/java/nl/atlas/gallery/featuregrid/AtlasTimelineGallery.kt", import.meta.url), "utf8");

test("both plain and dated photo tiles publish actual window bounds only on click", () => {
  for (const source of [grid, timeline]) {
    assert.match(source, /boundsInWindow\(\)/);
    assert.match(source, /SharedPhotoOrigins\.offer\(/);
  }
});

test("viewer motion is bounded to matching URI and access epoch", () => {
  assert.match(motion, /fun take\(uri: String, epoch: Long/);
  assert.match(motion, /it\.media\.uri == uri/);
  assert.match(motion, /it\.epoch == epoch/);
  assert.match(motion, /MAX_AGE_MS/);
  assert.match(main, /SharedPhotoOrigins\.take\(/);
  assert.match(main, /viewerAllowed/);
});

test("return still uses the settled Viewer URI, and never shrinks to a different photo", () => {
  assert.match(main, /onExitSettledUri\s*=\s*\{ lastViewedUri ->/);
  assert.match(main, /pendingViewerReturn\s*=\s*PendingViewerReturn\(/);
  assert.match(main, /lastSettledViewerUri = lastViewedUri/);
  assert.match(main, /request != null && request\.uri == lastSettledViewerUri/);
  assert.match(main, /returnPreparation = MeasuredViewerReturn\(request\)/);
  assert.match(main, /viewerReturnMatchesCurrentRoute\(preparation\.request, visible, returnRoute,/);
  assert.match(main, /measuredReturnOrigin = target/);
  assert.match(main, /returnOrigin = measuredReturnOrigin\?\.takeIf\s*\{\s*returnAuthorized && preparation\?\.measured\?\.bounds == it\.bounds/);
  assert.match(motion, /media\.uri != request\.uri \|\| epoch != request\.accessEpoch/);
  assert.match(motion, /val movingOrigin = if \(exiting\) returnOrigin else origin/);
  assert.match(motion, /sharedPhotoRect\(movingOrigin\.bounds, viewport, progress\.value\)/);
  assert.match(main, /SharedPhotoMotionLayer\(/);
});

test("square, dated and ratio grids measure only the requested return tile", () => {
  const ratio = readFileSync(new URL("../../app/src/main/java/nl/atlas/gallery/featuregrid/AtlasRatioGallery.kt", import.meta.url), "utf8");
  for (const source of [grid, timeline, ratio]) {
    assert.match(source, /\.measureViewerReturn\(media, catalogueEpoch\)/);
  }
  assert.match(motion, /target\.request\.uri != media\.uri \|\| target\.request\.accessEpoch != epoch \|\| media\.isVideo/);
  assert.match(motion, /target\.offer\(media, epoch, coordinates\.boundsInWindow\(\)/);
  assert.match(motion, /onDispose \{ target\.clear\(\) \}/);
  assert.match(main, /withTimeoutOrNull\(1500L\)/);
});

test("disabled system animation scale and stale grants cancel pixels immediately", () => {
  assert.match(motion, /ANIMATOR_DURATION_SCALE/);
  assert.match(motion, /TRANSITION_ANIMATION_SCALE/);
  assert.match(main, /if \(viewerUri != null && !viewerAllowed\)/);
  assert.match(main, /sharedPhotoOrigin = null/);
});

test("below-fit pinch uses the focused photo transform loop and settled return", () => {
  const viewer = readFileSync(new URL("../../app/src/main/java/nl/atlas/gallery/GalleryViewer.kt", import.meta.url), "utf8");
  assert.match(viewer, /viewerMayOwnPinchExit\(pinchOwned, latestCanPinchDismiss\(\)/);
  assert.match(viewer, /viewerPinchExitCommits\(pinchPose, latestCanPinchDismiss\(\)\)/);
  assert.match(viewer, /onPinchDismiss = \{ returnToGallery\(\) \}/);
  assert.match(viewer, /scaleX = zoomPose\.scale \* pinchPose\.scale/);
});

test("a fresh touch inherits a cancelling drag but must earn new dismissal intent", () => {
  const viewer = readFileSync(new URL("../../app/src/main/java/nl/atlas/gallery/GalleryViewer.kt", import.meta.url), "utf8");
  assert.match(viewer, /val dragBase = viewerDownExitRegrabBase\(dragOffset\.floatValue,/);
  assert.match(viewer, /dragOffset\.floatValue = dragBase\s+tracking = dragBase > 0f/);
  assert.match(viewer, /dragOffset\.floatValue = viewerDownExitTravel\(dragBase, travelY\)/);
  assert.match(viewer, /downExitEligible = viewerMayStartDownExit\(\s*travelY, travelX, elapsed, reversedUp, touchSlopPx\)/);
});
