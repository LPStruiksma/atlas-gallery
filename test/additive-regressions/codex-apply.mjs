import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import test from 'node:test';
import { inspectCodexReply, validateCodexPatch, parseCodexPatchPaths, codexPathRefusal } from '../../scripts/ci/codex-apply.mjs';

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
  // #522 port: this repository's writable prefixes and its own human-only paths.
  for (const ok of ['app/src/main/java/nl/atlas/gallery/X.kt','app/src/test/java/X.kt','test/x.mjs',
    'docs/a/b.md','tools/x.py'])
    assert.equal(codexPathRefusal(ok,denied),null,ok);
  for (const bad of ['package.json','README.md','build.gradle.kts','settings.gradle.kts','gradle.properties',
    'app/build.gradle.kts','app/src/main/AndroidManifest.xml','app/proguard-rules.pro','baselineprofile/x.kt',
    'scripts/ci_android_upgrade_emulator.sh','scripts/p0_termux_owner_sign.sh','scripts/ci/codex-apply.mjs',
    'scripts/ci/codex-apply-runner.mjs','tools/v4/private/x.json','signing/x','private/x','eval/x.json',
    'AGENTS.md','.github/workflows/build-apk.yml','.autodev/project.json','SECURITY.md','CLAUDE.md',
    'test/additive-regressions/codex-apply.mjs','test/additive-regressions/codex-apply-new.mjs'])
    assert.notEqual(codexPathRefusal(bad,denied),null,bad);
  assert.notEqual(codexPathRefusal('docs/x.md',['src/**/x']),null,'unsupported glob refuses everything');
  assert.equal(codexPathRefusal('docs/x.md',['.env.*']),null,'#522: a dot-file glob is covered by the dot-segment rule');
  assert.notEqual(codexPathRefusal('docs/x.md',['docs/*.md']),null,'#522: a non-dot glob still refuses everything');
  assert.notEqual(codexPathRefusal('.env.local',['.env.*']),null,'#522: dot files stay refused');
  assert.notEqual(codexPathRefusal('docs/x.md',[42]),null,'malformed entry refuses everything');
  assert.notEqual(codexPathRefusal('docs/x.md',null),null,'missing list refuses everything');
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

// Design-evidence images (#1931). Fixtures are tiny synthetic files turned into real
// `git format-patch --binary` output, so the parser sees what git emits.
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { createHash, randomBytes } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { verifyEvidenceManifest, EVIDENCE_MAX_FILE_BYTES } from '../../scripts/ci/codex-apply.mjs';

const PNG_MAGIC = Buffer.from([0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a]);
const png = (fill=7,len=64) => Buffer.concat([PNG_MAGIC,Buffer.from([0]),Buffer.alloc(len,fill)]); // the NUL makes git treat it as binary, as in real images
const webp = (len=64) => Buffer.concat([Buffer.from('RIFF'),Buffer.from([len,0,0,0]),Buffer.from('WEBP'),Buffer.alloc(len,3)]);
const sha = (b) => createHash('sha256').update(b).digest('hex');
const git = (cwd,...args) => {
  const r=spawnSync('git',['-C',cwd,'-c','user.name=t','-c','user.email=t@example.invalid','-c','commit.gpgsign=false',...args],{encoding:'buffer',maxBuffer:64*1024*1024});
  if(r.status!==0) throw Error('git '+args[0]+': '+r.stderr);
  return r.stdout;
};
const manifestFor = (entries) => JSON.stringify({files:Object.fromEntries(entries.map(([n,b])=>[n,{sha256:sha(b)}]))},null,2)+'\n';
// base: files committed first; change: files written for the commit that becomes the patch.
function makePatch({base={},change,remove=[],rename}) {
  const dir=mkdtempSync(join(tmpdir(),'codex-evidence-'));
  try {
    git(dir,'init','-q');
    writeFileSync(join(dir,'seed.txt'),'seed\n');
    for (const [n,c] of Object.entries(base)) { mkdirSync(dirname(join(dir,n)),{recursive:true}); writeFileSync(join(dir,n),c); }
    git(dir,'add','-A'); git(dir,'commit','-q','-m','base');
    const parent=git(dir,'rev-parse','HEAD').toString().trim();
    for (const [n,c] of Object.entries(change||{})) { mkdirSync(dirname(join(dir,n)),{recursive:true}); writeFileSync(join(dir,n),c); }
    for (const n of remove) rmSync(join(dir,n));
    if (rename) git(dir,'mv',rename[0],rename[1]);
    git(dir,'add','-A'); git(dir,'commit','-q','-m','add canary');
    const raw=git(dir,'format-patch','-1','--stdout','--binary').toString('latin1');
    const lines=raw.split('\n');
    lines.splice(lines.findIndex(l=>l.startsWith('Subject: '))+1,0,'Parent: '+parent);
    return {patch:lines.join('\n').replace(/\n$/,''),parent,dir};
  } catch(e) { rmSync(dir,{recursive:true,force:true}); throw e; }
}
const EV=['docs/design/'];
const check=(m,opts={evidencePrefixes:EV}) => validateCodexPatch(m.patch,m.parent,opts);

