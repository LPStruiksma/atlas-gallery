import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// FIND-4 (#304), the inventory companion to scoped-search-179.mjs.
// Source-only contract, intentionally not a runtime/Compose integration test.
// Every entry has an explicit screen scope, a shared surface, and provenance.
// Add new routes to EXPECTED and INVENTORY together, not just to the allowlist.
const ROOT = 'app/src/main/java/nl/atlas/gallery/';
const read = (path) => readFileSync(ROOT + path, 'utf8');
const pathOf = (path) => ROOT + path;
const expected = [
  'pictures', 'albums', 'album-folder-detail', 'person-filter',
  'native-person', 'people', 'stories', 'trash-declutter',
  'global-search', 'face-results', 'visual-similar-results',
  'hidden-albums', 'hidden-people',
  'same-person-expanded',
];
const inventory = [
  {
    id: 'pictures', path: 'MainActivity.kt', scope: 'THIS_SCREEN',
    surface: /if \(\(photosControls \|\| personFilter != null\)[\s\S]*?AtlasSearch\(/,
    provenance: /val albumItems = if \(albumRoute == null\) photosShown\.orEmpty\(\) else selectedAlbum\.orEmpty\(\)/,
    filter: /scopedMediaMatches\(overviewBase, sorted, people, overviewQuery,\s*AtlasSearchScope\.THIS_SCREEN/,
    route: /val photosControls = access && tab == 0 && personFilter == null/,
    escape: /requested == AtlasSearchScope\.LIBRARY[\s\S]*?personFilter = null[\s\S]*?album = null[\s\S]*?tab = 3/,
  },
  {
    id: 'albums', path: 'featurealbums/AtlasAlbumsScreen.kt', scope: 'THIS_SCREEN',
    surface: /AtlasSearch\(query = searchText/,
    provenance: /scopedNamedMatches\(albums, searchText\) \{ it\.title \}/,
    filter: /scopedNamedMatches\(albums, searchText\)/,
    escape: /if \(scope == AtlasSearchScope\.LIBRARY\) onSearchWholeLibrary\?\.invoke\(\)/,
    hostPath: 'MainActivity.kt', host: /AtlasAlbumsScreen\([\s\S]*?onSearchWholeLibrary = \{ tab = 3 \}/,
  },
  {
    id: 'album-folder-detail', path: 'MainActivity.kt', scope: 'THIS_SCREEN',
    surface: /if \(\(photosControls \|\| personFilter != null\)[\s\S]*?AtlasSearch\(/,
    provenance: /val albumItems = if \(albumRoute == null\) photosShown\.orEmpty\(\) else selectedAlbum\.orEmpty\(\)/,
    filter: /scopedMediaMatches\(overviewBase, sorted, people, overviewQuery,\s*AtlasSearchScope\.THIS_SCREEN/,
    route: /tab == 0 && album != null -> album!!\.trimEnd/,
    escape: /requested == AtlasSearchScope\.LIBRARY[\s\S]*?album = null[\s\S]*?albumVolume = null[\s\S]*?tab = 3/,
  },
  {
    id: 'person-filter', path: 'MainActivity.kt', scope: 'THIS_SCREEN',
    surface: /if \(\(photosControls \|\| personFilter != null\)[\s\S]*?AtlasSearch\(/,
    provenance: /personFilter != null -> selectedPersonPhotos\.orEmpty\(\)/,
    filter: /scopedMediaMatches\(overviewBase, sorted, people, overviewQuery,\s*AtlasSearchScope\.THIS_SCREEN/,
    route: /personFilter != null -> \{\s*PeopleAlbumContent\(/,
    escape: /requested == AtlasSearchScope\.LIBRARY[\s\S]*?personFilter = null[\s\S]*?tab = 3/,
  },
  {
    id: 'native-person', path: 'PersonPageRoute.kt', scope: 'THIS_SCREEN',
    surface: /searchAction = \{\s*AtlasSearch\(/,
    provenance: /scopedMediaMatches\(source, source, facetPeople, searchText/,
    filter: /ScopedMediaSearchSnapshot\(source, source, facetPeople,[\s\S]*?AtlasSearchScope\.THIS_SCREEN/,
    escape: /if \(next == AtlasSearchScope\.LIBRARY\) onSearchWholeLibrary\?\.invoke\(\)/,
    hostPath: 'MainActivity.kt', host: /NativePersonAlbumRoute\([\s\S]*?onSearchWholeLibrary = \{ nativePersonAlbum = null; tab = 3 \}/,
    delegatePath: 'PersonPageScreen.kt', delegate: /actions = \{ searchAction\?\.invoke\(\) \}/,
  },
  {
    id: 'people', path: 'featurepeopleui/PeopleHomeScreen.kt', scope: 'THIS_SCREEN',
    surface: /AtlasSearch\(query = searchText/,
    provenance: /val filteredTiles = tiles\.filter/,
    filter: /scopedNameMatches\(tile\.(?:profile|tile)\.name, searchText\)/,
    escape: /if \(scope == AtlasSearchScope\.LIBRARY\) onSearchWholeLibrary\?\.invoke\(\)/,
    hostPath: 'MainActivity.kt', host: /PeopleHomeScreen\([\s\S]*?onSearchWholeLibrary = \{ tab = 3 \}/,
  },
  {
    id: 'stories', path: 'featurestories/AtlasStoriesScreen.kt', scope: 'THIS_SCREEN',
    surface: /AtlasSearch\(query = searchText/,
    provenance: /currentStoryCardsOrEmpty\(deck, currentGrantEpoch, currentCatalogRevision\)/,
    filter: /scopedNamedMatches\([\s\S]*?currentStoryCardsOrEmpty/,
    // Deliberately fails until a safe host handoff is wired. Do not widen the deck in-place.
    escape: /onScopeChange\s*=\s*\{[\s\S]*?AtlasSearchScope\.LIBRARY[\s\S]*?onSearchWholeLibrary/,
    hostPath: 'MainActivity.kt', host: /AtlasStoriesScreen\([\s\S]*?onSearchWholeLibrary\s*=/,
  },
  {
    id: 'trash-declutter', path: 'DeclutterScreen.kt', scope: 'THIS_SCREEN',
    surface: /actions = \{ AtlasSearch\(/,
    provenance: /scopedNamedMatches\(pending, searchText\) \{ it\.name \+ " " \+ it\.album \}/,
    filter: /scopedNamedMatches\(pending, searchText\)/,
    // Deliberately fails until a safe host handoff is wired.
    escape: /onScopeChange\s*=\s*\{[\s\S]*?AtlasSearchScope\.LIBRARY[\s\S]*?onSearchWholeLibrary/,
  },
  {
    id: 'global-search', path: 'MainActivity.kt', scope: 'LIBRARY',
    surface: /tab == 3 -> \{\s*AtlasSearch\(/,
    provenance: /tab == 3 -> if \(!searchActive\) sorted else searched\.orEmpty\(\)/,
    filter: /val searchSnapshot = rememberAsyncFacetedSearch\(sorted, people, searchFacets/,
    route: /initialScope = AtlasSearchScope\.LIBRARY,[\s\S]*?selectedScope = overviewScope/,
    inline: /persistentInline = true,[\s\S]*?textTool = \{\s*LocalSearchControls\(/,
  },
  {
    id: 'face-results', path: 'featuresearch/face/FaceSearchResultsScreen.kt', scope: 'THIS_SCREEN',
    surface: /AtlasSearch\(query = searchText/,
    provenance: /scopedMediaMatches\(source, source, facetPeople, searchText/,
    filter: /ScopedMediaSearchSnapshot\(source, source, facetPeople, results\.epoch,[\s\S]*?AtlasSearchScope\.THIS_SCREEN/,
    escape: /if \(next == AtlasSearchScope\.LIBRARY\) onSearchWholeLibrary\?\.invoke\(\)/,
    hostPath: 'MainActivity.kt', host: /FaceSearchRoute\([\s\S]*?onSearchWholeLibrary = \{ closeFaceSearch\(\); tab = 3 \}/,
  },
  {
    id: 'visual-similar-results', path: 'featurevisual/search/VisualSearchResultsScreen.kt', scope: 'THIS_SCREEN',
    surface: /if \(onSearchQueryChange != null\) \{\s*AtlasSearch\(/,
    provenance: /onSearchQueryChange: \(\(String\) -> Unit\)\? = null/,
    filterPath: 'featuresearch/visual/FindSimilarRoute.kt',
    filter: /scopedMediaMatches\(source, source, facetPeople, resultQuery/,
    delegatePath: 'featuresearch/visual/FindSimilarRoute.kt',
    delegate: /VisualSearchResultsScreen\([\s\S]*?onSearchQueryChange = \{ resultQuery = it \},[\s\S]*?onSearchWholeLibrary = onSearchWholeLibrary/,
    escape: /if \(next == AtlasSearchScope\.LIBRARY\) onSearchWholeLibrary\?\.invoke\(\)/,
    hostPath: 'MainActivity.kt', host: /FindSimilarRoute\([\s\S]*?onSearchWholeLibrary = \{ closeFindSimilar\(\); tab = 3 \}/,
  },
  {
    id: 'hidden-albums', path: 'featurealbums/HideAlbumsScreen.kt', scope: 'THIS_SCREEN',
    surface: /AtlasSearch\(query = searchText/,
    provenance: /val visibleAlbums = albums\?\.let \{ scopedNamedMatches\(it, searchText\)/,
    filter: /scopedNamedMatches\(it, searchText\)/,
    escape: /if \(scope == AtlasSearchScope\.LIBRARY\) onSearchWholeLibrary\?\.invoke\(\)/,
    hostPath: 'MainActivity.kt', host: /HideAlbumsScreen\([\s\S]*?onSearchWholeLibrary = \{ hideAlbumsOpen = false; tab = 3 \}/,
  },
  {
    id: 'hidden-people', path: 'PersonPageScreen.kt', scope: 'THIS_SCREEN',
    surface: /AtlasSearch\(query = searchText/,
    provenance: /val visiblePeople = scopedNamedMatches\(people, searchText\)/,
    filter: /scopedNamedMatches\(people, searchText\)/,
    escape: /if \(scope == AtlasSearchScope\.LIBRARY\) onSearchWholeLibrary\?\.invoke\(\)/,
    hostPath: 'MainActivity.kt', host: /HiddenPeopleRoute\([\s\S]*?onSearchWholeLibrary = \{ hiddenPeopleOpen = false; tab = 3 \}/,
  },
  {
    id: 'same-person-expanded', path: 'MainActivity.kt', scope: 'PENDING',
    // Conditional #170: explicitly pending until the expanded grid is an overview.
    reason: 'Conditional Same person? expanded grid (#170), not yet a verified independent search surface',
    marker: /SamePersonQueueRoute\(/,
  },
];

assert.equal(new Set(expected).size, expected.length, 'FIND-4 duplicate expected route');
assert.equal(new Set(inventory.map((entry) => entry.id)).size, inventory.length,
  'FIND-4 duplicate inventory id');
assert.deepEqual(inventory.map((entry) => entry.id).sort(), [...expected].sort(),
  'FIND-4 missing, extra or unreviewed overview search entry');
const shared = read('featuresearch/AtlasSearch.kt');
const prior = readFileSync('test/additive-regressions/scoped-search-179.mjs', 'utf8');
assert.match(prior, /FIND-0 shared-scoped-search contract passed/,
  'FIND-4 depends on the preserved FIND-0 regression');
assert.match(shared, /fun AtlasSearch\(/);
assert.match(shared, /initialScope: AtlasSearchScope = AtlasSearchScope\.THIS_SCREEN/);
assert.match(shared, /val scope = selectedScope \?: localScope/);
assert.match(shared, /if \(persistentInline\) \{\s*contents\(\)/);
assert.match(shared, /testTag\("atlas-search-expand"\)/);
assert.match(shared, /DropdownMenu\(\s*expanded = expanded,[\s\S]*?onDismissRequest = close[\s\S]*?contents\(\)/);
assert.match(shared, /testTag\("atlas-search-expanded"\)/);
for (const tag of ['atlas-search-shared', 'atlas-scoped-search-field',
  'atlas-search-scope-screen', 'atlas-search-scope-library']) {
  assert.ok(shared.includes('testTag("' + tag + '")'), 'FIND-4 lost shared control ' + tag);
}
for (const slot of ['textTool', 'faceTool', 'imageTool', 'filtersTool',
  'historyTool', 'suggestionsTool']) {
  assert.match(shared, new RegExp('\\b' + slot + '\\?\\.invoke\\(\\)'),
    'FIND-4 missing expanded tool slot ' + slot);
}
for (const entry of inventory) {
  assert.ok(entry.path && entry.scope, 'FIND-4 incomplete source provenance for ' + entry.id);
  const src = read(entry.path);
  if (entry.scope === 'PENDING') {
    assert.match(src, entry.marker,
      'FIND-4 pending screen moved: audit ' + entry.id + ' (' + pathOf(entry.path) + ')');
    assert.ok(entry.reason, 'FIND-4 pending screen needs a named reason: ' + entry.id);
    console.log('FIND-4 PENDING ' + entry.id + ': ' + entry.reason);
    continue;
  }
  for (const key of ['surface', 'provenance', 'filter']) {
    assert.ok(entry[key] instanceof RegExp, 'FIND-4 missing ' + key + ' for ' + entry.id);
    assert.match(read(key === 'filter' && entry.filterPath ? entry.filterPath : entry.path),
      entry[key], 'FIND-4 ' + entry.id + ' lacks ' + key + ' at ' +
      pathOf(key === 'filter' && entry.filterPath ? entry.filterPath : entry.path));
  }
  if (entry.route) assert.match(src, entry.route,
    'FIND-4 route provenance absent for ' + entry.id);
  if (entry.delegate) assert.match(read(entry.delegatePath), entry.delegate,
    'FIND-4 delegated shared search not wired for ' + entry.id);
  if (entry.inline) assert.match(src, entry.inline,
    'FIND-4 global Search lost persistent full-library tools');
  if (entry.scope === 'THIS_SCREEN') {
    assert.ok(entry.escape instanceof RegExp,
      'FIND-4 missing library escape check for ' + entry.id);
    assert.match(src, entry.escape,
      'FIND-4 ' + entry.id + ' has no explicit library handoff in ' + pathOf(entry.path));
    if (entry.host) assert.match(read(entry.hostPath), entry.host,
      'FIND-4 ' + entry.id + ' has no host library handoff in ' + pathOf(entry.hostPath));
  }
}
console.log('FIND-4 shared search entry inventory passed');
