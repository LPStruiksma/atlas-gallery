'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function part(a,b){
  const i=source.indexOf(a),j=source.indexOf(b,i+a.length);
  assert(i>=0&&j>i,'Expected actual browser function '+a);
  return source.slice(i,j);
}
const code=part('function reviewWriteContext(){','// A Person\'s aesthetic cover choice')+
  part('async function repaintReviewKeepingPosition(){','async function finishDirectReview(')+
  '\nthis.repaint=repaintReviewKeepingPosition;';
const callbacks=[],scrolls=[],focuses=[],lookups=[];
let mode='accept';
const ctx={
  tab:'person',page:3,person:17,group:null,personMode:'suggested',renderEpoch:9,
  window:{scrollY:674,scrollTo:(x,y)=>{assert.equal(x,0);scrolls.push(y);ctx.window.scrollY=y}},
  document:{
    activeElement:{dataset:{action:'accept'}},
    querySelector:selector=>{
      lookups.push(selector);
      if(selector==='#view [data-action="undomanual"]')return null;
      return {focus:opts=>{assert.equal(opts.preventScroll,true);focuses.push(selector)}};
    }
  },
  requestAnimationFrame:callback=>callbacks.push(callback),
  render:async()=>{ctx.renderEpoch++;ctx.window.scrollY=0}
};
vm.createContext(ctx);vm.runInContext(code,ctx);
(async()=>{
  await ctx.repaint();
  assert.equal(callbacks.length,1);
  assert.equal(scrolls.length,0,'Scroll must wait for the finished render');
  callbacks.shift()();
  assert.deepEqual(scrolls,[674]);
  assert.deepEqual(focuses,['#view [data-action="accept"]']);

  // A completed Undo removes its own button. Move to a safe page-local
  // selection action rather than focusing a detached element.
  ctx.window.scrollY=990;
  ctx.document.activeElement={dataset:{action:'undomanual'}};
  await ctx.repaint();callbacks.shift()();
  assert.equal(scrolls.at(-1),990);
  assert.deepEqual(lookups.slice(-2),[
    '#view [data-action="undomanual"]','#view [data-action="selectpage"]']);
  assert.equal(focuses.at(-1),'#view [data-action="selectpage"]');

  // A face's Use-as-portrait button should retain focus if its face is still
  // visible, even though selecting it repaints the whole current Person grid.
  ctx.window.scrollY=1200;
  ctx.document.activeElement={dataset:{coverFace:'71'}};
  await ctx.repaint();callbacks.shift()();
  assert.equal(focuses.at(-1),'#view [data-cover-face="71"]');
  assert.equal(scrolls.at(-1),1200);

  // A previously queued animation frame must never steal scroll or focus
  // from the Person/group page opened immediately after the POST completed.
  ctx.window.scrollY=343;
  ctx.document.activeElement={dataset:{action:'reject'}};
  await ctx.repaint();
  const beforeScroll=scrolls.length,beforeFocus=focuses.length;
  ctx.person=18;ctx.renderEpoch++;ctx.window.scrollY=7;
  callbacks.shift()();
  assert.equal(scrolls.length,beforeScroll);
  assert.equal(focuses.length,beforeFocus);
  assert.equal(ctx.window.scrollY,7,'Late callback rewound new Person review');
  ctx.person=17;ctx.renderEpoch++;

  // Even if the view has identical Person/page fields, a newer render
  // generation suppresses a stale focus restoration.
  ctx.window.scrollY=850;
  await ctx.repaint();
  ctx.renderEpoch++;ctx.window.scrollY=32;
  callbacks.shift()();
  assert.equal(ctx.window.scrollY,32);

  // Keyboard is optional: a normal touch review should restore scroll
  // without force-focusing another unrelated button.
  ctx.window.scrollY=487;
  ctx.document.activeElement={dataset:{}};
  const focusCount=focuses.length;
  await ctx.repaint();callbacks.shift()();
  assert.equal(scrolls.at(-1),487);
  assert.equal(focuses.length,focusCount);

  // Invalid or missing scroll values cannot produce bogus scrollTo calls.
  ctx.window.scrollY=NaN;
  ctx.document.activeElement={dataset:{action:'accept'}};
  const oldN=scrolls.length;
  await ctx.repaint();
  assert.equal(callbacks.length,0);
  assert.equal(scrolls.length,oldN);

  assert.match(source,/await repaintReviewKeepingPosition\(\);/);
  assert.match(source,/Review action undone.*try\{await repaintReviewKeepingPosition\(\)\}catch/,
    'Acknowledged Undo must still repaint locally and classify render failures');
  console.log('PASS: V4 real repaint restores deep-page scroll/action/portrait focus after writes and Undo, never steals newer navigation or persists identifiers');
})().catch(error=>{console.error(error);process.exitCode=1});
