'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=src.indexOf('async function mergePeople(){');
const end=src.indexOf('\nasync function restoreMergedPerson(){',start);
assert(start>0&&end>start,'Execute the real V4 merge entrypoint');
const people=Array.from({length:2001},(_,i)=>({
  id:i+1,name:'Fictional Person '+(i+1),faces:i%23+1,images:i%17+1
}));
people[2].name='Alice Distinct';people[1999].name='Alice Distinct';
const calls=[],events=[];let chooser=null,focusPerson=3;
const context={
  lastUndoToken:null,
  person:focusPerson,tab:'person',page:0,group:null,personMode:'faces',renderEpoch:1,
  get:async path=>{
    assert.equal(path,'/api/people?covers=0','Entering Merge must avoid all-People portrait ranking');
    calls.push(['get',path]);return people;
  },
  choosePerson:async options=>{chooser=options;events.push('picker');},
  post:async(path,payload)=>{calls.push(['post',path,payload]);return {
    ok:true,count:people.find(p=>p.id===payload.source).faces,
    undo_token:'a'.repeat(32)
  };},
  keepReviewUndo:r=>events.push(['undo',r.undo_token]),
  toast:t=>events.push(['toast',t]),
  render:async()=>events.push('render')
};
vm.createContext(context);
const guardStart=src.indexOf('function reviewWriteContext(){');
const guardEnd=src.indexOf('async function finishDirectReview(',guardStart);
assert(guardStart>0&&guardEnd>guardStart);
vm.runInContext(src.slice(guardStart,guardEnd)+src.slice(start,end)+'\nthis.openMerge=mergePeople;',context);
(async()=>{
  await context.openMerge();
  assert.equal(calls.length,1);
  assert(chooser,'Merge chooser must be opened, not auto-select a name');
  assert.match(chooser.explanation,/Alice Distinct/);
  assert.equal(chooser.confirmDanger,true);
  assert.equal(chooser.confirmLabel,'Merge profiles');
  const source=people[1999];
  assert.match(chooser.confirmText(source),/2\d|\d/);
  assert.match(chooser.confirmText(source),/archived/);
  assert.equal(calls.some(c=>c[0]==='post'),false,'Previewing Merge is read-only');
  await assert.rejects(chooser.onChoose(people[2]),/different profile/);
  assert.equal(calls.filter(c=>c[0]==='post').length,0,'Cannot merge a person into itself');
  await chooser.onChoose(source);
  const writes=calls.filter(c=>c[0]==='post');
  assert.equal(writes.length,1,'Explicit callback should trigger exactly one merge');
  assert.equal(writes[0][1],'/api/person/merge');
  assert.equal(writes[0][2].target,3);
  assert.equal(writes[0][2].source,2000);
  assert(events.some(e=>Array.isArray(e)&&e[0]==='undo'));
  assert(events.includes('render'));
  console.log('PASS: 2001 fictional People merge reads only name/count metadata; explicit source preview, self-merge refusal, one reviewed merge, undo retained');
})().catch(error=>{console.error(error);process.exitCode=1});
