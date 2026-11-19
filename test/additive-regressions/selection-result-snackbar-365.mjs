import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// #365 DESIGN-10c: selection batch results are one honest snackbar above the
// visible pill (selection pill or nav pill), never a Toast and never an Undo.
const read = (path) => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8');
const main = read('app/src/main/java/nl/atlas/gallery/MainActivity.kt');
const host = read('app/src/main/java/nl/atlas/gallery/featureorganization/SelectionResultSnackbar.kt');
const journey = read('app/src/androidTest/java/nl/atlas/gallery/SelectionResultSnackbarJourneyTest.kt');
const evidence = read('scripts/ui-evidence.d/365-selection-result.txt');

/** Body of the first `fun name(` or `val name = ...` block, by brace matching. */
function block(source, header) {
  const start = source.indexOf(header);
  assert.notEqual(start, -1, `missing ${header}`);
  const open = source.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === '{') depth++;
    else if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error(`unbalanced ${header}`);
}

const flows = [
  'val batchTrashLauncher = rememberLauncherForActivityResult(',
  'fun finishAlbumTransfer(',
  'val batchMoveLauncher = rememberLauncherForActivityResult(',
  'fun shareBatch(',
  'fun trashBatch(',
  'fun favouriteBatch(',
  'fun transferBatch(',
];

test('selection batch flows report through the snackbar, not Toasts', () => {
  for (const header of flows) {
    const body = block(main, header);
    assert.doesNotMatch(body, /Toast\./, `${header} still shows a Toast`);
    assert.match(body, /selectionResults\.show\(/, `${header} does not use the snackbar`);
  }
});

test('the honest result copy is kept word for word', () => {
  for (const copy of [
    'Moved selected items to Android Trash',
    'Trash canceled · your photos are unchanged',
    'Move canceled · your photos are unchanged',
    'albumTransferMessage(plan.mode, plan.target.title, result)',
    'Select again; nothing was trashed.',
  ]) {
    assert.ok(main.includes(copy), `missing copy: ${copy}`);
  }
});

test('one host lives in the Scaffold snackbar slot, above whichever bar is shown', () => {
  assert.match(main, /snackbarHost = \{ SelectionResultSnackbarHost\(selectionResults\) \}/);
  assert.equal(main.match(/SelectionResultSnackbarHost\(/g).length, 1);
  assert.equal(main.match(/rememberSaveable\(saver = SelectionResultMessages\.Saver\) \{ SelectionResultMessages\(\) \}/g).length, 1);
});

test('the host replaces instead of stacking, auto-dismisses and offers no Undo', () => {
  assert.match(host, /generation/);
  assert.match(host, /calculateRecommendedTimeoutMillis/);
  assert.match(host, /LiveRegionMode\.Polite/);
  assert.match(host, /inverseSurface/);
  assert.doesNotMatch(host, /\bSnackbarHostState\b|\bactionLabel\b|TextButton|onAction/);
  assert.doesNotMatch(host.replace(/\/\/.*|\/\*[\s\S]*?\*\//g, ''), /Undo/i);
});

test('Trash stays Android consent only, with its prompt captured before and after', () => {
  assert.match(main, /MediaStore\.createTrashRequest\(\s*context\.contentResolver, records/);
  assert.match(journey, /com\.android\.providers\.media/);
  assert.match(journey, /takeScreenshot|saveUiEvidence/);
  for (const theme of ['light', 'dark']) {
    for (const shot of ['trash-prompt', 'trash-canceled-selection-pill', 'trash-done-nav-pill']) {
      assert.match(evidence, new RegExp(`selection-result-365-${theme}-${shot}`));
    }
  }
  assert.doesNotMatch(journey, /Assume\.assume|assumeNoException|@Ignore/);
});
