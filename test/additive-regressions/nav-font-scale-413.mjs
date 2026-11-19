import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const nav = readFileSync('app/src/main/java/nl/atlas/gallery/featuredesign/AtlasGalleryDesign.kt', 'utf8');
const evidence = readFileSync('app/src/androidTest/java/nl/atlas/gallery/NavigationFontScaleEvidenceTest.kt', 'utf8');
const declared = readFileSync('scripts/ui-evidence.d/413-nav-font-scale.txt', 'utf8');
test('tab labels cap effective scale inside the compact floating pill', () => {
  assert.match(nav, /navigationLabelSize\(LocalDensity\.current\.fontScale\)/);
  assert.match(nav, /coerceAtMost\(1\.3f\)/);
  assert.match(nav, /fontSize = labelSize/);
  assert.match(nav, /atlas-nav-label-/);
  // The inherited sp line height is capped with the size (200% render crowded the pill edge).
  assert.match(nav, /lineHeight = labelLineHeight/);
  assert.match(nav, /inheritedLineHeight \* navigationLabelCap\(fontScale\)/);
});
test('S24 light and dark evidence validates all tab labels at 100, 130 and 200 percent', () => {
  assert.match(evidence, /S24DisplayRule\(\)/);
  assert.match(evidence, /1f to "100", 1\.3f to "130", 2f to "200"/);
  assert.match(evidence, /assertContained\("label \$name/);
  assert.match(evidence, /labels\[i\]\.right <= labels\[i \+ 1\]\.left/);
  assert.match(evidence, /labelHeights\.getValue\("200"\) <= labelHeights\.getValue\("130"\) \+ 1f/);
  for (const theme of ['light', 'dark']) for (const scale of ['100', '130', '200']) {
    assert.match(declared, new RegExp(`nav-413-font${scale}-${theme}`));
  }
});
