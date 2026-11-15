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
const nativePerson = source('app/src/main/java/nl/atlas/gallery/PersonPageRoute.kt');
const personPage = source('app/src/main/java/nl/atlas/gallery/PersonPageScreen.kt');
const hiddenAlbums = source('app/src/main/java/nl/atlas/gallery/featurealbums/HideAlbumsScreen.kt');
assert.match(search, /fun AtlasSearch\(/);
assert.match(search, /AtlasSearchScope\.THIS_SCREEN/);
assert.match(search, /AtlasSearchScope\.LIBRARY/);
assert.match(search, /selectedScope: AtlasSearchScope\?/);
assert.match(search, /val scope = selectedScope \?: localScope/);
for (const tool of ['textTool', 'faceTool', 'imageTool', 'filtersTool', 'historyTool', 'suggestionsTool']) {
  assert.ok(search.includes(tool), 'extensible tool slot ' + tool);
}
assert.match(scoped, /fun scopedMediaMatches\(/);
assert.match(scoped, /searchWithFacets\(/);
assert.match(scoped, /fun scopedNameMatches\(/);
assert.match(main, /AtlasSearch\(/); // Pictures, album, person, Search destination shell
assert.match(main, /selectedScope = overviewScope/);
assert.match(main, /onSearchWholeLibrary = \{ tab = 3 \}/);
assert.match(main, /val scopedActive = overviewQuery\.isNotBlank\(\) &&\s*\(tab == 0 \|\| personFilter != null\)/);
assert.match(main, /if \(searchActive\) key\(CatalogSourceKey\(visible\)\)/);
assert.match(albums, /AtlasSearch\(/); // Albums list
assert.match(albums, /scope == AtlasSearchScope\.LIBRARY\) onSearchWholeLibrary\?\.invoke\(\)/);
assert.match(people, /AtlasSearch\(/); // People tab
assert.match(people, /scope == AtlasSearchScope\.LIBRARY\) onSearchWholeLibrary\?\.invoke\(\)/);
assert.match(stories, /AtlasSearch\(/); // Stories collection
assert.match(trash, /AtlasSearch\(/); // Trash review
// Native person pages and secondary list routes must not bypass search.
assert.match(nativePerson, /scopedMediaMatches\(/);
assert.match(nativePerson, /facetPeople: List<PersonEntry>/);
assert.match(personPage, /searchAction: \(@Composable \(\) -> Unit\)\?/);
assert.match(personPage, /AtlasSearch\(/);
assert.match(personPage, /scopedNamedMatches\(/);
assert.match(hiddenAlbums, /AtlasSearch\(/);
assert.match(hiddenAlbums, /scopedNamedMatches\(/);
assert.match(main, /facetPeople = people/);
console.log('FIND-0 shared-scoped-search contract passed');
