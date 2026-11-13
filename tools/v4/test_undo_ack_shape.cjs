'use strict';
// Execute the actual V4 Undo event handler against fictional HTTP replies.
// An HTTP-success body without a valid committed Undo count is UNKNOWN.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const a=source.indexOf('// Direct review writes, including Undo');
const b=source.indexOf("\ndocument.addEventListener('input'",a);
assert(a>=0&&b>a,'Must execute production review dispatcher');
const code=source.slice(a,b)+
  '\nthis.undoClick=()=>eventAction(\'undomanual\');'+
  'this.statusClick=()=>eventAction(\'verifyundo\');'+
  'this.pendingUndo=()=>[...unverifiedUndoTokens];';
const token='a'.repeat(32);
function fixture(reply){
  const calls=[],toasts=[];let renders=0,replyStatus='undone';
  const ctx={
    tab:'person',person:19,page:3,personMode:'faces',group:null,
    renderEpoch:6,lastUndoToken:token,selected:new Set([77,78]),
    post:async(url,body)=>{
      calls.push([url,JSON.parse(JSON.stringify(body))]);
      if(url==='/api/review/undo')return reply;
      assert.equal(url,'/api/review/undo/status');
      return {status:replyStatus};
    },
    toast:message=>toasts.push(message),
    confirm:()=>false,
    render:async()=>{renders++;ctx.renderEpoch++},
  };
  vm.createContext(ctx);
  vm.runInContext(code,ctx);
  ctx.repaintReviewKeepingPosition=async()=>{renders++;ctx.renderEpoch++};
  return {ctx,calls,toasts,renders:()=>renders,
    status:value=>{replyStatus=value}};
}
(async()=>{
  const malformed=[
    undefined,null,{},[],false,'',0,
    {count:null},{count:'2'},{count:-1},{count:1.5},{count:true},
    {count:Number.NaN},{count:Infinity},{count:Number.MAX_SAFE_INTEGER+1}
  ];
  for(const reply of malformed){
    const f=fixture(reply);
    await f.ctx.undoClick();
    assert.equal(f.calls.length,1);
    assert.deepEqual(f.calls[0],[
      '/api/review/undo',{undo_token:token}
    ]);
    assert.equal(f.ctx.lastUndoToken,token,
      'Malformed Undo ACK cannot consume the browser one-use token');
    assert.deepEqual(Array.from(f.ctx.pendingUndo()),[token],
      'Keep opaque recovery key until read-only server status confirms Undo');
    assert.deepEqual([...f.ctx.selected],[77,78],
      'Unknown Undo outcome cannot retire currently selected faces');
    assert.equal(f.renders(),0);
    assert.match(f.toasts.at(-1),/Could not verify Undo: Incomplete Undo acknowledgement/);
    assert.match(f.toasts.at(-1),/inspect People or face tags before retrying/);
    assert(!f.toasts.some(x=>x.startsWith('Review action undone')));

    // Only a read-only verification is allowed without a new explicit
    // inspected retry. No mutation is retransmitted.
    await f.ctx.statusClick();
    assert.equal(f.calls.length,2);
    assert.deepEqual(f.calls[1],[
      '/api/review/undo/status',{undo_token:token}
    ]);
    assert.deepEqual(Array.from(f.ctx.pendingUndo()),[]);
    assert.equal(f.ctx.lastUndoToken,null);
    assert.match(f.toasts.at(-1),/1 Undo confirmed/);
  }

  // Empty new profile's genuine Undo restores ZERO faces; count=0 is valid.
  // Reviewed multi-face Undo returns a positive integer. In both cases,
  // the exact one-use token can be retired without clearing selections.
  for(const count of [0,2,5000]){
    const f=fixture({count});
    await f.ctx.undoClick();
    assert.equal(f.calls.length,1);
    assert.equal(f.ctx.lastUndoToken,null);
    assert.deepEqual(Array.from(f.ctx.pendingUndo()),[]);
    assert.deepEqual([...f.ctx.selected],[77,78]);
    assert.equal(f.renders(),1);
    assert.match(f.toasts.at(-1),new RegExp('Review action undone · '+count+' faces restored'));
    assert(!f.toasts.some(x=>x.includes('Incomplete Undo acknowledgement')));
  }
  console.log('PASS: 15 malformed HTTP-200 Undo receipts stay unverified and retain one-use token/selection; read-only check recovers, valid zero/positive counts still succeed');
})().catch(e=>{console.error(e);process.exitCode=1});
