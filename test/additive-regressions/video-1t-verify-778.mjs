// VIDEO-1T (#778 / PR #999): run the docs-only geometry verifier in exact-head CI.
// Python standard library only; no app code, network or credentials.
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const VERIFY = 'docs/design/video-580-target/verify.py';

function run(script) {
  return spawnSync('python3', [script, '--check'], { encoding: 'utf8', timeout: 120000 });
}

test('VIDEO-1T verify.py --check passes at this head', () => {
  const r = run(VERIFY);
  assert.equal(r.status, 0, r.stdout + '\n' + r.stderr);
  assert.match(r.stdout, /^PASS: 26 generated files byte-exact; 24 viewport-states;/m);
});

test('VIDEO-1T verifier rejects a tampered generated SVG (not vacuous)', () => {
  const root = mkdtempSync(join(tmpdir(), 'atlas-video1t-'));
  try {
    for (const dir of ['video-580-target', 'viewer-142-target']) {
      cpSync(join('docs/design', dir), join(root, 'docs/design', dir), { recursive: true });
    }
    for (const doc of ['docs/design/QUIET_FRAME.md', 'docs/OWNER_INTENT.md', 'docs/product/SAMSUNG_PARITY_SPEC.md']) {
      cpSync(doc, join(root, doc));
    }
    const svg = join(root, 'docs/design/video-580-target/svg/portrait-speed-sheet.svg');
    writeFileSync(svg, readFileSync(svg, 'utf8').replace('4×', '5×'));
    const r = run(join(root, VERIFY));
    assert.equal(r.status, 1, r.stdout + '\n' + r.stderr);
    assert.match(r.stdout, /FAIL: generated drift: svg\/portrait-speed-sheet\.svg/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
