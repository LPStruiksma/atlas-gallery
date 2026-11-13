'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const begin=src.indexOf('async function moveReviewPage(direction){');
const end=src.indexOf('\nasync function eventAction(a){',begin);
assert(begin>0&&end>begin,'Test must execute real V4 page-turn handler');
const queue=Array.from({length:2001},(_,i)=>i+1); // entirely fictional groups
const pending=[],frames=[],events=[];
let visible=queue.slice(0,40),identityWrites=0;
const ctx={
  page:0,tab:'groups',person:9,group:10,personMode:'photos',
  renderEpoch:0,selected:new Set([444,445]),
  document:{querySelector:selector=>{
    if(selector==='[data-action="next"]')return {
      disabled:ctx.page>=Math.ceil(queue.length/40)-1};
    if(selector==='#view .tile[data-group], #view .tile[data-photo], #view .face-open')
      return visible.length?{focus:opts=>events.push(['focus',ctx.tab,ctx.page,opts.preventScroll])}:null;
    throw Error('Unexpected selector '+selector);
  }},
  window:{scrollTo:(x,y)=>events.push(['scroll',ctx.tab,ctx.page,x,y])},
  requestAnimationFrame:cb=>frames.push(cb),
  render:()=>{
    const epoch=++ctx.renderEpoch,tab=ctx.tab,page=ctx.page;
    return new Promise((resolve,reject)=>pending.push({
      epoch,tab,page,
      resolve:()=>{if(ctx.renderEpoch===epoch&&ctx.tab===tab)
        visible=queue.slice(page*40,page*40+40);resolve();},
      reject
    }));
  }
};
vm.createContext(ctx);
vm.runInContext(src.slice(begin,end)+'\nthis.turnPage=moveReviewPage;',ctx);
function settle(){
  assert.equal(pending.length,1,'Only one page render must be outstanding');
  pending.shift().resolve();
}
(async()=>{
  // 80 pointer/keyboard events can be delivered by the old toolbar while
  // an asynchronous page fetch is pending. They must yield exactly one turn.
  const bursts=Array.from({length:80},(_,i)=>ctx.turnPage(i%2?'prev':'next'));
  assert.equal(ctx.page,1,'Rapid stale controls skipped an unseen page');
  assert.equal(pending.length,1,'Repeated taps generated redundant page fetches');
  assert.equal(ctx.selected.size,0,'Selection must not bleed into next page');
  settle();await Promise.all(bursts);
  assert.equal(frames.length,1);frames.shift()();
  assert.deepEqual(visible,queue.slice(40,80));
  assert.deepEqual(events.at(-1),['focus','groups',1,true]);

  // A full 2001-group fictional queue needs 51 pages, including the last
  // one-item page. Completing one turn allows the next, with no repeats.
  const seen=[...queue.slice(0,40),...visible];
  for(let i=2;i<=50;i++){
    const turn=ctx.turnPage('next');
    assert.equal(ctx.page,i,'Page number did not advance after settled fetch');
    settle();await turn;
    assert.equal(frames.length,1);frames.shift()();
    assert.deepEqual(visible,queue.slice(i*40,(i+1)*40));
    seen.push(...visible);
  }
  assert.equal(seen.length,2001);
  assert.equal(new Set(seen).size,2001,'Unchanged-queue navigation skipped/repeated groups');
  assert.equal(ctx.page,50);assert.equal(visible.length,1);
  await ctx.turnPage('next');
  assert.equal(ctx.page,50,'Last-page Next cannot advance beyond end of queue');
  assert.equal(pending.length,0);

  // A concurrent review may shrink the queue. Even if a stale render is
  // already in flight, a new Person screen must be able to paginate.
  queue.splice(0,240); // fictional removal of six previously reviewed pages
  ctx.page=30;ctx.renderEpoch++;ctx.tab='groups';
  const stale=ctx.turnPage('next');
  assert.equal(ctx.page,31);assert.equal(pending.length,1);
  ctx.tab='person';ctx.person=123;ctx.personMode='faces';
  ctx.page=0;ctx.renderEpoch++;visible=queue.slice(0,40);
  const newer=ctx.turnPage('next');
  assert.equal(ctx.page,1,'Old groups turn improperly locked a different Person');
  assert.equal(pending.length,2);
  const oldRender=pending.shift(),newRender=pending.shift();
  const before=events.length;
  oldRender.resolve();await stale;
  assert.equal(events.length,before,'Old groups page stole scroll/focus');
  const extra=ctx.turnPage('next');
  assert.equal(ctx.page,1,'Old async completion cleared the new Person turn lock');
  newRender.resolve();await newer;await extra;
  assert.equal(frames.length,1);frames.shift()();
  assert.equal(ctx.page,1);assert.equal(events.at(-1)[1],'person');

  // Even an exceptional synthetic rendering failure must release the turn
  // guard so the reviewer can retry; no identity write is involved.
  ctx.tab='untagged';ctx.page=0;ctx.renderEpoch++;
  const failure=ctx.turnPage('next');
  assert.equal(pending.length,1);
  pending.shift().reject(Error('Fictional failed render'));
  await assert.rejects(failure,/Fictional failed render/);
  const retry=ctx.turnPage('next');
  assert.equal(ctx.page,2);
  settle();await retry;frames.shift()();
  assert.equal(identityWrites,0);
  console.log('PASS: 80 duplicate taps coalesced; 2001 fictional groups exactly 51 pages; 240-item queue shrink, stale rendering isolation, new-Person turn and failure recovery, zero identity writes');
})().catch(e=>{console.error(e);process.exitCode=1});
