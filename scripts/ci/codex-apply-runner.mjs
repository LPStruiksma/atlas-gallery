// Trusted-main GitHub Actions entry point for bounded Codex patch application.
// Never checkout the untrusted PR until its SHA, branch and patch are validated.
import { spawnSync } from 'node:child_process';
import { readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { inspectCodexReply, codexPathRefusal } from './codex-apply.mjs';

const repository=process.env.GITHUB_REPOSITORY;
const token=process.env.GH_TOKEN;
const eventPath=process.env.GITHUB_EVENT_PATH;
const event=JSON.parse(readFileSync(eventPath,'utf8'));
const prNumber=event.issue?.number;
const commentId=event.comment?.id;
let outcome='REFUSED';
let reason='unverified event';
let appliedSha=null;
let reportedCodexSha=null;
let folder=null;

function exec(command,args,options={}) {
  const result=spawnSync(command,args,{encoding:'utf8',timeout:60000,maxBuffer:1024*1024,
    env:{...process.env,GIT_CONFIG_NOSYSTEM:'1',GIT_CONFIG_GLOBAL:'/dev/null'},...options});
  if(result.error||result.status!==0)
    throw Error(command+' '+args[0]+' failed: '+String(result.stderr||result.error?.message||'error').slice(0,300));
  return String(result.stdout||'').trim();
}
function gh(args){return exec('gh',args);}
function validPr(pr) {
  return pr && pr.state==='open' && pr.draft===true && pr.base?.ref==='main' &&
    pr.head?.repo?.full_name===repository &&
    /^chatgpt\/codex-[a-zA-Z0-9_-]+$/.test(pr.head?.ref||'') &&
    /^[a-f0-9]{40}$/.test(pr.head?.sha||'') && pr.head?.ref!=='main';
}
function refuse(text) { reason=text; throw Error(text); }
function validComment(comment) {
  return comment && comment.id===commentId &&
    comment.user?.login==='chatgpt-codex-connector[bot]' &&
    typeof comment.body==='string' &&
    comment.html_url==='https://github.com/'+repository+'/pull/'+prNumber+'#issuecomment-'+commentId &&
    comment.issue_url==='https://api.github.com/repos/'+repository+'/issues/'+prNumber;
}
try {
  if(repository!=='LPStruiksma/atlas-gallery'||!token||event.action!=='created'||
     !event.issue?.pull_request || event.comment?.user?.login!=='chatgpt-codex-connector[bot]' ||
     !Number.isSafeInteger(prNumber)||!Number.isSafeInteger(commentId)) refuse('event identity');
  const comment=JSON.parse(gh(['api','repos/'+repository+'/issues/comments/'+commentId]));
  if(!validComment(comment)) refuse('comment identity or PR association mismatch');
  const pr=JSON.parse(gh(['api','repos/'+repository+'/pulls/'+prNumber]));
  if(!validPr(pr)) refuse('PR must be open same-repo draft on chatgpt/codex-* against main');
  const baseSha=pr.head.sha;
  const prComments=JSON.parse(gh(['api','--paginate','--slurp','repos/'+repository+'/issues/'+prNumber+'/comments?per_page=100'])).flat();
  if(prComments.some(c=>c.id!==commentId && c.user?.login==='github-actions[bot]' &&
      String(c.body||'').startsWith('Actor: autodev/codex-apply'+String.fromCharCode(10)+'Status: APPLIED')))
    refuse('one auto-apply per canary PR');
  // The Codex reply must declare the exact expected head in its mail header.
  if(event.comment.body!==comment.body) refuse('event comment changed since delivery');
  const result=inspectCodexReply(comment.body,baseSha);
  if(!result.ok) refuse(result.reason);
  reportedCodexSha=result.reportedCommit||null;
  // Path policy comes from trusted main (this runner's own checkout), never the PR.
  const protection=JSON.parse(readFileSync(new URL('../../.autodev/project.json',import.meta.url),'utf8'))?.authority?.protection;
  const denied=[protection?.unconditionalHumanOnlyPaths,protection?.candidateHardWriteDenied];
  if(denied.some(list=>!Array.isArray(list)||!list.length)) refuse('trusted path policy unreadable');
  for(const name of result.paths) {
    const why=codexPathRefusal(name,denied.flat());
    if(why) refuse('path refused ('+why+'): '+name);
  }
  const kinds=new Map(result.files.map(f=>[f.path,f.kind]));
  if(kinds.size!==result.paths.length || result.paths.some(name=>!kinds.has(name))) refuse('patch file kinds mismatch');
  folder=mkdtempSync(join(tmpdir(),'autodev-codex-'));
  const checkout=join(folder,'repo');
  // No candidate code is executed: git am uses disabled hooks and worktree-only
  // patching, with no npm install, scripts, or PR-provided Git configuration.
  exec('git',['-c','http.extraheader=AUTHORIZATION: basic '+Buffer.from('x-access-token:'+token).toString('base64'),
    'clone','--no-tags','https://github.com/'+repository+'.git',checkout]);
  exec('git',['-C',checkout,'-c','http.extraheader=AUTHORIZATION: basic '+Buffer.from('x-access-token:'+token).toString('base64'),
    'fetch','origin',baseSha]);
  exec('git',['-C',checkout,'checkout','--detach',baseSha]);
  const current=JSON.parse(gh(['api','repos/'+repository+'/pulls/'+prNumber]));
  if(!validPr(current)||current.head.sha!==baseSha) refuse('head moved before apply');
  for(const name of result.paths) {
    const existing=spawnSync('git',['-C',checkout,'ls-tree','-r',baseSha,'--',name],{encoding:'utf8'});
    if(existing.status!==0) refuse('cannot inspect base tree');
    const entry=existing.stdout.trim();
    if(kinds.get(name)==='add' && entry) refuse('added file already exists: '+name);
    if(kinds.get(name)==='modify' &&
       !new RegExp('^100(?:644|755) blob [a-f0-9]{40}\t'+name.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')+'$').test(entry))
      refuse('edited file is not an existing regular file: '+name);
  }
  const patchFile=join(folder,'candidate.patch');
  writeFileSync(patchFile,result.patch+'\n',{mode:0o600});
  const emptyHooks=join(folder,'hooks');
  exec('mkdir',['-p',emptyHooks]);
  exec('git',['-C',checkout,'-c','core.hooksPath='+emptyHooks,
    '-c','user.name=Codex patch relay','-c','user.email=codex-relay@users.noreply.github.com',
    'am','--no-gpg-sign','--no-3way',patchFile]);
  const touched=exec('git',['-C',checkout,'diff-tree','--no-commit-id','--name-only','-r','HEAD']).split('\n').filter(Boolean).sort();
  if(JSON.stringify(touched)!==JSON.stringify([...result.paths].sort())) refuse('applied paths changed');
  const checkFiles=exec('git',['-C',checkout,'diff-tree','--no-commit-id','--name-status','-r','HEAD']).split('\n');
  if(checkFiles.some(x=>{const [status,name]=x.split('\t');
      return status!==(kinds.get(name)==='add'?'A':'M');})) refuse('unexpected commit operation');
  const modes=exec('git',['-C',checkout,'diff-tree','--no-commit-id','--raw','-r','HEAD']).split('\n');
  if(modes.some(x=>{const name=x.split('\t')[1];
      return kinds.get(name)==='add' ? !/^:000000 100644 [a-f0-9]{40} [a-f0-9]{40} A\t/.test(x)
        : !/^:(100644|100755) \1 [a-f0-9]{40} [a-f0-9]{40} M\t/.test(x);}))
    refuse('unexpected file mode');
  const committedParent=exec('git',['-C',checkout,'rev-parse','HEAD^']);
  if(committedParent!==baseSha) refuse('parent mismatch after am');
  appliedSha=exec('git',['-C',checkout,'rev-parse','HEAD']);
  const latest=JSON.parse(gh(['api','repos/'+repository+'/pulls/'+prNumber]));
  if(!validPr(latest)||latest.head.sha!==baseSha) refuse('head moved before push');
  if (latest.head.ref!==pr.head.ref) refuse('branch moved before push');
  // The push token is scoped to this workflow; lease prevents overwriting another seat.
  exec('git',['-C',checkout,'-c','http.extraheader=AUTHORIZATION: basic '+Buffer.from('x-access-token:'+token).toString('base64'),
    'push','origin',appliedSha+':refs/heads/'+pr.head.ref,
    '--force-with-lease=refs/heads/'+pr.head.ref+':'+baseSha]);
  outcome='APPLIED';
  reason='patch applied; CI dispatch not confirmed';
  // GITHUB_TOKEN pushes do not trigger new workflows, so run the build and JVM
  // tests directly on the branch ref (no emulators: those run at next:review).
  // The static check runs on the next pull_request event (ready for review).
  gh(['api','--method','POST','repos/'+repository+'/actions/workflows/build-apk.yml/dispatches',
      '-f','ref='+pr.head.ref,'-f','inputs[run_ui_tests]=false']);
  reason='patch applied; build-apk workflow_dispatch requested on branch '+pr.head.ref;
 } catch(error) {
  reason=(outcome==='APPLIED'?'post-push failure: ':'')+String(error.message||error).slice(0,400);
  // A denied GitHub issue-comment POST must not hide the original guard failure.
  // This log is diagnostic only; a workflow log is not a machine apply receipt.
  process.stderr.write('codex-apply outcome='+outcome+' reason='+reason+'\n');
  if(outcome==='APPLIED') process.exitCode=1;
} finally {
  if(folder) rmSync(folder,{recursive:true,force:true});
  if(repository==='LPStruiksma/atlas-gallery'&&Number.isSafeInteger(prNumber)&&prNumber>0) {
    const body='Actor: autodev/codex-apply\nStatus: '+outcome+
      '\nComment: '+String(commentId||'unknown')+
      (appliedSha?'\nApplied SHA: '+appliedSha:'')+
      (reportedCodexSha?'\nReported Codex SHA: '+reportedCodexSha:'')+'\nReason: '+reason;
    try { gh(['api','--method','POST','repos/'+repository+'/issues/'+prNumber+'/comments','-f','body='+body]); }
    catch(error){process.stderr.write('receipt failed: '+error.message+'\n');process.exitCode=1;}
  }
}
if(outcome!=='APPLIED') process.exitCode=1;
