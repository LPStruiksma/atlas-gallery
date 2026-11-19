// Fail-closed Codex format-patch parser. This module never executes untrusted patch text.
import path from 'node:path';

const SHA = '[a-f0-9]{40}';
const protectedPrefix = ['.github/','roles/','.claude/','eval/'];
const protectedExact = new Set(['AGENTS.md','CLAUDE.md','SECURITY.md','.mcp.json']);
const pathDenied = (name) => {
  if (!name || name.startsWith('/') || name.includes('\\') || name.includes('%') ||
      name.includes('//') || name.split('/').some(x=>x==='' || x==='.' || x==='..' || x[0]==='.') ||
      /[\r\n\t ]/.test(name) || /[^a-zA-Z0-9_./-]/.test(name)) return true;
  if (path.posix.normalize(name)!==name) return true;
  return protectedExact.has(name) || protectedPrefix.some(p=>name.startsWith(p)) ||
    name==='.github' || name==='eval' || name==='roles';
};

// Where an applied Codex patch may write. Ported from LPStruiksma/autodev
// bin/autodev-codex-apply.mjs at 2c44457 (#522); this repository's code lives in
// app/src/, not bin/ or src/. Build files, scripts/ (CI and signing scripts) and
// every protected or human-only path stay refused, as does this guard itself.
// Pass the trusted main's unconditionalHumanOnlyPaths and
// candidateHardWriteDenied lists as `denied`.
export const CODEX_WRITABLE_PREFIXES = Object.freeze(['app/src/','test/','docs/','tools/']);
const selfGuard = new Set(['scripts/ci/codex-apply.mjs','scripts/ci/codex-apply-runner.mjs',
  'package.json','package-lock.json']);
const matchesDenied = (name, entry) => {
  if (typeof entry!=='string' || !entry) return true; // malformed policy refuses everything
  // #522 port: this repository's policy also lists dot-file globs such as `.env.*`.
  // pathDenied already refuses every path with a segment starting with '.', so a
  // dot-led entry can only match a path that never gets here. Any other glob still
  // refuses everything.
  if (entry.startsWith('.') && !entry.includes('/')) return name.split('/')[0].startsWith('.');
  const base = entry.endsWith('/**') ? entry.slice(0,-3) : entry.endsWith('/') ? entry.slice(0,-1) : entry;
  if (base.includes('*')) return true;
  return name===base || name.startsWith(base+'/');
};
export function codexPathRefusal(name, denied=[]) {
  if (pathDenied(name)) return 'protected or malformed path';
  if (!CODEX_WRITABLE_PREFIXES.some(p=>name.startsWith(p))) return 'outside writable prefixes';
  if (selfGuard.has(name) || name.startsWith('test/additive-regressions/codex-apply'))
    return 'apply guard or its tests';
  if (!Array.isArray(denied) || denied.some(entry=>matchesDenied(name,entry))) return 'human-only or hard-denied path';
  return null;
}

