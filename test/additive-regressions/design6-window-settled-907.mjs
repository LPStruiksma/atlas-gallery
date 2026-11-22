import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const path = new URL(
  '../../app/src/androidTest/java/nl/atlas/gallery/Design6QuietStatesEvidenceTest.kt',
  import.meta.url,
);
const source = readFileSync(path, 'utf8');

test('Design6 inset regression measures correlated system and Compose geometry', () => {
  for (const evidence of [
    'rootWindowInsets',
    'PlatformWindowInsets.Type.statusBars()',
    'PlatformWindowInsets.Type.navigationBars()',
    'PlatformWindowInsets.Type.systemBars()',
    'PlatformWindowInsets.Type.displayCutout()',
    'WindowInsets.safeDrawing',
    'WindowInsets.statusBars',
    'WindowInsets.navigationBars',
    'contentBoundsDp',
    'layoutAtUptimeMs',
    'ATLAS_DESIGN6_907',
  ]) {
    assert.ok(source.includes(evidence), `missing diagnostic evidence: ${evidence}`);
  }
});

test('Design6 inset comparison waits for stable display frames, not elapsed quiet time', () => {
  assert.ok(source.includes('captureInsetEvidence("before-theme-$mode")'));
  assert.ok(source.includes('captureInsetEvidence("after-theme-before-media-$mode")'));
  assert.ok(source.includes('captureInsetEvidence("after-media-$mode")'));
  assert.ok(source.includes('awaitWindowSettled()'));
  assert.ok(source.includes('sampleOnFrames(frames = 2)'));
  assert.ok(source.includes('val tolerance = 1f'));
  assert.ok(!source.includes('450L'));
  assert.ok(!source.includes('settledBounds'));
});
