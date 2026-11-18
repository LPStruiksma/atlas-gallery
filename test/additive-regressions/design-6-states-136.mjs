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

// Search "No results" moved to #134 and shipped in #401 (SearchOneFieldEvidenceTest).
test('Stories has distinct empty and planning states', () => {
  assert.match(stories, /testTag\("atlas-stories-empty"\)/);
  assert.match(stories, /testTag\("atlas-stories-planning"\)/);
});

test('Photos permission has one real Android recovery action and no explanatory chrome', () => {
  assert.match(search, /AtlasEmptyState\(\s*title = "Photos need access"/);
  assert.match(search, /actionLabel = "Allow access"/);
  assert.match(search, /onAction = \{ permissionLauncher\.launch\(necessaryPermissions\(\)\) \}/);
  assert.doesNotMatch(search, /Grant photo and video access to build your private gallery/);
});

test('empty and loading grids avoid a full-screen loading message', () => {
  const grid = read('app/src/main/java/nl/atlas/gallery/featuregrid/AtlasFeatureGrid.kt');
  const timeline = read('app/src/main/java/nl/atlas/gallery/featuregrid/AtlasTimelineGallery.kt');
  assert.match(grid, /if \(!busy\) Column\([\s\S]*?Text\(emptyText/);
  assert.match(grid, /testTag\("atlas-feature-grid-empty"\)/);
  assert.doesNotMatch(grid, /Text\(if \(busy\) "Loading photos…"/);
  assert.match(timeline, /if \(ready == null \|\| items\.isEmpty\(\)\)/);
  assert.match(timeline, /if \(items\.isEmpty\(\) && !busy\)/);
  assert.doesNotMatch(timeline, /Text\([^)]*"Arranging timeline…"/);
});

test('viewer decode errors are named while neighbour paging stays mounted', () => {
  const viewer = read('app/src/main/java/nl/atlas/gallery/GalleryViewer.kt');
  assert.match(viewer, /onDecodeError = \{ previewFailed = true \}/);
  assert.match(viewer, /testTag\("atlas-viewer-decode-error"\)/);
  assert.match(viewer, /Text\("Can't open photo"/);
  assert.match(viewer, /HorizontalPager\(/);
});
