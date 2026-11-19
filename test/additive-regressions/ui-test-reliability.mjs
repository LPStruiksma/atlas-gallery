// Emulator UI test reliability ratchet (docs/testing/EMULATOR_TESTS.md).
//
// Counts, per instrumented test file, the patterns that the 2026-11-18 audit
// found behind the recurring emulator flakes. A file may never have MORE of a
// pattern than ui-test-debt.json records for it, and a new file starts at
// zero. Lowering a number is always welcome; raising one needs a reason in
// the PR. Regenerate after a migration with:
//
//   ATLAS_UI_DEBT_WRITE=1 node test/additive-regressions/ui-test-reliability.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const testRoot = join(root, 'app', 'src', 'androidTest', 'java', 'nl', 'atlas', 'gallery');
const baselinePath = join(root, 'test', 'additive-regressions', 'ui-test-debt.json');

// The shared helpers and their proof test are where these calls belong.
const exempt = (file) => file.startsWith('testkit/') ||
  ['S24DisplayRule.kt', 'SystemWindows.kt', 'UiWaits.kt'].includes(file);

const count = (text, pattern) => (text.match(pattern) || []).length;

export const patterns = {
  // Rule 1: a sleep waits for neither clock.
  sleep: (text) => count(text, /\b(?:Thread|SystemClock)\.sleep\(/g),
  // Rule 3: a raw click on a cut-off node is silently lost; use tap().
  rawClick: (text) => count(text, /\.performClick\(\)/g),
  // Rule 9: a raw capture copies only the visible part; use captureWhole().
  rawCapture: (text) => count(text, /\.captureToImage\(\)/g),
  // Rule 2: raw typing without a test keyboard opens the emulator's own; use typeText().
  typingWithoutKeyboardRule: (text) =>
    /\b(?:KeyboardRule|withTestKeyboard)\b/.test(text) ? 0
      : count(text, /\.performText(?:Input|Replacement)\(/g),
  // Rule 7: device-wide state belongs in a kit rule that restores it.
  deviceStateWrite: (text) =>
    count(text, /"(?:settings (?:put|delete) |wm (?:size|density) |cmd uimode night ["$y n])/g),
  // Rule 1: one shared wait that reports the screen, not a private copy per file.
  privateWait: (text) => count(text, /private fun (?:waitFor\w*|settle)\(/g),
};

// Counted and recorded, not yet enforced: 449 raw clicks wait for the
// migration units. Move a name out of this set once its count is near zero.
const advisory = new Set(['rawClick']);

function kotlinFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return kotlinFiles(path);
    return entry.name.endsWith('.kt') ? [path] : [];
  });
}

export function measure() {
  const debt = {};
  for (const path of kotlinFiles(testRoot).sort()) {
    const file = relative(testRoot, path).split('\\').join('/');
    if (exempt(file)) continue;
    const text = readFileSync(path, 'utf8');
    const found = {};
    for (const [name, counter] of Object.entries(patterns)) {
      const n = counter(text);
      if (n > 0) found[name] = n;
    }
    if (Object.keys(found).length > 0) debt[file] = found;
  }
  return debt;
}

if (process.env.ATLAS_UI_DEBT_WRITE === '1') {
  writeFileSync(baselinePath, JSON.stringify(measure(), null, 2) + '\n');
}

test('no instrumented test file gains a known flake pattern', () => {
  const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'));
  const grown = [];
  for (const [file, found] of Object.entries(measure())) {
    for (const [name, n] of Object.entries(found)) {
      const allowed = baseline[file]?.[name] ?? 0;
      if (!advisory.has(name) && n > allowed) grown.push(`${file}: ${name} ${allowed} -> ${n}`);
    }
  }
  assert.deepEqual(grown, [],
    'These files gained a pattern that docs/testing/EMULATOR_TESTS.md replaces ' +
    '(tap, captureWhole, typeText or KeyboardRule, awaitThat, advanceVirtualTime):\n' +
    grown.join('\n'));
});

test('the ratchet patterns recognise the calls they are named for', () => {
  assert.equal(patterns.sleep('Thread.sleep(200); SystemClock.sleep(5)'), 2);
  assert.equal(patterns.rawClick('a.performClick()\nb.tap()'), 1);
  assert.equal(patterns.rawCapture('n.captureToImage().toPixelMap(); n.captureWhole()'), 1);
  assert.equal(patterns.typingWithoutKeyboardRule('f.performTextInput("a")'), 1);
  assert.equal(patterns.typingWithoutKeyboardRule(
    'val k = KeyboardRule(TestKeyboard.None)\nf.performTextInput("a")'), 0);
  assert.equal(patterns.deviceStateWrite(
    'shell("wm size 1080x2340"); shell("cmd uimode night " + x); shell("settings put global a 1")'), 3);
  assert.equal(patterns.deviceStateWrite('shell("cmd uimode night")'), 0);
  assert.equal(patterns.privateWait('private fun waitForTag(tag: String) = 1\nprivate fun settle() {}'), 2);
});
