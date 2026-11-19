// The emulator stress switch (#490, docs/testing/EMULATOR_TESTS.md "Stress a
// test") is opt-in: a head that commits scripts/ci-opt-in/stress-classes runs
// only the named tests and skips the full suite. That file must never be on
// main or on a branch under review, so this job fails while it exists.
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const script = readFileSync(join(root, 'scripts', 'ci_android_upgrade_emulator.sh'), 'utf8');

test('no stress list is committed on a branch that can merge', () => {
  assert.equal(existsSync(join(root, 'scripts', 'ci-opt-in', 'stress-classes')), false,
    'scripts/ci-opt-in/stress-classes is for throwaway proof branches only: remove it');
});

test('the stress run is reached only through the opt-in file and never passes as the suite', () => {
  const start = script.indexOf('if [[ -f scripts/ci-opt-in/stress-classes');
  assert.notEqual(start, -1, 'the stress block is guarded by the opt-in file');
  const suite = script.indexOf('connectedDebugAndroidTest', start);
  const block = script.slice(start, suite === -1 ? undefined : suite);
  assert.match(block, /exit "\$stress_fail"/, 'a stress head exits with its own result');
  assert.match(block, /\^\[A-Za-z0-9_\.\]\+\(#\[A-Za-z0-9_\]\+\)\?\$/,
    'targets are checked against a class or class#method pattern before use');
  assert.ok(script.indexOf('connectedDebugAndroidTest') !== -1 && suite > start,
    'the full suite still follows the opt-in block');
});
