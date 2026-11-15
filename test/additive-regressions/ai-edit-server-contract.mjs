// Existing exact-head CI runs all additive .mjs cases. Exercise ONLY the CPU fake
// backend with Python's stdlib unittest runner; tests also collect under pytest.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

test('AIE-8a: synthetic HTTP protocol conformance with fake backend', () => {
  const result = spawnSync('python3', [
    '-m', 'unittest', 'discover',
    '-s', 'tools/ai_edit_server/tests',
    '-p', 'test_*.py',
    '-v',
  ], { encoding: 'utf8', timeout: 45_000 });
  assert.equal(result.error, undefined, String(result.error));
  assert.equal(result.status, 0, (result.stdout || '') + '\n' + (result.stderr || ''));
});
