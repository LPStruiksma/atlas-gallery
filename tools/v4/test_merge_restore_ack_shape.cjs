'use strict';
// The ACTUAL V4 Merge/Restore browser callbacks, fictional HTTP and People.
// Incomplete HTTP-200 replies may follow a committed archival/restoration.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(first,last){
  const a=source.indexOf(first),b=source.indexOf(last,a+first.length);
  assert(a>=0&&b>a,'Actual V4 callback missing: '+first);
  return source.slice(a,b);
}
const code=actual('function reviewWriteContext(){','async function finishDirectReview(')+
  actual('async function mergePeople(){','async function moveReviewPage(')+
  '\nthis.actions={mergePeople,restoreMergedPerson};';
const before='f'.repeat(32),saved='a'.repeat(32);
function fixture(faces=2){
  const calls=[],messages=[],installed=[],pickers=[];
  let renders=0;
  const ctx={
    tab:'person',page:0,person:7,group:null,personMode:'faces',
    renderEpoch:3,lastUndoToken:before,selected:new Set([57,58]),
    get:async url=>{
      if(url==='/api/people?covers=0')return [
        {id:7,name:'Fictional Target',faces:8,images:4},
        {id:8,name:'Fictional Source',faces,images:faces}
      ];
      assert.equal(url,'/api/people/merged?target=7');
      return [{id:8,name:'Fictional Archived',faces}];
    },
    choosePerson:async options=>{pickers.push(options)},
    post:(url,payload)=>new Promise((resolve,reject)=>{
      calls.push({url,payload:JSON.parse(JSON.stringify(payload)),resolve,reject})
    }),
    toast:message=>messages.push(message),
    keepReviewUndo:receipt=>{
      installed.push(receipt.undo_token);ctx.lastUndoToken=receipt.undo_token;
    },
    render:async()=>{renders++;ctx.renderEpoch++},
  };
  vm.createContext(ctx);vm.runInContext(code,ctx);
  async function open(kind){
    await ctx.actions[kind==='merge'?'mergePeople':'restoreMergedPerson']();
    assert.equal(pickers.length,1,'Reviewer identity chooser must open');
    assert.equal(calls.length,0,'Opening chooser must be read-only');
    const source={id:8,name:'Fictional Source',faces};
    const task=pickers[0].onChoose(source);
    assert.equal(calls.length,1,'Only one explicit reviewed mutation');
    assert.equal(calls[0].url,kind==='merge'?'/api/person/merge':'/api/person/restore');
    assert.deepEqual(calls[0].payload,{target:7,source:8});
    return {task,request:calls[0]};
  }
  return {ctx,calls,messages,installed,pickers,open,renders:()=>renders};
}
(async()=>{
  let rejected=0;
  for(const count of [0,2]){
    for(const kind of ['merge','restore']){
      const invalid=[
        undefined,null,{},[],
        {count:NaN},{count:count+1},{count:String(count)},{count:-1},
        {count:count+0.1},{count:null},{count:Infinity},
      ];
      if(kind==='merge')invalid.push(
        {ok:true,count},
        {ok:false,count,undo_token:saved},
        {ok:'true',count,undo_token:saved},
        {ok:true,count,undo_token:null},
        {ok:true,count,undo_token:'A'.repeat(32)},
        {ok:true,count,undo_token:'a'.repeat(31)},
        {ok:true,count,undo_token:7},
        {count,undo_token:saved},
        {ok:true,count:count+1,undo_token:saved},
        {ok:true,count:String(count),undo_token:saved}
      );
      for(const response of invalid){
        const f=fixture(count),{task,request}=await f.open(kind);
        request.resolve(response);
        await assert.rejects(task,
          kind==='merge'
            ?/Incomplete profile merge acknowledgement/
            :/Incomplete profile restore acknowledgement/);
        assert.equal(f.ctx.lastUndoToken,before,
          'Invalid ACK cannot consume or replace an existing Undo');
        assert.deepEqual(f.installed,[],'No invented merge Undo token');
        assert.deepEqual([...f.ctx.selected],[57,58],
          'Invalid response must preserve selection and its original scope');
        assert.equal(f.renders(),0,'Invalid receipt must not announce success by rendering');
        assert.equal(f.calls.length,1,'Invalid receipt may never auto-retry');
        rejected++;
      }
      // Both zero-face and nonempty archived profiles are legitimate.
      const f=fixture(count),{task,request}=await f.open(kind);
      request.resolve(kind==='merge'
        ?{ok:true,count,undo_token:saved}
        :{count});
      await task;
      assert.equal(f.renders(),1);
      if(kind==='merge'){
        assert.equal(f.ctx.lastUndoToken,saved);
        assert.deepEqual(f.installed,[saved]);
        assert.equal(f.messages.at(-1),'Profiles merged');
      }else{
        assert.equal(f.ctx.lastUndoToken,null,
          'Only confirmed restoration consumes the archived merge Undo');
        assert.deepEqual(f.installed,[]);
        assert.match(f.messages.at(-1),/Restored Fictional Source/);
      }
    }
  }
  assert.equal(rejected,64);
  console.log('PASS: actual V4 Merge/Restore handlers reject 64 malformed receipts across 0/2-face profiles, preserve Undo/selection, never auto-retry; four valid receipts complete');
})().catch(error=>{console.error(error);process.exitCode=1});
