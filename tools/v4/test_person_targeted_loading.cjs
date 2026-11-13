'use strict';
const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=source.indexOf('async function personPage(epoch){');
const end=source.indexOf('\nasync function untagged(epoch){',start);
assert(start>0&&end>start,'Can isolate the real Person page implementation');
const personPageCode=source.slice(start,end);
let requests=[];
let pending=[];
const ctx={
  person:99,page:3,personMode:'photos',personModeTest:true,
  reviewRequestCurrent:()=>true,
  get:url=>new Promise(resolve=>{requests.push(url);pending.push(resolve)}),
  header:(title,description)=>'<h1>'+title+'</h1><p>'+description+'</p>',
  btn:(label,action)=>'<button data-action="'+action+'">'+label+'</button>',
  fmt:v=>String(v),
  photoTile:item=>'<img data-photo="'+item.id+'">',
  faceTile:item=>'<img data-face="'+item.id+'">',
  pager:count=>'<p data-count="'+count+'"></p>',
  selectionToolbar:()=>'<b>page-local selection</b>',
  undoButton:()=>'<b>undo action</b>',
};
vm.createContext(ctx);
vm.runInContext(personPageCode+'\nthis.reviewPage=personPage;',ctx);
const summary={id:99,name:'Fictional & reviewer',faces:123,images:75};
async function checkMode(mode,expectedEndpoint,expectedTile){
  requests=[];pending=[];ctx.personMode=mode;
  const promise=ctx.reviewPage();
  assert.deepEqual(requests,['/api/person/summary?id=99',expectedEndpoint],
      'Person details and ONLY the active review mode must be started before awaiting either request');
  assert.equal(pending.length,2,'Both requests must run in parallel');
  // Resolve the mode result before the metadata; both remain eligible.
  pending[1]({items:[{id:6},{id:7}],has_more:false});
  pending[0](summary);
  const html=await promise;
  assert.ok(html.includes(expectedTile+'6'),'Expected correct current-mode tiles');
  assert.ok(html.includes('data-count="2"'),'Only active page rows counted');
  assert.ok(html.includes('Fictional & reviewer'),'Single-profile summary used');
  assert.ok(html.includes('page-local selection')=== (mode!=='photos'));
  assert.ok(html.includes('undo action'));
  assert.ok(!html.includes((mode==='photos'?'data-face="':'data-photo="')),
      'Inactive mode must not leak into current DOM');
}
(async()=>{
  await checkMode('photos','/api/person?id=99&page=3&with_next=1','data-photo="');
  await checkMode('faces','/api/person/faces?id=99&page=3&with_next=1&cursor=1','data-face="');
  await checkMode('suggested','/api/person/suggested?id=99&page=3&with_next=1','data-face="');
  // A newly chosen person / page must build new paths, not reuse stale metadata.
  ctx.person=2001;ctx.page=0;requests=[];pending=[];
  const p=ctx.reviewPage();
  assert.deepEqual(requests,['/api/person/summary?id=2001','/api/person/suggested?id=2001&page=0&with_next=1']);
  pending[0]({...summary,id:2001});pending[1]({items:[],has_more:false});
  const empty=await p;
  assert.ok(empty.includes('data-count="0"'),'Empty pages render safely');
  // Confirmed-face mode uses the last VISIBLE face ID as an in-memory
  // boundary. Unassigning earlier rows must not shift an unseen row away.
  ctx.person=99;ctx.personMode='faces';ctx.page=0;
  requests=[];pending=[];
  const initial=ctx.reviewPage();
  assert.deepEqual(requests,['/api/person/summary?id=99',
    '/api/person/faces?id=99&page=0&with_next=1&cursor=1']);
  pending[0](summary);
  pending[1]({items:Array.from({length:60},(_,i)=>({id:180-i})),
    has_more:true,next_before:121});
  await initial;
  ctx.page=1;requests=[];pending=[];
  const next=ctx.reviewPage();
  assert.deepEqual(requests,['/api/person/summary?id=99',
    '/api/person/faces?id=99&page=1&with_next=1&cursor=1&before_id=121']);
  pending[0](summary);pending[1]({items:[{id:120}],has_more:false,next_before:120});
  await next;
  // Switching modes excludes stale face cursor and keeps separate source.
  ctx.personMode='suggested';ctx.page=0;requests=[];pending=[];
  const other=ctx.reviewPage();
  assert.deepEqual(requests,['/api/person/summary?id=99',
    '/api/person/suggested?id=99&page=0&with_next=1']);
  pending[0](summary);pending[1]({items:[],has_more:false});
  await other;
  ctx.personMode='faces';ctx.page=0;requests=[];pending=[];
  const reenter=ctx.reviewPage();
  assert.deepEqual(requests,['/api/person/summary?id=99',
    '/api/person/faces?id=99&page=0&with_next=1&cursor=1']);
  pending[0](summary);pending[1]({items:[],has_more:false});
  await reenter;
  console.log('PASS: targeted Person mode-only parallel loading; Review tags forward IDs survive earlier removals and reset safely across modes');
})().catch(e=>{console.error(e);process.exitCode=1});
