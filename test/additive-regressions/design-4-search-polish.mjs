// DESIGN-4 (#134): static presentation contract; runs with node --test.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const controls = readFileSync('app/src/main/java/nl/atlas/gallery/featuresearch/LocalSearchControls.kt','utf8');
const host = readFileSync('app/src/main/java/nl/atlas/gallery/MainActivity.kt','utf8');
const shortcuts = readFileSync('app/src/main/java/nl/atlas/gallery/PeopleSearchShortcuts.kt','utf8');
test('Search has a compact 56dp pill, accessible row targets and stable kinds', () => {
  assert.match(controls,/heightIn\(min = 56\.dp\)/);
  // #134 brief: fully rounded filled field, × only while there is text.
  assert.match(controls,/TextField\(\s*value = filters\.text,[\s\S]*?shape = CircleShape/);
  assert.match(controls,/trailingIcon = if \(filters\.text\.isNotEmpty\(\)\)/);
  // Suggestion rows are 56 dp in total: the minimum sits outside the padding.
  assert.match(controls,/heightIn\(min = 56\.dp\)\.padding\(horizontal = 16\.dp\)\s*\.testTag\("atlas-search-person-suggestion"\)/);
  assert.match(shortcuts,/heightIn\(min = 56\.dp\)\.padding\(horizontal = 16\.dp\)/);
  assert.match(controls,/SearchMediaKind\.entries/);
  assert.match(controls,/testTag\("atlas-search-field"\)/);
});
test('Search chips stay M3 32dp pills: no added height (closed #381)', () => {
  assert.match(controls,/internal val SearchChipShape: Shape = CircleShape/);
  for (const chip of controls.match(/\b(AssistChip|FilterChip|InputChip)\([\s\S]*?testTag\([^)]*\)/g)) {
    assert.match(chip,/shape = SearchChipShape/, chip.slice(0, 80));
    assert.doesNotMatch(chip,/heightIn|\.height\(|defaultMinSize/, chip.slice(0, 80));
  }
});
test('Search retains Pictures grid, exposes distinct no-results state and IME-safe layout', () => {
  assert.match(host,/AtlasFeatureGrid\([\s\S]*?viewKey = "search"/);
  assert.match(host,/"No results for \\u201C\$it\\u201D"/);
  assert.match(host,/emptyDetail = "Try another word or name\."/);
  // #448: IME-safe without stacking on the scaffold bottom padding.
  assert.match(host,/Modifier\.padding\(top = 12\.dp\)\.atlasImePadding\(\)/);
});
