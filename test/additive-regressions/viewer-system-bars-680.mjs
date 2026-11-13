import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const viewer = readFileSync(new URL('../../app/src/main/java/nl/atlas/gallery/GalleryViewer.kt', import.meta.url), 'utf8');
const details = readFileSync(new URL('../../app/src/main/java/nl/atlas/gallery/ViewerDetailsSheet.kt', import.meta.url), 'utf8');

test('viewer bars stay black across chrome toggles and restore the gallery on exit (#680)', () => {
  assert.match(viewer, /view\.context\.viewerHostActivity\(\)/);
  assert.match(viewer, /is android\.content\.ContextWrapper -> baseContext\.viewerHostActivity\(\)/);
  assert.match(viewer, /DisposableEffect\(window, view\)\s*\{/);
  assert.match(viewer, /window\.navigationBarColor = android\.graphics\.Color\.BLACK/);
  assert.match(viewer, /window\.statusBarColor = android\.graphics\.Color\.BLACK/);
  assert.match(viewer, /window\.isNavigationBarContrastEnforced = false/);
  assert.match(viewer, /controller\.isAppearanceLightNavigationBars = false/);
  assert.match(viewer, /controller\.isAppearanceLightStatusBars = false/);
  assert.match(viewer, /onDispose\s*\{[\s\S]*window\.navigationBarColor = oldNavigationColor/);
  assert.match(viewer, /onDispose\s*\{[\s\S]*window\.isNavigationBarContrastEnforced = oldNavContrast/);
  assert.match(viewer, /LaunchedEffect\(window, view, visible\)/);
});

test('Details content accounts for Android navigation insets before bottom padding (#680)', () => {
  assert.match(details, /windowInsetsPadding\(WindowInsets\.navigationBars\.only\(WindowInsetsSides\.Bottom\)\)/);
});
