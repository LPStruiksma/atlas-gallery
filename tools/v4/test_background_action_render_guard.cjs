'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(start,end){
 const a=source.indexOf(start),b=source.indexOf(end,a+start.length);
 assert(a>0&&b>a,'Missing actual V4 browser function '+start);
 return source.slice(a,b);
}
const code=actual('async function action(url,data,msg){','const btn=')+
 actual('function reviewWriteContext(){','async function finishDirectReview(')+
 '\nthis.startBackground=action;';
const requests=[],messages=[];
let renders=0,failNextRender=false;
const ctx={
  tab:'groups',page:2,person:null,group:null,personMode:'photos',
  renderEpoch:7,
  post:(path,payload)=>new Promise((resolve,reject)=>{
    requests.push({path,payload:JSON.parse(JSON.stringify(payload)),resolve,reject});
  }),
  render:async()=>{renders++;ctx.renderEpoch++;if(failNextRender){failNextRender=false;throw Error('Synthetic render lost after ACK')}},
  toast:message=>messages.push(message)
};
vm.createContext(ctx);
vm.runInContext(code,ctx);
const latest=()=>requests.at(-1);
(async()=>{
  // Starting a background scan on an unchanged screen should repaint once.
  const first=ctx.startBackground('/api/job/start',{kind:'scan'},'scan started');
  assert.equal(requests.length,1);
  assert.deepEqual([latest().path,latest().payload],
                   ['/api/job/start',{kind:'scan'}]);
  latest().resolve({job_id:4});
  assert.deepEqual(await first,{job_id:4});
  assert.equal(renders,1);assert.equal(messages.at(-1),'scan started');

  // A stop request should still reach the server, but its late completion
  // after navigation must not redraw the next Person or clear focus.
  const stop=ctx.startBackground('/api/job/stop',{},'Pause requested');
  const existing=ctx.renderEpoch;
  ctx.tab='person';ctx.person=99;ctx.page=8;ctx.renderEpoch++;
  latest().resolve({stopping:true});
  assert.deepEqual(await stop,{stopping:true});
  assert.equal(renders,1,'Old stop acknowledgement repainted Person screen');
  assert(ctx.renderEpoch>existing);

  // Even the same tab/page/Person has moved on if a newer render replaced
  // it. An older job completion cannot repaint after that generation.
  const group=ctx.startBackground('/api/job/start',{kind:'group'},'group started');
  const epoch=ctx.renderEpoch;
  ctx.renderEpoch++;latest().resolve({job_id:5});
  await group;assert.equal(renders,1);
  assert(ctx.renderEpoch>epoch);

  // A new folder action does not steal another screen, while its success
  // message honestly reports that the command was accepted.
  const root=ctx.startBackground('/api/roots/add',{path:'/fictional/'},'Folder added');
  ctx.tab='settings';ctx.renderEpoch++;
  latest().resolve({added:true});await root;
  assert.equal(renders,1);
  assert.equal(messages.at(-1),'Folder added');

  // Errors should be surfaced, but cannot force any stale UI repaint.
  const fail=ctx.startBackground('/api/job/start',{kind:'propose'},'propose started');
  ctx.tab='groups';ctx.renderEpoch++;
  latest().reject(Error('Synthetic server rejected request'));await fail;
  assert.equal(renders,1);
  assert.match(messages.at(-1),/Synthetic server rejected request/);
  assert.equal(messages.filter(m=>m==='propose started').length,0);
  assert.equal(requests.length,5);
  assert.match(messages.at(-1),
    /Could not verify command: Synthetic server rejected request · check status before retrying/);

  // The server can ACK a job before a repaint fails. The error must explain
  // that the command was accepted rather than implying an unsuccessful write.
  failNextRender=true;
  const ackButNoRepaint=ctx.startBackground('/api/job/start',
    {kind:'scan'},'scan started');
  latest().resolve({job_id:6});
  assert.equal(await ackButNoRepaint,undefined,
    'A failed local repaint should not invent a success result');
  assert.equal(renders,2);
  assert.match(messages.at(-1),
    /Command accepted; could not refresh view: Synthetic render lost after ACK/);
  assert(!messages.some(m=>m==='Error: Synthetic render lost after ACK'));

  // After uncertain transport error, the helper makes no autonomous second
  // request. Reviewer can inspect status or choose another explicit action.
  assert.equal(requests.length,6);
  console.log('PASS: actual V4 background actions keep stale UI safe and classify dropped responses vs ACK-then-repaint errors without retry');
})().catch(error=>{console.error(error);process.exitCode=1});
