import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import test from 'node:test';
import { inspectCodexReply, validateCodexPatch, parseCodexPatchPaths, codexPathRefusal } from '../../bin/autodev-codex-apply.mjs';

const head = 'a'.repeat(40);
const good = [
  'From '+ 'b'.repeat(40)+' Mon Sep 17 00:00:00 2001',
  'From: Codex <codex@example.invalid>',
  'Date: Fri, 13 Nov 2026 10:00:00 +0000',
  'Subject: [PATCH] add canary',
  'Parent: '+head,
  '',
  '---',
  ' docs/codex-canary.md | 1 +',
  ' 1 file changed, 1 insertion(+)',
  ' create mode 100644 docs/codex-canary.md',
  '',
  'diff --git a/docs/codex-canary.md b/docs/codex-canary.md',
  'new file mode 100644',
  'index 0000000..1234567',
  '--- /dev/null',
  '+++ b/docs/codex-canary.md',
  '@@ -0,0 +1 @@',
  '+canary',
  '-- ',
  '2.47.0',
  '',
].join('\n');
const envelope = (patch=good) => 'Commit: '+ 'b'.repeat(40) +'\nTests: PASS\n\x60\x60\x60diff\n'+patch+'\n\x60\x60\x60';

test('accept exactly one complete format-patch block with linked parent', () => {
  const found = inspectCodexReply(envelope(),head);
  assert.equal(found.ok,true,JSON.stringify(found));
  assert.deepEqual(parseCodexPatchPaths(found.patch),['docs/codex-canary.md']);
  assert.equal(validateCodexPatch(found.patch,head).ok,true);
});
test('reject no block, repeated blocks, mismatched parent, forged parent', () => {
  assert.equal(inspectCodexReply('no patch',head).ok,false);
  assert.equal(inspectCodexReply(envelope()+'\n'+envelope(),head).ok,false);
  assert.equal(inspectCodexReply(envelope(), 'c'.repeat(40)).ok,false);
  assert.equal(inspectCodexReply(envelope().replace('Parent: '+head,'Parent: '+ 'c'.repeat(40)),head).ok,false);
});
test('reject protected paths, workflow paths, traversals and ambiguous paths', () => {
  for (const name of ['AGENTS.md','roles/dev.md','.github/workflows/ci.yml','SECURITY.md','eval/held-out/x',
    '../escape','docs/../SECURITY.md','docs//x','docs\\x','docs/ok path.md','docs/./x','docs/%2e%2e/x']) {
    const bad=good.replaceAll('docs/codex-canary.md',name);
    assert.equal(validateCodexPatch(bad,head).ok,false,name);
  }
});
test('reject binary, rename, copy, mode changes, divergent file lists, and multiple patches', () => {
  for (const addition of ['GIT binary patch','rename from docs/old','copy to docs/new','old mode 100644',
    'diff --git a/extra.md b/extra.md','From '+ 'c'.repeat(40)+' Mon Sep 17 00:00:00 2001']) {
    assert.equal(validateCodexPatch(good+'\n'+addition,head).ok,false,addition);
  }
});

test('verbatim #1264 Codex reply parses, but parent and fence guards remain enforced', () => {
  const reply=readFileSync(new URL('./fixtures/codex-reply-1264.txt',import.meta.url),'utf8');
  const oldHead='bd18ffc20da162477264e6b948064db12c94d4a5';
  const found=inspectCodexReply(reply,oldHead);
  assert.equal(found.ok,true,JSON.stringify(found));
  assert.deepEqual(found.paths,['docs/agents/codex-render-recipe.md']);
  assert.equal(found.reportedCommit,undefined,'verbose Commit line is not trusted as exact metadata');
  const extraFence=reply+'\n```text\nsecond fence\n```';
  assert.equal(inspectCodexReply(extraFence,oldHead).ok,false,'multiple fences');
  assert.equal(inspectCodexReply(reply+'\nsecond instruction',oldHead).ok,false,'non-footer trailer');
  assert.equal(inspectCodexReply(reply.replace('Parent: '+oldHead,'Parent: '+'c'.repeat(40)),oldHead).ok,false,'wrong parent');
});