test('accept a PNG and a WebP with matching manifest SHA, and git am applies it identically', () => {
  const a=png(1), b=webp();
  const m=makePatch({change:{
    'docs/design/saved-1.png':a,'docs/design/saved-2.webp':b,
    'docs/design/manifest.json':manifestFor([['saved-1.png',a],['saved-2.webp',b]])}});
  try {
    const found=check(m);
    assert.equal(found.ok,true,JSON.stringify(found));
    const bins=found.files.filter(f=>f.binary);
    assert.deepEqual(bins.map(f=>[f.path,f.kind,f.sha256]),[['docs/design/saved-1.png','add',sha(a)],['docs/design/saved-2.webp','add',sha(b)]]);
    assert.equal(inspectCodexReply('```diff\n'+m.patch+'\n```',m.parent,{evidencePrefixes:EV}).ok,true);
    // Real git applies the exact validated text to the parent and yields the validated bytes.
    git(m.dir,'reset','-q','--hard','HEAD^');
    writeFileSync(join(m.dir,'p.patch'),m.patch+'\n','latin1');
    git(m.dir,'am','--no-3way',join(m.dir,'p.patch'));
    assert.equal(sha(git(m.dir,'cat-file','blob','HEAD:docs/design/saved-1.png')),sha(a));
  } finally { rmSync(m.dir,{recursive:true,force:true}); }
});

test('accept editing an existing PNG when the edited manifest is in the patch', () => {
  const a=png(1), a2=png(2);
  const m=makePatch({base:{'docs/design/x.png':a,'docs/design/manifest.json':manifestFor([['x.png',a]])},
    change:{'docs/design/x.png':a2,'docs/design/manifest.json':manifestFor([['x.png',a2]])}});
  try {
    const found=check(m);
    assert.equal(found.ok,true,JSON.stringify(found));
    assert.equal(found.files.find(f=>f.binary).kind,'modify');
  } finally { rmSync(m.dir,{recursive:true,force:true}); }
});

test('binary data is refused by default and outside the allowlisted prefix', () => {
  const a=png();
  const m=makePatch({change:{'docs/design/s.png':a,'docs/design/manifest.json':manifestFor([['s.png',a]])}});
  const o=makePatch({change:{'docs/other/s.png':a,'docs/other/manifest.json':manifestFor([['s.png',a]])}});
  try {
    assert.equal(check(m,{}).ok,false,'no prefixes configured');
    assert.equal(check(m,{evidencePrefixes:[]}).ok,false);
    assert.equal(check(m,{evidencePrefixes:['docs/design/**']}).ok,true,'/** form is accepted');
    assert.equal(check(o).ok,false,'path outside prefix');
    assert.equal(check(m,{evidencePrefixes:['docs/**','*']}).ok,true,'broad valid prefix still works');
    assert.equal(check(m,{evidencePrefixes:['*','docs/des*']}).ok,false,'globs are dropped');
  } finally { rmSync(m.dir,{recursive:true,force:true}); rmSync(o.dir,{recursive:true,force:true}); }
});

test('refuse wrong extension, bad magic, and files over the size cap', () => {
  const a=png();
  const cases={
    gif:makePatch({change:{'docs/design/s.gif':a,'docs/design/manifest.json':manifestFor([['s.gif',a]])}}),
    jpg:makePatch({change:{'docs/design/s.jpg':a,'docs/design/manifest.json':manifestFor([['s.jpg',a]])}}),
    upper:makePatch({change:{'docs/design/s.PNG':a,'docs/design/manifest.json':manifestFor([['s.PNG',a]])}}),
    badMagic:(()=>{const b=Buffer.alloc(80,0);return makePatch({change:{'docs/design/s.png':b,'docs/design/manifest.json':manifestFor([['s.png',b]])}});})(),
    pngAsWebp:makePatch({change:{'docs/design/s.webp':a,'docs/design/manifest.json':manifestFor([['s.webp',a]])}}),
    // Compressible, so the base85 text stays small while the declared size exceeds the cap.
    tooBig:(()=>{const b=Buffer.concat([PNG_MAGIC,Buffer.alloc(EVIDENCE_MAX_FILE_BYTES,0)]);return makePatch({change:{'docs/design/s.png':b,'docs/design/manifest.json':manifestFor([['s.png',b]])}});})(),
  };
  try {
    for (const [name,m] of Object.entries(cases)) assert.equal(check(m).ok,false,name);
    const just=Buffer.concat([PNG_MAGIC,Buffer.alloc(EVIDENCE_MAX_FILE_BYTES-8,0)]);
    const okM=makePatch({change:{'docs/design/s.png':just,'docs/design/manifest.json':manifestFor([['s.png',just]])}});
    try { assert.equal(check(okM).ok,true,'exactly at the cap is accepted'); } finally { rmSync(okM.dir,{recursive:true,force:true}); }
  } finally { for (const m of Object.values(cases)) rmSync(m.dir,{recursive:true,force:true}); }
});

