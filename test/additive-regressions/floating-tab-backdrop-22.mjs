// #22: a real captured backdrop must be blurred before the pill chrome is drawn.
// This intentionally fails on main, where a translucent Surface has no blur source.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const design = readFileSync('app/src/main/java/nl/atlas/gallery/featuredesign/AtlasGalleryDesign.kt', 'utf8');
const activity = readFileSync('app/src/main/java/nl/atlas/gallery/MainActivity.kt', 'utf8');
const evidence = readFileSync('scripts/ui-evidence.d/12-pill-backdrop.txt', 'utf8');

assert.match(design, /fun Modifier\.atlasCaptureTabBackdrop\(/, 'record the actual grid/page behind the pill');
assert.match(design, /this@drawWithContent\.drawContent\(\)/, 'record the background, not a decorative blur');
assert.match(design, /BlurEffect\(/, 'use a native RenderEffect blur on API 31+');
assert.match(design, /Build\.VERSION\.SDK_INT >= 31/, 'do not try RenderEffect on Android 10/11');
assert.match(design, /drawLayer\(blurredLayer\)/, 'draw blurred backdrop independently of crisp pill content');
assert.match(design, /\.clip\(CircleShape\)/, 'bound blur within the rounded pill');
assert.match(design, /alpha = 0\.78f/, 'inactive tabs need readable contrast on bright thumbnails');
assert.match(activity, /atlasCaptureTabBackdrop\(tabBackdrop\)/, 'capture actual Scaffold body content');
assert.match(activity, /LocalAtlasTabBackdrop provides tabBackdrop/, 'use that captured body in the pill');
for (const mode of ['light', 'dark']) {
  for (const surface of ['photos', 'albums', 'search']) {
    assert.ok(evidence.includes(`pill-22-${surface}-${mode}`), 'missing S24+ image declaration');
  }
}
console.log('Floating tab backdrop source contract verified.');
