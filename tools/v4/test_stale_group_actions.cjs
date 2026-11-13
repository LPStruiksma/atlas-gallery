'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function part(start,end){const a=src.indexOf(start),b=src.indexOf(end,a+start.length);
 assert(a>0&&b>a,'Expected real V4 browser function '+start);return src.slice(a,b);}
const script=part('function reviewWriteContext(){','async function finishDirectReview(')+
 part('async function createReviewedPerson(','// Creating a SEPARATE person is cheap')+
 part('async function nameGroup(){','async function renameReviewedPerson(){')+
 part('function comparablePersonName(name){','// Creating a profile with a name already in use')+
 '\nthis.actions={nameGroup,attachWholeGroup,createReviewedPerson};';
const pending=[],calls=[],pickers=[];let returns=0,undo=[],namePrompts=0;
let fixtureKeySeed=0;
const ctx={
  lastUndoToken:null,
  // Deterministic fictional secure-random stub; production requires Web Crypto.
  crypto:{getRandomValues:bytes=>{const seed=++fixtureKeySeed;for(let i=0;i<bytes.length;i++)bytes[i]=(seed*29+i*17)&255;return bytes}},
  tab:'group',group:11,person:null,page:0,personMode:'photos',renderEpoch:1,
  get:uri=>new Promise((resolve,reject)=>{
    calls.push(['get',uri]);pending.push({uri,resolve,reject});
  }),
  post:(uri,payload)=>new Promise((resolve,reject)=>{
    calls.push(['post',uri,JSON.parse(JSON.stringify(payload))]);
    pending.push({uri,resolve,reject});
  }),
  choosePerson:async opts=>{pickers.push(opts)},
  promptPersonName:()=>{namePrompts++;return 'Fictional Alex'},
  keepReviewUndo:r=>{if(r?.undo_token)undo.push(r.undo_token)},
  toast:msg=>calls.push(['toast',msg]),
  confirm:()=>true,
  returnToGroups:async()=>{returns++},
};
vm.createContext(ctx);vm.runInContext(script,ctx);
const respond=(url,value)=>{const item=pending.shift();assert(item,'No expected request');
 assert.equal(item.uri,url);item.resolve(value);};
const changeGroup=to=>{ctx.group=to;ctx.renderEpoch++};
const writeCount=()=>calls.filter(x=>x[0]==='post').length;
const tick=()=>new Promise(setImmediate);
(async()=>{
  // Group switched while its count request is loading: no prompt/chooser/write.
  let action=ctx.actions.nameGroup();
  changeGroup(12);respond('/api/group/count?id=11',{count:4});await action;
  assert.equal(namePrompts,0);assert.equal(writeCount(),0);

  // If the count completed but name uniqueness lookup is delayed, new
  // group screen must not inherit this Create Person write.
  changeGroup(11);action=ctx.actions.nameGroup();
  respond('/api/group/count?id=11',{count:4});await tick();
  assert.equal(pending[0]?.uri,'/api/people?covers=0');
  changeGroup(12);respond('/api/people?covers=0',[]);await action;
  assert.equal(writeCount(),0);

  // Original group 11 cannot open an attachment chooser over group 12.
  changeGroup(11);action=ctx.actions.attachWholeGroup(7);
  changeGroup(12);respond('/api/group/count?id=11',{count:4});await action;
  assert.equal(pickers.length,0);

  // A picker already visible must still refuse confirmation if another
  // group becomes active before the reviewer taps the Person destination.
  changeGroup(11);action=ctx.actions.attachWholeGroup(7);
  respond('/api/group/count?id=11',{count:4});await action;
  assert.equal(pickers.length,1);
  assert.match(pickers[0].confirmText({name:'Fictional Person'}),/group #11/);
  changeGroup(12);
  await assert.rejects(pickers[0].onChoose({id:7,name:'Fictional Person'}),/group changed/);
  assert.equal(writeCount(),0);

  // A deliberately confirmed request targets original group 11 even if
  // the response arrives after switching to group 12.
  changeGroup(11);action=ctx.actions.attachWholeGroup(7);
  respond('/api/group/count?id=11',{count:4});await action;
  let posted=pickers.at(-1).onChoose({id:7,name:'Fictional Person'});
  assert.deepEqual(calls.findLast(x=>x[0]==='post').slice(1),
   ['/api/group/attach',{person_id:7,cluster_id:11,expected_count:4}]);
  changeGroup(12);respond('/api/group/attach',{count:4,undo_token:'d'.repeat(32)});await posted;
  assert.deepEqual(undo,['d'.repeat(32)]);assert.equal(returns,0);

  // Exact-name chooser is opened for group 11, then navigating to group
  // 12 before explicit confirmation forbids even a same-named attachment.
  changeGroup(11);action=ctx.actions.createReviewedPerson(
    'Fictional Alex',{name:'Fictional Alex',cluster_id:11,expected_count:4},
    async()=>{throw Error('stale existing assignment')},
    async()=>{returns++});
  respond('/api/people?covers=0',[{id:7,name:'Fictional Alex'}]);await action;
  assert.equal(pickers.length,3);
  changeGroup(12);
  await assert.rejects(pickers.at(-1).onChoose({id:7,name:'Fictional Alex'}),/view changed/);
  assert.equal(writeCount(),1);

  // Slow server response to a *confirmed* create never redirects current
  // view, but preserves its review Undo token.
  changeGroup(11);action=ctx.actions.createReviewedPerson(
    'Other Fictional',{name:'Other Fictional',cluster_id:11,expected_count:4},
    async()=>{},async()=>{returns++});
  respond('/api/people?covers=0',[]);await tick();
  assert.equal(pending[0]?.uri,'/api/person/create');
  changeGroup(12);respond('/api/person/create',{person_id:99,undo_token:'a'.repeat(32)});
  await action;
  assert.equal(returns,0);
  assert.deepEqual(undo,['d'.repeat(32),'a'.repeat(32)]);
  assert.equal(writeCount(),2,'Only two deliberately confirmed synthetic writes permitted');
  assert.equal(pending.length,0);
  console.log('PASS: delayed group count/name lookup, stale chooser and post completion cannot attach/create to new group, redirect newer review or lose Undo');
})().catch(e=>{console.error(e);process.exitCode=1});
