'use strict';
const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');

const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=src.indexOf('function openPersonPhoto(id){');
const stop=src.indexOf('\nasync function photoPage(){',start);
assert(start>0&&stop>start,'Actual photo detour functions must be testable');
const events=[],scheduled=[],renders=[];
let scrollY=1248;
let delayedResolve=null;
const tile={
  focus:opts=>events.push(['focus',opts.preventScroll])
};
const ctx={
  tab:'person',page:7,person:321,personMode:'photos',photo:null,
  selected:new Set([99]),renderEpoch:9,
  window:{
    get scrollY(){return scrollY},
    scrollTo:(x,y)=>events.push(['scrollTo',x,y])
  },
  document:{
    querySelector:selector=>{
      events.push(['query',selector]);
      return selector==='[data-photo="488"]'?tile:null;
    }
  },
  go:t=>{
    events.push(['go',t]);
    ctx.tab=t;ctx.page=0;ctx.selected.clear();
  },
  render:async()=>{
    ctx.renderEpoch++;
    renders.push({tab:ctx.tab,page:ctx.page,person:ctx.person,mode:ctx.personMode});
    if(delayedResolve)await new Promise(resolve=>delayedResolve=resolve);
  },
  requestAnimationFrame:callback=>scheduled.push(callback)
};
vm.createContext(ctx);
vm.runInContext('let photoReturn=null;\n'+src.slice(start,stop)+
  '\nthis.enterPhoto=openPersonPhoto;this.exitPhoto=returnFromPhoto;'+
  'this.savedPhoto=()=>photoReturn;',ctx);

(async()=>{
  ctx.enterPhoto(488);
  assert.equal(ctx.tab,'photo');
  assert.equal(ctx.page,0);
  assert.equal(ctx.photo,488);
  assert.equal(ctx.selected.size,0);
  assert.equal(ctx.savedPhoto().person,321);
  assert.equal(ctx.savedPhoto().page,7);
  assert.equal(ctx.savedPhoto().scroll,1248);
  assert.equal(ctx.savedPhoto().photo,488);
  assert.equal(ctx.savedPhoto().mode,'photos');

  // Full-screen image return should leave original profile page and scroll.
  await ctx.exitPhoto();
  assert.equal(ctx.savedPhoto(),null,'One-use return context must be cleared');
  assert.equal(ctx.tab,'person');
  assert.equal(ctx.person,321);
  assert.equal(ctx.personMode,'photos');
  assert.equal(ctx.page,7);
  assert.deepEqual(renders.at(-1),{tab:'person',page:7,person:321,mode:'photos'});
  assert.equal(events.some(v=>v[0]==='scrollTo'),false,'Wait for DOM paint before restoring scroll');
  assert.equal(scheduled.length,1);
  scheduled.shift()();
  assert.deepEqual(events.slice(-3),[
    ['scrollTo',0,1248],['query','[data-photo="488"]'],['focus',true]
  ]);

  // A keyboard/photo response that returns after a new user navigation must
  // NEVER scroll/focus into the unrelated page.
  ctx.tab='person';ctx.personMode='photos';ctx.person=321;ctx.page=4;
  scrollY=845;
  ctx.enterPhoto(488);
  await ctx.exitPhoto();
  assert.equal(scheduled.length,1);
  const lengthBefore=events.length;
  ctx.tab='groups';ctx.renderEpoch++;
  scheduled.shift()();
  assert.equal(events.length,lengthBefore,'Stale animation callback leaked into new tab');

  // Same tab with another Person should not steal focus back.
  ctx.tab='person';ctx.person=321;ctx.personMode='photos';ctx.page=2;
  ctx.enterPhoto(488);
  await ctx.exitPhoto();
  const before=events.length;
  ctx.person=999;ctx.renderEpoch++;
  scheduled.shift()();
  assert.equal(events.length,before);

  // Opening from a non-profile context must not create an invalid return
  // destination; fallback follows existing go(person) semantics safely.
  ctx.tab='groups';ctx.page=9;
  ctx.enterPhoto(777);
  assert.equal(ctx.savedPhoto(),null);
  await ctx.exitPhoto();
  assert.equal(ctx.tab,'person');
  assert.equal(ctx.page,0);
  assert.equal(scheduled.length,0);

  // Browser delegation still uses these exact production handlers.
  assert(src.includes("if(ph){openPersonPhoto(ph.dataset.photo);return}"));
  assert(src.includes("if(a==='backperson'){await returnFromPhoto();return}"));
  assert(!src.includes('localStorage.setItem(DISCOVER_CURSOR_KEY,JSON.stringify(photoReturn)'),
    'Photo context must never be stored outside this browser session');
  console.log('PASS: fictional Person photo detour restores page 8/scroll/keyboard focus, guards stale returns and uses only ephemeral context');
})().catch(e=>{console.error(e);process.exitCode=1});