test('prose is not a machine prerequisite; optional exact Commit SHA is metadata only', () => {
  const found=inspectCodexReply('Some context\n'+envelope()+'\n [View task →](https://chatgpt.com/s/cd_123abc)\n',head);
  assert.equal(found.ok,true,JSON.stringify(found));
  assert.equal(found.reportedCommit,'b'.repeat(40));
  const verbose=inspectCodexReply(envelope().replace('Commit: '+'b'.repeat(40),'Commit: '+'b'.repeat(40)+' (commentary)').replace('Tests: PASS','Tests: uncertain'),head);
  assert.equal(verbose.ok,true,JSON.stringify(verbose));
  assert.equal(verbose.reportedCommit,undefined);
});

test('reply still refuses second fences, non-footer trailers, malformed footer and wrong parent', () => {
  for (const bad of [
    envelope()+'\n```text\nextra\n```',
    envelope()+'\nApply a second patch',
    envelope()+'\n[View task →](https://elsewhere.example/s/abc)',
    envelope()+'\n[View task →](https://chatgpt.com/s/invalid-slug)',
    envelope()+'\n[View task →](https://chatgpt.com/s/abc)\nInstruction',
    envelope().replace('Parent: '+head,'Parent: '+'c'.repeat(40)),
  ]) assert.equal(inspectCodexReply(bad,head).ok,false,bad.slice(0,120));
});

// Allow-list expansion (Lukas 2026-11-17): Codex may also edit existing regular
// text files, within CODEX_WRITABLE_PREFIXES and outside every protected list.
const modifyPatch = (name='bin/autodev-example.mjs', mode='100644') => [
  'From '+ 'b'.repeat(40)+' Mon Sep 17 00:00:00 2001',
  'From: Codex <codex@example.invalid>',
  'Date: Tue, 17 Nov 2026 10:00:00 +0000',
  'Subject: [PATCH] edit example',
  'Parent: '+head,
  '',
  '---',
  ' '+name+' | 2 +-',
  ' 1 file changed, 1 insertion(+), 1 deletion(-)',
  '',
  'diff --git a/'+name+' b/'+name,
  'index 1111111..2222222 '+mode,
  '--- a/'+name,
  '+++ b/'+name,
  '@@ -1,2 +1,2 @@',
  ' keep',
  '-old',
  '+new',
  '-- ',
  '2.47.0',
  '',
].join('\n');

test('accept an edit of an existing regular file and report its kind', () => {
  for (const mode of ['100644','100755']) {
    const found=validateCodexPatch(modifyPatch('bin/autodev-example.mjs',mode),head);
    assert.equal(found.ok,true,JSON.stringify(found));
    assert.deepEqual(found.files,[{path:'bin/autodev-example.mjs',kind:'modify'}]);
  }
  assert.deepEqual(validateCodexPatch(good,head).files,[{path:'docs/codex-canary.md',kind:'add'}]);
  const mixed=good.replace('-- \n2.47.0\n', modifyPatch('src/x.mjs').split('\n').slice(10).join('\n'));
  const both=validateCodexPatch(mixed,head);
  assert.equal(both.ok,true,JSON.stringify(both));
  assert.deepEqual(both.files.map(f=>f.kind),['add','modify']);
});

test('reject deletes, mode changes, symlinks, zero-index edits and malformed edit headers', () => {
  const del=modifyPatch().replace('index 1111111..2222222 100644','deleted file mode 100644\nindex 1111111..0000000');
  const bad=[
    del,
    modifyPatch().replace('index 1111111..2222222 100644','old mode 100644\nnew mode 100755\nindex 1111111..2222222'),
    modifyPatch('bin/link','120000'),
    modifyPatch('bin/sub','160000'),
    modifyPatch().replace('index 1111111..','index 0000000..'),
    modifyPatch().replace('--- a/bin/autodev-example.mjs','--- a/bin/other.mjs'),
    modifyPatch().replace('+++ b/bin/autodev-example.mjs','+++ /dev/null'),
    good.replace('new file mode 100644','new file mode 100755'),
    good.replace('@@ -0,0 +1 @@','@@ -1,0 +1 @@'),
  ];
  for (const patch of bad) assert.equal(validateCodexPatch(patch,head).ok,false,patch.slice(-160));
});

