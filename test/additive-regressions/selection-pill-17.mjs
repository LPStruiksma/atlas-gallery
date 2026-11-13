import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const selection = readFileSync(
  new URL('../../app/src/main/java/nl/atlas/gallery/featureorganization/SamsungSelectionChrome.kt', import.meta.url),
  'utf8'
);
const journey = readFileSync(
  new URL('../../app/src/androidTest/java/nl/atlas/gallery/SelectionPillJourneyTest.kt', import.meta.url),
  'utf8'
);

test('selection chrome uses floating pill instead of full-width Material NavigationBar', () => {
  assert.doesNotMatch(selection, /\bNavigationBar(Item)?\s*\(/);
  assert.match(selection, /testTag\("atlas-selection-pill"\)/);
  assert.match(selection, /CircleShape/);
  assert.match(selection, /navigationBarsPadding\(\)/);
  assert.match(selection, /widthIn\(|width\(pillWidth\)/);
});

test('selection actions retain explicit disabled, favourite and More semantics', () => {
  assert.match(selection, /enabled\s*=\s*active/);
  assert.match(selection, /role\s*=\s*Role\.Button/);
  assert.match(selection, /role\s*=\s*Role\.Checkbox/);
  assert.match(selection, /onTrash/);
  assert.match(selection, /onDismissRequest/);
  assert.match(journey, /assertIsNotEnabled/);
  assert.match(journey, /selection-pill-17-\$theme-more\.png/);
});
