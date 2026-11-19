import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// DESIGN-6 #136 (brief 7010307449, extends 6974542947): state-to-copy contract
// for the shared Quiet Frame quiet state and every screen that uses it.
const read = path => readFileSync(path, 'utf8');
const src = 'app/src/main/java/nl/atlas/gallery/';
const design = read(src + 'featuredesign/AtlasGalleryDesign.kt');
const albums = read(src + 'featurealbums/AtlasAlbumsScreen.kt');
const cover = read(src + 'featurealbums/AtlasAlbumCoverChooser.kt');
const people = read(src + 'featurepeopleui/PeopleHomeScreen.kt');
const stories = read(src + 'featurestories/AtlasStoriesScreen.kt');
const main = read(src + 'MainActivity.kt');
const viewer = read(src + 'GalleryViewer.kt');
const grid = read(src + 'featuregrid/AtlasFeatureGrid.kt');
const timeline = read(src + 'featuregrid/AtlasTimelineGallery.kt');

const quietState = design.slice(design.indexOf('fun AtlasEmptyState('),
  design.indexOf('object AtlasQuietState'));
const loading = design.slice(design.indexOf('fun AtlasGalleryLoading('));

test('one shared quiet state: centred above the nav pill, Quiet Frame look', () => {
  assert.match(quietState, /explanation: String = ""/);
  assert.match(quietState, /icon: ImageVector\? = null/);
  assert.match(quietState, /bottomInset: Dp = LocalAtlasBottomChrome\.current/);
  assert.match(quietState, /verticalArrangement = Arrangement\.Center/);
  assert.match(quietState, /if \(explanation\.isNotBlank\(\)\)/);
  assert.match(quietState, /if \(actionLabel != null && onAction != null\)/);
  // Accent text action, 44 dp+ target; no filled button, illustration box or primary-tinted icon.
  assert.match(quietState, /TextButton\(/);
  assert.match(quietState, /contentColor = MaterialTheme\.colorScheme\.primary/);
  assert.match(quietState, /heightIn\(min = AtlasSpace\.MinimumTarget\)/);
  assert.doesNotMatch(quietState, /\bButton\(\s*\n?\s*onClick/);
  assert.doesNotMatch(quietState, /EmptyVisual|visual:/);
  assert.match(quietState, /tint = MaterialTheme\.colorScheme\.onSurfaceVariant/);
  assert.match(design, /val IconSize = 22\.dp/);
  assert.match(design, /fontSize = 20\.sp, lineHeight = 26\.sp, fontWeight = FontWeight\.Medium/);
});

test('state 7: loading is quiet for 300 ms, then one small centred line', () => {
  assert.match(design, /const val LoadingQuietMillis = 300L/);
  assert.match(loading, /delay\(AtlasQuietState\.LoadingQuietMillis\)/);
  assert.match(loading, /if \(visible\)/);
  assert.match(loading, /verticalArrangement = Arrangement\.Center/);
  assert.match(loading, /Modifier\.size\(AtlasQuietState\.IconSize\)/);
  for (const text of [albums, cover, stories]) {
    assert.doesNotMatch(text, /Gathering albums on this device|Finding album cover choices/);
  }
  assert.match(albums, /AtlasGalleryLoading\("Loading albums…"\)/);
  assert.match(stories, /AtlasGalleryLoading\("Getting your stories ready…"/);
  // Grids and the timeline show nothing while busy, never a loading wall.
  assert.match(grid, /if \(!busy\) AtlasEmptyState\(/);
  assert.match(timeline, /if \(items\.isEmpty\(\) && !busy\)/);
  assert.doesNotMatch(timeline, /Text\([^)]*"Arranging timeline…"/);
  assert.doesNotMatch(main, /"Loading media…"/);
});

test('Albums: no albums, filtered empty, access and error each have one line', () => {
  assert.match(albums, /AlbumMediaFilter\.ALL -> "No albums yet"/);
  assert.match(albums, /AlbumMediaFilter\.PHOTOS -> "No photo albums"/);
  assert.match(albums, /AlbumMediaFilter\.VIDEOS -> "No video albums"/);
  assert.match(albums, /"Albums need photo access"/);
  assert.match(albums, /"Couldn’t load albums"/);
  assert.match(albums, /if \(accessFailure\) "Library status" else "Try again"/);
  assert.doesNotMatch(albums, /ATLAS can only show albums you allow it to access/);
  assert.doesNotMatch(albums, /Your original photos haven’t been changed/);
  assert.doesNotMatch(cover, /permission-aware|currently available media items/);
});

test('Photos: no media, permission with one real action, partial access without a banner', () => {
  assert.match(main, /AtlasEmptyState\(\s*title = "Photos need access"/);
  assert.match(main, /actionLabel = "Allow access"/);
  assert.match(main, /onAction = \{ permissionLauncher\.launch\(necessaryPermissions\(\)\) \}/);
  assert.doesNotMatch(main, /Grant photo and video access to build your private gallery/);
  assert.match(timeline, /AtlasEmptyState\(title = "No photos yet"/);
  assert.match(grid, /emptyText: String = "No photos yet"/);
  // 1c: READ_MEDIA_VISUAL_USER_SELECTED only feeds the grant; no banner copy.
  assert.doesNotMatch(main, /"[^"]*(selected photos|limited access|partial access|Select more)[^"]*"/i);
});

test('People: not started, scanning and no faces found read differently', () => {
  assert.match(people, /PeopleEmptyState\(discovery\.phase, scan\.start\)/);
  assert.match(people, /-> "No people yet"/);
  assert.match(people, /-> "No people found"/);
  assert.match(people, /else -> "No people"/);
  assert.match(people, /actionLabel = if \(onStart != null\) "Discover faces" else null/);
  assert.match(people, /titleTag = "atlas-people-empty"/);
  assert.match(people, /testTag\("atlas-people-restoring"\)/);
  assert.match(people, /AtlasEmptyState\(title = "No matching people"/);
  assert.doesNotMatch(people, /FilledTonalButton\(onClick = onStart\)/);
});

test('Stories and viewer stay consistent with the shared state', () => {
  assert.match(stories, /AtlasEmptyState\(title = "Your stories will appear here"/);
  assert.match(stories, /testTag\("atlas-stories-planning"\)/);
  assert.match(viewer, /onDecodeError = \{ previewFailed = true \}/);
  assert.match(viewer, /testTag\("atlas-viewer-decode-error"\)/);
  assert.match(viewer, /Text\("Can’t open photo"/);
  assert.match(viewer, /HorizontalPager\(/);
});

test('copy rules: titles of at most five words and no jargon', () => {
  const all = [design, albums, cover, people, stories, main, viewer, grid, timeline];
  const titles = all.flatMap(text => [...text.matchAll(/AtlasEmptyState\(\s*title = "([^"]+)"/g)]
    .map(m => m[1]));
  titles.push('No albums yet', 'No photo albums', 'No video albums', 'Albums need photo access',
    'Couldn’t load albums', 'No people yet', 'No people found', 'No people');
  assert.ok(titles.length >= 10);
  for (const title of titles) {
    assert.ok(title.split(/\s+/).length <= 5, `title too long: ${title}`);
    assert.doesNotMatch(title, /\b(index|scan job|SQLite|model|error \d+)\b/i);
  }
});
