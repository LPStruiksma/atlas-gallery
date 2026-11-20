import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import test from 'node:test';

// IDS-11 (#478): "Swap person" sits directly under AI Edit in the viewer ⋮ and in selection
// More for one supported still photo, opens the IDS-8 flow and returns through pending focus.
// Brief rev 2: https://github.com/LPStruiksma/atlas-gallery/issues/478#issuecomment-7027593393
const root = 'app/src/main/java/nl/atlas/gallery/';

test('viewer ⋮ offers Swap person right after AI Edit, with no new chrome icon', () => {
  const viewer = readFileSync(root + 'GalleryViewer.kt', 'utf8');
  const ai = viewer.indexOf('"atlas-viewer-ai-edit"');
  const swap = viewer.indexOf('"atlas-viewer-swap-person"');
  assert.ok(ai > 0 && swap > ai, 'Swap person must follow AI Edit in the same menu');
  const between = viewer.slice(ai, swap);
  assert.doesNotMatch(between, /IconButton\(/, 'no icon between AI Edit and Swap person');
  assert.doesNotMatch(between, /DropdownMenu\(/, 'same DropdownMenu as AI Edit');
  assert.match(viewer, /class ViewerSwapPerson\(/);
});

test('selection More inserts Swap person directly after AI Edit, before Copy/Move', () => {
  const main = readFileSync(root + 'MainActivity.kt', 'utf8');
  const ai = main.indexOf('"atlas-selection-ai-edit"');
  const swap = main.indexOf('"atlas-selection-swap-person"');
  const copy = main.indexOf('"atlas-selection-copy-to-album"');
  assert.ok(ai > 0 && swap > ai && copy > swap);
  assert.match(main, /singleSelectedPhoto/);
  assert.match(main, /SwapPersonEntry\(/);
  assert.match(main, /swapPersonSourceUri == null/, 'pending focus waits for the flow to close');
});

test('one shared eligibility guard decides from MIME and bytes, not the file name', () => {
  const path = root + 'featureidentity/ui/SwapPersonEntryRules.kt';
  assert.ok(existsSync(path));
  const rules = readFileSync(path, 'utf8');
  assert.match(rules, /fun staticImage\(mime: String\?, header: ByteArray\)/);
  assert.match(rules, /acTL/);
  assert.match(rules, /VP8X/);
  assert.doesNotMatch(rules, /endsWith\("\.(gif|jpg|png|webp)"/i);
  const entry = readFileSync(root + 'featureidentity/ui/SwapPersonEntry.kt', 'utf8');
  assert.match(entry, /SwapPersonEligibility\.staticImage/);
  assert.match(entry, /IdentityEditSave\(/);
  assert.doesNotMatch(entry, /Log\.[diwev]\(/, 'no logging of names, boxes or paths');
});

test('the evidence test renders the brief\'s states and the manifest declares them', () => {
  const evidence = readFileSync('app/src/androidTest/java/nl/atlas/gallery/featureidentity/ui/SwapPersonEntryEvidenceTest.kt', 'utf8');
  const manifest = readFileSync('scripts/ui-evidence.d/467-swap-person-entry.txt', 'utf8');
  for (const name of ['viewer-menu', 'selection-more', 'selection-two', 'flow-from-viewer',
    'selection-video', 'selection-gif']) {
    assert.match(evidence, new RegExp(name));
    assert.match(manifest, new RegExp('ids11-' + name));
  }
});
