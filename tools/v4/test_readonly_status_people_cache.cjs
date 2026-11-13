'use strict';
// Execute the production V4 POST helper and a fictional 2,001-Person roster.
// Tests cache *identity*, not synthetic speed, and never contacts a server.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=source.indexOf('function invalidatePeopleCache(){');
const end=source.indexOf('\nasync function action(',start);
assert(start>=0&&end>start,'Must run actual V4 POST helper');
const requests=[];
let answer={status:'unknown'},ok=true;
const roster=Array.from({length:2001},(_,i)=>({
  id:i+1,name:'Fictional Person '+(i+1),cover_loaded:i<48
}));
const ctx={
  window:{token:'fixture-opaque-session-token'},
  rosterFixture:roster,
  fetch:async(uri,options)=>{
    requests.push({uri,options});
    assert.equal(options.method,'POST');
    assert.equal(options.headers['Content-Type'],'application/json');
    assert.equal(options.headers['X-Studio-Token'],'fixture-opaque-session-token');
    return {
      ok,statusText:'Synthetic server rejection',
      json:async()=>ok?answer:{error:'Fictional server error'}
    };
  }
};
vm.createContext(ctx);
vm.runInContext('let peopleCache=rosterFixture,peopleCacheRevision=0;\n'+source.slice(start,end)+
  '\nthis.call=post;this.cache=()=>peopleCache;this.revision=()=>peopleCacheRevision;this.reset=value=>peopleCache=value;',ctx);
function fixture(reply,valid=true){answer=reply;ok=valid;ctx.reset(roster)}
async function call(path,body={}){
  const before=requests.length;
  const result=await ctx.call(path,body);
  assert.equal(requests.length,before+1);
  assert.equal(requests.at(-1).uri,path);
  assert.deepEqual(JSON.parse(requests.at(-1).options.body),body);
  return result;
}
(async()=>{
  // A reviewer may repeatedly check a lost Person-create acknowledgement
  // while staying on the People roster. All 2,001 objects and their
  // already-fetched visible portraits must remain in memory unchanged.
  fixture({status:'unknown'});
  for(let n=0;n<250;n++){
    await call('/api/person/create/status',{request_id:'a'.repeat(32)});
    assert.strictEqual(ctx.cache(),roster);
    assert.strictEqual(ctx.cache()[0],roster[0]);
    assert.strictEqual(ctx.cache()[47],roster[47]);
  }
  fixture({status:'recorded_not_undone'});
  for(let n=0;n<250;n++){
    await call('/api/review/undo/status',{undo_token:'b'.repeat(32)});
    assert.strictEqual(ctx.cache(),roster);
  }
  fixture({status:'unknown'});
  for(let n=0;n<250;n++){
    await call('/api/review/undo/status',{undo_token:'b'.repeat(32)});
    assert.strictEqual(ctx.cache(),roster);
  }
  assert.equal(requests.length,750);
  assert.strictEqual(ctx.cache(),roster);

  // A read-only result can expose a PREVIOUS mutation whose HTTP response
  // was lost. In that case cached counts/profile tiles might be stale.
  for(const pathAndReply of [
    ['/api/person/create/status',{status:'committed',person_id:27,undo_token:'f'.repeat(32)}],
    ['/api/person/create/status',{status:'committed',person_id:28,undo_token:null}],
    ['/api/person/create/status',{status:'undone'}],
    ['/api/person/create/status',{status:'archived_or_missing'}],
    ['/api/review/undo/status',{status:'undone'}],
  ]){
    fixture(pathAndReply[1]);
    await call(pathAndReply[0]);
    assert.equal(ctx.cache(),null,'Known terminal status must refresh potentially stale roster');
  }

  // Unknown/malformed status bodies are not an allowlisted no-change result.
  for(const invalid of [null,{},[],{status:'unexpected'},'unexpected']){
    fixture(invalid);
    await call('/api/person/create/status');
    assert.equal(ctx.cache(),null,'Invalid status cannot silently freeze a stale roster');
  }

  // Existing actual mutation routes keep their original invalidation rule.
  for(const path of ['/api/person/create','/api/person/assign','/api/group/attach',
                     '/api/person/rename','/api/review/undo','/api/face/ignore']){
    fixture({count:2,undo_token:'e'.repeat(32)});
    await call(path);
    assert.equal(ctx.cache(),null,'Write must invalidate People cache: '+path);
  }

  // Only the two read-only token status routes may preserve the roster
  // after a transport/HTTP error: neither sends an identity write.
  // Mutations invalidate both BEFORE the request and AFTER any rejection.
  for(const path of ['/api/person/create','/api/person/assign',
    '/api/face/unassign','/api/person/merge','/api/review/undo']){
    fixture(null,false);
    const before=ctx.revision();
    await assert.rejects(ctx.call(path,{}),/Fictional server error/);
    assert.equal(ctx.cache(),null,'Ambiguous write must NOT retain stale Person roster: '+path);
    assert.equal(ctx.revision(),before+2,
      'A write must invalidate at start and at the lost acknowledgement: '+path);
  }
  // Read-only status errors do not ACK a write; cached People can survive.
  fixture(null,false);
  await assert.rejects(ctx.call('/api/person/create/status',{}),/Fictional server error/);
  assert.strictEqual(ctx.cache(),roster);
  assert.equal(requests.length,750+5+5+6+5+1);
  console.log('PASS: 750 actual V4 token-status POSTs retain 2001 cached People, known terminal statuses and all writes invalidate; 5 ambiguous writes never preserve stale profiles; read-only status failure preserves cache');
})().catch(e=>{console.error(e);process.exitCode=1});
