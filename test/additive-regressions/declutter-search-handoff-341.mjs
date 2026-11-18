import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// FIND-5: source-level behavior contract; Android interactions and device insets
// require separate exact-head rendered and hardware evidence.
const root = 'app/src/main/java/nl/atlas/gallery/';
const declutter = readFileSync(root + 'DeclutterScreen.kt', 'utf8');
const host = readFileSync(root + 'MainActivity.kt', 'utf8');

assert.match(declutter, /scopedNamedMatches\(pending, searchText\)\s*\{ it\.name \+ " " \+ it\.album \}/,
  'query must filter only the staged pending list, not the full deck');
assert.match(declutter, /actions = \{\s*AtlasSearch\([\s\S]*?initialScope = AtlasSearchScope\.THIS_SCREEN[\s\S]*?onScopeChange\s*=\s*\{\s*scope\s*->/);
assert.match(declutter, /if \(scope == AtlasSearchScope\.LIBRARY\)\s*\{[\s\S]*?onSearchWholeLibrary\(\)/,
  'only an explicit Whole library choice may leave Declutter');
assert.match(declutter, /onSearchWholeLibrary: \(\) -> Unit/,
  'Declutter needs a dedicated host callback');
assert.match(host, /DeclutterScreen\([\s\S]*?onSearchWholeLibrary = \{[\s\S]*?closeDeclutter\(\)[\s\S]*?tab = 3/,
  'whole-library escape must close Declutter and route to the Search tab');
assert.match(declutter, /onTrashRequest\(pending\)/,
  'search must not replace the staged selection used for explicit Trash approval');
assert.match(declutter, /pending\.size <= maxTrashBatch/,
  '200-item limit must apply to the full staged selection, not filtered matches');
assert.match(declutter, /testTag\("atlas-declutter-verifying"\)/);
assert.match(declutter, /testTag\("atlas-declutter-trash"\)/);
console.log('FIND-5 scoped Declutter search handoff contract passed');
