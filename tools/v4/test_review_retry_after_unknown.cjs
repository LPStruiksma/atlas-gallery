'use strict';
// Production V4 click dispatcher and inspection gate, fictional HTTP only.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const a=source.indexOf('// Direct review writes, including Undo');
const b=source.indexOf("\ndocument.addEventListener('input'",a);
assert(a>=0&&b>a,'Actual V4 browser review dispatcher required');
const pending=[],calls=[],prompts=[],messages=[],undoTokens=[];
let inspectApproved=false,canConfirmActions=true;
const ctx={
  tab:'person',person:27,page:0,group:null,personMode:'suggested',
  renderEpoch:5,selected:new Set([101,102]),lastUndoToken:null,
  confirm:message=>{
    if(message.includes('MAY HAVE SAVED')){
      prompts.push(message);return inspectApproved;
    }
    return canConfirmActions;
  },
  post:(uri,body)=>new Promise((resolve,reject)=>{
    calls.push([uri,JSON.parse(JSON.stringify(body))]);pending.push({resolve,reject});
  }),
  toast:s=>messages.push(s),
  keepReviewUndo:response=>{undoTokens.push(response.undo_token);ctx.lastUndoToken=response.undo_token},
  render:async()=>{ctx.renderEpoch++},
};
vm.createContext(ctx);vm.runInContext(source.slice(a,b)+
  '\nthis.reviewAction=eventAction;this.uncertain=()=>unverifiedDirectReviewKind;',ctx);
(async()=>{
  // A committed accept may lose its acknowledgement. Browser cannot know
  // whether the server tagged the two fictional faces.
  const accept=ctx.reviewAction('accept');
  assert.equal(pending.length,1);
  pending.shift().reject(Error('Fixture post-commit response dropped'));
  await accept;
  assert.match(ctx.uncertain(),/Suggestions confirmed/);
  assert.deepEqual([...ctx.selected],[101,102]);
  assert.equal(undoTokens.length,0);
  assert.equal(calls.length,1);

  // Rapid attempts at a DIFFERENT mutation also require inspection. Cancel
  // must perform no write, keep warning/selection, and never invent Undo.
  const reject=Array.from({length:60},()=>ctx.reviewAction('reject'));
  await Promise.all(reject);
  assert.equal(calls.length,1);
  assert.equal(prompts.length,1,'60 rapid reject attempts must not spam confirmations');
  assert.match(prompts[0],/MAY HAVE SAVED/);
  assert.match(prompts[0],/Inspect the affected face tags or People/);
  assert.equal(ctx.uncertain(),'Suggestions confirmed');
  assert.deepEqual([...ctx.selected],[101,102]);
  assert.equal(undoTokens.length,0);
  assert.match(messages.at(-1),/Review change paused/);

  // A reviewer explicitly verifies the current tags, acknowledging the
  // uncertainty. A NEW deliberate write is then permitted and audited.
  inspectApproved=true;
  const allowed=ctx.reviewAction('reject');
  assert.equal(pending.length,1);
  assert.equal(calls.length,2);
  assert.equal(calls[1][0],'/api/person/reject');
  pending.shift().resolve({count:2,undo_token:'f'.repeat(32)});
  await allowed;
  assert.equal(ctx.uncertain(),null);
  assert.equal(ctx.lastUndoToken,'f'.repeat(32));
  assert.equal(undoTokens.length,1);
  assert.equal(ctx.selected.size,0);

  // Lose acknowledgement of Hide on the unsorted queue. Even the previous
  // "Undo last review action" must not proceed blindly: it could target the
  // wrong, older confirmed mutation rather than this unverified Hide.
  ctx.tab='untagged';ctx.renderEpoch++;ctx.selected=new Set([201]);
  const ignore=ctx.reviewAction('ignore');
  pending.shift().reject(Error('Fixture Hide ACK dropped'));
  await ignore;
  assert.equal(ctx.uncertain(),'Faces hidden · Undo is available');
  inspectApproved=false;
  const blockedUndo=ctx.reviewAction('undomanual');
  await blockedUndo;
  assert.equal(calls.length,3,'Unverified Hide must gate misleading older Undo');
  assert.equal(ctx.lastUndoToken,'f'.repeat(32));
  assert.equal(ctx.selected.size,1);
  assert.match(prompts.at(-1),/Faces hidden/);

  // An affirmative inspected Undo remains the same existing audited
  // one-use transaction, preserving newer face selection.
  inspectApproved=true;
  const safeUndo=ctx.reviewAction('undomanual');
  assert.equal(pending.length,1);
  assert.equal(calls.at(-1)[0],'/api/review/undo');
  assert.equal(calls.at(-1)[1].undo_token,'f'.repeat(32));
  pending.shift().resolve({count:2});
  await safeUndo;
  assert.equal(ctx.uncertain(),null);
  assert.equal(ctx.lastUndoToken,null);
  assert.deepEqual([...ctx.selected],[201]);
  assert.equal(calls.length,4);

  console.log('PASS: real V4 direct-review and Undo handlers pause after uncertain POST, 60 re-taps coalesce to one explicit inspection, Cancel writes nothing, next audited write preserves selection/Undo');
})().catch(e=>{console.error(e);process.exitCode=1});
