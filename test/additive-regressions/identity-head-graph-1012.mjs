import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

test('IDS-12B: typed graph and coordinate binding admission', () => {
  const result = spawnSync('python3', ['-m', 'unittest', 'discover',
    '-s', 'tools/ai_edit_server/tests', '-p', 'test_flux2_head.py', '-v'],
    { encoding: 'utf8', timeout: 45_000 });
  assert.equal(result.error, undefined, String(result.error));
  assert.equal(result.status, 0, (result.stdout || '') + '\n' + (result.stderr || ''));
});
