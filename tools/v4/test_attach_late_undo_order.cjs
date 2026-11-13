'use strict';
// Production V4 existing-Person face/group attach handlers; fictional data
// and synthetic overlapping HTTP responses only. No private photo/People DB.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function part(start,end){
  const i=src.indexOf(start),j=src.indexOf(end,i+start.length);
  assert(i>=0&&j>i,'Production handler missing '+start);
  return src.slice(i,j);
}
const script=part('function reviewWriteContext(){','async function finishDirectReview(')+
  part('async function assign(){','function comparablePersonName(')+
  part('async function nameGroup(){','async function renameReviewedPerson(){')+
  '\nthis.actions={assign,nameGroup,attachWholeGroup};';
const tick=()=>new Promise(setImmediate);
const originalIds=[21,22];
function fixture(){
  const requests=[],pickers=[],toasts=[],installed=[];
  let repaintCount=0,returnCount=0;
  const ctx={
    tab:'group',group:13,page:0,person:null,personMode:'photos',
    renderEpoch:4,lastUndoToken:null,selected:new Set(originalIds),
    get:async url=>{assert.equal(url,'/api/group/count?id=13');return {count:3}},
    post:(url,payload)=>new Promise((resolve,reject)=>{
      requests.push({url,payload:JSON.parse(JSON.stringify(payload)),resolve,reject});
    }),
    choosePerson:async options=>{pickers.push(options)},
    promptPersonName:()=> 'Fictional New Identity',
    createReviewedPerson:async(_name,_payload,onExisting,onComplete)=>{
      await onExisting({id:92,name:'Fictional Existing'});
      await onComplete();
    },
    keepReviewUndo:reply=>{
      installed.push(reply.undo_token);
      ctx.lastUndoToken=reply.undo_token;
    },
    toast:message=>toasts.push(message),
    repaintReviewKeepingPosition:async()=>{repaintCount++;ctx.renderEpoch++},
    returnToGroups:async()=>{returnCount++},
  };
  vm.createContext(ctx);vm.runInContext(script,ctx);
  const navigate=()=>{ctx.tab='person';ctx.person=91;ctx.renderEpoch++;
    ctx.selected=new Set([900,901])};
  const newer=()=>{const token='b'.repeat(32);ctx.keepReviewUndo({undo_token:token});return token};
  const resolve=()=>{assert.equal(requests.length,1);const request=requests.shift();request.resolve({count:request.url==='/api/group/attach'?3:2,undo_token:'a'.repeat(32)})};
  return {ctx,requests,pickers,toasts,installed,navigate,newer,resolve,
    repaints:()=>repaintCount,returns:()=>returnCount};
}
(async()=>{
  // 1. Explicit existing-Person selected-face assignment after entering its
  // confirmation picker. A newer Person/review has already supplied Undo.
  let f=fixture();
  await f.ctx.actions.assign();
  assert.equal(f.pickers.length,1);
  let task=f.pickers[0].onChoose({id:9,name:'Fictional Existing'});
  assert.equal(f.requests.length,1);
  assert.equal(f.requests[0].url,'/api/person/assign');
  assert.deepEqual(f.requests[0].payload,{person_id:9,face_ids:[21,22]});
  f.navigate();let token=f.newer();f.resolve();await task;
  assert.equal(f.ctx.lastUndoToken,token);
  assert.deepEqual(f.installed,[token]);
  assert.deepEqual([...f.ctx.selected],[900,901]);
  assert.equal(f.repaints(),0);
  assert(f.toasts.some(m=>m.includes('Face assignment saved · newer Undo preserved')));

  // 2. Add selected → New Person → name already exists → choose existing.
  // It uses the same audited assign API without replacing newer Undo.
  f=fixture();await f.ctx.actions.assign();
  task=f.pickers[0].onNew();await tick();
  assert.equal(f.requests.length,1);
  assert.deepEqual(f.requests[0].payload,{person_id:92,face_ids:[21,22]});
  f.navigate();token=f.newer();f.resolve();await task;
  assert.equal(f.ctx.lastUndoToken,token);
  assert.deepEqual(f.installed,[token]);
  assert.deepEqual([...f.ctx.selected],[900,901]);
  assert.equal(f.repaints(),0);

  // 3. Explicit whole-group attachment to a confirmed existing Person.
  f=fixture();await f.ctx.actions.attachWholeGroup();
  assert.equal(f.pickers.length,1);
  task=f.pickers[0].onChoose({id:10,name:'Fictional Existing'});
  assert.equal(f.requests[0].url,'/api/group/attach');
  assert.deepEqual(f.requests[0].payload,
    {person_id:10,cluster_id:13,expected_count:3});
  f.navigate();token=f.newer();f.resolve();await task;
  assert.equal(f.ctx.lastUndoToken,token);
  assert.deepEqual(f.installed,[token]);
  assert.equal(f.returns(),0);
  assert.match(f.toasts.at(-1),/Previous-view group attachment completed/);

  // 4. Name group, explicitly resolve same-name match to an existing Person.
  f=fixture();
  task=f.ctx.actions.nameGroup();await tick();
  assert.equal(f.requests.length,1);
  assert.equal(f.requests[0].url,'/api/group/attach');
  assert.deepEqual(f.requests[0].payload,
    {person_id:92,cluster_id:13,expected_count:3});
  f.navigate();token=f.newer();f.resolve();await task;
  assert.equal(f.ctx.lastUndoToken,token);
  assert.deepEqual(f.installed,[token]);
  assert.equal(f.returns(),0);
  assert(f.toasts.some(m=>m.includes('Group attachment saved · newer Undo preserved')));

  // 5. Without any newer concurrent operation, ordinary attachment
  // continues to install the server's own one-use Undo.
  f=fixture();await f.ctx.actions.attachWholeGroup();
  task=f.pickers[0].onChoose({id:7,name:'Fictional Existing'});
  f.resolve();await task;
  assert.equal(f.ctx.lastUndoToken,'a'.repeat(32));
  assert.deepEqual(f.installed,['a'.repeat(32)]);
  assert.equal(f.returns(),1);
  assert.match(f.toasts.at(-1),/Linked 3 faces/);

  console.log('PASS: four actual existing-Person selected-face/group attach paths preserve newer reviewed Undo across navigation; ordinary attachment still presents its audited one-use Undo');
})().catch(error=>{console.error(error);process.exitCode=1});
