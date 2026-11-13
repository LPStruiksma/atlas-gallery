'use strict';
// Validate the real V4 browser Undo dispatch path against fictional HTTP
// replies, including a SQLite-committed Undo whose reply is dropped.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const i=src.indexOf('// Direct review writes, including Undo');
const j=src.indexOf("\ndocument.addEventListener('input'",i);
assert(i>=0&&j>i,'Must execute real V4 Undo dispatcher');
const calls=[],pending=[],messages=[];
let renders=0,throwRender=false;
const ctx={
  tab:'person',page:4,person:9,group:null,personMode:'faces',
  renderEpoch:6,selected:new Set([70,71]),lastUndoToken:'token-original',
  post:(uri,body)=>new Promise((resolve,reject)=>{
    calls.push({uri,payload:JSON.parse(JSON.stringify(body))});
    pending.push({resolve,reject});
  }),
  toast:message=>messages.push(message),
  render:async()=>{renders++;ctx.renderEpoch++;if(throwRender){throwRender=false;throw Error('Synthetic repaint failed AFTER committed Undo')}},
  confirm:()=>true,
};
vm.createContext(ctx);vm.runInContext(src.slice(i,j)+'\nthis.undoClicked=()=>eventAction(\'undomanual\');',ctx);
(async()=>{
  // The actual SQLite transaction could have consumed this token before its
  // reply disappears. A burst of 60 clicks must never resend automatically.
  const taps=Array.from({length:60},()=>ctx.undoClicked());
  assert.equal(pending.length,1);
  assert.equal(calls.length,1);
  assert.deepEqual(calls[0],{
    uri:'/api/review/undo',payload:{undo_token:'token-original'}
  });
  pending.shift().reject(Error('Synthetic response LOST after SQLite Undo COMMIT'));
  await Promise.all(taps);
  assert.equal(ctx.lastUndoToken,'token-original',
    'Without ACK browser may NOT conclude Undo consumed or erase recovery evidence');
  assert.deepEqual([...ctx.selected],[70,71]);
  assert.equal(renders,0,'Unknown Undo outcome must not repaint as if successful');
  assert.match(messages.at(-1),/Could not verify Undo:/);
  assert.match(messages.at(-1),/inspect People or face tags before retrying/);
  assert(!messages.at(-1).includes('Undo failed'));
  assert.equal(calls.length,1);

  // The reviewer may inspect and then explicitly try again; server may say
  // one-use Undo has already been spent. Still never assert rollback failed.
  const deliberate=ctx.undoClicked();
  assert.equal(pending.length,1);
  pending.shift().reject(Error('Undo unavailable or already used'));
  await deliberate;
  assert.equal(calls.length,2);
  assert.match(messages.at(-1),/Could not verify Undo:/);
  assert.match(messages.at(-1),/already used/);

  // An ACKNOWLEDGED Undo is a known committed rollback. If the local view
  // fails to repaint, communicate that certainty, preserve face selection,
  // and retire only the consumed one-use token.
  ctx.lastUndoToken='another-one-use-token';
  throwRender=true;
  const done=ctx.undoClicked();
  assert.equal(pending.length,1);
  assert.equal(calls.at(-1).payload.undo_token,'another-one-use-token');
  pending.shift().resolve({count:2});
  await done;
  assert.equal(ctx.lastUndoToken,null);
  assert.deepEqual([...ctx.selected],[70,71]);
  assert.equal(renders,1);
  assert.match(messages.at(-1),/Undo confirmed; could not refresh view/);
  assert.match(messages.at(-1),/Synthetic repaint failed AFTER committed Undo/);

  // A slower older Person's acknowledged Undo must neither repaint the
  // newly active page nor consume an independently newer Undo token.
  ctx.lastUndoToken='old-person-token';ctx.renderEpoch++;
  const oldPerson=ctx.undoClicked();
  assert.equal(pending.length,1);
  ctx.person=77;ctx.page=0;ctx.selected=new Set([701]);
  ctx.renderEpoch++;ctx.lastUndoToken='latest-review-token';
  pending.shift().resolve({count:1});
  const beforeRender=renders;
  await oldPerson;
  assert.equal(renders,beforeRender);
  assert.deepEqual([...ctx.selected],[701]);
  assert.equal(ctx.lastUndoToken,'latest-review-token');
  assert.equal(messages.at(-1),'Previous-view Undo completed');

  // Without a token there is no write or status speculation.
  ctx.lastUndoToken=null;
  const beforeCalls=calls.length;
  await ctx.undoClicked();
  assert.equal(calls.length,beforeCalls);
  assert.equal(messages.at(-1),'Nothing to undo');
  console.log('PASS: 60 Undo clicks coalesce; committed-but-lost reply stays unknown, ACK+failed repaint identified, selection/newer Undo preserved across navigation, no invented rollback or auto retry');
})().catch(e=>{console.error(e);process.exitCode=1});
