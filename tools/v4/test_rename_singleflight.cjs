'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(start,end){
  const a=source.indexOf(start),b=source.indexOf(end,a+start.length);
  assert(a>=0&&b>a,'Run real V4 function '+start);
  return source.slice(a,b);
}
const script=actual('function reviewWriteContext(){','async function finishDirectReview(')+
  actual('function comparablePersonName(name){','// Creating a profile with a name already in use')+
  actual('async function renameReviewedPerson(){','async function mergePeople(){')+
  '\nthis.rename=renameReviewedPerson;';
const calls=[],pending=[],toasts=[];
let prompts=0,renders=0,confirmations=0,name='Fictional Zoe',approve=true;
const ctx={
  tab:'person',page:2,person:41,group:null,personMode:'faces',renderEpoch:3,
  pickerState:null,
  promptPersonName:()=>{prompts++;return name},
  get:url=>{assert.equal(url,'/api/people?covers=0');
    calls.push(['get',url]);return new Promise((resolve,reject)=>pending.push({kind:'get',resolve,reject}))},
  post:(url,data)=>{assert.equal(url,'/api/person/rename');
    calls.push(['post',url,JSON.parse(JSON.stringify(data))]);
    return new Promise((resolve,reject)=>pending.push({kind:'post',resolve,reject}))},
  confirm:()=>{confirmations++;return approve},
  toast:message=>toasts.push(message),
  render:async()=>{renders++}
};
vm.createContext(ctx);vm.runInContext(script,ctx);
function finish(kind,result,fail=false){
  const entry=pending.shift();assert(entry,'Missing pending '+kind);
  assert.equal(entry.kind,kind);
  if(fail)entry.reject(Error(result));else entry.resolve(result);
}
const tick=()=>new Promise(setImmediate);
const count=kind=>calls.filter(x=>x[0]===kind).length;
const burst=n=>Array.from({length:n},()=>ctx.rename());
(async()=>{
  // Rapid taps must not create 80 prompts or pending renamed writes.
  let actions=burst(80);
  assert.equal(prompts,1);assert.equal(count('get'),1);
  finish('get',[{id:41,name:'Fictional Old'},{id:42,name:'Fictional Other'}]);
  await tick();
  assert.equal(count('post'),1);
  assert.deepEqual(calls.find(x=>x[0]==='post').slice(1),
    ['/api/person/rename',{person_id:41,name:'Fictional Zoe',allow_duplicate_name:false}]);
  const repeated=burst(50);
  assert.equal(count('get'),1);assert.equal(count('post'),1);
  finish('post',{ok:true});await Promise.all([...actions,...repeated]);
  assert.equal(renders,1);
  assert.equal(ctx.rename.launching,false);

  // Metadata lookup failure and prompt cancellation must release the guard.
  name=null;await ctx.rename();assert.equal(prompts,2);assert.equal(count('get'),1);
  name='Fictional Renamed';
  let operation=ctx.rename();
  finish('get','Synthetic metadata failure',true);
  await assert.rejects(operation,/Synthetic metadata failure/);
  assert.equal(ctx.rename.launching,false);

  // Same-name review still needs the user's separate-identity approval.
  name='Ａｌｅｘ  Doe';approve=false;
  operation=ctx.rename();
  finish('get',[{id:9,name:'Alex Doe',faces:3},{id:41,name:'Current'}]);
  await operation;
  assert.equal(confirmations,1);assert.equal(count('post'),1);
  approve=true;
  operation=ctx.rename();
  finish('get',[{id:9,name:'Alex Doe',faces:3},{id:41,name:'Current'}]);
  await tick();
  assert.equal(confirmations,2);
  assert.deepEqual(calls.findLast(x=>x[0]==='post')[2],
    {person_id:41,name:'Ａｌｅｘ  Doe',allow_duplicate_name:true});
  finish('post',{ok:true});await operation;
  assert.equal(renders,2);

  // A lost acknowledgement can occur after the server committed. Never
  // claim the name is unchanged or let another fast tap race the pending POST.
  name='Maybe Saved';
  operation=ctx.rename();
  finish('get',[{id:41,name:'Current'}]);await tick();
  const before=count('post');
  assert.equal(ctx.rename.launching,true);
  await Promise.all(burst(20));
  assert.equal(count('post'),before,'Pending POST allowed competing rename');
  finish('post','Synthetic response lost after commit',true);await operation;
  assert.equal(ctx.rename.launching,false);
  assert.match(toasts.at(-1),
    /Could not verify rename:.*response lost after commit.*check People before retrying/);
  assert.equal(renders,2,'Ambiguous rename must not redraw a misleading old profile');

  // A slow old Person roster request must never target a new Person.
  name='Old Person Only';operation=ctx.rename();
  const beforePosts=count('post');ctx.person=99;ctx.renderEpoch++;
  finish('get',[{id:41,name:'Old'}]);await operation;
  assert.equal(count('post'),beforePosts);assert.equal(ctx.rename.launching,false);

  // Picker modal still owns focus and blocks a concurrent rename.
  ctx.pickerState={submitting:false};
  const beforePrompts=prompts;
  await ctx.rename();
  assert.equal(prompts,beforePrompts);
  assert.equal(pending.length,0);
  console.log('PASS: 130 rapid Rename taps coalesce; homonyms require review, failures/abort release, lost acknowledgement reports uncertainty, stale profile and open picker protected');
})().catch(error=>{console.error(error);process.exitCode=1});
