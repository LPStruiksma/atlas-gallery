// AIE-9: run the self-contained Python suite and mock pipeline in existing static CI.
// Uses no credentials, external provider, image download, or paid service.
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

function python(args) {
  const run = spawnSync('python3', args, {
    encoding: 'utf8',
    timeout: 240000,
  });
  assert.equal(run.status, 0,
    'python3 ' + args.join(' ') + '\n' + run.stdout + '\n' + run.stderr);
}

test('AIE-9 synthetic corpus, scorer, refusal and blind vote regressions', () => {
  // unittest.TestCase suite is also discoverable with pytest -q.
  python(['-m', 'unittest', 'discover', '-s', 'tools/ai_edit_bench', '-p', 'test_*.py']);
});

test('AIE-9 dry-run writes inspectable mock-only scorecards and reviewer UI', () => {
  const out = mkdtempSync(join(tmpdir(), 'atlas-aie9-'));
  try {
    python(['-m', 'tools.ai_edit_bench', '--dry-run', '--out', out]);
    const report = JSON.parse(readFileSync(join(out, 'scorecards.json'), 'utf8'));
    assert.equal(report.mode, 'synthetic-mock');
    assert.equal(report.corpus_size, 156);
    assert.equal(report.quality_scorecard['mock-alpha'].eligible_completed, 128);
    assert.equal(report.restriction_scorecard['mock-alpha'].lawful_sensitive.refusals, 3);
    assert.equal(report.restriction_scorecard['mock-alpha'].lawful_sensitive.degraded, 1);
    assert.ok(existsSync(join(out, 'review.html')));
    assert.match(readFileSync(join(out, 'report.md'), 'utf8'),
      /SIMULATED — NOT PROVIDER EVIDENCE/);
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});
