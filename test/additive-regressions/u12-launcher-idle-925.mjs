// U12 / #925: launcher transitions around the cold-start splash must be serialized.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const source = readFileSync(join(root,
  'app/src/androidTest/java/nl/atlas/gallery/LapisLauncherSplashEvidenceTest.kt'), 'utf8');
const start = source.indexOf('abstract class LapisColdStartSplashEvidence');
const end = source.indexOf('/**\n * The installed icon in the emulator', start);
assert.ok(start >= 0 && end > start, 'cold-start evidence class remains present');
const coldStart = source.slice(start, end);

test('splash evidence performs one actual cold start and waits for system transitions', () => {
  assert.match(coldStart, /am force-stop \$\{context\.packageName\}/,
    'stop the target app before measuring its cold-start splash');
  assert.match(coldStart, /waitForIdle\(500L,\s*10_000L\)/,
    'wait for accessibility/system-window idle instead of immediately relaunching');
  assert.match(coldStart, /private fun finishMainActivities\(\)[\s\S]*?awaitSystemIdle\(/,
    'serialize the system transition after the activity has finished');
  assert.match(coldStart, /val \(captured, note\) = try \{\s*launchAndCatchSplash\(\)/,
    'capture one splash from one launch');
  assert.doesNotMatch(coldStart, /attempts\s*<\s*3|while\s*\(captured\s*==\s*null\)/,
    'a missing splash must fail visibly, not be retried with more launches');
});
