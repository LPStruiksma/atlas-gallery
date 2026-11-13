'use strict';
// Actual embedded browser eventAction/transaction-acknowledgement paths.
// All POSTs operate only on fictional in-memory fixtures, never a People DB.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const first=src.indexOf('// Direct review writes, including Undo');
const last=src.indexOf("\ndocument.addEventListener('input'",first);
assert(first>=0&&last>first,'Must execute real V4 direct review event dispatcher');
const pending=[],messages=[],calls=[],undo=[];
let renders=0,failRepaint=false,allowConfirm=true;
const ctx={
  tab:'person',page:3,person:17,group:null,personMode:'suggested',
  renderEpoch:5,selected:new Set([21,22]),lastUndoToken:null,
  post:(path,payload)=>new Promise((resolve,reject)=>{
    calls.push([path,JSON.parse(JSON.stringify(payload))]);pending.push({resolve,reject});
  }),
  toast:message=>messages.push(message),
  keepReviewUndo:r=>{undo.push(r.undo_token);ctx.lastUndoToken=r.undo_token},
  render:async()=>{renders++;ctx.renderEpoch++;if(failRepaint){failRepaint=false;throw Error('Synthetic render failed AFTER committed write')}},
  confirm:()=>allowConfirm,
  selectVisibleFaces:()=>{},
  clearSelectedFaces:()=>ctx.selected.clear(),
};
vm.createContext(ctx);
vm.runInContext(src.slice(first,last)+'\nthis.doReviewAction=eventAction;',ctx);
const tick=()=>new Promise(setImmediate);
const select=(ids,tab='person',mode='suggested')=>{
  ctx.tab=tab;ctx.personMode=mode;ctx.page=3;ctx.person=17;
  ctx.renderEpoch++;ctx.selected=new Set(ids);
};
(async()=>{
  const paths=[
    ['accept','/api/person/accept','person','suggested'],
    ['reject','/api/person/reject','person','suggested'],
    ['ignore','/api/face/ignore','untagged','suggested'],
    ['unassign','/api/face/unassign','person','faces'],
  ];
  for(const [action,route,tab,mode] of paths){
    select([21,22],tab,mode);
    const beforeWrites=calls.length,beforeUndo=undo.length,beforeRender=renders;
    const burst=Array.from({length:60},()=>ctx.doReviewAction(action));
    assert.equal(pending.length,1,'Rapid clicks must send ONE review mutation');
    assert.equal(calls.at(-1)[0],route);
    pending.shift().reject(Error('Synthetic reply LOST after SQLite commit'));
    await Promise.all(burst);
    assert.equal(calls.length,beforeWrites+1);
    assert.deepEqual([...ctx.selected],[21,22],
      'No acknowledgement: keep reviewer selection visible for inspection');
    assert.equal(undo.length,beforeUndo,
      'No acknowledgement: never manufacture an Undo token');
    assert.equal(renders,beforeRender,'Unverified write must not repaint the page');
    assert.match(messages.at(-1),/Could not verify review change:/);
    assert.match(messages.at(-1),/inspect People or face tags before retrying/);
    assert(!messages.at(-1).includes('No changes'));

    // An independent, deliberate follow-up is allowed; once acknowledged it
    // must be reported as saved even when local repaint later fails.
    failRepaint=true;
    const retry=ctx.doReviewAction(action);
    assert.equal(pending.length,1);
    const token=action==='accept'?'a'.repeat(32):action==='reject'?'b'.repeat(32):action==='ignore'?'c'.repeat(32):'d'.repeat(32);
    pending.shift().resolve({count:2,undo_token:token});
    await retry;
    assert.equal(undo.at(-1),token);
    assert.equal(renders,beforeRender+1);
    assert.equal(ctx.selected.size,0);
    assert.match(messages.at(-1),/Review saved; could not refresh view:/);
    assert.match(messages.at(-1),/Undo remains available if still valid/);
    assert(!messages.at(-1).includes('Could not verify review change'));
  }

  // A confirmed write whose response arrives AFTER moving to a different
  // Person must preserve the new Person's selected faces and not repaint.
  select([31,32],'person','suggested');
  const oldAction=ctx.doReviewAction('accept');
  assert.equal(pending.length,1);
  ctx.person=89;ctx.renderEpoch++;ctx.selected=new Set([87,88]);
  const beforeRender=renders;
  pending.shift().resolve({count:2,undo_token:'e'.repeat(32)});
  await oldAction;
  assert.deepEqual([...ctx.selected],[87,88]);
  assert.equal(renders,beforeRender);
  assert.equal(ctx.lastUndoToken,'e'.repeat(32));
  assert.match(messages.at(-1),/previous view/);

  // Browser confirmation cancellation still sends no write.
  select([71],'person','faces');allowConfirm=false;
  const beforeWrites=calls.length;
  await ctx.doReviewAction('unassign');
  assert.equal(calls.length,beforeWrites);
  console.log('PASS: four real V4 review actions x 60 failed-ack taps remain unknown, preserve selection and Undo, acknowledged writes survive failed repaint, stale tabs are isolated');
})().catch(error=>{console.error(error);process.exitCode=1});
