import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const runner = fileURLToPath(new URL('../../bin/autodev-codex-apply-runner.mjs', import.meta.url));
const repository = 'LPStruiksma/autodev';
const prNumber = 1220;
const commentId = 731;
const login = 'chatgpt-codex-connector[bot]';

// The original U23 run reached the runner but could not post its refusal receipt.
// Make that failure observable in the job log without loosening any patch guards.
test('runner logs the exact refusal when the receipt POST returns HTTP 403', () => {
  const dir = mkdtempSync(join(tmpdir(), 'codex-apply-log-'));
  try {
    const comment = {
      id: commentId, user: { login }, body: 'bad envelope',
      html_url: 'https://github.com/' + repository + '/pull/' + prNumber + '#issuecomment-' + commentId,
      issue_url: 'https://api.github.com/repos/' + repository + '/issues/' + prNumber,
    };
    const event = {
      action: 'created',
      issue: { number: prNumber, pull_request: {} },
      comment: { id: commentId, user: { login }, body: comment.body },
    };
    const pr = {
      state: 'open', draft: true, base: { ref: 'main' },
      head: { ref: 'chatgpt/codex-1220', repo: { full_name: repository }, sha: 'a'.repeat(40) },
    };
    writeFileSync(join(dir, 'event.json'), JSON.stringify(event));
    const mockGh = [
      '#!/usr/bin/env node',
      'const args = process.argv.slice(2);',
      'const path = args.find(x => x.startsWith("repos/")) || "";',
      'if (path.endsWith("/issues/comments/731")) {',
      '  process.stdout.write(' + JSON.stringify(JSON.stringify(comment)) + ');',
      '} else if (path.endsWith("/pulls/1220")) {',
      '  process.stdout.write(' + JSON.stringify(JSON.stringify(pr)) + ');',
      '} else if (path.includes("/issues/1220/comments?")) {',
      '  process.stdout.write("[[]]");',
      '} else if (args.includes("POST") && path.endsWith("/issues/1220/comments")) {',
      '  process.stderr.write("gh: Resource not accessible by integration (HTTP 403)\\n");',
      '  process.exitCode = 1;',
      '} else {',
      '  process.stderr.write("unmocked gh call: " + args.join(" ") + "\\n");',
      '  process.exitCode = 2;',
      '}',
    ].join('\n') + '\n';
    writeFileSync(join(dir, 'gh'), mockGh, { mode: 0o755 });
    const result = spawnSync(process.execPath, [runner], {
      encoding: 'utf8', timeout: 15000,
      env: { ...process.env, PATH: dir + ':' + process.env.PATH,
        GITHUB_REPOSITORY: repository, GITHUB_EVENT_PATH: join(dir, 'event.json'),
        GH_TOKEN: 'local-mock-not-a-secret' },
    });
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, /codex-apply outcome=REFUSED reason=expected one diff fence/);
    assert.match(result.stderr, /receipt failed: gh api failed: gh: Resource not accessible by integration \(HTTP 403\)/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('runner refuses a well-formed patch to a human-only path from trusted policy, before cloning', () => {
  const dir = mkdtempSync(join(tmpdir(), 'codex-apply-policy-'));
  try {
    const head = 'a'.repeat(40);
    const name = 'src/runtime/model-budget.mjs';
    const patch = ['From '+'b'.repeat(40)+' Mon Sep 17 00:00:00 2001','From: Codex <codex@example.invalid>',
      'Subject: [PATCH] edit','Parent: '+head,'','---',' x','','diff --git a/'+name+' b/'+name,
      'index 1111111..2222222 100644','--- a/'+name,'+++ b/'+name,'@@ -1 +1 @@','-a','+b','-- ','2.47.0',''].join('\n');
    const body = '\x60\x60\x60diff\n'+patch+'\n\x60\x60\x60';
    const comment = {
      id: commentId, user: { login }, body,
      html_url: 'https://github.com/' + repository + '/pull/' + prNumber + '#issuecomment-' + commentId,
      issue_url: 'https://api.github.com/repos/' + repository + '/issues/' + prNumber,
    };
    const event = { action: 'created', issue: { number: prNumber, pull_request: {} },
      comment: { id: commentId, user: { login }, body } };
    const pr = { state: 'open', draft: true, base: { ref: 'main' },
      head: { ref: 'chatgpt/codex-1220', repo: { full_name: repository }, sha: head } };
    writeFileSync(join(dir, 'event.json'), JSON.stringify(event));
    writeFileSync(join(dir, 'gh'), [
      '#!/usr/bin/env node',
      'const args = process.argv.slice(2);',
      'const path = args.find(x => x.startsWith("repos/")) || "";',
      'if (path.endsWith("/issues/comments/731")) process.stdout.write(' + JSON.stringify(JSON.stringify(comment)) + ');',
      'else if (path.endsWith("/pulls/1220")) process.stdout.write(' + JSON.stringify(JSON.stringify(pr)) + ');',
      'else if (path.includes("/issues/1220/comments?")) process.stdout.write("[[]]");',
      'else if (args.includes("POST")) process.stdout.write("{}");',
      'else { process.stderr.write("unmocked gh call: " + args.join(" ") + "\\n"); process.exitCode = 2; }',
    ].join('\n') + '\n', { mode: 0o755 });
    writeFileSync(join(dir, 'git'), '#!/bin/sh\necho "git must not run" >&2\nexit 9\n', { mode: 0o755 });
    const result = spawnSync(process.execPath, [runner], {
      encoding: 'utf8', timeout: 15000,
      env: { ...process.env, PATH: dir + ':' + process.env.PATH,
        GITHUB_REPOSITORY: repository, GITHUB_EVENT_PATH: join(dir, 'event.json'),
        GH_TOKEN: 'local-mock-not-a-secret' },
    });
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, /outcome=REFUSED reason=path refused \(human-only or hard-denied path\): src\/runtime\/model-budget\.mjs/);
    assert.doesNotMatch(result.stderr, /git must not run/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
