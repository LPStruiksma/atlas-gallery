// CAP-0 (#666): run the Python benchmark regressions on this exact PR head.
// The existing read-only AutoDev tenant check discovers every .mjs file here.
// This uses only standard-library Python and synthetic fixtures; no models,
// datasets, credentials, network services or Android runtimes are needed.
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../../', import.meta.url));

test('CAP-0 Python cold-launch, failure, capacity and fixture regressions', () => {
  const args = [
    '-m', 'unittest', 'discover',
    '-s', 'tools/caption_bench/tests',
    '-p', 'test_*.py', '-v',
  ];
  const result = spawnSync('python3', args, {
    cwd: repoRoot,
    encoding: 'utf8',
    timeout: 120000,
    maxBuffer: 4 * 1024 * 1024,
  });
  const output = (result.stdout ?? '') + (result.stderr ?? '');
  // Expose the actual unittest case count and verdict in the CI job log.
  process.stdout.write('CAP-0 Python unittest output:\n' + output);
  assert.ifError(result.error);
  assert.equal(result.status, 0, output || 'Python unittest exited without output');
  assert.match(output, /Ran [1-9][0-9]* tests? in /);
  assert.match(output, /\nOK\s*$/);
});
