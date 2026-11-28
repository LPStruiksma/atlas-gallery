import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const nav = readFileSync('app/src/main/java/nl/atlas/gallery/featuredesign/AtlasGalleryDesign.kt', 'utf8');
const evidence = readFileSync('app/src/androidTest/java/nl/atlas/gallery/NavigationFontScaleEvidenceTest.kt', 'utf8');
const declared = readFileSync('scripts/ui-evidence.d/413-nav-font-scale.txt', 'utf8');
// BRAND-L4 (#657) supersedes the four-tab pill: one slab, labels follow the font to 200 %.
test('slab labels follow the font up to 200 percent and stay one line', () => {
  assert.match(nav, /navigationLabelSize\(fontScale\)/);
  assert.match(nav, /coerceAtMost\(2f\)/);
  assert.match(nav, /fontSize = labelSize/);
  assert.match(nav, /atlas-nav-label-/);
  // The sp line height takes the same cap as the size (#413).
  assert.match(nav, /NavigationLineHeight \* navigationLabelCap\(fontScale\)/);
  assert.match(nav, /softWrap = false/);
});
test('S24 light and dark evidence validates the slab at 100, 130 and 200 percent', () => {
  assert.match(evidence, /S24DisplayRule\(\)/);
  assert.match(evidence, /Triple\(1f, "100", AtlasDestination\.PHOTOS\)/);
  assert.match(evidence, /Triple\(1\.3f, "130", AtlasDestination\.PHOTOS\)/);
  assert.match(evidence, /Triple\(2f, "200", AtlasDestination\.PHOTOS\)/);
  for (const tab of ['ALBUMS', 'PEOPLE', 'SEARCH']) {
    assert.match(evidence, new RegExp(`Triple\\(2f, "200", AtlasDestination\\.${tab}\\)`));
  }
  assert.match(evidence, /assertIsSelected\(\) else tab\.assertIsNotSelected\(\)/);
  assert.match(evidence, /assertContained\("label \$name inside its pill/);
  assert.match(evidence, /under 48 dp/);
  assert.match(evidence, /overlap \$where/);
  assert.match(evidence, /slab off-centre/);
  for (const theme of ['light', 'dark']) {
    for (const scale of ['100', '130', '200']) {
      assert.match(declared, new RegExp(`^nav-413-font${scale}-${theme}$`, 'm'));
    }
    for (const tab of ['albums', 'people', 'search']) {
      assert.match(declared, new RegExp(`^nav-413-font200-${tab}-${theme}$`, 'm'));
    }
  }
});