test('refuse a missing manifest, a missing entry and a mismatched SHA-256', () => {
  const a=png(1), other=png(2);
  const cases={
    none:makePatch({change:{'docs/design/s.png':a}}),
    wrongDir:makePatch({change:{'docs/design/s.png':a,'docs/manifest.json':manifestFor([['s.png',a]])}}),
    noEntry:makePatch({change:{'docs/design/s.png':a,'docs/design/manifest.json':manifestFor([['t.png',a]])}}),
    mismatch:makePatch({change:{'docs/design/s.png':a,'docs/design/manifest.json':manifestFor([['s.png',other]])}}),
    badJson:makePatch({change:{'docs/design/s.png':a,'docs/design/manifest.json':'{nope'}}),
  };
  try { for (const [name,m] of Object.entries(cases)) assert.equal(check(m).ok,false,name); }
  finally { for (const m of Object.values(cases)) rmSync(m.dir,{recursive:true,force:true}); }
  // An edited manifest cannot be rebuilt from the patch, so the runner re-checks the committed blob.
  assert.equal(verifyEvidenceManifest(manifestFor([['s.png',a]]),[{name:'s.png',sha256:sha(a)}]),null);
  assert.notEqual(verifyEvidenceManifest(manifestFor([['s.png',other]]),[{name:'s.png',sha256:sha(a)}]),null);
  assert.notEqual(verifyEvidenceManifest('{"files":[]}',[{name:'s.png',sha256:sha(a)}]),null);
  assert.notEqual(verifyEvidenceManifest('{"files":{"s.png":{"sha256":"nothex"}}}',[{name:'s.png',sha256:sha(a)}]),null);
});

test('binary deletes, renames, and a mixed patch with another forbidden operation stay refused', () => {
  const a=png(1);
  const base={'docs/design/s.png':a,'docs/design/manifest.json':manifestFor([['s.png',a]])};
  const del=makePatch({base,remove:['docs/design/s.png']});
  const ren=makePatch({base,rename:['docs/design/s.png','docs/design/t.png']});
  const good2=makePatch({change:{'docs/design/n.png':a,'docs/design/manifest.json':manifestFor([['n.png',a]])}});
  try {
    assert.equal(check(del).ok,false,'delete');
    assert.equal(check(ren).ok,false,'rename');
    // Splice a forbidden mode-change section into an otherwise valid image patch.
    const mixed=good2.patch.replace(/\n-- \n/,'\ndiff --git a/docs/x.md b/docs/x.md\nold mode 100644\nnew mode 100755\n-- \n');
    assert.notEqual(mixed,good2.patch);
    assert.equal(check({patch:mixed,parent:good2.parent}).ok,false,'mixed with mode change');
    // A symlink-mode or 755 image is refused.
    assert.equal(check({patch:good2.patch.replace('new file mode 100644\nindex 0000000..','new file mode 100755\nindex 0000000..'),parent:good2.parent}).ok,false,'mode 755 binary');
    // Tampered payload: flip a data character so decoding or the declared length fails.
    const lines=good2.patch.split('\n'); const at=lines.findIndex(l=>l==='GIT binary patch');
    lines[at+2]=lines[at+2].slice(0,-1)+(lines[at+2].endsWith('0')?'1':'0');
    assert.equal(check({patch:lines.join('\n'),parent:good2.parent}).ok,false,'corrupt payload');
    assert.equal(check({patch:good2.patch.replace('GIT binary patch\nliteral','GIT binary patch\ndelta'),parent:good2.parent}).ok,false,'forward delta');
    assert.equal(check({patch:good2.patch.replace(/diff --git a\/docs\/design\/n.png b\/docs\/design\/n.png\n/,'diff --git a/docs/design/n.png b/docs/design/n.png\nBinary files /dev/null and b/docs/design/n.png differ\n'),parent:good2.parent}).ok,false,'Binary files line');
  } finally { for (const m of [del,ren,good2]) rmSync(m.dir,{recursive:true,force:true}); }
});

test('accept an incompressible multi-line PNG payload and refuse it when one byte of the manifest SHA differs', () => {
  
  const a=Buffer.concat([PNG_MAGIC,Buffer.from([0]),randomBytes(3000)]);
  const m=makePatch({change:{'docs/design/r.png':a,'docs/design/manifest.json':manifestFor([['r.png',a]])}});
  const bad=makePatch({change:{'docs/design/r.png':a,'docs/design/manifest.json':manifestFor([['r.png',a]]).replace(/[a-f0-9]"/,x=>(x[0]==='0'?'1':'0')+'"')}});
  try {
    const found=check(m);
    assert.equal(found.ok,true,JSON.stringify(found));
    assert.equal(found.files.find(f=>f.binary).sha256,sha(a));
    assert.equal(check(bad).ok,false);
  } finally { rmSync(m.dir,{recursive:true,force:true}); rmSync(bad.dir,{recursive:true,force:true}); }
});
