'use strict';
// Real V4 browser create handler, fictional replies and private-free
// sessionStorage only. A resolved HTTP response may still be malformed.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function part(a,b){
  const i=src.indexOf(a),j=src.indexOf(b,i+a.length);
  assert(i>=0&&j>i,'Missing production creation handler '+a);
  return src.slice(i,j);
}
const code=part('function reviewWriteContext(){','async function finishDirectReview(')+
  part('function comparablePersonName(name){','// Creating a SEPARATE person is cheap')+
  '\nthis.create=createReviewedPerson;'+
  'this.keys=()=>Array.from(pendingPersonCreateIds);'+
  'this.verify=verifyPendingPersonCreates;';
const validToken='f'.repeat(32);
let generation=0;
function fixture(response){
  const requests=[],toasts=[],undos=[],repaints=[];
  const storage=new Map();
  const ctx={
    tab:'people',page:0,person:null,group:null,personMode:'photos',
    renderEpoch:1,lastUndoToken:null,
    sessionStorage:{
      getItem:key=>storage.has(key)?storage.get(key):null,
      setItem:(key,value)=>storage.set(key,String(value))
    },
    crypto:{getRandomValues:buffer=>{
      const counter=++generation;
      for(let i=0;i<buffer.length;i++)buffer[i]=(counter*19+i*7)&255;
      return buffer;
    }},
    get:async url=>{assert.equal(url,'/api/people?covers=0');return []},
    post:async(url,payload)=>{
      requests.push([url,JSON.parse(JSON.stringify(payload))]);
      if(url==='/api/person/create')return response;
      if(url==='/api/person/create/status')return {
        status:'committed',person_id:277,undo_token:validToken
      };
      throw Error('Unexpected URL '+url);
    },
    toast:message=>toasts.push(message),
    keepReviewUndo:reply=>{if(reply?.undo_token)undos.push(reply.undo_token)},
    confirm:()=>false,
    choosePerson:async()=>{throw Error('No homonyms expected')}
  };
  vm.createContext(ctx);vm.runInContext(code,ctx);
  ctx.repaintReviewKeepingPosition=async()=>{repaints.push(1)};
  return {ctx,requests,toasts,undos,storage,repaints};
}
async function make(f){
  let completed=0;
  const task=f.ctx.create('Fictional Unknown',{name:'Fictional Unknown'},
    async()=>{throw Error('Unexpected existing Person')},
    async()=>{completed++});
  return {task,completed:()=>completed};
}
(async()=>{
  // Distinguish a well-formed server ACK from merely resolving r.json().
  const invalid=[
    undefined,null,{},[],
    {person_id:12},
    {person_id:0,undo_token:validToken},
    {person_id:'12',undo_token:validToken},
    {person_id:12,undo_token:'not-a-token'},
    {person_id:12,undo_token:null},
    {person_id:12,undo_token:null,idempotent_replay:'true'},
  ];
  for(const response of invalid){
    const f=fixture(response),job=await make(f);
    await assert.rejects(job.task,/Could not verify person creation: Incomplete person creation acknowledgement/);
    assert.equal(f.requests.length,1);
    assert.equal(f.requests[0][0],'/api/person/create');
    const key=f.requests[0][1].request_id;
    assert.match(key,/^[0-9a-f]{32}$/);
    assert.deepEqual(Array.from(f.ctx.keys()),[key],
      'Do not erase recovery evidence after malformed 200 response');
    const stored=f.storage.get('atlas-v4-pending-person-create-ids-v1');
    assert.equal(stored,JSON.stringify([key]));
    assert(!stored.includes('Fictional'),'Never store Person names in the browser');
    assert.equal(job.completed(),0,'Malformed response cannot show a completed Person');
    assert.equal(f.undos.length,0,'No fabricated one-use Undo');
    assert(f.toasts.every(m=>!m.startsWith('Created separate person')));
    // A later READ-ONLY status request can still confirm the actual write.
    await f.ctx.verify();
    assert.equal(f.requests.length,2);
    assert.equal(f.requests[1][0],'/api/person/create/status');
    assert.equal(f.requests[1][1].request_id,key);
    assert.deepEqual(Array.from(f.ctx.keys()),[]);
  }

  const successful=fixture({person_id:144,undo_token:validToken});
  let job=await make(successful);await job.task;
  assert.equal(job.completed(),1);
  assert.deepEqual(Array.from(successful.ctx.keys()),[]);
  assert.deepEqual(successful.undos,[validToken]);

  // An exact replay of an earlier create can legitimately have an expired
  // Undo token. Do not reject a valid Person ID when the server explicitly
  // flags a replay; no fake Undo may be exposed.
  const replay=fixture({person_id:145,undo_token:null,idempotent_replay:true});
  job=await make(replay);await job.task;
  assert.equal(job.completed(),1);
  assert.deepEqual(Array.from(replay.ctx.keys()),[]);
  assert.deepEqual(replay.undos,[]);
  assert(replay.toasts.some(s=>s.includes('Earlier Person creation verified')));

  console.log('PASS: actual browser create rejects 10 malformed HTTP-200 bodies without clearing opaque recovery key or fabricating Undo; valid first ACK and expired exact replay remain compatible');
})().catch(e=>{console.error(e);process.exitCode=1});
