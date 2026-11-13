'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(start,end){
  const a=src.indexOf(start),b=src.indexOf(end,a+start.length);
  assert(a>=0&&b>a,'Expected actual production browser helper '+start);
  return src.slice(a,b);
}
const code=actual('function reviewWriteContext(){','async function finishDirectReview(')+
  actual('function comparablePersonName(name){','// Creating a SEPARATE person is cheap')+
  '\nthis.ui={create:createReviewedPerson,verify:verifyPendingPersonCreates,'+
  'keys:()=>pendingPersonCreateIds.slice(),toolbar:pendingPersonCreateToolbar,'+
  'retryGate:confirmRetryAfterUnverifiedCreate,dismiss:dismissOldestPendingPersonCreate};';
assert(src.includes("if(a==='verifycreates'){await verifyPendingPersonCreates();return}"),
  'The owner-facing People recovery button must reach the handler');
assert(src.includes("if(a==='dismisscreate'){await dismissOldestPendingPersonCreate();return}"),
  'Manual dismissal must be wired only to an explicit owner action');
assert(src.includes("${pendingPersonCreateToolbar()}"),
  'The real People roster must expose any unverified create requests');
assert(src.includes("elif path=='/api/person/create/status':v=person_create_request_status("),
  'Read-only token-scoped status endpoint must be routed');

