'use strict';
// Execute the REAL V4 browser status-recovery helper against entirely fictional
// HTTP-200 responses. Never touches the user's SQLite, photos or Termux.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(begin,end){
  const a=source.indexOf(begin),b=source.indexOf(end,a+begin.length);
  assert(a>=0&&b>a,'Actual browser function missing: '+begin);
  return source.slice(a,b);
}
const code=actual('function reviewWriteContext(){','async function finishDirectReview(')+
  actual('function comparablePersonName(name){','// Creating a SEPARATE person is cheap')+
  '\nthis.recovery={verify:verifyPendingPersonCreates,'+
  'keys:()=>pendingPersonCreateIds.slice(),toolbar:pendingPersonCreateToolbar};';

function runFixture(replies,initialUndo=null){
  const ids=replies.map((_,i)=>String(i+1).padStart(32,'a'));
  assert.equal(new Set(ids).size,ids.length);
  const storage=new Map([['atlas-v4-pending-person-create-ids-v1',JSON.stringify(ids)]]);
  const calls=[],toasts=[],undos=[];
  let repaints=0;
  const ctx={
    tab:'people',person:null,group:null,page:0,personMode:'photos',
    renderEpoch:1,lastUndoToken:initialUndo,
    sessionStorage:{
      getItem:key=>storage.get(key)??null,
      setItem:(key,value)=>storage.set(key,String(value))
    },
    post:async(uri,body)=>{
      calls.push({uri,body:JSON.parse(JSON.stringify(body))});
      assert.equal(uri,'/api/person/create/status','Verification must never issue a creation write');
      const index=ids.indexOf(body.request_id);
      assert(index>=0,'Status request must use existing opaque key');
      return replies[index];
    },
    toast:message=>toasts.push(message),
    keepReviewUndo:reply=>{
      undos.push(reply.undo_token);ctx.lastUndoToken=reply.undo_token;
    },
    repaintReviewKeepingPosition:async()=>{repaints++},
    btn:(label,action)=>'<button data-action="'+action+'">'+label+'</button>'
  };
  vm.createContext(ctx);vm.runInContext(code,ctx);
  // The extracted real review-helper block defines its own DOM/RAF repaint.
  // Override only that already independently tested browser surface here.
  ctx.repaintReviewKeepingPosition=async()=>{repaints++};
  return {ctx,calls,toasts,undos,storage,ids,
    keys:()=>Array.from(ctx.recovery.keys()),
    verify:()=>ctx.recovery.verify(),
    repaints:()=>repaints};
}

(async()=>{
  const validToken='f'.repeat(32);
  const invalid=[
    null,undefined,{},[],
    {status:'committed',person_id:19},
    {status:'committed',person_id:19,undo_token:''},
    {status:'committed',person_id:19,undo_token:'UPPERCASE'.padEnd(32,'A')},
    {status:'committed',person_id:19,undo_token:'a'.repeat(31)},
    {status:'committed',person_id:19,undo_token:'a'.repeat(33)},
    {status:'committed',person_id:19,undo_token:9},
    {status:'committed',person_id:19,undo_token:true},
    {status:'committed',person_id:19,undo_token:{}},
    {status:'committed',person_id:0,undo_token:validToken},
    {status:'committed',person_id:-7,undo_token:validToken},
    {status:'committed',person_id:2.5,undo_token:validToken},
    {status:'committed',person_id:'19',undo_token:validToken},
    {status:'committed',person_id:Number.MAX_SAFE_INTEGER+1,undo_token:validToken},
    {status:'committed',person_id:19,undo_token:null,idempotent_replay:'true'},
  ];
  // A committed status with null Undo is legitimate after retention expiry;
  // the "replay" flag is not needed for the READ-ONLY ledger query.
  invalid.pop();
  for(const reply of invalid){
    const f=runFixture([reply]);
    const before=f.storage.get('atlas-v4-pending-person-create-ids-v1');
    await f.verify();
    assert.deepEqual(f.keys(),f.ids,
      'Malformed committed status must never destroy recovery key: '+JSON.stringify(reply));
    assert.equal(f.storage.get('atlas-v4-pending-person-create-ids-v1'),before);
    assert.equal(f.ctx.lastUndoToken,null,'No fake Undo token');
    assert.equal(f.undos.length,0,'No unauthenticated Undo');
    assert.equal(f.calls.length,1);
    assert.equal(f.calls[0].uri,'/api/person/create/status');
    assert.deepEqual(f.calls[0].body,{request_id:f.ids[0]});
    assert.match(f.toasts.at(-1),/0 confirmed saved .* 1 still unverified/);
    assert.match(f.toasts.at(-1),/inspect People before retrying/);
    assert.equal(f.repaints(),1);
  }

  const committed=runFixture([{status:'committed',person_id:19,undo_token:validToken}]);
  await committed.verify();
  assert.deepEqual(committed.keys(),[]);
  assert.deepEqual(committed.undos,[validToken]);
  assert.equal(committed.ctx.lastUndoToken,validToken);
  assert.match(committed.toasts.at(-1),/1 confirmed saved .* 0 still unverified/);

  const expired=runFixture([{status:'committed',person_id:19,undo_token:null}]);
  await expired.verify();
  assert.deepEqual(expired.keys(),[]);
  assert.deepEqual(expired.undos,[]);
  assert.equal(expired.ctx.lastUndoToken,null);

  const newerUndo=runFixture(
    [{status:'committed',person_id:19,undo_token:validToken}],
    'a'.repeat(32));
  await newerUndo.verify();
  assert.deepEqual(newerUndo.keys(),[]);
  assert.equal(newerUndo.ctx.lastUndoToken,'a'.repeat(32),
    'Older recovered Undo must never displace a newer confirmed review');

  const mixed=runFixture([
    {status:'committed',person_id:19,undo_token:'NOT-VALID'},
    {status:'committed',person_id:20,undo_token:null},
    {status:'undone'},
    {status:'archived_or_missing'},
  ]);
  await mixed.verify();
  assert.deepEqual(mixed.keys(),[mixed.ids[0]],
    'Only exact known-complete statuses may remove their own key');
  assert.equal(mixed.storage.get('atlas-v4-pending-person-create-ids-v1'),
    JSON.stringify([mixed.ids[0]]));
  assert.equal(mixed.calls.length,4);
  assert(mixed.calls.every(c=>c.uri==='/api/person/create/status'));
  assert.equal(mixed.undos.length,0);
  assert.match(mixed.toasts.at(-1),/1 confirmed saved · 1 undone · 1 archived\/missing · 1 still unverified/);

  console.log('PASS: 17 malformed committed status bodies retain only opaque request keys, valid token/expired Undo clear correctly, mixed outcomes preserve older evidence and newer Undo');
})().catch(error=>{console.error(error);process.exitCode=1});
