'use strict';
// Actual V4 Person rename handler with only fictional names and HTTP replies.
// A malformed HTTP 200 is not evidence a SQLite name change was committed.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(begin,end){
  const a=source.indexOf(begin),b=source.indexOf(end,a+begin.length);
  assert(a>=0&&b>a,'Actual browser helper missing: '+begin);
  return source.slice(a,b);
}
const script=actual('function reviewWriteContext(){','async function finishDirectReview(')+
  actual('function comparablePersonName(name){','// Creating a profile with a name already in use')+
  actual('async function renameReviewedPerson(){','async function mergePeople(){')+
  '\nthis.rename=renameReviewedPerson;';
function fixture(reply,options={}){
  const requests=[],toasts=[],prompts=[];
  let renders=0;
  const ctx={
    tab:'person',page:0,person:4,group:null,personMode:'faces',
    renderEpoch:2,pickerState:null,
    promptPersonName:()=>options.name??'Fictional Dana',
    get:async path=>{
      assert.equal(path,'/api/people?covers=0');
      return [{id:4,name:'Fictional Old',faces:8},
              ...(options.homonym?[{id:5,name:'fictional dana',faces:12}]:[])];
    },
    post:async(path,payload)=>{
      requests.push({path,payload:JSON.parse(JSON.stringify(payload))});
      assert.equal(path,'/api/person/rename');
      return reply;
    },
    confirm:message=>{prompts.push(message);return options.approve??false},
    render:async()=>{
      renders++;ctx.renderEpoch++;
      if(options.renderFailure)throw Error('Synthetic repaint failure AFTER committed rename');
    },
    toast:message=>toasts.push(message),
  };
  vm.createContext(ctx);vm.runInContext(script,ctx);
  return {ctx,requests,toasts,prompts,renders:()=>renders};
}
(async()=>{
  const malformed=[
    undefined,null,{},[],true,false,0,'true',
    {ok:false},{ok:0},{ok:1},{ok:'true'},{ok:null},
  ];
  for(const reply of malformed){
    const f=fixture(reply);
    await f.ctx.rename();
    assert.equal(f.requests.length,1);
    assert.deepEqual(f.requests[0].payload,
      {person_id:4,name:'Fictional Dana',allow_duplicate_name:false});
    assert.equal(f.renders(),0,'Incomplete ACK must NOT optimistically redraw');
    assert.equal(f.ctx.rename.launching,false);
    assert.match(f.toasts.at(-1),/Could not verify rename:/);
    assert.match(f.toasts.at(-1),/Incomplete person rename acknowledgement/);
    assert.match(f.toasts.at(-1),/check People before retrying/);
    assert(!f.toasts.some(x=>x==='Person name updated'));
  }

  let f=fixture({ok:true});
  await f.ctx.rename();
  assert.equal(f.renders(),1);
  assert.equal(f.toasts.at(-1),'Person name updated');

  // Failure AFTER an acknowledged SQLite change is a repaint issue,
  // not an unknown server write and never claims the old name survived.
  f=fixture({ok:true},{renderFailure:true});
  await f.ctx.rename();
  assert.equal(f.renders(),1);
  assert.match(f.toasts.at(-1),/Person name saved; could not refresh view/);
  assert.match(f.toasts.at(-1),/Synthetic repaint failure AFTER committed rename/);
  assert.equal(f.ctx.rename.launching,false);

  // Same-name homonyms always require the explicit separate-identity
  // decision BEFORE POST, including when server ACK is well-formed.
  f=fixture({ok:true},{homonym:true,approve:false});
  await f.ctx.rename();
  assert.equal(f.requests.length,0);
  assert.equal(f.prompts.length,1);
  f=fixture({ok:true},{homonym:true,approve:true});
  await f.ctx.rename();
  assert.equal(f.requests.length,1);
  assert.equal(f.requests[0].payload.allow_duplicate_name,true);

  // A delayed confirmed rename from the previous Person may NOT redraw
  // the newer Person after navigation.
  const pending=[],events=[];
  const ctx={
    tab:'person',person:4,page:0,group:null,personMode:'faces',renderEpoch:2,
    pickerState:null,promptPersonName:()=> 'Fictional Later',
    get:async()=>[{id:4,name:'Fictional Current'}],
    post:(url,payload)=>new Promise(resolve=>pending.push(resolve)),
    toast:msg=>events.push(msg),
    render:async()=>{throw Error('Old response stole current view')},
  };
  vm.createContext(ctx);vm.runInContext(script,ctx);
  const task=ctx.rename();await new Promise(setImmediate);
  assert.equal(pending.length,1);
  ctx.person=77;ctx.renderEpoch++;
  pending.shift()({ok:true});await task;
  assert.equal(events.at(-1),'Previous-view rename completed');

  console.log('PASS: actual V4 Person rename rejects 13 malformed HTTP-success receipts, identifies ACK+repaint failure, preserves explicit homonym review and stale-page focus');
})().catch(error=>{console.error(error);process.exitCode=1});
