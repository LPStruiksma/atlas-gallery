// #780: a regression in a blank zoom-16 screenshot must not be repaired by
// checking only the semantic column state or by dropping the blank-frame guard.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = readFileSync(
  new URL('../../app/src/androidTest/java/nl/atlas/gallery/featuregrid/MonthYearOverviewEvidenceTest.kt', import.meta.url),
  'utf8',
);
const capture = readFileSync(
  new URL('../../app/src/androidTest/java/nl/atlas/gallery/CompactHeaderJourneyTest.kt', import.meta.url),
  'utf8',
);

test('all Month/Year and original captures require real painted synthetic tile pixels', () => {
  assert.match(source, /uiAutomation\.takeScreenshot\(\)/,
    'the probe must inspect the actual device frame');
  assert.match(source, /Color\.HSVToColor\(/,
    'the paint probe must compare with known synthetic photo colors');
  assert.match(source, /matchedIds\.size\s*>=\s*3/,
    'one lone UI accent is not proof of a painted thumbnail grid');
  assert.match(source, /saveUiEvidence\(filename\)\s*\{/,
    'each captured look must re-check pixels, not just the first look');
  assert.match(source, /savePaintedEvidence\("zoom-16-\$theme-7\.png"/);
  assert.match(source, /savePaintedEvidence\("zoom-16-\$theme-12-month\.png"/);
  assert.match(source, /savePaintedEvidence\("zoom-16-\$theme-28-year\.png"/);
  assert.match(source, /savePaintedEvidence\("zoom-16-\$theme-original\.png"/);
});

test('the real blank-frame guard and ten-second retry deadline stay enabled', () => {
  assert.match(capture, /val deadline = android\.os\.SystemClock\.uptimeMillis\(\) \+ 10_000L/);
  assert.match(capture, /val problem = uiEvidenceProblem\(bitmap\) \?: break/);
  assert.match(source, /10_000L - \(android\.os\.SystemClock\.uptimeMillis\(\) - startedAt\)/);
});
