// EDIT-6 (#724): static source contracts run in cheap PR CI, in addition to JVM behavior tests.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(path, 'utf8');

test('green-heavy auto tint has the sign that attenuates green in ColourOps', () => {
  const adjust = read('app/src/main/java/nl/atlas/gallery/featureeditor/auto/AutoAdjust.kt');
  const renderer = read('app/src/main/java/nl/atlas/gallery/featureeditor/render/ColourOps.kt');
  assert.match(renderer, /c\[1\] \*= 2f\.pow\(-WB_STOPS \* v\[6\]\)/);
  assert.match(adjust, /val tint = \(\(greenMean - \(redMean \+ blueMean\) \/ 2\.0\) \* 1\.5\)\.deadZone\(0\.012\)/);
});

test('straighten vote uses ceiling stride so dense edges remain bounded', () => {
  const straighten = read('app/src/main/java/nl/atlas/gallery/featureeditor/auto/AutoStraighten.kt');
  assert.match(straighten, /val stride = voteSampleStride\(points\.size\)/);
  assert.match(straighten, /internal fun voteSampleStride\(edgeCount: Int\): Int =\s*\(\(edgeCount \+ MAX_POINTS - 1\) \/ MAX_POINTS\)\.coerceAtLeast\(1\)/);
});
