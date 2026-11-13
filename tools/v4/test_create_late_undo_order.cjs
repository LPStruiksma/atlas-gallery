'use strict';
// Execute production V4 Person-create helper with deliberately overlapping,
// fictional replies. Never modify private People/photos or start a server.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(a,b){
  const i=src.indexOf(a),j=src.indexOf(b,i+a.length);
  assert(i>=0&&j>i,'Actual V4 browser helper missing '+a);
  return src.slice(i,j);
}
const code=actual('function reviewWriteContext(){','async function finishDirectReview(')+
  actual('function comparablePersonName(name){','// Creating a SEPARATE person is cheap')+
  '\nthis.create=createReviewedPerson;';
const requests=[],messages=[],installed=[];
let keySeed=0,completed=0;
const ctx={
  tab:'people',page:0,person:null,group:null,personMode:'photos',
  renderEpoch:4,lastUndoToken:null,
  crypto:{getRandomValues:buf=>{
    const seed=++keySeed;
    for(let i=0;i<buf.length;i++)buf[i]=(seed*13+i*11)&255;
    return buf;
  }},
  get:async url=>{
    assert.equal(url,'/api/people?covers=0');
    return [];
  },
  post:(url,payload)=>new Promise((resolve,reject)=>{
    requests.push({url,payload:JSON.parse(JSON.stringify(payload)),resolve,reject});
  }),
  toast:message=>messages.push(message),
  keepReviewUndo:response=>{
    installed.push(response.undo_token);
    ctx.lastUndoToken=response.undo_token;
  },
  confirm:()=>true,
  choosePerson:async()=>{throw Error('Unexpected existing Person chooser')},
};
vm.createContext(ctx);vm.runInContext(code,ctx);
const tick=()=>new Promise(setImmediate);
function create(name){
  return ctx.create(name,{name},
    async()=>{throw Error('No existing Person can be selected')},
    async()=>{completed++});
}
function respond(id,token){
  assert.equal(requests.length,1,'Expected exactly one pending Person POST');
  const request=requests.shift();
  assert.equal(request.url,'/api/person/create');
  assert.match(request.payload.request_id,/^[0-9a-f]{32}$/);
  request.resolve({person_id:id,undo_token:token});
}
(async()=>{
  // The older create request started first, but another Person's reviewed
  // action completed and surfaced a newer Undo on a different page.
  const old=create('Fictional Earlier Creation');
  await tick();
  assert.equal(requests.length,1);
  ctx.tab='person';ctx.person=84;ctx.renderEpoch++;
  const newer='b'.repeat(32);
  ctx.keepReviewUndo({undo_token:newer});
  respond(201,'a'.repeat(32));
  await old;
  assert.equal(ctx.lastUndoToken,newer);
  assert.deepEqual(installed,[newer],
    'Stale Person creation must not override newer reviewed Undo');
  assert.equal(completed,0,'Old completion stole active page navigation');
  assert.equal(messages.at(-1),'Previous-view person created · newer Undo preserved');

  // Even if the reviewer stays on the same screen, an independently
  // completed newer action must keep its Undo; creation still succeeds.
  ctx.tab='people';ctx.person=null;ctx.renderEpoch++;ctx.lastUndoToken='c'.repeat(32);
  const sameView=create('Fictional Same Screen');
  await tick();
  const current='d'.repeat(32);
  ctx.keepReviewUndo({undo_token:current});
  respond(202,'e'.repeat(32));await sameView;
  assert.equal(ctx.lastUndoToken,current);
  assert.equal(completed,1);
  assert.match(messages.at(-1),/Created separate person Fictional Same Screen · newer Undo preserved/);
  assert.equal(installed.at(-1),current);

  // Without an intervening newer operation, normal creation keeps its own
  // real one-use token and proceeds with its original onComplete callback.
  ctx.renderEpoch++;ctx.lastUndoToken='f'.repeat(32);
  const regular=create('Fictional Normal Creation');
  await tick();
  respond(203,'1'.repeat(32));await regular;
  assert.equal(ctx.lastUndoToken,'1'.repeat(32));
  assert.equal(installed.at(-1),'1'.repeat(32));
  assert.equal(completed,2);
  assert.equal(messages.at(-1),'Created separate person Fictional Normal Creation');

  console.log('PASS: actual V4 Person creation preserves newer reviewed Undo after delayed response across navigation/same screen; unchanged successful creation still exposes audited Undo');
})().catch(error=>{console.error(error);process.exitCode=1});
