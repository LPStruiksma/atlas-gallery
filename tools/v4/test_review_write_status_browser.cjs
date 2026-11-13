'use strict';
/* Execute production V4 direct-review JS against fictional HTTP, sessionStorage
   and cryptographic randomness. No real SQLite, faces, People or phone. */
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const a=source.indexOf('// Direct review writes, including Undo');
const b=source.indexOf("\ndocument.addEventListener('input'",a);
assert(a>=0&&b>a,'Real production review dispatcher is required');
assert(source.includes("u==='/api/review/write/status'"),
  'Read-only review status route must not invalidate People metadata on unknown');
assert(source.includes("btn('Verify '+pendingReviewWriteRequests.length+' earlier reviews'"),
  'A browser-visible manual verification action is required');
assert(source.includes("if(a==='verifyreviews'){await verifyPendingReviewWrites();return}"));
const script=source.slice(a,b)+
  '\nthis.api={review:eventAction,verify:verifyPendingReviewWrites,'+
  'dismiss:dismissPendingReviewReminder,keys:()=>pendingReviewWriteRequests.map(x=>({...x})),'+
  'gate:confirmAfterUnverifiedReview,makeKey:makeReviewWriteKey};';

function storage(){
  const values=new Map();
  return {getItem:key=>values.has(key)?values.get(key):null,
    setItem:(key,value)=>values.set(key,String(value)),
    value:()=>values.get('atlas-v4-reviewed-write-recovery-v1'),
    preset:value=>values.set('atlas-v4-reviewed-write-recovery-v1',value)};
}
let seed=0;
function runtime(store,options={}){
  const calls=[],messages=[],prompts=[],undo=[];
  const ctx={
    tab:'person',person:77,group:null,page:0,personMode:'suggested',
    renderEpoch:3,selected:new Set([11,12]),lastUndoToken:options.undo??null,
    sessionStorage:store,
    crypto:options.noCrypto?undefined:{getRandomValues:bytes=>{
      seed++;for(let i=0;i<bytes.length;i++)bytes[i]=(seed*37+i*19)&255;
      return bytes;
    }},
    post:(url,payload)=>{
      calls.push([url,JSON.parse(JSON.stringify(payload))]);
      if(url==='/api/review/write/status'){
        if(options.statusDeferred)return options.statusDeferred();
        return Promise.resolve(options.status??{status:'unknown'});
      }
      if(url==='/api/person/accept'||url==='/api/person/reject'||
         url==='/api/face/ignore'||url==='/api/face/unassign'){
        if(options.lost)return Promise.reject(Error('Synthetic response lost AFTER commit'));
        return Promise.resolve({count:2,undo_token:'f'.repeat(32)});
      }
      return Promise.reject(Error('Unexpected mutation '+url));
    },
    confirm:text=>{prompts.push(text);return !!options.confirm},
    toast:text=>messages.push(text),
    keepReviewUndo:result=>{ctx.lastUndoToken=result.undo_token;undo.push(result.undo_token)},
    repaintReviewKeepingPosition:async()=>{},
    render:async()=>{ctx.renderEpoch++}
  };
  vm.createContext(ctx);vm.runInContext(script,ctx);
  return {ctx,calls,messages,prompts,undo};
}
function items(ui){return JSON.parse(JSON.stringify(ui.ctx.api.keys()))}
(async()=>{
  const store=storage();
  let ui=runtime(store,{lost:true});
  await ui.ctx.api.review('accept');
  assert.equal(ui.calls.length,1);
  assert.equal(ui.calls[0][0],'/api/person/accept');
  const key=ui.calls[0][1].request_id;
  assert.match(key,/^[0-9a-f]{32}$/);
  assert.deepEqual(items(ui),[{id:key,count:2}]);
  assert.deepEqual([...ui.ctx.selected],[11,12],'Unverified faces remain selected');
  assert.equal(ui.ctx.lastUndoToken,null,'No fake Undo on ambiguous acknowledgement');
  assert.equal(store.value(),JSON.stringify([{id:key,count:2}]));
  assert(!store.value().includes('11')&&!store.value().includes('12'),
    'No fictional face IDs or identity payload are stored');

  // No automatic HTTP activity on reload. Unknown status stays recoverable;
  // a prior request may STILL COMMIT after the read.
  ui=runtime(store,{status:{status:'unknown'}});
  assert.equal(ui.calls.length,0);
  assert.equal(ui.ctx.api.gate(),false,'Reloaded uncertain review needs inspection');
  await ui.ctx.api.verify();
  assert.deepEqual(items(ui),[{id:key,count:2}]);
  assert.equal(ui.calls.length,1);
  assert.deepEqual(ui.calls[0],['/api/review/write/status',{request_id:key}]);

  // Malformed HTTP-200 status or incorrect face count must not manufacture
  // success, clear the recovery key, or install a purported Undo token.
  for(const status of [
    {},{status:'recorded_not_undone',count:1,undo_token:'f'.repeat(32)},
    {status:'recorded_not_undone',count:2,undo_token:'BAD'},
    {status:'undone',count:3}
  ]){
    ui=runtime(store,{status});await ui.ctx.api.verify();
    assert.equal(items(ui).length,1);
    assert.equal(ui.ctx.lastUndoToken,null);
  }

  // A genuine durable status with the matching count can recover original
  // one-use Undo, without submitting a second identity write.
  ui=runtime(store,{status:{status:'recorded_not_undone',
                            count:2,undo_token:'e'.repeat(32)}});
  await ui.ctx.api.verify();
  assert.deepEqual(items(ui),[]);
  assert.equal(ui.ctx.lastUndoToken,'e'.repeat(32));
  assert.equal(ui.calls.length,1);
  assert.equal(store.value(),'[]');
  assert.match(ui.messages.at(-1),/1 committed \(inspect tags\)/);

  // Known previously undone/expired status closes the reminder but does NOT
  // imply the current tag state or silently repeat reviewed mutations.
  for(const value of ['undone','recorded_unknown']){
    store.preset(JSON.stringify([{id:'a'.repeat(32),count:2}]));
    ui=runtime(store,{status:{status:value,count:2}});
    await ui.ctx.api.verify();
    assert.deepEqual(items(ui),[]);
    assert.equal(ui.ctx.lastUndoToken,null);
  }

  // A previous-view verification never steals a later Undo or changes focus.
  store.preset(JSON.stringify([{id:'b'.repeat(32),count:2}]));
  let release;
  ui=runtime(store,{statusDeferred:()=>new Promise(resolve=>{release=resolve})});
  const pending=ui.ctx.api.verify();
  const coalesced=Array.from({length:79},()=>ui.ctx.api.verify());
  assert.equal(ui.calls.length,1,'80 rapid Verify taps create one read-only POST');
  ui.ctx.tab='settings';ui.ctx.renderEpoch++;ui.ctx.lastUndoToken='c'.repeat(32);
  release({status:'recorded_not_undone',count:2,undo_token:'d'.repeat(32)});
  await Promise.all([pending,...coalesced]);
  assert.deepEqual(items(ui),[]);
  assert.equal(ui.ctx.lastUndoToken,'c'.repeat(32));

  // On success: record only the momentary key; a verified count+Undo retires
  // both submitted selection and the browser pending reminder.
  store.preset('[]');
  ui=runtime(store);
  await ui.ctx.api.review('accept');
  assert.deepEqual(items(ui),[]);
  assert.equal(ui.ctx.selected.size,0);
  assert.equal(ui.ctx.lastUndoToken,'f'.repeat(32));
  assert.match(ui.calls[0][1].request_id,/^[0-9a-f]{32}$/);

  // Missing browser crypto preserves the preexisting human inspection guard
  // and sends NO predictable idempotency key (never Math.random/Date.now).
  ui=runtime(store,{noCrypto:true,lost:true});
  await ui.ctx.api.review('accept');
  assert.equal('request_id' in ui.calls[0][1],false);
  assert.deepEqual(items(ui),[]);
  assert.match(ui.messages.at(-1),/inspect People or face tags before retrying/);

  // Corrupt/injected storage is never loaded into the DOM or sent to server.
  for(const invalid of ['{bad','{}',JSON.stringify([{id:'a'.repeat(32),count:1,name:'Secret'}]),
     JSON.stringify(Array.from({length:9},(_,i)=>({id:String(i).padStart(32,'0'),count:1}))),
     JSON.stringify([{id:'a'.repeat(32),count:1},{id:'a'.repeat(32),count:1}])]){
    store.preset(invalid);ui=runtime(store);
    assert.deepEqual(items(ui),[]);
    assert.equal(ui.calls.length,0);
  }

  // Only an explicit owner's confirmation dismisses one browser reminder,
  // never the actual server action. Declined dismissal preserves all keys.
  store.preset(JSON.stringify([{id:'1'.repeat(32),count:2},
                               {id:'2'.repeat(32),count:2}]));
  ui=runtime(store,{confirm:false});await ui.ctx.api.dismiss();
  assert.equal(items(ui).length,2);
  ui=runtime(store,{confirm:true});
  await Promise.all(Array.from({length:60},()=>ui.ctx.api.dismiss()));
  assert.deepEqual(items(ui),[{id:'2'.repeat(32),count:2}]);
  assert.equal(ui.calls.length,0);
  assert.equal(ui.prompts.length,1);
  assert.match(ui.prompts[0],/MAY STILL COMMIT/);
  console.log('PASS: four direct-review routes use opaque bounded recovery, retained across reload, unknown/malformed fails closed, verified saved/undone/expired statuses, single-flight, existing Undo precedence and owner-only dismissal; fully fictional browser VM');
})().catch(error=>{console.error(error);process.exitCode=1});
