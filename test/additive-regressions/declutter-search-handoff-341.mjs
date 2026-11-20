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
assert.match(declutter, /if \(scope == AtlasSearchScope\.LIBRARY\)\s*\{[\s\S]*?if \(pendingCount > 0\)\s*\{[\s\S]*?confirmingLeave = true[\s\S]*?\} else \{\s*searchWholeLibrary\(\)/,
  'only an explicit Whole library choice may leave Declutter, and staged choices ask first (frame D1)');
assert.match(declutter, /val searchWholeLibrary = \{[\s\S]*?onSearchWholeLibrary\(\)\s*\}/,
  'leaving calls the host callback only');
assert.match(declutter, /"Search whole library\?"[\s\S]*?Nothing is moved to Trash\.[\s\S]*?"Search library"[\s\S]*?"Stay"/,
  'D1 dialog names the consequence and offers Stay');
assert.doesNotMatch(declutter.slice(declutter.indexOf('val searchWholeLibrary'), declutter.indexOf('if (confirmingLeave)')),
  /onTrashRequest|session\s*=/, 'leaving must never submit Trash or edit the session');
assert.match(declutter, /suggestionsTool = if \(reviewing\) null else[\s\S]*?"Show staged files"/,
  'frame E: search while swiping offers the staged list and never filters the deck');
assert.match(declutter, /"No staged files yet"[\s\S]*?"No matching staged files"[\s\S]*?Clear the search to see all \$\{pending\.size\}/,
  'S1/S2 copy');
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
const search = readFileSync('app/src/main/java/nl/atlas/gallery/featuresearch/AtlasSearch.kt', 'utf8');
assert.match(search, /ATLAS_SEARCH_SINGLE_ROW_BELOW_DP = 280/, 'frame L threshold');
assert.match(search, /WindowInsets\.ime\.getBottom/, 'single-row switch reads the host IME inset');
assert.match(search, /LocalWindowInfo\.current\.containerSize\.height/, 'single-row switch reads the host window height, so resized windows count');
assert.match(search, /queryField\(Modifier\.width\(240\.dp\)\)\s*scopeChips\(\)\s*closeButton\(\)/,
  'frame L order: field, This screen, Whole library, Close');
console.log('FIND-5 scoped Declutter search handoff contract passed');
