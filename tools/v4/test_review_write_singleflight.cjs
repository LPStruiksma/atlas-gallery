'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const first=src.indexOf('// Direct review writes, including Undo');
const last=src.indexOf("\ndocument.addEventListener('input'",first);
assert(first>0&&last>first,'Must execute real V4 browser eventAction');
const pending=[],calls=[],messages=[];
let renders=0,selections=0,allowConfirm=true;
const ctx={
  tab:'person',page:0,person:17,group:null,personMode:'suggested',
  renderEpoch:9,selected:new Set([11,12]),lastUndoToken:null,
  post:(uri,payload)=>new Promise((resolve,reject)=>{
    calls.push({uri,payload});pending.push({resolve,reject});
  }),
  render:async()=>{renders++},
  toast:msg=>messages.push(msg),
  keepReviewUndo:result=>{ctx.lastUndoToken=result.undo_token},
  confirm:()=>allowConfirm,
  selectVisibleFaces:()=>{selections++},
  clearSelectedFaces:()=>ctx.selected.clear(),
};
vm.createContext(ctx);
vm.runInContext(src.slice(first,last)+'\nthis.reviewAction=eventAction;',ctx);
const burst=(action,n)=>Array.from({length:n},()=>ctx.reviewAction(action));
(async()=>{
  // Physical double tap and keyboard repeat cannot submit a second assign or
  // conflicting reject while the first POST has not returned.
  const accept=[...burst('accept',50),...burst('reject',10)];
  assert.equal(pending.length,1);
  assert.deepEqual(JSON.parse(JSON.stringify(calls[0])),{uri:'/api/person/accept',payload:{
    person_id:17,face_ids:[11,12]}});
  // Inspection/selection remains responsive while the write is in flight.
  await ctx.reviewAction('selectpage');
  assert.equal(selections,1);
  ctx.selected.add(13); // selected after the submitted batch
  pending.shift().resolve({count:2,undo_token:'a'.repeat(32)});
  await Promise.all(accept);
  assert.deepEqual([...ctx.selected],[13], 'Newly selected face was erased');
  assert.equal(renders,1);
  assert.equal(ctx.lastUndoToken,'a'.repeat(32));
  assert.equal(calls.length,1);

  // Second action may now proceed; when the user navigates while it waits,
  // its completion must not clear the NEW Person's page selection or paint.
  ctx.tab='untagged';ctx.page=2;ctx.renderEpoch++;ctx.selected=new Set([22]);
  const hide=ctx.reviewAction('ignore');
  assert.equal(pending.length,1);
  assert.deepEqual(JSON.parse(JSON.stringify(calls.at(-1))),{uri:'/api/face/ignore',payload:{face_ids:[22]}});
  ctx.tab='person';ctx.person=99;ctx.page=3;ctx.personMode='faces';
  ctx.renderEpoch++;ctx.selected=new Set([555]);
  pending.shift().resolve({count:1,undo_token:'b'.repeat(32)});
  await hide;
  assert.deepEqual([...ctx.selected],[555], 'Older response erased new page selection');
  assert.equal(renders,1, 'Older response stole newer navigation render');
  assert.equal(ctx.lastUndoToken,'b'.repeat(32));
  assert.match(messages.at(-1),/previous view/);

  // Failed POST must release guard. A deliberate retry is a new request.
  const failed=ctx.reviewAction('reject');
  assert.equal(pending.length,1);
  pending.shift().reject(Error('Synthetic network interruption'));
  await failed;
  assert.match(messages.at(-1),/Synthetic network interruption/);
  const retry=ctx.reviewAction('reject');
  assert.equal(pending.length,1,'Failure permanently locked review actions');
  pending.shift().resolve({count:1,undo_token:'c'.repeat(32)});
  await retry;
  assert.equal(ctx.lastUndoToken,'c'.repeat(32));

  // Undo is single flight, including a newer Undo token arriving from an
  // independent, separately confirmed action before this request finishes.
  // Undo consumes a historical token, NOT the current face selection.
  ctx.selected.add(91);
  const undo=burst('undomanual',25);
  assert.equal(pending.length,1);
  assert.deepEqual(JSON.parse(JSON.stringify(calls.at(-1))),{uri:'/api/review/undo',payload:{
    undo_token:'c'.repeat(32)}});
  ctx.selected.add(92); // selected while Undo was still on the network
  ctx.lastUndoToken='newer-confirmed-token';
  pending.shift().resolve({count:1});
  await Promise.all(undo);
  assert.deepEqual([...ctx.selected],[91,92],
    'Undo erased faces selected before or while the rollback was pending');
  assert.equal(ctx.lastUndoToken,'newer-confirmed-token',
    'Undo response overwrote a newer review token');

  // Cancelling a confirmation never creates a request or blocks later work.
  ctx.selected.add(777);
  allowConfirm=false;
  await ctx.reviewAction('unassign');
  assert.equal(pending.length,0);
  allowConfirm=true;
  const unassign=ctx.reviewAction('unassign');
  assert.equal(pending.length,1);
  pending.shift().resolve({count:1,undo_token:'d'.repeat(32)});
  await unassign;

  // Automatic batch Undo has its own explicit confirmation and is also
  // guarded against repeated requests while the first request is pending.
  const auto=burst('undolastauto',12);
  assert.equal(pending.length,1);
  assert.equal(calls.at(-1).uri,'/api/auto/undo');
  pending.shift().resolve({count:4});
  await Promise.all(auto);

  assert.equal(calls.length,7, 'Duplicate POST escaped single-flight guard');
  console.log('PASS: 60 rapid conflicting taps coalesce; new selection and navigation survive late POST; retry/Undo/token preservation; confirmation and automatic Undo guards; seven fictional network writes only');
})().catch(error=>{console.error(error);process.exitCode=1});
