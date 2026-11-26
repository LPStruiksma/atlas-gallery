// Fail-closed Codex format-patch parser. This module never executes untrusted patch text.
import path from 'node:path';
import { createHash } from 'node:crypto';
import { inflateSync } from 'node:zlib';

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

// Design-evidence images (#1931). A `GIT binary patch` is accepted only for a
// .png/.webp file under a trusted-main allowlisted prefix, within the size caps,
// with real magic bytes and a SHA-256 in a manifest.json shipped by the same patch.
// With no evidence prefixes (the default) every binary hunk is still refused.
export const EVIDENCE_MAX_FILE_BYTES = 2*1024*1024;
export const EVIDENCE_MAX_PATCH_BYTES = 8*1024*1024;
const B85 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()*+-;<=>?@^_`{|}~';
const B85_INDEX = new Map([...B85].map((c,i)=>[c,i]));
// Prefixes come from trusted main; reduce to plain 'dir/' form, drop anything odd.
export function normalizeEvidencePrefixes(list) {
  if (!Array.isArray(list)) return [];
  const out=[];
  for (const entry of list) {
    if (typeof entry!=='string') continue;
    const base = entry.endsWith('/**') ? entry.slice(0,-3) : entry.replace(/\/$/,'');
    if (!base || base.includes('*') || pathDenied(base+'/x')) continue;
    out.push(base+'/');
  }
  return out;
}
// Decode one git binary block body (data lines) to its inflated bytes; null if malformed.
function decodeBinaryLines(dataLines, declared, maxBytes) {
  const chunks=[];
  for (const line of dataLines) {
    const c=line.charCodeAt(0);
    const n = c>=65&&c<=90 ? c-64 : c>=97&&c<=122 ? c-70 : 0;
    if (!n || line.length-1 !== Math.ceil(n/4)*5) return null;
    const out=Buffer.alloc(Math.ceil(n/4)*4);
    for (let g=0; g<line.length-1; g+=5) {
      let acc=0;
      for (let k=0;k<5;k++) {
        const v=B85_INDEX.get(line[1+g+k]);
        if (v===undefined) return null;
        acc=acc*85+v;
      }
      if (acc>0xFFFFFFFF) return null;
      out.writeUInt32BE(acc,(g/5)*4);
    }
    chunks.push(out.subarray(0,n));
  }
  try {
    const bytes=inflateSync(Buffer.concat(chunks),{maxOutputLength:maxBytes+1});
    return bytes.length===declared ? bytes : null;
  } catch { return null; }
}
function evidenceMagic(bytes, ext) {
  if (ext==='.png') return bytes.length>8 && bytes.subarray(0,8).equals(Buffer.from([0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a]));
  return bytes.length>12 && bytes.toString('latin1',0,4)==='RIFF' && bytes.toString('latin1',8,12)==='WEBP';
}
// Parse a "GIT binary patch" body. Forward block must be a deflated literal that we decode;
// an optional reverse block (literal or delta) is only checked structurally.
function parseBinarySection(section, start) {
  let pos=start;
  const readBlock=()=>{
    const m=/^(literal|delta) (\d{1,8})$/.exec(section[pos]||'');
    if (!m) return null;
    pos++;
    const data=[];
    while (pos<section.length && section[pos]!=='') data.push(section[pos++]);
    if (!data.length || section[pos]!=='') return null;
    pos++;
    return {kind:m[1],size:Number(m[2]),data};
  };
  const fwd=readBlock();
  if (!fwd || fwd.kind!=='literal' || fwd.size>EVIDENCE_MAX_FILE_BYTES) return null;
  let rev=null;
  if (/^(?:literal|delta) /.test(section[pos]||'')) {
    rev=readBlock();
    if (!rev) return null;
    const ok=rev.data.every(l=>{const c=l.charCodeAt(0);const n=c>=65&&c<=90?c-64:c>=97&&c<=122?c-70:0;
      return n && l.length-1===Math.ceil(n/4)*5 && [...l.slice(1)].every(ch=>B85_INDEX.has(ch));});
    if (!ok) return null;
  }
  const bytes=decodeBinaryLines(fwd.data,fwd.size,EVIDENCE_MAX_FILE_BYTES);
  return bytes ? {bytes,pos} : null;
}
// manifest.json: {"files":{"<basename>":{"sha256":"<64 hex>"}}}. Returns a refusal reason or null.
export function verifyEvidenceManifest(text, entries) {
  let doc;
  try { doc=JSON.parse(text); } catch { return 'evidence manifest is not valid JSON'; }
  const files=doc&&typeof doc==='object'&&!Array.isArray(doc)?doc.files:null;
  if (!files||typeof files!=='object'||Array.isArray(files)) return 'evidence manifest has no files map';
  for (const {name,sha256} of entries) {
    const entry=Object.hasOwn(files,name)?files[name]:null;
    const listed=entry&&typeof entry.sha256==='string'?entry.sha256.toLowerCase():'';
    if (!/^[a-f0-9]{64}$/.test(listed)) return 'evidence manifest lacks a SHA-256 for '+name;
    if (listed!==sha256) return 'evidence manifest SHA-256 mismatch for '+name;
  }
  return null;
}
export const evidenceSha256 = (bytes)=>createHash('sha256').update(bytes).digest('hex');

export function inspectCodexReply(body, expectedHead, options={}) {
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
  const checked=validateCodexPatch(patch,expectedHead,options);
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

// One section holding a `GIT binary patch`. Returns a refusal reason or {file}.
function validateBinarySection(name, section, binAt, prefixes) {
  const header=section.slice(1,binAt);
  const added=header.length===2 && header[0]==='new file mode 100644' && /^index 0{7,40}\.\.[a-f0-9]{7,40}$/.test(header[1]);
  const modified=header.length===1 && /^index [a-f0-9]{7,40}\.\.[a-f0-9]{7,40} 100644$/.test(header[0]) &&
    !/^index 0{7,40}\./.test(header[0]);
  if (!added && !modified) return 'binary data may only add or edit a regular file';
  const ext=path.posix.extname(name);
  if (ext!=='.png' && ext!=='.webp') return 'binary data only for .png or .webp';
  if (!prefixes.some(p=>name.startsWith(p))) return 'binary data outside evidence prefixes';
  const parsed=parseBinarySection(section,binAt+1);
  if (!parsed) return 'invalid or oversize binary data';
  if (!evidenceMagic(parsed.bytes,ext)) return 'binary data lacks PNG/WebP magic number';
  const tail=section.slice(parsed.pos);
  if (tail.length && !(tail[0]==='-- ' && /^\d+(?:\.\d+)+$/.test(tail[1]||'') &&
      (tail.length===2 || (tail.length===3 && tail[2]===''))))
    return 'unexpected data outside binary hunk';
  return {file:{path:name,kind:added?'add':'modify',binary:true,size:parsed.bytes.length,
    sha256:evidenceSha256(parsed.bytes)}};
}

export function validateCodexPatch(patch,expectedHead,options={}) {
  const evidencePrefixes=normalizeEvidencePrefixes(options?.evidencePrefixes);
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
  const forbidden=/^(?:From [a-f0-9]{40} Mon Sep 17|Binary files |rename (?:from|to) |copy (?:from|to) |(?:old|new) mode |deleted file mode |similarity index |dissimilarity index |index [a-f0-9]+,[a-f0-9]+)/;
  // Allowed operations: add a new regular text file, or edit an existing regular
  // file's text. No deletes, renames, copies, mode changes or binary data.
  // Binary data is refused unless the trusted policy lists an evidence prefix; the
  // per-section check below then admits only a vetted PNG/WebP literal.
  if (lines.slice(1).some(x=>forbidden.test(x) || (x==='GIT binary patch' && !evidencePrefixes.length)))
    return {ok:false,reason:'unsupported patch operation'};
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
  let binaryBytes=0;
  for(const name of paths) {
    const sectionStart=lines.findIndex(x=>x==='diff --git a/'+name+' b/'+name);
    const next=lines.findIndex((x,i)=>i>sectionStart && x.startsWith('diff --git '));
    const section=lines.slice(sectionStart,next<0?undefined:next);
    const binAt=section.indexOf('GIT binary patch');
    if (binAt>=0) {
      const bad=validateBinarySection(name,section,binAt,evidencePrefixes);
      if (typeof bad==='string') return {ok:false,reason:bad};
      files.push(bad.file); binaryBytes+=bad.file.size;
      if (binaryBytes>EVIDENCE_MAX_PATCH_BYTES) return {ok:false,reason:'binary evidence over patch cap'};
      continue;
    }
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
    const entry={path:name,kind:added?'add':'modify'};
    if (added && /(?:^|\/)manifest\.json$/.test(name))
      entry.text=section.slice(firstHunk).filter(l=>l.startsWith('+')).map(l=>l.slice(1)).join('\n');
    files.push(entry);
  }
  const binaries=files.filter(f=>f.binary);
  if (binaries.length) {
    const byDir=new Map();
    for (const b of binaries) {
      const dir=path.posix.dirname(b.path);
      if (!byDir.has(dir)) byDir.set(dir,[]);
      byDir.get(dir).push({name:path.posix.basename(b.path),sha256:b.sha256});
    }
    for (const [dir,entries] of byDir) {
      const manifest=files.find(f=>!f.binary && f.path===dir+'/manifest.json');
      if (!manifest) return {ok:false,reason:'binary evidence needs manifest.json in the same patch: '+dir};
      // An added manifest is checked now; an edited one is checked on the committed blob after git am.
      if (manifest.kind==='add') {
        const why=verifyEvidenceManifest(manifest.text,entries);
        if (why) return {ok:false,reason:why};
      }
    }
  }
  if (lines.some(l=>l.startsWith('Parent: ') && l!==parent)) return {ok:false,reason:'conflicting parent'};
  if (lines.filter(l=>l.startsWith('Subject: ')).length!==1) return {ok:false,reason:'duplicate subject'};
  return {ok:true,paths,files};
}
