'use strict';
// Actual V4 existing-Person assignment/group chooser callbacks with entirely
// fictional data. A fulfilled HTTP/JSON promise is not a review receipt.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function part(a,b){const i=src.indexOf(a),j=src.indexOf(b,i+a.length);
  assert(i>=0&&j>i,'Actual handler missing '+a);return src.slice(i,j)}
const code=part('function reviewWriteContext(){','async function finishDirectReview(')+
  part('async function assign(){','function comparablePersonName(')+
  part('async function nameGroup(){','async function renameReviewedPerson(){')+
  '\nthis.actions={assign,nameGroup,attachWholeGroup};';
const tick=()=>new Promise(setImmediate),undoBefore='f'.repeat(32),token='a'.repeat(32);
const modes=[
  {mode:'face-picker',url:'/api/person/assign',count:2},
  {mode:'face-name-existing',url:'/api/person/assign',count:2},
  {mode:'group-picker',url:'/api/group/attach',count:3},
  {mode:'group-name-existing',url:'/api/group/attach',count:3},
];
function fixture(){
  const requests=[],pickers=[],messages=[],installed=[];
  let renders=0,returns=0;
  const ctx={
    tab:'group',group:13,page:0,person:null,personMode:'photos',
    renderEpoch:3,selected:new Set([71,72]),lastUndoToken:undoBefore,
    get:async url=>{assert.equal(url,'/api/group/count?id=13');return {count:3}},
    post:(url,payload)=>new Promise((resolve,reject)=>{
      requests.push({url,payload:JSON.parse(JSON.stringify(payload)),resolve,reject});
    }),
    choosePerson:async options=>pickers.push(options),
    createReviewedPerson:async(_name,_payload,onExisting,onComplete)=>{
      await onExisting({id:91,name:'Fictional Existing'});
      await onComplete();
    },
    promptPersonName:()=> 'Fictional Duplicate',
    keepReviewUndo:result=>{installed.push(result.undo_token);ctx.lastUndoToken=result.undo_token},
    toast:msg=>messages.push(msg),
    repaintReviewKeepingPosition:async()=>{renders++;ctx.renderEpoch++},
    returnToGroups:async()=>{returns++},
  };
  vm.createContext(ctx);vm.runInContext(code,ctx);
  // The actual helper block declares its DOM/RAF repaint after this fixture
  // is initialized. Override that browser surface only after VM evaluation;
  // production scroll/focus continuity has its own isolated regression.
  ctx.repaintReviewKeepingPosition=async()=>{renders++;ctx.renderEpoch++};
  return {ctx,requests,pickers,messages,installed,
    renders:()=>renders,returns:()=>returns};
}
async function begin(f,mode){
  const a=f.ctx.actions;
  if(mode==='face-picker'){await a.assign();return f.pickers.at(-1).onChoose({id:91,name:'Fictional Existing'})}
  if(mode==='face-name-existing'){await a.assign();return f.pickers.at(-1).onNew()}
  if(mode==='group-picker'){await a.attachWholeGroup();return f.pickers.at(-1).onChoose({id:91,name:'Fictional Existing'})}
  return a.nameGroup();
}
(async()=>{
  let malformedCount=0;
  for(const spec of modes){
    const broken=[
      undefined,null,{},[],
      {count:spec.count},
      {count:spec.count+1,undo_token:token},
      {count:String(spec.count),undo_token:token},
      {count:spec.count,undo_token:null},
      {count:spec.count,undo_token:'A'.repeat(32)},
      {count:spec.count,undo_token:'invalid'},
    ];
    for(const body of broken){
      const f=fixture(),task=begin(f,spec.mode);
      await tick();
      assert.equal(f.requests.length,1,'Explicit assignment must issue exactly one POST');
      assert.equal(f.requests[0].url,spec.url);
      f.requests.shift().resolve(body);
      await assert.rejects(task,
        /Incomplete (face|group) attachment acknowledgement/);
      assert.equal(f.ctx.lastUndoToken,undoBefore,
        'Malformed ACK must NOT displace older/current Undo');
      assert.deepEqual([...f.ctx.selected],[71,72],
        'Cannot clear submitted selection without verified assignment');
      assert.deepEqual(f.installed,[],
        'Malformed ACK must never install phantom one-use Undo');
      assert.equal(f.renders(),0);
      assert.equal(f.returns(),0);
      assert.equal(f.requests.length,0,'No automatic retry or additional write');
      malformedCount++;
    }
    const good=fixture(),task=begin(good,spec.mode);
    await tick();assert.equal(good.requests.length,1);
    good.requests.shift().resolve({count:spec.count,undo_token:token});
    await task;
    assert.equal(good.ctx.lastUndoToken,token,
      'Real audited assignment ACK must still surface its Undo token');
    assert.deepEqual(good.installed,[token]);
    if(spec.url==='/api/person/assign')
      assert.deepEqual([...good.ctx.selected],[]);
    else assert.equal(good.returns(),1);
  }
  assert.equal(malformedCount,40);
  console.log('PASS: real V4 selected-face/whole-group existing-Person callbacks refuse 40 malformed HTTP-200 replies, preserve selection/Undo and no auto retry; four valid reviewed receipts still attach');
})().catch(error=>{console.error(error);process.exitCode=1});
