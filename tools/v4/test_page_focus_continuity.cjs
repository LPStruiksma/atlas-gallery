'use strict';
const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=src.indexOf('async function moveReviewPage(direction){');
const end=src.indexOf('async function eventAction(a){',start);
assert(start>0&&end>start,'Cannot isolate production pager focus handler');

let disabled=false,visible=true;
const pending=[],frames=[],events=[];
const ctx={
  page:0,tab:'untagged',person:11,group:33,personMode:'photos',
  renderEpoch:1,selected:new Set([77]),
  document:{
    querySelector:selector=>{
      if(selector==='[data-action="next"]')return {disabled};
      if(selector==='#view .tile[data-group], #view .tile[data-photo], #view .face-open'){
        return visible?{focus:opts=>events.push(['focus',opts.preventScroll])}:null;
      }
      throw new Error('Unexpected selector: '+selector);
    }
  },
  window:{scrollTo:(x,y)=>events.push(['scroll',x,y])},
  requestAnimationFrame:callback=>frames.push(callback),
  render:()=>{
    ctx.renderEpoch++;
    return new Promise(resolve=>pending.push(resolve));
  }
};
vm.createContext(ctx);
vm.runInContext(src.slice(start,end)+'\nthis.movePage=moveReviewPage;',ctx);

(async()=>{
  // Directly clicking keyboard-operable Previous on page 1 is inert.
  await ctx.movePage('prev');
  assert.equal(ctx.page,0);
  assert.equal(pending.length,0);

  // Page 2: clear only visible selection; restore scroll then focus the first
  // actual tile after the DOM update and the browser's next animation frame.
  let running=ctx.movePage('next');
  assert.equal(ctx.page,1);
  assert.equal(ctx.selected.size,0);
  assert.equal(pending.length,1);
  pending.shift()();await running;
  assert.deepEqual(events,[['scroll',0,0]]);
  assert.equal(frames.length,1);
  frames.shift()();
  assert.deepEqual(events.at(-1),['focus',true]);

  // No more results: a disabled Next button must not change page or render.
  disabled=true;
  running=ctx.movePage('next');await running;
  assert.equal(ctx.page,1);
  assert.equal(pending.length,0);
  disabled=false;

  // Going back from page 2 retains keyboard navigation into the first row.
  running=ctx.movePage('prev');
  assert.equal(ctx.page,0);
  pending.shift()();await running;
  frames.shift()();
  assert.deepEqual(events.slice(-2),[['scroll',0,0],['focus',true]]);

  // A render superseded by new navigation never scrolls another screen.
  running=ctx.movePage('next');
  ctx.tab='groups';ctx.renderEpoch++;
  const before=events.length;
  pending.shift()();await running;
  assert.equal(events.length,before);
  assert.equal(frames.length,0);

  // A RAF queued after a successful render must not focus into a subsequent
  // Person or newer pagination frame.
  ctx.tab='person';ctx.person=11;ctx.personMode='faces';ctx.page=0;
  running=ctx.movePage('next');
  pending.shift()();await running;
  assert.equal(frames.length,1);
  const noFocus=events.length;
  ctx.person=12;
  frames.shift()();
  assert.equal(events.length,noFocus,'Stale Person selection stole focus');

  // Missing tile / empty shrinking page must not throw or steal focus.
  ctx.person=11;ctx.page=0;ctx.tab='untagged';visible=false;
  running=ctx.movePage('next');
  pending.shift()();await running;
  frames.shift()();

  // Original button handlers use this shared guarded function; no second
  // unsynchronized pagination implementation may linger.
  assert(src.includes("if(a==='next'){await moveReviewPage('next');return}"));
  assert(src.includes("if(a==='prev'){await moveReviewPage('prev');return}"));
  console.log('PASS: page-local review navigation restores keyboard tile focus after paint; guards disabled/first-page, stale tab/person/frame and empty lists');
})().catch(e=>{console.error(e);process.exitCode=1;});
