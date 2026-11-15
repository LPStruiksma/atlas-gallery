import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// FIND-0 fails on the pre-feature source: one shared search surface, with
// scoped default and explicit library escape, must appear on the overview routes.
const source = (p) => readFileSync(p, 'utf8');
const search = source('app/src/main/java/nl/atlas/gallery/featuresearch/AtlasSearch.kt');
const scoped = source('app/src/main/java/nl/atlas/gallery/featuresearch/ScopedSearch.kt');
const main = source('app/src/main/java/nl/atlas/gallery/MainActivity.kt');
const albums = source('app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt');
const people = source('app/src/main/java/nl/atlas/gallery/featurepeopleui/PeopleHomeScreen.kt');
const stories = source('app/src/main/java/nl/atlas/gallery/featurestories/AtlasStoriesScreen.kt');
const trash = source('app/src/main/java/nl/atlas/gallery/DeclutterScreen.kt');
assert.match(search, /fun AtlasSearch\(/);
assert.match(search, /AtlasSearchScope\.THIS_SCREEN/);
assert.match(search, /AtlasSearchScope\.LIBRARY/);
for (const tool of ['textTool', 'faceTool', 'imageTool', 'filtersTool', 'historyTool', 'suggestionsTool']) {
  assert.ok(search.includes(tool), 'extensible tool slot ' + tool);
}
assert.match(scoped, /fun scopedMediaMatches\(/);
assert.match(scoped, /searchWithFacets\(/);
assert.match(scoped, /fun scopedNameMatches\(/);
assert.match(main, /AtlasSearch\(/); // Pictures, album, person, Search destination shell
assert.match(albums, /AtlasSearch\(/); // Albums list
assert.match(people, /AtlasSearch\(/); // People tab
assert.match(stories, /AtlasSearch\(/); // Stories collection
assert.match(trash, /AtlasSearch\(/); // Trash review
console.log('FIND-0 shared-scoped-search contract passed');
