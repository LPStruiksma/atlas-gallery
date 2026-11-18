import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const viewer = readFileSync(new URL('../../app/src/main/java/nl/atlas/gallery/GalleryViewer.kt', import.meta.url), 'utf8');
// #131: scrim colours live in AtlasTokens (raw-colour guard); values are checked there.
const tokens = readFileSync(new URL('../../app/src/main/java/nl/atlas/gallery/featuredesign/AtlasTokens.kt', import.meta.url), 'utf8');

// #142: a structural guard supplements actual emulator image comparisons.
test('viewer chrome has independent top/bottom scrims and cutout-safe horizontal margins', () => {
  assert.match(viewer, /atlas-viewer-top-chrome/);
  assert.match(viewer, /atlas-viewer-bottom-chrome/);
  assert.match(viewer, /Brush\.verticalGradient\(listOf\(AtlasTokens\.Colors\.Viewer\.ChromeScrimTop, Color\.Transparent\)\)/);
  assert.match(viewer, /Brush\.verticalGradient\(listOf\(Color\.Transparent, AtlasTokens\.Colors\.Viewer\.ChromeScrimBottom\)\)/);
  assert.match(tokens, /val ChromeScrimTop = Color\(0xB8000000\)/);
  assert.match(tokens, /val ChromeScrimBottom = Color\(0xCC000000\)/);
  assert.match(viewer, /WindowInsets\.safeDrawing\.only\(\s*WindowInsetsSides\.Top \+ WindowInsetsSides\.Horizontal\)/);
  assert.match(viewer, /WindowInsets\.safeDrawing\.only\(\s*WindowInsetsSides\.Bottom \+ WindowInsetsSides\.Horizontal\)/);
});

test('tap-to-hide also toggles system bars, leaving video controls inside safe area', () => {
  assert.match(viewer, /ViewerSystemBars\(visible = chromeVisible\)/);
  assert.match(viewer, /if \(visible\) controller\.show\(WindowInsetsCompat\.Type\.systemBars\(\)\)/);
  assert.match(viewer, /else controller\.hide\(WindowInsetsCompat\.Type\.systemBars\(\)\)/);
  assert.match(viewer, /onToggleChrome = \{ chromeVisible = !chromeVisible \}/);
  assert.match(viewer, /AnimatedVisibility\(chromeVisible,[\s\S]*?WindowInsets\.safeDrawing\.only\(\s*WindowInsetsSides\.Bottom \+ WindowInsetsSides\.Horizontal\)/);
});
