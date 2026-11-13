'use strict';
// Run ACTUAL embedded V4 post() and people() functions against a fictional
// 2,001-profile roster. No real data, browser network or device is accessed.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function part(a,b){
  const i=src.indexOf(a),j=src.indexOf(b,i+a.length);
  assert(i>=0&&j>i,'Could not extract production '+a);
  return src.slice(i,j);
}
const production=part('function invalidatePeopleCache(){','async function action(')+
  part('async function people(epoch){','async function returnToPeople(){')+
  '\nthis.api={post,people,cache:()=>peopleCache,revision:()=>peopleCacheRevision};';
const stale=Array.from({length:2001},(_,i)=>({
  id:i+1,name:'Fictional stale Person '+i,images:i%4,faces:i%8,cover:null
}));
const fresh=Array.from({length:2001},(_,i)=>({
  id:i+1,name:'Fictional fresh Person '+i,images:i%4,faces:i%8,cover:null
}));
const statuses=[];
const rosterReads=[],pendingWrites=[];
const ctx={
  tab:'people',renderEpoch:10,
  peopleQuery:'',peopleListPage:0,
  window:{token:'synthetic-token'},
  get:url=>{
    assert.equal(url,'/api/people?covers=0');
    return new Promise(resolve=>rosterReads.push(resolve));
  },
  fetch:(url,options)=>{
    assert.equal(options.method,'POST');
    assert.equal(options.headers['X-Studio-Token'],'synthetic-token');
    return new Promise((resolve,reject)=>pendingWrites.push({
      url,resolve:data=>resolve({ok:true,json:async()=>data}),
      reject
    }));
  },
  peopleSearchPage:(list,query,page)=>({
    page,match_count:list.length,count:list.length,pages:42,
    matches:list.slice(page*48,(page+1)*48)
  }),
  fillVisiblePeopleCovers:async()=>{},
  header:(title,description)=>'<h1>'+title+'</h1><p>'+description+'</p>',
  btn:(label,action)=>'<button data-action="'+action+'">'+label+'</button>',
  undoButton:()=>'',
  pendingPersonCreateToolbar:()=>'',
  peopleTile:p=>'<article data-person="'+p.id+'">'+p.name+'</article>',
  esc:s=>String(s??''),
  fmt:x=>String(x),
  toast:message=>statuses.push(message)
};
vm.createContext(ctx);
vm.runInContext('let peopleCache=null,peopleCacheRevision=0;'+production,ctx);
const tick=()=>new Promise(setImmediate);
const resolveRoster=rows=>{
  assert(rosterReads.length,'No fictional roster read pending');
  rosterReads.shift()(rows);
};
const resolveWrite=body=>{
  assert(pendingWrites.length,'No fictional POST pending');
  pendingWrites.shift().resolve(body);
};
async function settleRoster(expected){
  const task=ctx.api.people(10);
  assert.equal(rosterReads.length,1);
  resolveRoster(expected);
  return task;
}
(async()=>{
  // A slow GET started just BEFORE a merge (commit ACK lost) cannot install
  // its old 2001 Person counts as a fresh cache once the POST starts.
  let page=ctx.api.people(10);
  assert.equal(rosterReads.length,1);
  const prior=ctx.api.revision();
  let write=ctx.api.post('/api/person/merge',{target:1,source:2});
  assert.equal(ctx.api.revision(),prior+1,'Write dirties cache BEFORE HTTP');
  resolveRoster(stale);
  await tick();
  assert.equal(rosterReads.length,1,'Stale GET must attempt a fresh lookup');
  pendingWrites.shift().reject(Error('Synthetic merge reply lost AFTER commit'));
  await assert.rejects(write,/reply lost AFTER commit/);
  assert.equal(ctx.api.revision(),prior+2,
    'Lost ACK must invalidate any GET loaded during the write');
  resolveRoster(stale);  // This second GET also overlaps the uncertain write.
  const incomplete=await page;
  assert.match(incomplete,/People changed while loading/);
  assert.strictEqual(ctx.api.cache(),null,'Two racing old GETs must never repopulate cache');

  // The next explicit People opening fetches the authoritative roster.
  let markup=await settleRoster(fresh);
  assert.strictEqual(ctx.api.cache(),fresh);
  assert.match(markup,/Fictional fresh Person 0/);
  assert.doesNotMatch(markup,/Fictional stale Person/);

  // A failed mutation started AFTER a cached roster was populated also
  // invalidates immediately, before any response can be received.
  const pre=ctx.api.revision();
  write=ctx.api.post('/api/person/create',{name:'Fictional newly reviewed'});
  assert.strictEqual(ctx.api.cache(),null);
  assert.equal(ctx.api.revision(),pre+1);
  pendingWrites.shift().reject(Error('Synthetic response unavailable'));
  await assert.rejects(write,/response unavailable/);
  assert.equal(ctx.api.revision(),pre+2);
  assert.strictEqual(ctx.api.cache(),null);
  markup=await settleRoster(fresh);
  assert.match(markup,/Fictional fresh Person/);

  // An HTTP-success mutation also invalidates after commit, because an
  // earlier cache/GET might have been hydrated DURING its in-flight period.
  write=ctx.api.post('/api/person/rename',{person_id:1,name:'Fictional adjusted'});
  assert.strictEqual(ctx.api.cache(),null);
  const interim=await settleRoster(stale);
  assert.match(interim,/Fictional stale Person/);
  assert.strictEqual(ctx.api.cache(),stale,'GET during write may briefly be old');
  resolveWrite({ok:true});
  await write;
  assert.strictEqual(ctx.api.cache(),null,
    'Acknowledged mutation must retire an in-flight stale roster');

  // Read-only UNKNOWN status is not a write and must retain the just-
  // hydrated roster, preserving visible covers in a large People library.
  markup=await settleRoster(fresh);
  assert.strictEqual(ctx.api.cache(),fresh);
  const unchanged=ctx.api.revision();
  const check=ctx.api.post('/api/person/create/status',{request_id:'b'.repeat(32)});
  assert.strictEqual(ctx.api.cache(),fresh);
  assert.equal(ctx.api.revision(),unchanged);
  resolveWrite({status:'unknown'});
  await check;
  assert.strictEqual(ctx.api.cache(),fresh);
  assert.equal(ctx.api.revision(),unchanged);

  // A stale GET from a previous People tab cannot repaint the next tab or
  // install a cache with old People, even if no mutation occurs.
  ctx.api.post('/api/person/rename',{person_id:2,name:'Fictional other'});
  assert.strictEqual(ctx.api.cache(),null);
  ctx.tab='settings';ctx.renderEpoch++;
  const started=ctx.api.people(10);
  assert.equal(rosterReads.length,1);
  resolveRoster(stale);
  assert.equal(await started,'');
  assert.strictEqual(ctx.api.cache(),null);
  resolveWrite({ok:true});
  await tick();
  assert.strictEqual(ctx.api.cache(),null);

  console.log('PASS: production V4 roster/POST helpers prevent late 2001-Person stale GET rehydration, invalidate on both uncertain and acknowledged mutations, retain read-only UNKNOWN cache and isolate previous tabs');
})().catch(e=>{console.error(e);process.exitCode=1});
