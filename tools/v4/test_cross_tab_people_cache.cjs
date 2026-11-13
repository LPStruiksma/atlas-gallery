'use strict';
// Two isolated copies of the ACTUAL V4 browser POST/cache code, connected by
// an in-memory mock BroadcastChannel. All 2,001 profiles are fictional.
// No browser storage, original photos, identity writes or network requests.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=src.indexOf('function invalidatePeopleCache(){');
const end=src.indexOf('\nasync function action(',start);
assert(start>=0&&end>start,'Actual V4 cache/POST code must be executed');
const calls=[],messages=[];
const channels=[];
class FixtureBroadcastChannel{
  constructor(name){this.name=name;this.onmessage=null;channels.push(this)}
  postMessage(data){
    const payload=JSON.parse(JSON.stringify(data));
    calls.push({name:this.name,payload});
    for(const target of channels){
      if(target===this||target.name!==this.name)continue;
      target.onmessage?.({data:payload});
    }
  }
}
const roster=Array.from({length:2001},(_,i)=>({
  id:i+1,name:'Fictional Person '+i,faces:i%11,cover:null
}));
function page(withChannel=true){
  const pending=[],notices=[];
  const ctx={
    rosterFixture:roster,
    tab:'people',window:{token:'synthetic-token'},
    ...(withChannel?{BroadcastChannel:FixtureBroadcastChannel}:{}),
    fetch:(url,options)=>{
      assert.equal(options.method,'POST');
      return new Promise((resolve,reject)=>pending.push({
        url,resolve:body=>resolve({ok:true,json:async()=>body}),reject
      }));
    },
    toast:text=>notices.push(text)
  };
  vm.createContext(ctx);
  vm.runInContext('let peopleCache=rosterFixture,peopleCacheRevision=0;\n'+
    src.slice(start,end)+
    '\nthis.api={post,cache:()=>peopleCache,revision:()=>peopleCacheRevision,'+
    'setCache:v=>peopleCache=v};',ctx);
  return {ctx,pending,notices,api:ctx.api};
}
const resolve=(p,body)=>{
  assert.equal(p.pending.length,1,'Exactly one independent HTTP request expected');
  p.pending.shift().resolve(body);
};
const reject=(p,message)=>{
  assert.equal(p.pending.length,1);
  p.pending.shift().reject(Error(message));
};
(async()=>{
  const a=page(),b=page();
  assert.equal(channels.length,2);
  assert.strictEqual(a.api.cache(),roster);
  assert.strictEqual(b.api.cache(),roster);
  let operation=a.api.post('/api/person/merge',{target:2,source:3});
  assert.strictEqual(a.api.cache(),null);
  assert.strictEqual(b.api.cache(),null,
    'Local Person change must invalidate cached People in other tab immediately');
  assert.equal(b.api.revision(),1);
  assert.match(b.notices.at(-1),/People may have changed in another tab/);

  // B could re-fetch the old roster while A's SQLite write is still
  // in-flight. A's final ACK must invalidate B's mid-write cache again.
  b.api.setCache(roster);
  resolve(a,{ok:true,count:2,undo_token:'f'.repeat(32)});
  await operation;
  assert.equal(b.api.revision(),2);
  assert.strictEqual(b.api.cache(),null);
  assert.equal(calls.length,2,
    'One mutation emits start+completion events, never echo loops');
  assert(calls.every(x=>x.name==='atlas-v4-people-refresh-v1'));
  assert(calls.every(x=>JSON.stringify(x.payload)==='{"type":"people-changed"}'),
    'The cross-tab bus must contain NO Person/face IDs, names or payloads');

  // A mutation with lost acknowledgement also invalidates every tab before
  // and after failure. No uncertain identity is considered undone.
  a.api.setCache(roster);b.api.setCache(roster);
  operation=b.api.post('/api/person/create',{name:'Fictional New'});
  assert.strictEqual(a.api.cache(),null);
  a.api.setCache(roster);
  reject(b,'Synthetic ACK lost AFTER commit');
  await assert.rejects(operation,/ACK lost AFTER commit/);
  assert.strictEqual(a.api.cache(),null);
  assert.equal(calls.length,4);

  // Exactly unknown / recorded-not-undone read-only statuses stay silent,
  // retaining 2,001-person rosters and all visible covers across tabs.
  a.api.setCache(roster);b.api.setCache(roster);
  const sent=calls.length;
  operation=a.api.post('/api/person/create/status',{request_id:'a'.repeat(32)});
  resolve(a,{status:'unknown'});
  await operation;
  operation=b.api.post('/api/review/undo/status',{undo_token:'b'.repeat(32)});
  resolve(b,{status:'recorded_not_undone'});
  await operation;
  assert.equal(calls.length,sent);
  assert.strictEqual(a.api.cache(),roster);
  assert.strictEqual(b.api.cache(),roster);

  // An earlier committed creation newly DISCOVERED via read-only status
  // may invalidate cached counts/names in another tab.
  operation=a.api.post('/api/person/create/status',{request_id:'a'.repeat(32)});
  resolve(a,{status:'committed',person_id:27,undo_token:'f'.repeat(32)});
  await operation;
  assert.strictEqual(b.api.cache(),null);
  assert.equal(calls.length,sent+1);

  // Ignore arbitrary messages, even ones carrying metadata; no reflection.
  a.api.setCache(roster);b.api.setCache(roster);
  const before=a.api.revision();
  channels[0].onmessage({data:{type:'NOT-A-PEOPLE-CHANGE',person_id:999}});
  assert.strictEqual(a.api.cache(),roster);
  assert.equal(a.api.revision(),before);
  assert.equal(calls.length,sent+1);

  // Older WebViews/denied BroadcastChannel must retain single-tab safety.
  const fallback=page(false);
  assert.equal(channels.length,2);
  operation=fallback.api.post('/api/person/rename',{person_id:2,name:'Fictional Changed'});
  assert.strictEqual(fallback.api.cache(),null);
  reject(fallback,'Synthetic BrowserChannel unavailable');
  await assert.rejects(operation,/BrowserChannel unavailable/);
  assert.strictEqual(fallback.api.cache(),null);
  assert.equal(calls.length,sent+1);
  assert.strictEqual(a.api.cache(),roster,'Unsupported fallback must not alter other tabs');

  console.log('PASS: actual V4 browser cache invalidation crosses two fictional local tabs with ID-free events, before/after uncertain writes, old roster cannot survive, read-only unknown stays cached, no ping-pong, optional API safe');
})().catch(e=>{console.error(e);process.exitCode=1});
