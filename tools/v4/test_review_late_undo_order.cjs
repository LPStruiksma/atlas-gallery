'use strict';
// Production direct-review dispatcher with overlapping fictional completions.
// A late older face-write must NOT replace a more recently surfaced Undo.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=src.indexOf('// Direct review writes, including Undo');
const end=src.indexOf("\ndocument.addEventListener('input'",start);
assert(start>=0&&end>start,'Actual embedded V4 browser dispatcher required');
const code=src.slice(start,end)+'\nthis.review=eventAction;';
const requests=[],messages=[],installed=[];
let paintCount=0;
const ctx={
  tab:'person',person:20,personMode:'suggested',page:1,group:null,renderEpoch:4,
  selected:new Set([51,52]),lastUndoToken:null,
  post:(url,payload)=>new Promise((resolve,reject)=>{
    requests.push({url,payload:JSON.parse(JSON.stringify(payload)),resolve,reject});
  }),
  confirm:()=>true,
  toast:msg=>messages.push(msg),
  keepReviewUndo:reply=>{installed.push(reply.undo_token);ctx.lastUndoToken=reply.undo_token},
  render:async()=>{paintCount++;ctx.renderEpoch++},
};
vm.createContext(ctx);
vm.runInContext(code,ctx);
ctx.repaintReviewKeepingPosition=async()=>{paintCount++;ctx.renderEpoch++};
function reset(person,selection=[51,52],tab='person'){
  ctx.person=person;ctx.tab=tab;ctx.page=1;ctx.personMode='suggested';
  ctx.renderEpoch++;ctx.selected=new Set(selection);
}
(async()=>{
  // An earlier Accept was submitted on Person A; a separate later Create
  // returned a legitimate one-use Undo before the old Accept reply.
  const first=ctx.review('accept');
  assert.equal(requests.length,1);
  assert.equal(requests[0].url,'/api/person/accept');
  assert.deepEqual(requests[0].payload,{person_id:20,face_ids:[51,52]});
  reset(99,[901,902]);
  const newlyConfirmedToken='b'.repeat(32);
  ctx.keepReviewUndo({undo_token:newlyConfirmedToken});
  const beforePaint=paintCount;
  requests.shift().resolve({count:2,undo_token:'a'.repeat(32)});
  await first;
  assert.equal(ctx.lastUndoToken,newlyConfirmedToken,
    'Late earlier Accept overwrote newer Create Undo');
  assert.deepEqual(installed,[newlyConfirmedToken]);
  assert.deepEqual([...ctx.selected],[901,902]);
  assert.equal(paintCount,beforePaint,
    'Late previous-Person completion redrew current Person');
  assert.match(messages.at(-1),/newer Undo preserved/);

  // Another action updates Undo without navigation: the reviewer should
  // still see the new token, while the currently selected batch retires.
  reset(20,[61,62]);ctx.lastUndoToken='c'.repeat(32);
  const second=ctx.review('reject');
  assert.equal(requests.length,1);
  const currentNewer='d'.repeat(32);
  ctx.keepReviewUndo({undo_token:currentNewer});
  requests.shift().resolve({count:2,undo_token:'e'.repeat(32)});
  await second;
  assert.equal(ctx.lastUndoToken,currentNewer);
  assert.deepEqual([...ctx.selected],[]);
  assert.equal(paintCount,beforePaint+1);
  assert.match(messages.at(-1),/Suggestions rejected · newer Undo preserved/);
  assert.equal(installed.at(-1),currentNewer);

  // If no other action replaced the original Undo, ordinary direct review
  // still shows its own new audited Undo as before.
  reset(20,[71,72]);ctx.lastUndoToken='f'.repeat(32);
  const third=ctx.review('accept');
  assert.equal(requests.length,1);
  requests.shift().resolve({count:2,undo_token:'1'.repeat(32)});
  await third;
  assert.equal(ctx.lastUndoToken,'1'.repeat(32));
  assert.equal(installed.at(-1),'1'.repeat(32));
  assert.deepEqual([...ctx.selected],[]);
  assert.equal(paintCount,beforePaint+2);
  assert.equal(messages.at(-1),'Suggestions confirmed');

  // No status, explicit confirmation or background job was auto-sent.
  assert.equal(requests.length,0);
  console.log('PASS: real V4 older direct review completion cannot displace newer Undo after Person navigation or same-view independent action; normal Undo and selection retirement remain functional');
})().catch(error=>{console.error(error);process.exitCode=1});
