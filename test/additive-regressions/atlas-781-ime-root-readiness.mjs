// #781: preserve real-system IME and viewer first-compose readiness checks.
// These tests are source contracts for Android instrumentation checks; the
// emulator shard remains the authority for runtime and rendered evidence.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const root = new URL('../../app/src/androidTest/java/nl/atlas/gallery/', import.meta.url);
const read = (name) => readFileSync(new URL(name, root), 'utf8');
const declutter = read('DeclutterSearchEvidenceTest.kt');
const viewer = read('ViewerInteractionTest.kt');

test('landscape evidence waits for a stable IME and never skips frame-L control bounds', () => {
  const landscape = declutter.slice(
    declutter.indexOf('class DeclutterSearchLandscapeEvidenceTest'),
    declutter.indexOf('internal fun ComposeTestRule.typeInSearchPanel')
  );
  assert.match(landscape, /typeInSearchPanel\("beach"\)[\s\S]*?awaitWindowSettled\(\)[\s\S]*?awaitThat\("single-row panel after settled landscape IME\/insets"\)/);
  assert.match(landscape, /landscapeImeReport\(automation\)/);
  assert.match(landscape, /TYPE_INPUT_METHOD/);
  assert.match(landscape, /rootWindowInsets\?\.getInsets\(WindowInsets\.Type\.ime\(\)\)/);
  assert.match(landscape, /assertIsDisplayed\(\)[\s\S]*?assertTagsAboveIme\(controls\)/);
  assert.match(landscape, /assertTextContains\("Move 3 to Android Trash/);
  assert.match(landscape, /saveUiEvidence\("declutter-341-L-landscape-keyboard-\$theme\.png"\)/);
});

test('viewer resumes and attaches its pager before the original swipe-up', () => {
  assert.match(viewer, /getActivitiesInStage\(Stage\.RESUMED\)/);
  assert.match(viewer, /isAttachedToWindow && view\.width > 0 && view\.height > 0/);
  assert.match(viewer, /exists\(hasTestTag\("atlas-main-pager"\)\)/);
  const gesture = viewer.slice(
    viewer.indexOf('@Test fun swipeUpOnPhotoOpensDetails()'),
    viewer.indexOf('@Test fun swipeDownOnPhotoReturnsToGallery()')
  );
  assert.match(gesture, /awaitSwipeViewerReady\(\)[\s\S]*?performTouchInput/);
  assert.match(gesture, /swipeUp\(startY = height \* \.75f, endY = height \* \.35f, durationMillis = 250\)/);
  assert.match(gesture, /atlas-viewer-details[\s\S]*?assertIsDisplayed\(\)/);
});
