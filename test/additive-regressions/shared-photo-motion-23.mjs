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
  assert.match(main, /sharedPhotoOrigin\?\.media\?\.uri == lastSettledViewerUri/);
  assert.match(main, /SharedPhotoMotionLayer\(/);
});

test("disabled system animation scale and stale grants cancel pixels immediately", () => {
  assert.match(motion, /ANIMATOR_DURATION_SCALE/);
  assert.match(motion, /TRANSITION_ANIMATION_SCALE/);
  assert.match(main, /if \(viewerUri != null && !viewerAllowed\)/);
  assert.match(main, /sharedPhotoOrigin = null/);
});
