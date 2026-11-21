// IDS-12A (#772): run the two required Python suites inside the existing additive-regression job,
// so exact-head CI proves them. Offline, standard library only; no model inference.
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
import test from 'node:test';

for (const mod of ['tools.identity_bench.head.test_quality', 'tools.identity_bench.head.test_probe']) {
  test(`python3 -m unittest ${mod}`, () => {
    const r = spawnSync('python3', ['-m', 'unittest', mod], { encoding: 'utf8' });
    assert.equal(r.status, 0, `${r.stdout}\n${r.stderr}`);
    assert.match(r.stderr, /\nOK\s*$/);
    assert.doesNotMatch(r.stderr, /NO TESTS RAN|skipped/i);
  });
}