export function inspectCodexReply(body, expectedHead) {
  if (typeof body !== 'string' || !new RegExp('^'+SHA+'$').test(expectedHead||'')) return {ok:false,reason:'invalid input/head'};
  const blocks=[...body.matchAll(/^```diff\r?\n([\s\S]*?)\r?\n```[ \t]*$/gm)];
  const allFences=[...body.matchAll(/^```/gm)];
  if (blocks.length!==1 || allFences.length!==2) return {ok:false,reason:'expected one diff fence'};
  const prelude=body.slice(0,blocks[0].index);
  const trailing=body.slice(blocks[0].index+blocks[0][0].length);
  // Codex may add prose before the patch and its platform task footer after it.
  // Machine input remains exactly one diff fence; trailing instructions refuse.
  if(trailing.trim()!=='' &&
     !/^\s*\[View task →\]\(https:\/\/chatgpt\.com\/s\/[A-Za-z0-9_]+\)\s*$/.test(trailing))
    return {ok:false,reason:'unexpected text outside patch fence'};
  // Optional SHA is untrusted bot-reported metadata, not the parent binding.
  const commits=[...prelude.matchAll(new RegExp('^Commit: ('+SHA+')\\r?$','gm'))];
  const reportedCommit=commits.length===1?commits[0][1]:undefined;
  const patch=blocks[0][1];
  const checked=validateCodexPatch(patch,expectedHead);
  return checked.ok?{ok:true,patch,paths:checked.paths,files:checked.files,...(reportedCommit?{reportedCommit}:{})}:{ok:false,reason:checked.reason};
}
export function parseCodexPatchPaths(patch) {
  if (typeof patch!=='string') return [];
  const headers=[...patch.matchAll(/^diff --git a\/([^\n]+) b\/([^\n]+)$/gm)];
  if (!headers.length) return [];
  const paths=[];
  for(const m of headers) {
    if (m[1]!==m[2] || pathDenied(m[1])) return [];
    paths.push(m[1]);
  }
  return new Set(paths).size===paths.length?paths:[];
}

export function validateCodexPatch(patch,expectedHead) {
  if (typeof patch!=='string'||patch.length>150000||!/^[a-f0-9]{40}$/.test(expectedHead||''))
    return {ok:false,reason:'invalid patch/head'};
  if (patch.includes('\r') || patch.includes('\0')) return {ok:false,reason:'invalid bytes'};
  const lines=patch.split('\n');
  if(lines.some(l=>l.startsWith('diff --cc ')||l.startsWith('diff --combined ')))
    return {ok:false,reason:'combined diffs unsupported'};
  if (!new RegExp('^From '+SHA+' Mon Sep 17 00:00:00 2001$').test(lines[0]||''))
    return {ok:false,reason:'missing format-patch commit header'};
  // Git format-patch does not encode the parent. The PR reply must bind it in an
  // explicit, unambiguous trailer; git am later verifies applicability on exact head.
  const parent='Parent: '+expectedHead;
  if (lines.filter(l=>l.startsWith('Parent: ')).length!==1 || !lines.includes(parent))
    return {ok:false,reason:'patch parent mismatch'};
  // Traditional file patches may also be consumed by git am from a forged
  // mail message body, before the format-patch separator. Keep only ordinary
  // prose there; no untracked traditional file/hunk headers.
  const preamble=lines.slice(1,lines.indexOf('---'));
  if (preamble.some(line=>/^(?:--- |\+\+\+ |@@ |Index: )/.test(line)))
    return {ok:false,reason:'traditional diff in mail header'};
  const headerBoundary=lines.indexOf('---');
  if (headerBoundary<2 || !lines.slice(1,headerBoundary).some(x=>x.startsWith('Subject: [PATCH] ')))
    return {ok:false,reason:'format-patch mail header missing'};
  const forbidden=/^(?:From [a-f0-9]{40} Mon Sep 17|GIT binary patch|Binary files |rename (?:from|to) |copy (?:from|to) |(?:old|new) mode |deleted file mode |similarity index |dissimilarity index |index [a-f0-9]+,[a-f0-9]+)/;
  // Allowed operations: add a new regular text file, or edit an existing regular
  // file's text. No deletes, renames, copies, mode changes or binary data.
  if (lines.slice(1).some(x=>forbidden.test(x))) return {ok:false,reason:'unsupported patch operation'};
  // The parent binding belongs in the mail header (not in a forged added line).
  if (!lines.slice(1,headerBoundary).includes(parent) || lines.slice(headerBoundary+1).some(l=>l.startsWith('Parent: ')))
    return {ok:false,reason:'parent must be in header'};
  if (!lines.slice(1,headerBoundary).some(l=>/^From: [^\n<>]+ <[^\n<>]+>$/.test(l))) return {ok:false,reason:'missing author'};
  // Only diffstat entries may appear between the mail separator and the first
  // declared git section. Git am also recognizes traditional file headers here,
  // so accepting them would allow undeclared (possibly protected) changes.
  const firstDiff=lines.findIndex((line,i)=>i>headerBoundary && line.startsWith('diff --git '));
  if (firstDiff<0 || lines.slice(headerBoundary+1,firstDiff).some(line=>
    line!=='' && !line.startsWith(' ') ||
    /^(?:--- |\+\+\+ |@@ |Index: )/.test(line)))
    return {ok:false,reason:'unexpected data before first diff section'};
  const paths=parseCodexPatchPaths(patch);
  if (!paths.length || paths.length>12) return {ok:false,reason:'invalid paths'};
  const headers=lines.filter(x=>x.startsWith('diff --git '));
  if(lines.some(x=>x.startsWith('diff --git ') && !/^diff --git a\/[a-zA-Z0-9_./-]+ b\/[a-zA-Z0-9_./-]+$/.test(x)))
    return {ok:false,reason:'invalid diff header'};
  if (headers.length!==paths.length) return {ok:false,reason:'unrecognized path header'};
  const files=[];
  for(const name of paths) {
    const sectionStart=lines.findIndex(x=>x==='diff --git a/'+name+' b/'+name);
    const next=lines.findIndex((x,i)=>i>sectionStart && x.startsWith('diff --git '));
    const section=lines.slice(sectionStart,next<0?undefined:next);
    const firstHunk=section.findIndex(x=>x.startsWith('@@ '));
    if (firstHunk<0) return {ok:false,reason:'section without hunk'};
    const header=section.slice(1,firstHunk);
    const added=header.length===4 && header[0]==='new file mode 100644' &&
      /^index 0{7,40}\.\.[a-f0-9]{7,40}$/.test(header[1]) && header[2]==='--- /dev/null' &&
      header[3]==='+++ b/'+name && section[firstHunk].startsWith('@@ -0,0 +');
    const modified=header.length===3 &&
      /^index [a-f0-9]{7,40}\.\.[a-f0-9]{7,40} 100(?:644|755)$/.test(header[0]) &&
      !/^index 0{7,40}\./.test(header[0]) &&
      header[1]==='--- a/'+name && header[2]==='+++ b/'+name;
    if (!added && !modified) return {ok:false,reason:'only new or edited regular text files supported'};
    // Walk every hunk by its declared old/new line counts. A second, headerless
    // traditional diff must not be mistaken for data belonging to this section.
    let pos=firstHunk;
    let sawHunk=false;
    while (pos<section.length && section[pos].startsWith('@@ ')) {
      sawHunk=true;
      const m=/^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(?:.*)$/.exec(section[pos]);
      if (!m) return {ok:false,reason:'invalid hunk header'};
      let oldRemaining=m[2]===undefined?1:Number(m[2]);
      let newRemaining=m[4]===undefined?1:Number(m[4]);
      pos++;
      while (oldRemaining>0 || newRemaining>0) {
        const line=section[pos++];
        if (line===undefined) return {ok:false,reason:'truncated hunk'};
        if (line.startsWith(' ') && oldRemaining>0 && newRemaining>0) {
          oldRemaining--; newRemaining--;
        } else if (line.startsWith('-') && oldRemaining>0) {
          oldRemaining--;
        } else if (line.startsWith('+') && newRemaining>0) {
          newRemaining--;
        } else if (line==='\\ No newline at end of file') {
          // This marker does not consume source or destination lines.
        } else return {ok:false,reason:'invalid hunk body'};
      }
      if (section[pos]==='\\ No newline at end of file') pos++;
    }
    if (!sawHunk) return {ok:false,reason:'section without hunk'};
    // Only the standard format-patch signature/footer may follow the final hunk.
    const tail=section.slice(pos);
    if (tail.length && !(tail[0]==='-- ' &&
      /^\d+(?:\.\d+)+$/.test(tail[1]||'') &&
      (tail.length===2 || (tail.length===3 && tail[2]===''))))
      return {ok:false,reason:'unexpected data outside hunk'};
    files.push({path:name,kind:added?'add':'modify'});
  }
  if (lines.some(l=>l.startsWith('Parent: ') && l!==parent)) return {ok:false,reason:'conflicting parent'};
  if (lines.filter(l=>l.startsWith('Subject: ')).length!==1) return {ok:false,reason:'duplicate subject'};
  return {ok:true,paths,files};
}
