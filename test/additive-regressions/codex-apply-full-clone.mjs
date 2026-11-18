import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Regression for #1332: the apply runner passes its HTTP authorization as a
// one-command git -c option. A partial clone can lazily fetch missing blobs
// during checkout, am or diff-tree without that authorization.
test('Codex apply clones complete blobs instead of using a promisor remote', () => {
  const runner = readFileSync(fileURLToPath(new URL('../../bin/autodev-codex-apply-runner.mjs', import.meta.url)), 'utf8');
  const clone = runner.match(/exec\('git',\[([\s\S]*?)\]\);/g)?.find(command => command.includes("'clone'"));
  assert.ok(clone, 'runner must invoke git clone');
  assert.match(clone, /'clone','--no-tags'/, 'clone must keep tags disabled');
  assert.doesNotMatch(clone, /--filter(?:=|')|--filter\b|--depth\b|--single-branch\b/, 'clone must not defer blob retrieval');
});
