'use strict';
// Exact production V4 chooser and review-recovery JS with fictional responses.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function part(from,to){
  const i=src.indexOf(from),j=src.indexOf(to,i+from.length);
  assert(i>=0&&j>i,'Missing real V4 browser code '+from);
  return src.slice(i,j);
}
const code=part('async function assign(){','function comparablePersonName(')+
  part('// Direct review writes, including Undo',"\ndocument.addEventListener('input'")+
  '\nthis.api={assign,verify:verifyPendingReviewWrites,keys:()=>pendingReviewWriteRequests.map(x=>({...x})),gate:confirmAfterUnverifiedReview};';
let seed=0;
function store(){
  const values=new Map();
  return {getItem:k=>values.has(k)?values.get(k):null,
    setItem:(k,v)=>values.set(k,String(v)),
    value:()=>values.get('atlas-v4-reviewed-write-recovery-v1')};
}
function runtime(session,options={}){
  const posts=[],pickers=[],toasts=[],installed=[];
  let repaints=0;
  const ctx={
    sessionStorage:session,
    crypto:options.noCrypto?undefined:{getRandomValues:bytes=>{
      const n=++seed;for(let i=0;i<bytes.length;i++)bytes[i]=(n*29+i*11)&255;
      return bytes;
    }},
    tab:'group',group:7,page:0,person:null,personMode:'photos',
    renderEpoch:2,selected:new Set([101,102]),lastUndoToken:options.undo??null,
    choosePerson:async opts=>{pickers.push(opts)},
    post:(url,payload)=>{
      posts.push([url,JSON.parse(JSON.stringify(payload))]);
      if(url==='/api/review/write/status')
        return Promise.resolve(options.status??{status:'unknown'});
      assert.equal(url,'/api/person/assign');
      if(options.lost)return Promise.reject(Error('Fictional lost ACK AFTER durable commit'));
      return Promise.resolve(options.receipt??{count:2,undo_token:'e'.repeat(32)});
    },
    promptPersonName:()=> 'Fictional Homonym',
    createReviewedPerson:async(_name,_payload,onExisting,onComplete)=>{
      await onExisting({id:24,name:'Fictional Existing'});
      await onComplete();
    },
    keepReviewUndo:reply=>{ctx.lastUndoToken=reply.undo_token;installed.push(reply.undo_token)},
    repaintReviewKeepingPosition:async()=>{repaints++;ctx.renderEpoch++},
    toast:s=>toasts.push(s),
    confirm:()=>false
  };
  vm.createContext(ctx);vm.runInContext(code,ctx);
  // Production excerpt defines a DOM repaint function. This headless test
  // isolates status/identity semantics; viewport repaint has dedicated tests.
  ctx.repaintReviewKeepingPosition=async()=>{repaints++;ctx.renderEpoch++};
  return {ctx,posts,pickers,toasts,installed,repaints:()=>repaints};
}
const ordinary=async (ui,target=24)=>{
  await ui.ctx.api.assign();
  return ui.pickers.at(-1).onChoose({id:target,name:'Fictional Existing'});
};
const homonym=async ui=>{
  await ui.ctx.api.assign();
  return ui.pickers.at(-1).onNew();
};
const keys=ui=>JSON.parse(JSON.stringify(ui.ctx.api.keys()));
(async()=>{
  const session=store();
  let ui=runtime(session,{lost:true,undo:'a'.repeat(32)});
  await assert.rejects(ordinary(ui),/Fictional lost ACK/);
  assert.equal(ui.posts.length,1);
  assert.equal(ui.posts[0][0],'/api/person/assign');
  const key=ui.posts[0][1].request_id;
  assert.match(key,/^[0-9a-f]{32}$/);
  assert.equal(ui.posts[0][1].person_id,24);
  assert.deepEqual(ui.posts[0][1].face_ids,[101,102]);
  assert.deepEqual(keys(ui),[{id:key,count:2}]);
  assert.equal(ui.ctx.lastUndoToken,'a'.repeat(32));
  assert.deepEqual([...ui.ctx.selected],[101,102]);
  assert.equal(ui.repaints(),0);
  assert(!session.value().includes('person_id')&&!session.value().includes('face_ids'));
  assert.equal(ui.ctx.api.gate(),false,'Same-tab lost reply must gate another review');
  assert.equal(ui.posts.length,1,'No automatic replay');

  // A reload recovers an opaque key only. Explicit read-only server status
  // proves the historical commit and safely leaves the newer Undo untouched.
  ui=runtime(session,{undo:'a'.repeat(32),
     status:{status:'recorded_not_undone',count:2,undo_token:'e'.repeat(32)}});
  assert.equal(ui.posts.length,0,'No automatic status call or mutation after reload');
  await ui.ctx.api.verify();
  assert.deepEqual(keys(ui),[]);
  assert.equal(ui.posts.length,1);
  assert.equal(ui.posts[0][0],'/api/review/write/status');
  assert.equal(ui.ctx.lastUndoToken,'a'.repeat(32),'Older recovery cannot steal Undo');

  // The New Person -> existing name path uses the exact same reviewed
  // attachment endpoint, a different cryptographic per-intent key and count.
  ui=runtime(session);
  await homonym(ui);
  assert.equal(ui.posts.length,1);
  assert.equal(ui.posts[0][1].person_id,24);
  assert.match(ui.posts[0][1].request_id,/^[0-9a-f]{32}$/);
  assert.notEqual(ui.posts[0][1].request_id,key);
  assert.equal(ui.ctx.selected.size,0);
  assert.equal(ui.ctx.lastUndoToken,'e'.repeat(32));
  assert.deepEqual(keys(ui),[]);

  // Invalid success reply is still unknown; store and selection survive.
  ui=runtime(session,{receipt:{count:1,undo_token:'d'.repeat(32)}});
  await assert.rejects(ordinary(ui),/Incomplete face attachment acknowledgement/);
  assert.equal(keys(ui).length,1);
  assert.equal(ui.ctx.selected.size,2);
  assert.equal(ui.ctx.lastUndoToken,null);
  const unknown=ui.posts[0][1].request_id;
  await ui.ctx.api.verify();
  assert.deepEqual(keys(ui),[{id:unknown,count:2}]);

  // Browsers without WebCrypto keep earlier guarded review semantics, never
  // produce a guessable request ID or incorrectly claim replay safety.
  const fresh=store();
  ui=runtime(fresh,{noCrypto:true,lost:true});
  await assert.rejects(ordinary(ui),/Fictional lost ACK/);
  assert.equal('request_id' in ui.posts[0][1],false);
  assert.equal(keys(ui).length,0);
  assert.equal(ui.ctx.api.gate(),false,'Legacy uncertain outcome still requires inspection');

  // A newer one-use Undo completing while a chooser's POST is in flight is
  // already covered by existing V4 regression; ordinary chooser remains safe.
  console.log('PASS: two real existing-Person chooser callbacks use secure status-recoverable review keys, preserve lost-ACK selections/current Undo, disallow blind retry, survive reload, honor older/newer Undo and safely fall back without WebCrypto; synthetic browser only');
})().catch(error=>{console.error(error);process.exitCode=1});
