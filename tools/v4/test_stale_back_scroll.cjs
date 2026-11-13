'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function part(a,b){const start=src.indexOf(a),end=src.indexOf(b,start+a.length);
 assert(start>0&&end>start,'Missing actual V4 browser '+a);return src.slice(start,end);}
const code=part('async function returnToGroups(){','const header=(title,sub)')+
 part('async function returnToPeople(){','// A render generation also guards')+
 part('async function returnFromPhoto(){','async function photoPage(){')+
 part('async function returnFromSimilarity(){','function promptPersonName(')+
 '\nthis.back={groups:returnToGroups,people:returnToPeople,photo:returnFromPhoto,similar:returnFromSimilarity};';
const waits=[],frames=[],events=[];
const ctx={
 tab:'group',page:3,person:55,group:31,personMode:'photos',photo:4,
 peopleListPage:0,peopleQuery:'',peopleCache:null,
 groupReturn:null,peopleReturn:null,photoReturn:null,
 similarityReturn:null,similarityTrail:[],renderEpoch:1,
 selected:new Set([44]),
 window:{
   currentFace:13,profileSearch:false,lastTab:'group',
   scrollTo:(x,y)=>events.push(['scroll',ctx.tab,y])
 },
 document:{querySelector:()=>({focus:()=>events.push(['focus',ctx.tab])})},
 render:()=>{
   ctx.renderEpoch++;
   return new Promise(resolve=>waits.push(resolve));
 },
 requestAnimationFrame:f=>frames.push(f),
 go:tab=>{ctx.tab=tab;ctx.renderEpoch++},
};
vm.createContext(ctx);vm.runInContext(code,ctx);
const finish=async action=>{
 assert.equal(waits.length,1);
 waits.shift()();await action;
 assert.equal(frames.length,1);
 return frames.shift();
};
(async()=>{
  // An old Groups Back finishes only AFTER a newer Person screen is shown.
  ctx.groupReturn={page:8,scroll:820};
  let action=ctx.back.groups();
  ctx.tab='person';ctx.renderEpoch++;
  let frame=await finish(action);frame();
  assert.equal(events.length,0,'Stale Groups Back scrolled newer Person');
  // Ordinary Back still restores original page position.
  ctx.groupReturn={page:4,scroll:450};action=ctx.back.groups();
  frame=await finish(action);frame();
  assert.deepEqual(events.pop(),['scroll','groups',450]);

  // People query may change without a full render while RAF is queued.
  ctx.peopleReturn={page:2,query:'alex',scroll:290};
  action=ctx.back.people();frame=await finish(action);
  ctx.peopleQuery='new query';frame();
  assert.equal(events.length,0,'Stale People search scrolled new results');
  ctx.peopleReturn={page:3,query:'alex',scroll:380};
  action=ctx.back.people();frame=await finish(action);frame();
  assert.deepEqual(events.pop(),['scroll','people',380]);

  // Photo return uses the render epoch captured BEFORE an await; stale
  // completion on the same person/page must not gain a newer generation.
  ctx.photoReturn={person:55,mode:'photos',page:5,scroll:900,photo:1};
  action=ctx.back.photo();
  ctx.renderEpoch++; // newer asynchronous render for same Person
  frame=await finish(action);frame();
  assert.equal(events.length,0,'Superseded Photo Back stole focus/scroll');

  // Deep similarity trails have their own queued scroll restore. Even if
  // another similarity appears on the same tab, a stale old face loses.
  ctx.tab='similar';ctx.similarityTrail=[{
   face:101,profileSearch:true,scroll:123
  }];
  action=ctx.back.similar();frame=await finish(action);
  ctx.window.currentFace=777;frame();
  assert.equal(events.length,0,'Superseded similarity face scrolled');
  // Outer similarity Back also never scrolls into an unrelated tab.
  ctx.similarityReturn={tab:'group',page:14,scroll:660};
  action=ctx.back.similar();
  ctx.tab='people';ctx.renderEpoch++;
  frame=await finish(action);frame();
  assert.equal(events.length,0,'Stale outer similarity Back scrolled unrelated People view');
  // Happy path: original similarity context restored once.
  ctx.similarityReturn={tab:'group',page:6,scroll:220};
  action=ctx.back.similar();frame=await finish(action);frame();
  assert.deepEqual(events.pop(),['scroll','group',220]);
  assert.equal(waits.length,0);assert.equal(frames.length,0);
  console.log('PASS: four V4 Back paths restore intended scroll when current, never scroll or focus newer tabs/search/face/page after delayed render');
})().catch(e=>{console.error(e);process.exitCode=1});
