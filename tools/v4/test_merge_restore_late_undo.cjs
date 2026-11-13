'use strict';
// Execute actual V4 People merge/restore chooser handlers using only a
// fictional in-memory roster and deferred HTTP replies (no private DB).
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function part(a,b){const i=src.indexOf(a),j=src.indexOf(b,i+a.length);
  assert(i>=0&&j>i,'Missing production browser handler '+a);
  return src.slice(i,j)}
const code=part('function reviewWriteContext(){','async function finishDirectReview(')+
  part('async function mergePeople(){','async function moveReviewPage(')+
  '\nthis.actions={mergePeople,restoreMergedPerson};';
function fixture(){
  const requests=[],pickers=[],messages=[],installed=[];let renders=0;
  const ctx={
    tab:'person',person:7,group:null,page:0,personMode:'faces',
    renderEpoch:3,lastUndoToken:'c'.repeat(32),selected:new Set([81]),
    get:async url=>{
      if(url==='/api/people?covers=0')return [
        {id:7,name:'Fictional Target',faces:8,images:4},
        {id:8,name:'Fictional Source',faces:2,images:2}
      ];
      assert.equal(url,'/api/people/merged?target=7');
      return [{id:8,name:'Fictional Archived',faces:2}];
    },
    choosePerson:async opts=>{pickers.push(opts)},
    post:(url,payload)=>new Promise((resolve,reject)=>{
      requests.push({url,payload:JSON.parse(JSON.stringify(payload)),resolve,reject});
    }),
    keepReviewUndo:result=>{
      if(result?.undo_token){
        installed.push(result.undo_token);
        ctx.lastUndoToken=result.undo_token;
      }
    },
    toast:msg=>messages.push(msg),
    render:async()=>{renders++;ctx.renderEpoch++}
  };
  vm.createContext(ctx);vm.runInContext(code,ctx);
  const navigate=()=>{
    ctx.person=99;ctx.renderEpoch++;ctx.selected=new Set([901]);
  };
  const newer=()=>{const token='b'.repeat(32);ctx.keepReviewUndo({undo_token:token});return token};
  const complete=(response)=>{
    assert.equal(requests.length,1);
    requests.shift().resolve(response);
  };
  return {ctx,requests,pickers,messages,installed,navigate,newer,complete,
    renders:()=>renders};
}
(async()=>{
  // Late merge must NOT overwrite independent newer Undo after navigation.
  let f=fixture();
  await f.ctx.actions.mergePeople();
  assert.equal(f.pickers.length,1);
  let task=f.pickers[0].onChoose({id:8,name:'Fictional Source',faces:2});
  assert.deepEqual(f.requests[0].payload,{target:7,source:8});
  assert.equal(f.requests[0].url,'/api/person/merge');
  f.navigate();let token=f.newer();
  f.complete({ok:true,count:2,undo_token:'a'.repeat(32)});await task;
  assert.equal(f.ctx.lastUndoToken,token);
  assert.deepEqual(f.installed,[token]);
  assert.deepEqual([...f.ctx.selected],[901]);
  assert.equal(f.renders(),0);
  assert(f.messages.some(x=>x.includes('Profile merge saved · newer Undo preserved')));
  assert.equal(f.messages.at(-1),'Previous-view merge completed');

  // Normal merge still presents its own server-supplied one-use Undo.
  f=fixture();await f.ctx.actions.mergePeople();
  task=f.pickers[0].onChoose({id:8,name:'Fictional Source',faces:2});
  f.complete({ok:true,count:2,undo_token:'d'.repeat(32)});await task;
  assert.equal(f.ctx.lastUndoToken,'d'.repeat(32));
  assert.deepEqual(f.installed,['d'.repeat(32)]);
  assert.equal(f.renders(),1);
  assert.equal(f.messages.at(-1),'Profiles merged');

  // A confirmed restore consumes the OLD archived-merge Undo token.
  // If a newer independent Undo arrives during the POST, don't erase it.
  f=fixture();await f.ctx.actions.restoreMergedPerson();
  assert.equal(f.pickers.length,1);
  task=f.pickers[0].onChoose({id:8,name:'Fictional Archived',faces:2});
  assert.deepEqual(f.requests[0].payload,{target:7,source:8});
  assert.equal(f.requests[0].url,'/api/person/restore');
  f.navigate();token=f.newer();
  f.complete({count:2});await task;
  assert.equal(f.ctx.lastUndoToken,token);
  assert.deepEqual(f.installed,[token]);
  assert.deepEqual([...f.ctx.selected],[901]);
  assert.equal(f.renders(),0);
  assert(f.messages.some(x=>x.includes('Profile restored · newer Undo preserved')));
  assert.equal(f.messages.at(-1),'Previous-view restore completed');

  // Without an intervening newer action, restore must retire the token
  // associated with the archived merge as it always has.
  f=fixture();await f.ctx.actions.restoreMergedPerson();
  task=f.pickers[0].onChoose({id:8,name:'Fictional Archived',faces:2});
  f.complete({count:2});await task;
  assert.equal(f.ctx.lastUndoToken,null);
  assert.equal(f.installed.length,0);
  assert.equal(f.renders(),1);
  assert.match(f.messages.at(-1),/Restored Fictional Archived · 2 faces/);

  console.log('PASS: production V4 merge and restore keep newer Undo after delayed ACK/new Person navigation; ordinary merge installs audited token and ordinary restore retires consumed one');
})().catch(error=>{console.error(error);process.exitCode=1});
