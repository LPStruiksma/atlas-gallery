// DESIGN-4 (#134): static presentation contract; runs with node --test.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const controls = readFileSync('app/src/main/java/nl/atlas/gallery/featuresearch/LocalSearchControls.kt','utf8');
const host = readFileSync('app/src/main/java/nl/atlas/gallery/MainActivity.kt','utf8');
test('Search has a compact 56dp pill, accessible row targets and stable kinds', () => {
  assert.match(controls,/heightIn\(min = 56\.dp\)/);
  assert.match(controls,/RoundedCornerShape\(28\.dp\)/);
  assert.match(controls,/heightIn\(min = 56\.dp\).*testTag\("atlas-search-person-suggestion"\)/s);
  assert.match(controls,/SearchMediaKind\.entries/);
  assert.match(controls,/testTag\("atlas-search-field"\)/);
  // Small-screen Search chips remain finger-sized, without changing their actions.
  for (const tag of ["atlas-search-recent", "atlas-search-by-face", "atlas-search-find-similar", "atlas-search-suggestion", "atlas-search-kind-"]) {
    assert.match(controls, new RegExp("heightIn\\(min = 48\\.dp\\)[\\s\\S]{0,80}testTag\\(\\\""+tag));
  }
});
test('Search retains Pictures grid, exposes distinct no-results state and IME-safe layout', () => {
  assert.match(host,/AtlasFeatureGrid\([\s\S]*?viewKey = "search"/);
  assert.match(host,/emptyText = "No results"/);
  assert.match(host,/Modifier\.padding\(top = 12\.dp\)\.imePadding\(\)/);
});
