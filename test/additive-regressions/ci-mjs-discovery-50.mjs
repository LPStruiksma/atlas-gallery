import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const workflow = readFileSync(
  new URL('../../.github/workflows/autodev-ci.yml', import.meta.url),
  'utf8',
);

// #50: the source-contract tests are only useful if the cheap static CI job
// discovers them on every PR head, including test-only changes.
test('static CI executes the complete additive .mjs glob', () => {
  assert.match(workflow, /^  static:\s*$/m);
  assert.match(workflow, /test\/additive-regressions\/\*\.mjs/);
  assert.match(workflow, /node --test "\$\{tests\[@\]\}"/);
});

test('static CI refuses an empty glob and exposes the file count', () => {
  assert.match(workflow, /shopt -s nullglob/);
  assert.match(workflow, /if \(\( \$\{#tests\[@\]\} == 0 \)\); then/);
  assert.match(workflow, /exit 1/);
  assert.match(workflow, /Discovered \$\{#tests\[@\]\} additive/);
});
