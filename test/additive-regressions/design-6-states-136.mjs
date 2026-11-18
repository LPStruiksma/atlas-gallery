import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(path, 'utf8');
const design = read('app/src/main/java/nl/atlas/gallery/featuredesign/AtlasGalleryDesign.kt');
const albums = read('app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt');
const people = read('app/src/main/java/nl/atlas/gallery/featurepeopleui/PeopleHomeScreen.kt');
const stories = read('app/src/main/java/nl/atlas/gallery/featurestories/AtlasStoriesScreen.kt');
const search = read('app/src/main/java/nl/atlas/gallery/MainActivity.kt');

test('shared quiet state holds illustration and copy at screen centre, with optional action', () => {
  assert.match(design, /fun AtlasEmptyState\([\s\S]*?explanation: String = ""/);
  assert.match(design, /modifier\.fillMaxSize\(\)\.padding\(horizontal = 28\.dp\)/);
  assert.match(design, /verticalArrangement = Arrangement\.Center/);
  assert.match(design, /if \(explanation\.isNotBlank\(\)\)/);
  assert.match(design, /if \(actionLabel != null && onAction != null\)/);
});

test('albums distinguish no access, recoverable error, and genuinely empty filters', () => {
  assert.match(albums, /title = if \(accessFailure\) "Albums need photo access"/);
  assert.match(albums, /actionLabel = if \(action == null\) null else/);
  assert.match(albums, /AlbumMediaFilter\.ALL -> "Your albums will appear here"/);
  assert.match(albums, /AlbumMediaFilter\.PHOTOS -> "No photo albums here"/);
  assert.match(albums, /AlbumMediaFilter\.VIDEOS -> "No video albums here"/);
  assert.doesNotMatch(albums, /ATLAS can only show albums you allow it to access/);
  assert.doesNotMatch(albums, /Your original photos haven’t been changed/);
});

test('People has quiet unscanned/restoring/scanning and search-miss states', () => {
  assert.match(people, /testTag\("atlas-people-restoring"\)/);
  assert.match(people, /testTag\("atlas-people-empty"\)/);
  assert.match(people, /Text\("No matching people"\)/);
  assert.match(people, /PeopleScanProgress\(discovery\)/);
  assert.match(people, /PeopleEmptyState\(scan\.start\)/);
});

test('Search and Stories have distinct no-results and empty/planning states', () => {
  assert.match(search, /emptyText = "No results"/);
  assert.match(stories, /testTag\("atlas-stories-empty"\)/);
  assert.match(stories, /testTag\("atlas-stories-planning"\)/);
});
