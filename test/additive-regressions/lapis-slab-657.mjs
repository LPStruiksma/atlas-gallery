// BRAND-L4 (#657, Quiet Frame v3 Structure 1 and 6, Signature): source contract
// for the lapis slab, Collections sections and the held sphere. Fails on main,
// which still draws the four-tab blurred pill.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (path) => readFileSync(path, 'utf8');
const design = read('app/src/main/java/nl/atlas/gallery/featuredesign/AtlasGalleryDesign.kt');
const sphere = read('app/src/main/java/nl/atlas/gallery/featuredesign/AtlasHeldSphere.kt');
const tokens = read('app/src/main/java/nl/atlas/gallery/featuredesign/AtlasTokens.kt');
const activity = read('app/src/main/java/nl/atlas/gallery/MainActivity.kt');
const viewer = read('app/src/main/java/nl/atlas/gallery/GalleryViewer.kt');
const scrub = read('app/src/main/java/nl/atlas/gallery/featuregrid/TimelineScrubHandle.kt');
const selection = read('app/src/main/java/nl/atlas/gallery/featureorganization/SamsungSelectionChrome.kt');
const people = read('app/src/main/java/nl/atlas/gallery/featurepeopleui/PeopleHomeScreen.kt');
const declared = read('scripts/ui-evidence.d/657-lapis-slab.txt');

test('the slab holds two places and the search sphere, no People place', () => {
  assert.match(design, /PHOTOS\("Pictures"/);
  assert.match(design, /ALBUMS\("Collections"/);
  assert.match(design, /AtlasSlabPlaces: List<AtlasDestination> = listOf\(AtlasDestination\.PHOTOS, AtlasDestination\.ALBUMS\)/);
  assert.match(design, /AtlasSearchSphere\(/);
  assert.match(design, /RoundedCornerShape\(AtlasTokens\.Radii\.Slab\)/);
  assert.match(tokens, /val Slab = 24\.dp/);
  assert.doesNotMatch(design, /AtlasDestination\.entries\.forEach/);
  // Opaque slab: nothing captured or blurred behind it.
  assert.doesNotMatch(design, /BlurEffect\(/);
  assert.doesNotMatch(activity, /atlasCaptureTabBackdrop/);
});

test('People and Stories keep a home as Collections sections', () => {
  assert.match(design, /ALBUMS\("Albums"\), PEOPLE\("People"\), STORIES\("Stories"\)/);
  assert.match(activity, /AtlasCollectionsSections\(AtlasCollectionsSection\.ALBUMS, onSelectCollectionsSection\)/);
  assert.match(activity, /AtlasCollectionsSections\(AtlasCollectionsSection\.PEOPLE, onSelectCollectionsSection\)/);
  assert.match(activity, /AtlasCollectionsSection\.STORIES -> \{ tab = 1; storiesOpen = true \}/);
  assert.match(design, /if \(destination == AtlasDestination\.PEOPLE\) AtlasDestination\.ALBUMS else destination/);
});

test('the held sphere marks fast scroll, selection, filmstrip and loading', () => {
  assert.match(tokens, /val FastScroll = 46\.dp/);
  assert.match(tokens, /val Halo = 2\.dp/);
  assert.match(scrub, /atlas-fast-scroll-sphere/);
  assert.match(scrub, /sphereLabel/);
  assert.match(selection, /AtlasSelectionSphere\(modifier, size\.dp, order\)/);
  assert.match(sphere, /border\(AtlasTokens\.Sphere\.Halo, Color\.White, CircleShape\)/);
  assert.match(viewer, /atlas-filmstrip-sphere/);
  assert.match(viewer, /atlas-filmstrip-ring/);
  assert.match(viewer, /private val STRIP_ITEM_WIDTH = 48\.dp/);
  assert.match(people, /AtlasLoadingArc\(progress, Modifier\.testTag\("atlas-people-progress"\)/);
  // Nothing moves on its own with "remove animations" on.
  assert.match(sphere, /AtlasMotion\.animationsDisabled\(context\)/);
});

test('viewer actions sit on an opaque #3448E0 slab', () => {
  assert.match(tokens, /val ActionPill = Dark\.BrandFill\n/);
  assert.match(viewer, /RoundedCornerShape\(AtlasTokens\.Radii\.Slab\)\)\.background\(AtlasTokens\.Colors\.Viewer\.ActionPill\)/);
});

test('every brief state is declared as S24+ evidence in both themes', () => {
  for (const mode of ['light', 'dark']) {
    for (const state of ['pictures', 'fastscroll', 'selection-3', 'viewer',
      'collections-albums', 'collections-people', 'search', 'spheres']) {
      assert.match(declared, new RegExp(`^l4-657-${state}-${mode}$`, 'm'));
    }
  }
  assert.match(declared, /^l4-657-fastscroll-reduced-motion$/m);
  assert.match(declared, /^l4-657-pictures-font200-dark$/m);
});
