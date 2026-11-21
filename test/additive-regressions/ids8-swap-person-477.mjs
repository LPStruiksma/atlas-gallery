import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import test from 'node:test';

// IDS-8 (#477): Swap person studio screens, design brief rev 2.
const ui = 'app/src/main/java/nl/atlas/gallery/featureidentity/ui/';
const files = readdirSync(ui).filter((f) => f.endsWith('.kt'));
const sources = Object.fromEntries(files.map((f) => [f, readFileSync(ui + f, 'utf8')]));

// Every string a person can read or hear: Text("…"), labels, contentDescription and chip labels.
function visibleStrings(source) {
  const out = [];
  const patterns = [
    /Text\(\s*"([^"]*)"/g,
    /contentDescription\s*=\s*"([^"]*)"/g,
    /add\(\s*"([^"]*)"\s*to/g,
    /CanvasMessage\(\s*"([^"]*)"/g,
    /"([^"]*)"\s*to\s+machine::/g,
  ];
  for (const re of patterns) for (const m of source.matchAll(re)) out.push(m[1]);
  return out;
}

test('every IDS-8 screen file from the Touches line exists', () => {
  for (const f of ['SwapPersonFlow.kt', 'SwapPersonViewModel.kt', 'PersonPickerSheet.kt', 'TargetPickerScreen.kt',
    'CandidateStudioScreen.kt', 'CompareAllScreen.kt', 'ReferencePhotosSheet.kt']) {
    assert.ok(files.includes(f), `${f} missing`);
  }
});

test('no technical words, numbers-as-percentages or model names anywhere in the flow', () => {
  const banned = /\b(model|seed|engine|slider|strength|blend|setting|settings|onnx|hyperswap|insightface|ghost|inswapper|embedding|landmark|gpu|npu|cpu|candidate)\b|%/i;
  const strings = files.flatMap((f) => visibleStrings(sources[f]).map((s) => [f, s]));
  assert.ok(strings.length > 40, 'expected to find the flow copy');
  for (const [f, s] of strings) assert.doesNotMatch(s, banned, `${f}: "${s}"`);
});

test('brief rev 2 copy is present', () => {
  const all = Object.values(sources).join('\n');
  for (const copy of ['Swap person', 'Choose photos', 'Tap the face to change', 'Get Swap person ready',
    'Your photos never leave this phone.', 'Hold to see original', 'Compare all versions', 'Original',
    'More natural', 'Stronger likeness', 'Keep expression', 'With their hair', 'Keep original hair',
    'Discard these versions?', 'Keep editing', 'Saved as a copy', 'Change face', 'Change person',
    'Forget photos for', 'Forget? Your saved photos stay.', 'Skip this photo', 'Name this person',
    "Couldn't make versions of this photo", 'Your phone needed more memory. Close some apps and try again.',
    'This face is small, results may look soft', 'Add a few more photos of']) {
    assert.ok(all.includes(copy), `missing copy: ${copy}`);
  }
  assert.doesNotMatch(all, /Changes here don't affect People/);
});

test('entry wiring stays in IDS-11: the flow never references the viewer or main activity', () => {
  for (const [f, s] of Object.entries(sources)) {
    assert.doesNotMatch(s, /\b(GalleryViewer|MainActivity)\b(?!\.kt)/, f);
  }
});

test('the evidence list covers every state in light and dark at 100% and 200% font', () => {
  const list = readFileSync('scripts/ui-evidence.d/467-swap-person.txt', 'utf8')
    .split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
  const evidence = readFileSync('app/src/androidTest/java/nl/atlas/gallery/featureidentity/ui/SwapPersonEvidenceTest.kt', 'utf8');
  const states = [...evidence.matchAll(/shot\("([a-z0-9-]+)"\)/g)].map((m) => m[1]);
  assert.ok(states.length >= 30, 'expected every brief state to be shot');
  const expected = [];
  for (const theme of ['light', 'dark']) for (const font of ['100', '200']) for (const s of states) expected.push(`ids8-${s}-${theme}-${font}`);
  assert.deepEqual([...list].sort(), [...expected].sort());
});
