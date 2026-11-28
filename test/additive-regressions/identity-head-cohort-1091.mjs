// IDS-12F (#1091): run the cohort runner's offline suite inside the existing additive-regression job,
// so exact-head CI proves the cohort freeze, resume, record format and sheet metadata checks.
// Standard library only; no model inference in CI (the real run happens off Actions).
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
import test from 'node:test';

test('python3 -m unittest tools/identity_bench/head/test_run_cohort.py -v', () => {
  const r = spawnSync('python3', ['-m', 'unittest', 'tools/identity_bench/head/test_run_cohort.py', '-v'], { encoding: 'utf8' });
  assert.equal(r.status, 0, `${r.stdout}\n${r.stderr}`);
  assert.match(r.stderr, /\nOK\s*$/);
  assert.doesNotMatch(r.stderr, /NO TESTS RAN|skipped/i);
});
