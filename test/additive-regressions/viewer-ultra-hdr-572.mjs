// BENCH-1 (#572): a focused Ultra HDR photo (gain map) switches only the
// viewer window to COLOR_MODE_HDR on API 34+, and every other state restores
// the window's prior colour mode. Never in the manifest, never app-wide.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';

const read = (path) => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8');
const hdrPath = 'app/src/main/java/nl/atlas/gallery/ViewerHdrMode.kt';

test('decision lives in one pure function gated on API 34, gain map and foreground (#572)', () => {
  assert.ok(existsSync(new URL(`../../${hdrPath}`, import.meta.url)), `${hdrPath} missing`);
  const hdr = read(hdrPath);
  assert.match(hdr, /internal fun viewerWindowColorMode\(/);
  assert.match(hdr, /sdkInt >= GAINMAP_MIN_SDK/);
  assert.match(hdr, /ActivityInfo\.COLOR_MODE_HDR/);
  assert.match(hdr, /\.hasGainmap\(\)/);
  assert.match(hdr, /onDispose \{ window\.colorMode = prior \}/);
});

test('viewer feeds the focused photo only, and the page reports its base decode (#572)', () => {
  const viewer = read('app/src/main/java/nl/atlas/gallery/GalleryViewer.kt');
  assert.match(viewer, /ViewerHdrWindowMode\(focusedHasGainmap = /);
  assert.match(viewer, /onGainmapDecoded = \{ hasGainmap ->/);
  assert.match(viewer, /viewerImageHasGainmap\(result\.result\.image\)/);
});

test('no manifest or app-wide HDR (#572)', () => {
  const manifest = read('app/src/main/AndroidManifest.xml');
  assert.doesNotMatch(manifest, /colorMode/);
  assert.doesNotMatch(read('app/src/main/java/nl/atlas/gallery/MainActivity.kt'), /COLOR_MODE_HDR/);
});

test('instrumented proof uses a generated gain-map JPEG, base and tile paths, focus and close (#572)', () => {
  const t = read('app/src/androidTest/java/nl/atlas/gallery/ViewerUltraHdrTest.kt');
  assert.match(t, /\.gainmap = Gainmap\(/);
  assert.match(t, /viewerBaseRequestOrNull\(/);
  assert.match(t, /decodeRegion\(/);
  assert.match(t, /ActivityInfo\.COLOR_MODE_HDR/);
  assert.match(t, /swipeLeft\(\)/);
});