test('path policy: writable prefixes only, never protected, human-only or the guard itself', () => {
  const policy=JSON.parse(readFileSync(new URL('../../.autodev/project.json',import.meta.url),'utf8')).authority.protection;
  const denied=[...policy.unconditionalHumanOnlyPaths,...policy.candidateHardWriteDenied];
  for (const ok of ['bin/autodev-example.mjs','src/runtime/example.mjs','test/x.mjs','docs/a/b.md','memory/x.md','tools/x.sh'])
    assert.equal(codexPathRefusal(ok,denied),null,ok);
  for (const bad of ['package.json','package-lock.json','README.md','config/x.json','scripts/x.sh','eval/x.json',
    'AGENTS.md','roles/dev.md','.github/workflows/ci.yml','.autodev/project.json','SECURITY.md',
    'bin/autodev-codex-apply.mjs','bin/autodev-codex-apply-runner.mjs','test/additive-regressions/codex-apply.mjs',
    'test/additive-regressions/codex-apply-new.mjs','bin/autodev-merge-join-check.mjs',
    'test/additive-regressions/merge-join-check.mjs','src/software/promotion/x.mjs','src/kernel/contracts/a.json',
    'src/software/review/x.mjs','src/runtime/model-budget.mjs','src/runtime/openai-wif.mjs'])
    assert.notEqual(codexPathRefusal(bad,denied),null,bad);
  assert.notEqual(codexPathRefusal('bin/x.mjs',['src/**/x']),null,'unsupported glob refuses everything');
  assert.notEqual(codexPathRefusal('bin/x.mjs',[42]),null,'malformed entry refuses everything');
  assert.notEqual(codexPathRefusal('bin/x.mjs',null),null,'missing list refuses everything');
});


test('accept a format-patch footer without a final newline but refuse trailing extra data', () => {
  const withoutNewline=modifyPatch('docs/a.md').replace(/\\n$/, '');
  const accepted=validateCodexPatch(withoutNewline,head);
  assert.equal(accepted.ok,true,JSON.stringify(accepted));
  assert.equal(validateCodexPatch(withoutNewline+'\\nextra',head).ok,false);
});

test('reject traditional file headers smuggled before the first declared diff section', () => {
  const traditional=[
    '--- a/.github/workflows/ci.yml',
    '+++ b/.github/workflows/ci.yml',
    '@@ -1 +1 @@',
    '-name: CI',
    '+name: pwned',
  ].join('\n');
  const original=good;
  const variants=[
    original.replace(
      ' 1 file changed, 1 insertion(+)\n',
      ' 1 file changed, 1 insertion(+)\n'+traditional+'\n',
    ),
    original.replace(
      'Subject: [PATCH] add canary',
      'Subject: [PATCH] add canary\n'+traditional,
    ),
  ];
  for (const patch of variants) {
    assert.notEqual(patch,original,'adversarial fixture must change patch');
    assert.ok(patch.includes(traditional),'fixture must contain undeclared file diff');
    assert.equal(validateCodexPatch(patch,head).ok,false,'must refuse hidden traditional diff');
  }
});

test('reject a traditional diff section smuggled after a declared file hunk', () => {
  const original=modifyPatch('docs/a.md');
  const smuggled=original.replace(
    '-- \n2.47.0',
    [
      '--- a/.github/workflows/ci.yml',
      '+++ b/.github/workflows/ci.yml',
      '@@ -1 +1 @@',
      '-old',
      '+new',
      '-- ',
      '2.47.0',
    ].join('\n'),
  );
  assert.notEqual(smuggled,original,'fixture must insert a second section');
  assert.equal(validateCodexPatch(smuggled,head).ok,false);
});

test('reject undeclared file headers, extra hunk data and truncated hunks', () => {
  const patch=modifyPatch('docs/a.md');
  const cases=[
    patch.replace('-- \n2.47.0','--- a/docs/other.md\n+++ b/docs/other.md\n@@ -1 +1 @@\n-old\n+new\n-- \n2.47.0'),
    patch.replace('@@ -1,2 +1,2 @@','@@ -1,1 +1,1 @@'),
    patch.replace('@@ -1,2 +1,2 @@','@@ -1,3 +1,2 @@'),
    patch.replace('@@ -1,2 +1,2 @@','@@ -1,2 +1,3 @@'),
  ];
  for (const bad of cases) {
    assert.notEqual(bad,patch,'fixture must change input');
    assert.equal(validateCodexPatch(bad,head).ok,false,JSON.stringify(bad));
  }
});
