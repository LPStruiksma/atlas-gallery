'use strict';
// Actual V4 group chooser + direct review status browser code; all fictional.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function part(a,b){const i=src.indexOf(a),j=src.indexOf(b,i+a.length);
  assert(i>=0&&j>i,'Missing production handler '+a);return src.slice(i,j)}
const production=part('async function nameGroup(){','async function renameReviewedPerson(){')+
  part('// Direct review writes, including Undo',"\ndocument.addEventListener('input'")+
  '\nthis.review={attach:attachWholeGroup,name:nameGroup,verify:verifyPendingReviewWrites,'+
  'pending:()=>pendingReviewWriteRequests.map(x=>({...x})),gate:confirmAfterUnverifiedReview};';
assert(src.includes("elif path=='/api/group/attach':v=attach_group_to_person(")&&
       src.includes("request_id=body.get('request_id'))"),
       'Actual V4 /api/group/attach route must pass optional opaque key');
let seed=0;
function storage(){
  const values=new Map();
  return {getItem:key=>values.has(key)?values.get(key):null,
          setItem:(key,value)=>values.set(key,String(value)),
          raw:()=>values.get('atlas-v4-reviewed-write-recovery-v1')};
}
function fixture(store,options={}){
  const requests=[],pickers=[],toasts=[],undo=[],prompts=[];
  let returnCount=0;
  const ctx={
    sessionStorage:store,
    crypto:options.noCrypto?undefined:{getRandomValues:bytes=>{
      seed++;for(let i=0;i<bytes.length;i++)bytes[i]=(seed*17+i*23)&255;
      return bytes;
    }},
    tab:'group',person:null,group:13,page:0,personMode:'photos',
    renderEpoch:5,selected:new Set([11,12]),lastUndoToken:options.undo??null,
    get:async url=>{assert.equal(url,'/api/group/count?id=13');return {count:3}},
    choosePerson:async setup=>pickers.push(setup),
    createReviewedPerson:async(_name,_payload,onExisting,onComplete)=>{
      await onExisting({id:77,name:'Existing Fiction'});
      await onComplete();
    },
    promptPersonName:()=> 'Fictional Same Name',
    returnToGroups:async()=>{returnCount++},
    repaintReviewKeepingPosition:async()=>{},
    keepReviewUndo:result=>{ctx.lastUndoToken=result.undo_token;undo.push(result.undo_token)},
    confirm:msg=>{prompts.push(msg);return !!options.confirm},
    toast:msg=>toasts.push(msg),
    post:(url,payload)=>{
      requests.push([url,JSON.parse(JSON.stringify(payload))]);
      if(url==='/api/review/write/status')
        return Promise.resolve(options.status??{status:'unknown'});
      assert.equal(url,'/api/group/attach');
      if(options.lost)return Promise.reject(Error('Simulated ACK lost AFTER commit'));
      return Promise.resolve(options.response??{count:3,undo_token:'e'.repeat(32)});
    }
  };
  vm.createContext(ctx);vm.runInContext(production,ctx);
  // The isolated test executes real review handlers without a DOM; scroll/
  // focus repaint is validated in its existing dedicated browser regression.
  ctx.repaintReviewKeepingPosition=async()=>{};
  return {ctx,requests,pickers,toasts,undo,prompts,returns:()=>returnCount};
}
async function chooser(ui){
  await ui.ctx.review.attach();
  assert.equal(ui.pickers.length,1);
  return ui.pickers[0].onChoose({id:77,name:'Existing Fiction'});
}
const keys=ui=>JSON.parse(JSON.stringify(ui.ctx.review.pending()));
(async()=>{
  const store=storage();
  let ui=fixture(store,{lost:true,undo:'a'.repeat(32)});
  await assert.rejects(chooser(ui),/Simulated ACK lost/);
  assert.equal(ui.requests.length,1);
  assert.equal(ui.requests[0][0],'/api/group/attach');
  const key=ui.requests[0][1].request_id;
  assert.match(key,/^[0-9a-f]{32}$/);
  assert.equal(ui.requests[0][1].person_id,77);
  assert.equal(ui.requests[0][1].cluster_id,13);
  assert.equal(ui.requests[0][1].expected_count,3);
  assert.deepEqual(keys(ui),[{id:key,count:3}]);
  assert.equal(ui.ctx.lastUndoToken,'a'.repeat(32));
  assert.equal(ui.returns(),0);
  assert.equal(ui.ctx.review.gate(),false);
  assert.equal(ui.requests.length,1,'No automatic review replay');
  assert(!store.raw().includes('person_id')&&!store.raw().includes('cluster_id')&&
         !store.raw().includes('Existing Fiction'));

  // Same backend operation may be proven by an explicit read-only status
  // after browser reload, but cannot steal an already available newer Undo.
  ui=fixture(store,{status:{
    status:'recorded_not_undone',count:3,undo_token:'f'.repeat(32)},
    undo:'b'.repeat(32)});
  assert.equal(ui.requests.length,0);
  assert.equal(ui.ctx.review.gate(),false,'Reloaded ambiguous review is gated');
  await ui.ctx.review.verify();
  assert.equal(ui.requests.length,1);
  assert.equal(ui.requests[0][0],'/api/review/write/status');
  assert.deepEqual(keys(ui),[]);
  assert.equal(ui.ctx.lastUndoToken,'b'.repeat(32));

  // Group-new name homonym redirects to the SAME keyed attach endpoint,
  // with no accidental person-create replay or fake second group write.
  ui=fixture(store);
  await ui.ctx.review.name();
  assert.equal(ui.requests.length,1);
  assert.equal(ui.requests[0][0],'/api/group/attach');
  assert.match(ui.requests[0][1].request_id,/^[0-9a-f]{32}$/);
  assert.notEqual(ui.requests[0][1].request_id,key);
  assert.equal(ui.ctx.lastUndoToken,'e'.repeat(32));
  assert.equal(ui.returns(),1);
  assert.deepEqual(keys(ui),[]);

  // HTTP-200 with wrong count, bad Undo or missing body is NOT proof of
  // success. The exact opaque recovery key remains for inspection.
  for(const response of [{count:2,undo_token:'e'.repeat(32)},
                          {count:3,undo_token:'BAD'},{}]){
    const fresh=storage();
    ui=fixture(fresh,{response});
    await assert.rejects(chooser(ui),/Incomplete group attachment acknowledgement/);
    assert.equal(keys(ui).length,1);
    assert.equal(ui.ctx.lastUndoToken,null);
    assert.equal(ui.returns(),0);
    assert.equal(ui.requests.length,1);
  }

  // A historical browser with no secure randomness retains human
  // inspection-required behavior; never insert a guessable request key.
  ui=fixture(storage(),{noCrypto:true,lost:true});
  await assert.rejects(chooser(ui),/Simulated ACK lost/);
  assert.equal('request_id' in ui.requests[0][1],false);
  assert.deepEqual(keys(ui),[]);
  assert.equal(ui.ctx.review.gate(),false,'Failed legacy request must be inspected');

  console.log('PASS: production V4 whole-group chooser and same-name attach support opaque status recovery, strict receipts, no blind replay, reload/newer Undo precedence and secure fallback; synthetic browser VM');
})().catch(e=>{console.error(e);process.exitCode=1});
