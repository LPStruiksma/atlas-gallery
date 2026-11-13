'use strict';
// Execute actual V4 Undo button + review dispatcher; every token and call is
// synthetic. No device, network, SQLite file or real Person data.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(a,b){
  const i=src.indexOf(a),j=src.indexOf(b,i+a.length);
  assert(i>=0&&j>i,'Actual V4 browser code missing '+a);
  return src.slice(i,j);
}
const script=actual('const undoButton=()=>','\nfunction updateSelectionCount()')+
  actual('// Direct review writes, including Undo',"\ndocument.addEventListener('input'")+
  '\nthis.ui={undoButton,action:eventAction,pending:()=>[...unverifiedUndoTokens]};';
const calls=[],pending=[],messages=[],confirmations=[];
let repaints=0,allowRetry=false;
const ctx={
  tab:'person',page:2,person:9,group:null,personMode:'faces',
  renderEpoch:2,selected:new Set([11,12]),lastUndoToken:'a'.repeat(32),
  post:(url,body)=>new Promise((resolve,reject)=>{
    calls.push([url,JSON.parse(JSON.stringify(body))]);
    pending.push({url,resolve,reject});
  }),
  toast:message=>messages.push(message),
  confirm:message=>{confirmations.push(message);return allowRetry},
  repaintReviewKeepingPosition:async()=>{repaints++;ctx.renderEpoch++},
  btn:(label,action)=>'<button data-action="'+action+'">'+label+'</button>'
};
vm.createContext(ctx);vm.runInContext(script,ctx);
// The real focus-preserving repaint is covered in its own DOM/RAF test.
// Replace it in this VM fixture to isolate status/token semantics.
ctx.repaintReviewKeepingPosition=async()=>{repaints++;ctx.renderEpoch++};
const click=a=>ctx.ui.action(a);
const settle=(url,value,reject=false)=>{
  assert.equal(pending.length,1);
  const request=pending.shift();
  assert.equal(request.url,url);
  if(reject)request.reject(Error(value));else request.resolve(value);
};
const state=()=>Array.from(ctx.ui.pending());
(async()=>{
  // 80 button clicks when Undo reply is lost must NOT issue multiple writes.
  const taps=Array.from({length:80},()=>click('undomanual'));
  assert.equal(pending.length,1);
  settle('/api/review/undo','Synthetic Undo response lost AFTER COMMIT',true);
  await Promise.all(taps);
  assert.deepEqual(state(),['a'.repeat(32)]);
  assert.equal(ctx.lastUndoToken,'a'.repeat(32),
    'Unverified one-use token is retained as evidence, not presumed usable');
  assert.match(ctx.ui.undoButton(),/data-action="verifyundo"/);
  assert.match(ctx.ui.undoButton(),/data-action="undomanual"/);
  assert.equal(calls.length,1);
  assert.deepEqual([...ctx.selected],[11,12]);

  // Confirm is required even to retry the *same* unverified Undo token.
  await Promise.all(Array.from({length:60},()=>click('undomanual')));
  assert.equal(calls.length,1);
  assert.equal(confirmations.length,1);
  assert(confirmations[0].includes('MAY HAVE COMMITTED'));
  assert.match(messages.at(-1),/Undo paused/);

  // Explicit status checks coalesce. A previously committed old Undo may
  // be confirmed AFTER a different Person and new Undo token were opened.
  const checks=Array.from({length:90},()=>click('verifyundo'));
  assert.equal(pending.length,1);
  assert.equal(calls.length,2);
  assert.deepEqual(calls.at(-1),[
    '/api/review/undo/status',{undo_token:'a'.repeat(32)}
  ]);
  ctx.person=99;ctx.renderEpoch++;ctx.lastUndoToken='b'.repeat(32);
  settle('/api/review/undo/status',{status:'undone'});
  await Promise.all(checks);
  assert.equal(ctx.lastUndoToken,'b'.repeat(32),
    'Late prior-status query may not retire the newer Undo token');
  assert.deepEqual(state(),[]);
  assert.equal(repaints,0,'Late old status must not repaint new Person');
  assert.doesNotMatch(ctx.ui.undoButton(),/data-action="verifyundo"/);
  assert.match(messages.at(-1),/1 Undo confirmed/);

  // Unknown is deliberately NOT reported as failed: a 12-row history can
  // rotate out an earlier token, or a transaction might still be pending.
  ctx.lastUndoToken='c'.repeat(32);
  const next=click('undomanual');
  settle('/api/review/undo','Unknown synthetic response loss',true);
  await next;
  assert.deepEqual(state(),['c'.repeat(32)]);
  let check=click('verifyundo');
  settle('/api/review/undo/status',{status:'unknown'});
  await check;
  assert.deepEqual(state(),['c'.repeat(32)]);
  assert.match(messages.at(-1),/1 unknown \(possibly expired history\)/);
  assert.match(messages.at(-1),/inspect current face tags/);

  check=click('verifyundo');
  settle('/api/review/undo/status',{status:'recorded_not_undone'});
  await check;
  assert.deepEqual(state(),['c'.repeat(32)]);
  assert.match(messages.at(-1),/recorded but not yet undone/);
  assert.equal(ctx.lastUndoToken,'c'.repeat(32),
    'Recorded status is NOT evidence an Undo is currently safe to retry');

  // A status transport failure also retains uncertainty without retry.
  check=click('verifyundo');
  settle('/api/review/undo/status','Synthetic status offline',true);
  await check;
  assert.deepEqual(state(),['c'.repeat(32)]);

  // Explicit, inspected retry with server acknowledgement consumes the
  // original token and clears only that pending entry. No client-side Undo
  // token is manufactured or reused.
  allowRetry=true;
  const allowed=click('undomanual');
  assert.equal(pending.length,1);
  settle('/api/review/undo',{count:1});
  await allowed;
  assert.deepEqual(state(),[]);
  assert.equal(ctx.lastUndoToken,null);
  assert.equal(repaints,4,
    'Three same-view status results and the acknowledged Undo should repaint');
  assert.deepEqual([...ctx.selected],[11,12]);

  console.log('PASS: real V4 Undo status UI coalesces 90 taps, preserves newer Undo on stale tab, retains unknown/recorded evidence, gates repeated one-use POST, clears only confirmed consumed token');
})().catch(e=>{console.error(e);process.exitCode=1});
