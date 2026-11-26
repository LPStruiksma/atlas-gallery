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
  assert.match(main, /val request = PendingViewerReturn\(contextItems, uri, returnRoute, publishedGallery\.epoch\)/);
  assert.match(main, /else pendingViewerReturn = request/);
  assert.match(main, /lastSettledViewerUri = lastViewedUri/);
  assert.match(main, /returnPreparation\?\.request\?\.uri == lastSettledViewerUri/);
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
  assert.match(viewer, /viewerPinchExitCommits\(pinchPose, latestCanPinchDismiss\(\),/);
  assert.match(viewer, /onPinchDismiss = \{\s*recordGestureFrame\(pinchExitPose\.value\)\s*returnToGallery\(\)/);
  assert.match(viewer, /scaleX = zoomPose\.scale \* pinchPose\.scale/);
});

test("a fresh touch inherits a cancelling drag but must earn new dismissal intent", () => {
  const viewer = readFileSync(new URL("../../app/src/main/java/nl/atlas/gallery/GalleryViewer.kt", import.meta.url), "utf8");
  assert.match(viewer, /val dragBase = viewerDownExitRegrabBase\(dragOffset\.floatValue,/);
  assert.match(viewer, /dragOffset\.floatValue = dragBase\s+tracking = dragBase > 0f/);
  assert.match(viewer, /dragOffset\.floatValue = viewerDownExitTravel\(dragBase, travelY\)/);
  assert.match(viewer, /downExitEligible = viewerMayStartDownExit\(\s*travelY, travelX, elapsed, reversedUp, touchSlopPx\)/);
});

test("idle settled photo prepositions without committing or freezing viewer input", () => {
  const viewer = readFileSync(new URL("../../app/src/main/java/nl/atlas/gallery/GalleryViewer.kt", import.meta.url), "utf8");
  assert.match(viewer, /pagerState\.settledPage == pagerState\.currentPage/);
  assert.match(viewer, /collectLatest \{ uri ->\s*if \(uri != null\) \{\s*delay\(350L\)/);
  assert.match(viewer, /latestPrepareCallback\?\.invoke\(uri\)/);
  assert.match(main, /onPrepareSettledUri = \{ uri -> prepareViewerReturn\(uri\) \}/);
  assert.match(main, /if \(!returnCommitted\) return@LaunchedEffect/);
  assert.match(main, /if \(currentUri != null && returnCommitted\)/);
  assert.match(main, /existing\.uri == uri &&\s*viewerReturnMatchesCurrentRoute/);
});

test("direct manipulation reveals only a measured settled return and fades chrome separately from the image", () => {
  const viewer = readFileSync(new URL("../../app/src/main/java/nl/atlas/gallery/GalleryViewer.kt", import.meta.url), "utf8");
  assert.match(main, /val revealTargetUri = preparation\?\.measured\?\.media\?\.uri\?\.takeIf/);
  assert.match(main, /allowUnderlay = revealTargetUri != null/);
  assert.match(main, /returnRevealUri = revealTargetUri/);
  assert.match(viewer, /returnRevealUri == items\[pagerState\.settledPage\.coerceIn\(items\.indices\)\]\.uri/);
  assert.match(viewer, /drawBehind \{ drawRect\(Color\.Black\.copy\(alpha = exitBackdropAlpha\(\)\)\) \}/);
  assert.equal((viewer.match(/graphicsLayer \{ alpha = exitBackdropAlpha\(\) \}/g) || []).length, 2);
  assert.match(viewer, /focusedPinchExitPose = if \(focused\) pinchExitPose else null/);
  assert.match(motion, /else if \(!exiting && \(!allowUnderlay \|\| progress\.value < 1f\)\) drawRect\(Color\.Black\)/);
});

test("a regrabbed pinch must earn fresh contraction before committing inherited shrink", () => {
  const viewer = readFileSync(new URL("../../app/src/main/java/nl/atlas/gallery/GalleryViewer.kt", import.meta.url), "utf8");
  assert.match(viewer, /var freshPinchScale = 1f/);
  assert.match(viewer, /inheritedPinchPose = true/);
  assert.match(viewer, /freshPinchScale = viewerFreshPinchScale\(freshPinchScale, gestureZoom\)/);
  assert.match(viewer, /viewerPinchExitCommits\(pinchPose, latestCanPinchDismiss\(\),\s*inheritedPinchPose, freshPinchScale\)/);
});

test("a fresh single pointer can interrupt pinch return without starting a competing parent drag", () => {
  const viewer = readFileSync(new URL("../../app/src/main/java/nl/atlas/gallery/GalleryViewer.kt", import.meta.url), "utf8");
  assert.match(viewer, /val regrabPinch = viewerMayRegrabPinchExit\(pinchPose,/);
  assert.match(viewer, /if \(regrabPinch\) pinchReturn\[0\]\?\.cancel\(\)/);
  assert.match(viewer, /var claimed = zoomPose\.scale > 1\.01f \|\| regrabPinch/);
  assert.match(viewer, /var pinchOwned = regrabPinch/);
  assert.match(viewer, /var inheritedPinchPose = regrabPinch/);
  assert.match(viewer, /if \(pinchExitPose\.value != ViewerPinchExitPose\(\)\) \{\s*cancelViewerDrag\(\)\s*break/);
});


test("non-grid return fades without a stale tile, freezes input, and cuts on revoked access", () => {
  assert.match(main, /if \(readGalleryMediaGrant\(context\) != grants \|\|\s*vm\.publishedInventory\.value\.epoch != viewerAccessEpoch\) \{\s*closeViewer\(\)/);
  assert.match(main, /returnPreparation = null\s+measuredReturnOrigin = null\s+returnCommitted = true\s+viewerExitShared = true/);
  assert.match(main, /exitWithoutUnderlay = returnCommitted && preparation == null/);
  assert.match(main, /if \(currentUri != null && returnCommitted\) \{\s*\/\/ Freeze further viewer interaction/);
  assert.match(motion, /if \(exiting && exitWithoutUnderlay\) drawRect\(Color\.Black\)/);
  assert.match(motion, /val movingOrigin = if \(exiting\) returnOrigin else origin/);
  assert.match(motion, /if \(!exitDisabled\) \{\s*progress\.animateTo\(0f,/);
});

test("gesture return freezes the oriented fitted frame and does not repaint the revealed grid black", () => {
  const viewer = readFileSync(new URL("../../app/src/main/java/nl/atlas/gallery/GalleryViewer.kt", import.meta.url), "utf8");
  assert.match(viewer, /recordGestureFrame\(viewerDownExitPose\(dragOffset\.floatValue,/);
  assert.match(viewer, /focusedReturnSize\?\.value = decodedSize/);
  assert.match(viewer, /latestCommitGestureFrame\?\.invoke\(uri, sharedPhotoGestureFrame\(/);
  assert.match(main, /onCommitGestureFrame = \{ uri, frame ->/);
  assert.match(main, /committedGestureFrame\?\.first == it\.media\.uri/);
  assert.match(motion, /if \(exiting && gestureFrame != null\) Rect\(/);
  assert.match(motion, /else if \(exiting && !allowUnderlay\) drawRect/);
});

test("committed return derives its clip from normalized crop and uniform scale", () => {
  assert.match(motion, /fun sharedPhotoReturnCrop\(/);
  assert.match(motion, /val fullScale = cover \+ \(1f - cover\) \* p/);
  assert.match(motion, /val crop = sharedPhotoRect\(tileCrop, Rect\(0f, 0f, 1f, 1f\), p\)/);
  assert.match(motion, /sharedPhotoReturnCrop\(viewport, movingOrigin.bounds, progress.value\)/);
  assert.match(motion, /val frame = cropFrame\?\.frame \?: sharedPhotoRect/);
  assert.match(motion, /\.clipToBounds\(\)\s+\.alpha\(1f - progress.value\)/);
});

test("FLOW-3 journey captures held, reversed and committed drag in both themes", () => {
  const journey = readFileSync(new URL("../../app/src/androidTest/java/nl/atlas/gallery/GalleryViewerSharedTransitionJourneyTest.kt", import.meta.url), "utf8");
  for (const frame of ["flow3-drag-held", "flow3-drag-reversed", "flow3-drag-commit-mid", "flow3-drag-returned", "flow3-paged-back-mid"]) assert.ok(journey.includes(frame), frame);
  assert.match(journey, /class SharedPhotoLightJourneyTest/);
  assert.match(journey, /class SharedPhotoDarkJourneyTest/);
});

test("FLOW-3 journey targets below-fit pinch reversal and committed return in both themes", () => {
  const journey = readFileSync(new URL("../../app/src/androidTest/java/nl/atlas/gallery/GalleryViewerSharedTransitionJourneyTest.kt", import.meta.url), "utf8");
  for (const frame of ["flow3-pinch-held", "flow3-pinch-reversed", "flow3-pinch-commit-mid", "flow3-pinch-returned"]) assert.ok(journey.includes(frame), frame);
  assert.match(journey, /fun belowFitPinchReversesThenCommitsToMeasuredTile\(/);
  assert.match(journey, /up\(0\); up\(1\)/);
});

test("FLOW-3 offscreen journey proves the actual settled tile identity, not just viewer closure", () => {
  const journey = readFileSync(new URL("../../app/src/androidTest/java/nl/atlas/gallery/GalleryViewerSharedTransitionJourneyTest.kt", import.meta.url), "utf8");
  assert.match(journey, /fun offscreenSettledPhotoReturnsToItsOwnTile\(/);
  assert.match(journey, /onNodeWithTag\(targetTag, useUnmergedTree = true\)\.assertDoesNotExist\(\)/);
  assert.match(journey, /performScrollToIndex\(OFFSCREEN_COUNT - 1\)/);
  assert.match(journey, /onNodeWithTag\(targetTag, useUnmergedTree = true\)\.assertIsDisplayed\(\)/);
  for (const frame of ["flow3-offscreen-before", "flow3-offscreen-back-mid", "flow3-offscreen-returned"]) assert.ok(journey.includes(frame), frame);
});