const storageKey='atlas-v4-pending-person-create-ids-v1';
function storage(){
  const data=new Map();
  return {
    getItem(key){return data.has(key)?data.get(key):null},
    setItem(key,value){data.set(key,String(value))},
    value(){return data.get(storageKey)},
    preset(value){data.set(storageKey,value)}
  };
}
const committed=new Map();
let keySeed=0;
function runtime(store,options={}){
  const requests=[],notifications=[],confirmations=[],rendered=[];
  const ctx={
    tab:'people',page:0,person:null,group:null,personMode:'photos',
    renderEpoch:1,lastUndoToken:options.undoToken??null,
    sessionStorage:store,
    btn:(label,action)=>'<button data-action="'+action+'">'+label+'</button>',
    crypto:{getRandomValues:bytes=>{
      const seed=++keySeed;
      for(let i=0;i<bytes.length;i++)bytes[i]=(seed*13+i*7)&255;
      return bytes;
    }},
    get:async uri=>{
      assert.equal(uri,'/api/people?covers=0');
      return [];
    },
    post:async(uri,payload)=>{
      requests.push([uri,JSON.parse(JSON.stringify(payload))]);
      if(uri==='/api/person/create'){
        committed.set(payload.request_id,{
          status:'committed',person_id:700+committed.size,
          undo_token:'f'.repeat(32)
        });
        throw Error('Synthetic acknowledgement LOST after SQLite commit');
      }
      assert.equal(uri,'/api/person/create/status');
      return options.forceStatus??committed.get(payload.request_id)??{status:'unknown'};
    },
    confirm:message=>{confirmations.push(message);return options.confirm??false},
    keepReviewUndo:result=>{if(result?.undo_token)ctx.lastUndoToken=result.undo_token},
    toast:message=>notifications.push(message),
    // Production status repaint uses scroll/focus restoration rather than
    // a blind full rerender. This VM exercises that call and navigation guard.
    render:async()=>{rendered.push(true);ctx.renderEpoch++},
    repaintReviewKeepingPosition:async()=>{rendered.push(true);ctx.renderEpoch++},
    choosePerson:async()=>{throw Error('Unexpected chooser in unique-name fixture')}
  };
  vm.createContext(ctx);vm.runInContext(code,ctx);
  return {ctx,requests,notifications,confirmations,rendered};
}
(async()=>{
  const store=storage();
  let ui=runtime(store);
  await assert.rejects(ui.ctx.ui.create('Fictional Ophelia',{name:'Fictional Ophelia'},
    async()=>{},async()=>{}),/Could not verify person creation/);
  assert.equal(ui.requests.length,1);
  assert.equal(ui.requests[0][0],'/api/person/create');
  const key=ui.requests[0][1].request_id;
  assert.match(key,/^[0-9a-f]{32}$/);
  assert.deepEqual(Array.from(ui.ctx.ui.keys()),[key]);
  assert.equal(store.value(),JSON.stringify([key]));
  assert(!store.value().includes('Ophelia'),'Only opaque keys enter browser storage');
  assert(!store.value().includes('face_ids'),'Face selections must never enter browser storage');
  assert.match(ui.ctx.ui.toolbar(),/data-action="verifycreates"/);
  assert.match(ui.ctx.ui.toolbar(),/1 earlier creation/);
  assert.equal(ui.ctx.lastUndoToken,null,'Unknown response must not fake Undo');

  // Recreating the JS context simulates a browser reload while keeping
  // sessionStorage. The old (private) creation name/payload is not restored.
  ui=runtime(store);
  assert.deepEqual(Array.from(ui.ctx.ui.keys()),[key]);
  assert.equal(ui.ctx.ui.retryGate(),false);
  assert(ui.confirmations.at(-1).includes('browser reloaded'));
  assert.equal(ui.requests.length,0,'No automatic POST or status lookup on reload');
  await ui.ctx.ui.verify();
  assert.deepEqual(Array.from(ui.ctx.ui.keys()),[]);
  assert.equal(store.value(),'[]');
  assert.equal(ui.requests.length,1);
  assert.deepEqual(ui.requests[0],[
    '/api/person/create/status',{request_id:key}
  ]);
  assert.equal(ui.ctx.lastUndoToken,'f'.repeat(32),
    'Only confirmed saved transaction can safely expose its original Undo');
  assert.equal(ui.rendered.length,1);
  assert.match(ui.notifications.at(-1),/1 confirmed saved · 0 undone/);
  assert.equal(ui.ctx.ui.toolbar(),'');

  // Unknown is explicitly NOT rollback; repeated read-only checks keep
  // the opaque recovery key until a confirmed terminal status arrives.
  store.preset(JSON.stringify(['a'.repeat(32)]));
  ui=runtime(store,{forceStatus:{status:'unknown'}});
  await ui.ctx.ui.verify();
  assert.deepEqual(Array.from(ui.ctx.ui.keys()),['a'.repeat(32)]);
  assert.match(ui.notifications.at(-1),/still unverified · inspect People before retrying/);
  assert.equal(ui.ctx.lastUndoToken,null);
  assert(ui.requests.every(x=>x[0]==='/api/person/create/status'));

  // Explicitly undone/archived creations can release old operation keys;
  // they must not resurrect identities or issue create requests.
  store.preset(JSON.stringify(['b'.repeat(32),'c'.repeat(32)]));
  const statuses=new Map([
    ['b'.repeat(32),{status:'undone'}],
    ['c'.repeat(32),{status:'archived_or_missing'}],
  ]);
  ui=runtime(store);
  const post=ui.ctx.post;
  ui.ctx.post=async (uri,payload)=>{
    assert.equal(uri,'/api/person/create/status');
    ui.requests.push([uri,JSON.parse(JSON.stringify(payload))]);
    return statuses.get(payload.request_id);
  };
  await ui.ctx.ui.verify();
  assert.deepEqual(Array.from(ui.ctx.ui.keys()),[]);
  assert.equal(store.value(),'[]');
  assert.match(ui.notifications.at(-1),/1 undone · 1 archived\/missing/);
  assert.equal(ui.requests.length,2);

  // If another review action already owns Undo, recovery may NOT quietly
  // override that more recent token with the older creation's token.
  store.preset(JSON.stringify(['d'.repeat(32)]));
  ui=runtime(store,{undoToken:'e'.repeat(32),forceStatus:{
    status:'committed',person_id:899,undo_token:'f'.repeat(32)
  }});
  await ui.ctx.ui.verify();
  assert.equal(ui.ctx.lastUndoToken,'e'.repeat(32));
  assert.deepEqual(Array.from(ui.ctx.ui.keys()),[]);

  // A burst of 80 Verify taps coalesces into one read-only status POST.
  // If the reviewer navigates away before the response, the old check may
  // clear only the matching recovery key: no stale repaint or Undo takeover.
  store.preset(JSON.stringify(['e'.repeat(32)]));
  ui=runtime(store);
  let releaseStatus=null,reads=0;
  ui.ctx.post=async(uri,payload)=>{
    assert.equal(uri,'/api/person/create/status');reads++;
    return new Promise(resolve=>{releaseStatus=resolve});
  };
  const firstCheck=ui.ctx.ui.verify();
  const extraChecks=Array.from({length:79},()=>ui.ctx.ui.verify());
  assert.equal(reads,1,'Duplicate status taps must never amplify HTTP work');
  ui.ctx.tab='settings';ui.ctx.renderEpoch++;
  releaseStatus({status:'committed',person_id:733,undo_token:'f'.repeat(32)});
  await Promise.all([firstCheck,...extraChecks]);
  assert.equal(reads,1);
  assert.deepEqual(Array.from(ui.ctx.ui.keys()),[]);
  assert.equal(ui.rendered.length,0,'Old People verification repainted Settings');
  assert.equal(ui.ctx.lastUndoToken,null,'Late previous-view verification stole Undo');
  assert.equal(store.value(),'[]');

  // Unknown/failed status keeps key and releases the action's single-flight
  // flag for the reviewer's next explicit check.
  store.preset(JSON.stringify(['f'.repeat(32)]));
  ui=runtime(store);
  let attempts=0;
  ui.ctx.post=async()=>{attempts++;throw Error('Synthetic status transport failed')};
  await ui.ctx.ui.verify();
  await ui.ctx.ui.verify();
  assert.equal(attempts,2);
  assert.deepEqual(Array.from(ui.ctx.ui.keys()),['f'.repeat(32)]);
  assert.match(ui.notifications.at(-1),/still unverified/);

  // Eight permanently UNKNOWN browser reminders would block new creates
  // until the owner can inspect and deliberately dismiss one. Cancellation
  // retains every key; explicit acknowledgement discards ONLY one browser
  // reminder without touching the server. A burst of 60 taps coalesces.
  const staleKeys=Array.from({length:8},(_,i)=>(i+1).toString(16).padStart(32,'0'));
  store.preset(JSON.stringify(staleKeys));
  ui=runtime(store,{confirm:false});
  await ui.ctx.ui.dismiss();
  assert.deepEqual(Array.from(ui.ctx.ui.keys()),staleKeys);
  assert(ui.confirmations[0].includes('does NOT cancel a server request'));
  assert(ui.confirmations[0].includes('MAY STILL COMMIT'));
  assert.equal(ui.requests.length,0);
  assert.match(ui.notifications.at(-1),/reminder retained/);

  ui=runtime(store,{confirm:true});
  const manyDismissals=Array.from({length:60},()=>ui.ctx.ui.dismiss());
  await Promise.all(manyDismissals);
  assert.equal(ui.confirmations.length,1,'Concurrent dismiss taps must not discard multiple keys');
  assert.deepEqual(Array.from(ui.ctx.ui.keys()),staleKeys.slice(1));
  assert.equal(store.value(),JSON.stringify(staleKeys.slice(1)));
  assert.equal(ui.requests.length,0,'Dismissing a browser reminder cannot call the server');
  assert.equal(ui.ctx.lastUndoToken,null);
  assert.match(ui.notifications.at(-1),/People database unchanged/);

  // Corrupt/oversized or non-opaque storage contents cannot inject People
  // identifiers/names into the recovery queue or trigger POSTs.
  for(const malformed of [
    '{bad json','{}',JSON.stringify([{name:'Private Name'}]),
    JSON.stringify(Array.from({length:9},(_,i)=>(i+1).toString(16).padStart(32,'0'))),
    JSON.stringify(['g'.repeat(32)]),JSON.stringify(['a'.repeat(32),'a'.repeat(32)])
  ]){
    store.preset(malformed);
    ui=runtime(store);
    assert.deepEqual(Array.from(ui.ctx.ui.keys()),[]);
    assert.equal(ui.requests.length,0);
  }

  // An inaccessible sessionStorage is fail-soft for live memory only.
  const blocked={getItem(){throw Error('Denied')},setItem(){throw Error('Denied')}};
  ui=runtime(blocked);
  await assert.rejects(ui.ctx.ui.create('Volatile Fixture',{name:'Volatile Fixture'},
    async()=>{},async()=>{}),/Could not verify person creation/);
  assert.equal(Array.from(ui.ctx.ui.keys()).length,1,'The current tab can still inspect');
  console.log('PASS: real V4 browser session recovery retains opaque keys only, status survives reload, 80 rapid verification taps coalesce, late cross-tab result cannot steal Undo/focus, unknown stays ambiguous, explicit one-at-a-time browser-only dismissal, corrupt/private storage denied and no automatic creation retry');
})().catch(e=>{console.error(e);process.exitCode=1});
