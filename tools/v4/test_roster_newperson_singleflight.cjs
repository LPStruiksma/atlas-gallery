'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(begin,end){
  const a=src.indexOf(begin),b=src.indexOf(end,a+begin.length);
  assert(a>=0&&b>a,'Must execute production browser function '+begin);
  return src.slice(a,b);
}
const script=actual('function reviewWriteContext(){','async function finishDirectReview(')+
  actual('function comparablePersonName(name){','// Creating a SEPARATE person is cheap')+
  '\nthis.createFromRoster=createPersonFromRoster;';
const pending=[],calls=[],undo=[],shown=[];
let prompts=0,chosenName='Fixture Iris',mode='normal';
let fixtureKeySeed=0;
const ctx={
  lastUndoToken:null,
  // Deterministic fictional secure-random stub; production requires Web Crypto.
  crypto:{getRandomValues:bytes=>{const seed=++fixtureKeySeed;for(let i=0;i<bytes.length;i++)bytes[i]=(seed*29+i*17)&255;return bytes}},
  tab:'people',page:0,person:null,group:null,personMode:'photos',
  renderEpoch:1,peopleQuery:'iris',peopleListPage:6,peopleReturn:null,pickerState:null,
  window:{scrollY:620},
  promptPersonName:()=>{prompts++;return chosenName},
  get:uri=>{
    assert.equal(uri,'/api/people?covers=0');
    calls.push(['get',uri]);return new Promise((resolve,reject)=>pending.push({kind:'get',resolve,reject}));
  },
  post:(uri,payload)=>{
    assert.equal(uri,'/api/person/create');
    calls.push(['post',uri,JSON.parse(JSON.stringify(payload))]);
    return new Promise((resolve,reject)=>pending.push({kind:'post',resolve,reject}));
  },
  toast:msg=>calls.push(['toast',msg]),
  keepReviewUndo:r=>undo.push(r.undo_token),
  go:dest=>{shown.push(dest);ctx.tab=dest;ctx.renderEpoch++},
  confirm:()=>true,
  choosePerson:async options=>{calls.push(['chooser',options.title]);ctx.pickerState={options}},
};
vm.createContext(ctx);vm.runInContext(script,ctx);
function respond(kind,val,fail=false){
  const step=pending.shift();assert(step,'Missing '+kind);assert.equal(step.kind,kind);
  if(fail)step.reject(Error(String(val)));else step.resolve(val);
}
const tick=()=>new Promise(setImmediate);
const writes=()=>calls.filter(x=>x[0]==='post').length;
const reads=()=>calls.filter(x=>x[0]==='get').length;
const burst=n=>Array.from({length:n},()=>ctx.createFromRoster());
const reset=()=>{ctx.tab='people';ctx.renderEpoch++;ctx.person=null;ctx.pickerState=null};
(async()=>{
  // The user's first intent owns the New Person prompt, roster lookup and
  // confirmed write; other fast taps must not create competing dialog flows.
  const requests=burst(80);
  assert.equal(prompts,1);
  assert.equal(reads(),1);
  assert.equal(pending.length,1);
  respond('get',[]);await tick();
  assert.equal(writes(),1);
  const firstPayload=calls.find(x=>x[0]==='post')[2];
  assert.equal(firstPayload.name,'Fixture Iris');
  assert.equal(firstPayload.allow_duplicate_name,false);
  assert.match(firstPayload.request_id,/^[0-9a-f]{32}$/);
  const lateTaps=burst(50);
  assert.equal(writes(),1,'A pending POST must not spawn a second creation');
  assert.equal(prompts,1);
  respond('post',{person_id:31,undo_token:'a'.repeat(32)});
  await Promise.all([...requests,...lateTaps]);
  assert.deepEqual(undo,['a'.repeat(32)]);
  assert.deepEqual(shown,['people']);
  assert.equal(ctx.createFromRoster.launching,false);

  // Duplicate-name confirmation remains visible until explicitly dismissed;
  // even a stale programmatic click cannot replace its identity chooser.
  reset();chosenName='Fictional Iris';
  const duplicated=ctx.createFromRoster();
  respond('get',[{id:41,name:'Fictional Iris'}]);await duplicated;
  assert.equal(calls.filter(x=>x[0]==='chooser').length,1);
  assert.equal(writes(),1,'Opening a homonym chooser is read-only');
  await Promise.all(burst(70));
  assert.equal(prompts,2);
  assert.equal(reads(),2);
  ctx.pickerState=null;
  chosenName=null;
  await ctx.createFromRoster();
  assert.equal(prompts,3);
  assert.equal(reads(),2,'Cancelled prompt must not fetch roster');

  // A transport failure must release the guard so a deliberate retry works.
  chosenName='Retry Profile';
  let attempt=ctx.createFromRoster();
  respond('get','Synthetic metadata error',true);
  await assert.rejects(attempt,/Synthetic metadata error/);
  assert.equal(ctx.createFromRoster.launching,false);
  attempt=ctx.createFromRoster();
  respond('get',[]);await tick();
  assert.equal(writes(),2);
  respond('post','Synthetic ambiguous failure',true);
  await assert.rejects(attempt,/Synthetic ambiguous failure/);
  assert.equal(ctx.createFromRoster.launching,false,
    'A failed write must unlock a deliberate later reviewer action');
  // Other layers report ambiguous-write outcomes; do not automatically retry.

  // A stale network reply after leaving the roster does not open a picker,
  // write, or redirect the current tab.
  reset();chosenName='Stale Iris';
  attempt=ctx.createFromRoster();
  ctx.tab='settings';ctx.renderEpoch++;
  const beforeWrites=writes(),beforeShows=shown.length;
  respond('get',[{id:99,name:'Stale Iris'}]);await attempt;
  assert.equal(writes(),beforeWrites);
  assert.equal(shown.length,beforeShows);
  assert.equal(ctx.pickerState,null);
  assert.equal(ctx.createFromRoster.launching,false);
  assert.equal(pending.length,0);
  console.log('PASS: 130 rapid New Person taps coalesce to one create/Undo; homonym modal owns intent; cancel, rejection and stale navigation safely release the lock');
})().catch(e=>{console.error(e);process.exitCode=1});
