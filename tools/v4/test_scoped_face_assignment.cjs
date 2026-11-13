'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const a=src.indexOf('async function assign(){');
const b=src.indexOf('\nfunction comparablePersonName(',a);
const h=src.indexOf('function reviewWriteContext(){');
const z=src.indexOf('async function finishDirectReview(',h);
assert(a>0&&b>a&&h>0&&z>h);
const writes=[],pending=[],pickers=[],messages=[];
let renders=0,createCalls=0,prompts=0;
const ctx={
 tab:'group',group:7,person:null,page:0,personMode:'photos',renderEpoch:3,
 selected:new Set([11,12]),lastUndoToken:null,
 choosePerson:async options=>pickers.push(options),
 post:(endpoint,payload)=>new Promise((resolve,reject)=>{
   writes.push([endpoint,JSON.parse(JSON.stringify(payload))]);
   pending.push({resolve,reject});
 }),
 keepReviewUndo:result=>{if(result?.undo_token)ctx.lastUndoToken=result.undo_token},
 toast:message=>messages.push(message),
 render:async()=>{renders++},
 // This existing fixture isolates assignment; full viewport/focus continuity
 // is executed against the real helper by test_review_position_after_write.cjs.
 repaintReviewKeepingPosition:async()=>{renders++},
 promptPersonName:()=>{prompts++;return 'Imaginary New Person'},
 createReviewedPerson:async(name,payload,onExisting,onComplete)=>{
   createCalls++;
   assert.equal(name,'Imaginary New Person');
   assert.equal(payload.face_ids.length,2);
   await onExisting({id:88,name:'Imaginary Existing'});
   await onComplete();
 }
};
vm.createContext(ctx);
vm.runInContext(src.slice(h,z)+src.slice(a,b)+'\nthis.assignFaces=assign;',ctx);
const changeGroup=id=>{ctx.group=id;ctx.renderEpoch++};
const tick=()=>new Promise(setImmediate);
(async()=>{
  // A chooser opened for group 7 cannot be confirmed while group 8 is active.
  await ctx.assignFaces();assert.equal(pickers.length,1);
  assert.match(pickers[0].confirmText({name:'Imaginary'}),/exactly 2 selected faces/);
  changeGroup(8);
  await assert.rejects(pickers[0].onChoose({id:9,name:'Imaginary'}),/view changed/);
  assert.equal(writes.length,0);

  // A confirmed assignment keeps any newly selected face, not clear all.
  changeGroup(7);await ctx.assignFaces();
  let done=pickers.at(-1).onChoose({id:9,name:'Imaginary'});
  assert.deepEqual(writes.at(-1),['/api/person/assign',{person_id:9,face_ids:[11,12]}]);
  ctx.selected.add(13);
  pending.shift().resolve({count:2,undo_token:'a'.repeat(32)});
  await done;
  assert.deepEqual([...ctx.selected],[13],'Newly selected face was erased');
  assert.equal(ctx.lastUndoToken,'a'.repeat(32));
  assert.equal(renders,1);

  // A successful POST after switching to another group cannot rerender or
  // erase the new page, but the undo token of the confirmed write remains.
  ctx.selected=new Set([21,22]);changeGroup(7);
  await ctx.assignFaces();
  done=pickers.at(-1).onChoose({id:9,name:'Imaginary'});
  assert.deepEqual(writes.at(-1),['/api/person/assign',{person_id:9,face_ids:[21,22]}]);
  changeGroup(8);ctx.selected=new Set([99]);
  pending.shift().resolve({count:2,undo_token:'b'.repeat(32)});
  await done;
  assert.deepEqual([...ctx.selected],[99]);
  assert.equal(renders,1);
  assert.equal(ctx.lastUndoToken,'b'.repeat(32));

  // Create-new shortcut also cannot proceed after choosing another group.
  changeGroup(7);ctx.selected=new Set([31,32]);await ctx.assignFaces();
  changeGroup(8);
  await assert.rejects(pickers.at(-1).onNew(),/view changed/);
  assert.equal(prompts,0);assert.equal(createCalls,0);

  // Existing-name redirection inside Create New keeps the intended original
  // face IDs, and retains a newly selected face after its POST finishes.
  changeGroup(7);ctx.selected=new Set([31,32]);await ctx.assignFaces();
  done=pickers.at(-1).onNew();
  await tick();
  assert.equal(prompts,1);assert.equal(createCalls,1);
  assert.deepEqual(writes.at(-1),['/api/person/assign',{person_id:88,face_ids:[31,32]}]);
  ctx.selected.add(33);
  pending.shift().resolve({count:2,undo_token:'c'.repeat(32)});
  await done;
  assert.deepEqual([...ctx.selected],[33]);
  assert.equal(ctx.lastUndoToken,'c'.repeat(32));
  assert.equal(renders,2);
  assert.equal(writes.length,3);
  console.log('PASS: selected-face Person chooser pins original review screen/face IDs, ignores stale confirmation, preserves newer selection and Undo after delayed POST, guarded new-person route');
})().catch(e=>{console.error(e);process.exitCode=1});
