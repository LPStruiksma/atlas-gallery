'use strict';
// Exercise the REAL V4 review click dispatcher with disposable fictional
// HTTP replies. HTTP 200 alone is not a completed reviewed transaction.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=src.indexOf('// Direct review writes, including Undo');
const end=src.indexOf("\ndocument.addEventListener('input'",start);
assert(start>0&&end>start,'Production V4 review dispatcher missing');
const js=src.slice(start,end)+'\nthis.reviewAction=eventAction;'+
  'this.unverified=()=>unverifiedDirectReviewKind;';
const validToken='a'.repeat(32);
const routes=[
  ['accept','/api/person/accept','person','suggested'],
  ['reject','/api/person/reject','person','suggested'],
  ['ignore','/api/face/ignore','untagged','suggested'],
  ['unassign','/api/face/unassign','person','faces'],
];
function fixture(action,tab,mode,response){
  const calls=[],messages=[],tokens=[],prompts=[];
  let paints=0,approve=true;
  const ctx={
    tab,page:2,person:73,group:null,personMode:mode,renderEpoch:4,
    selected:new Set([801,802]),lastUndoToken:null,
    post:async (url,payload)=>{
      calls.push([url,JSON.parse(JSON.stringify(payload))]);
      return response;
    },
    toast:msg=>messages.push(msg),
    keepReviewUndo:reply=>{tokens.push(reply.undo_token);ctx.lastUndoToken=reply.undo_token},
    confirm:msg=>{prompts.push(msg);return approve},
    render:async()=>{paints++;ctx.renderEpoch++},
  };
  vm.createContext(ctx);
  vm.runInContext(js,ctx);
  // DOM/RAF focus preservation is separately covered in the production
  // repaint test; this fixture isolates transaction acknowledgement.
  ctx.repaintReviewKeepingPosition=async()=>{paints++;ctx.renderEpoch++};
  return {ctx,calls,messages,tokens,prompts,paints:()=>paints,
    disallow:()=>{approve=false}};
}
(async()=>{
  const malformed=[
    undefined,null,{},[],true,{count:2},{count:null,undo_token:validToken},
    {count:'2',undo_token:validToken},
    {count:1,undo_token:validToken},
    {count:3,undo_token:validToken},
    {count:2,undo_token:null},
    {count:2,undo_token:'a'.repeat(31)},
    {count:2,undo_token:'A'.repeat(32)},
    {count:2,undo_token:42},
    {count:2,undo_token:'not-an-undo-token'},
  ];
  for(const [action,path,tab,mode] of routes){
    for(const body of malformed){
      const f=fixture(action,tab,mode,body);
      await f.ctx.reviewAction(action);
      assert.equal(f.calls.length,1,action+' must issue one explicit review POST');
      assert.equal(f.calls[0][0],path);
      assert.deepEqual([...f.ctx.selected],[801,802],
        'Malformed reply must NOT retire reviewer-selected faces');
      assert.equal(f.ctx.lastUndoToken,null,
        'Malformed reply must NOT fabricate a one-use Undo token');
      assert.equal(f.tokens.length,0);
      assert.equal(f.paints(),0,
        'Malformed HTTP 200 must NOT redraw the page as if confirmed');
      assert.match(f.messages.at(-1),/Could not verify review change: Incomplete review acknowledgement/);
      assert.match(f.messages.at(-1),/inspect People or face tags before retrying/);
      assert(f.ctx.unverified()!==null,
        'Missing transaction evidence must retain ephemeral inspection warning');
      f.disallow();
      // Even switching from a malformed Hide/Unassign acknowledgement
      // to Accept is gated; no second write unless inspected explicitly.
      await f.ctx.reviewAction('accept');
      assert.equal(f.calls.length,1,
        'A second review action must not bypass the inspection guard');
      assert.equal(f.tokens.length,0);
    }

    // Backend's real audited contract always returns the exact reviewed
    // count and a 32-hex token. This still renders, creates real Undo,
    // and retires the two submitted faces normally.
    const f=fixture(action,tab,mode,{count:2,undo_token:validToken});
    await f.ctx.reviewAction(action);
    assert.equal(f.calls.length,1);
    assert.deepEqual([...f.ctx.selected],[]);
    assert.deepEqual(f.tokens,[validToken]);
    assert.equal(f.ctx.lastUndoToken,validToken);
    assert.equal(f.ctx.unverified(),null);
    assert.equal(f.paints(),1);
    assert.doesNotMatch(f.messages.at(-1),/Could not verify/);
  }
  console.log('PASS: 60 malformed HTTP-200 reviewed write responses across all four actual V4 action handlers retain selected faces/Undo evidence, forbid blind follow-up; real count+Undo ACK still succeeds');
})().catch(e=>{console.error(e);process.exitCode=1});
