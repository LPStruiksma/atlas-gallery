'use strict';
const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=src.indexOf('function peopleSearchMatches(people,query){');
const end=src.indexOf('\nasync function groups(epoch){',start);
const comparableStart=src.indexOf('function comparablePersonName(name){');
const comparableEnd=src.indexOf('// Creating a profile with a name already in use',comparableStart);
assert(start>=0&&end>start&&comparableStart>=0&&comparableEnd>comparableStart);
const code='const PEOPLE_PAGE_SIZE=48;\n'+src.slice(start,end)+'\n'+src.slice(comparableStart,comparableEnd);
const people=Array.from({length:2001},(_,i)=>({
  id:i+1,name:'Fictional Person '+String(i+1).padStart(4,'0'),
  faces:8,images:8,cover:null,cover_manual:false
}));
people[1889].name='Alice Fiction';
people[1890].name='Bob Fiction';
let requests=[];
let deferred=[];
let deferCovers=false;
const dom={};
for(const key of ['#people-grid','#people-search-count',
  '[data-action="peopleprev"]','[data-action="peoplenext"]'])
  dom[key]={innerHTML:'',textContent:'',disabled:false};
let focusedTile=null;
let focusRestorations=0;
const ctx={
  peopleCache:null,peopleCacheRevision:0,peopleListPage:0,peopleQuery:'',tab:'people',
  selected:new Set(),renderEpoch:8,peopleReturn:null,
  esc:s=>String(s??'').replace(/[&<>"']/g,c=>({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])),
  fmt:n=>String(n),portraitThumb:id=>'/thumb/portrait/'+id,
  header:()=>'<h1>People</h1>',
  btn:(label,action)=>'<button data-action="'+action+'">'+label+'</button>',
  // This VM isolates roster portrait hydration; actual Undo rendering is
  // exercised separately by test_roster_create_undo_visible.cjs.
  undoButton:()=>'',
  // Real pending-key recovery toolbar is tested in test_pending_person_create_recovery.cjs.
  pendingPersonCreateToolbar:()=>'', 
  $:selector=>dom[selector],
  document:{
    activeElement:{closest:selector=>selector==='[data-person]'&&focusedTile?{
      dataset:{person:String(focusedTile)}}:null},
    querySelector:selector=>({focus:()=>{assert(selector.includes(String(focusedTile)));
      focusRestorations++;}})
  },
  render:async()=>{},
  requestAnimationFrame:()=>{},
  window:{scrollTo:()=>{}},
  get:async uri=>{
    requests.push(uri);
    if(uri==='/api/people?covers=0')return people.map(p=>({...p}));
    if(uri.startsWith('/api/people/covers?ids=')){
      const ids=uri.split('ids=')[1].split(',').map(Number);
      assert(ids.length>0&&ids.length<=48,'No bulk portrait scan beyond visible tiles');
      assert(ids.every(x=>Number.isSafeInteger(x)&&x>0));
      if(deferCovers)return new Promise(resolve=>deferred.push({ids,resolve}));
      return Object.fromEntries(ids.map(id=>[id,{cover:id+20000,cover_manual:false}]));
    }
    throw Error('Unexpected synthetic network request '+uri);
  },
};
vm.createContext(ctx);
vm.runInContext(code+'\nthis.peopleView=people;this.refresh=updatePeopleResults;this.visible=peopleSearchPage;',ctx);
const flush=()=>new Promise(resolve=>setImmediate(resolve));
(async()=>{
  let html=await ctx.peopleView(8);
  assert.equal(requests[0],'/api/people?covers=0');
  assert.equal(requests.length,2,'Initial People render must fetch only metadata and one visible page');
  assert(requests[1].startsWith('/api/people/covers?ids='));
  assert.equal((html.match(/data-person="/g)||[]).length,48);
  assert(html.includes('/thumb/portrait/20001'),
    'First visible cover must be available before initial layout');
  assert(!html.includes('/thumb/portrait/21890'),
    'No portrait for distant People page should be requested');
  assert.equal(ctx.peopleCache.length,2001);
  assert.equal(ctx.peopleCache[100].cover_loaded,undefined,
    'Offscreen metadata stays unhydrated');
  assert.equal(ctx.peopleCache[0].cover_loaded,true);

  requests=[];deferCovers=true;
  ctx.peopleQuery='alice fiction';
  ctx.peopleListPage=0;
  ctx.refresh();
  assert.equal(deferred.length,1);
  assert.deepEqual(deferred[0].ids,[1890]);
  assert.match(dom['#people-grid'].innerHTML,/portrait-placeholder/,
    'Local typing remains usable before portrait lookup responds');
  ctx.peopleQuery='bob fiction';
  ctx.refresh();
  assert.equal(deferred.length,2);
  assert.deepEqual(deferred[1].ids,[1891]);
  focusedTile=1891;
  deferred[0].resolve({'1890':{cover:40001,cover_manual:true}});
  await flush();
  assert.match(dom['#people-grid'].innerHTML,/Bob Fiction/);
  assert.doesNotMatch(dom['#people-grid'].innerHTML,/40001/,
    'Late Alice response must not paint over Bob');
  deferred[1].resolve({'1891':{cover:40002,cover_manual:false}});
  await flush();
  assert.match(dom['#people-grid'].innerHTML,/\/thumb\/portrait\/40002/);
  assert.equal(focusRestorations,1,'Hydrating a focused button must restore keyboard focus');
  assert.equal(ctx.peopleCache[1889].cover,40001,'Out-of-view result may remain cached for future visit');

  // Navigating away while a portrait response is pending cannot repaint the
  // inactive People screen, or silently update a replaced roster.
  focusedTile=null;deferred=[];requests=[];
  ctx.peopleQuery='Fictional Person 1950';ctx.refresh();
  assert.equal(deferred.length,1);
  const roster=ctx.peopleCache;
  ctx.tab='person';ctx.peopleCache=null;
  deferred[0].resolve({'1950':{cover:30000,cover_manual:false}});
  await flush();
  assert.equal(ctx.peopleCache,null);
  assert.equal(roster[1949].cover_loaded,undefined);
  assert.equal(focusRestorations,1);

  // A fresh initial People metadata load can still be waiting on its
  // visible portrait GET when a reviewed identity mutation commits or loses
  // its ACK. Never render the old 48 tiles after that cache is invalidated.
  ctx.tab='people';ctx.renderEpoch=8;ctx.peopleCache=null;
  ctx.peopleCacheRevision++;ctx.peopleQuery='';ctx.peopleListPage=0;
  requests=[];deferred=[];deferCovers=true;
  const delayedInitial=ctx.peopleView(8);
  await flush(); // Roster metadata arrived; 48-only cover fetch is pending.
  assert.equal(deferred.length,1);
  assert.equal(requests.length,2);
  const obsoleteRoster=ctx.peopleCache;
  assert.equal(obsoleteRoster.length,2001);
  ctx.peopleCache=null;ctx.peopleCacheRevision++; // explicit in-flight write
  deferred[0].resolve({'1':{cover:90909,cover_manual:true}});
  html=await delayedInitial;
  assert.match(html,/People changed while loading/);
  assert.doesNotMatch(html,/data-person="1"/,
    'Late portrait response cannot render stale Person name/count tiles');
  assert.strictEqual(ctx.peopleCache,null);
  assert.equal(obsoleteRoster[0].cover_loaded,undefined,
    'Discarded metadata cache must not absorb stale cover results');

  // A subsequent explicit opening reloads fresh metadata and only the
  // next visible page of portraits, not 2001 Person images.
  requests=[];deferCovers=false;
  html=await ctx.peopleView(8);
  assert.equal(requests.length,2);
  assert.match(html,/data-person="1"/);
  assert.equal(ctx.peopleCache.length,2001);

  // Empty roster: no request for nonexistent thumbnails and safe disabled pager.
  ctx.tab='people';ctx.peopleCache=[];ctx.peopleQuery='';ctx.peopleListPage=0;requests=[];
  html=await ctx.peopleView(8);
  assert.equal(requests.length,0);
  assert.match(html,/data-action="peoplenext"[^>]*disabled/);
  assert.match(html,/data-action="peopleprev"[^>]*disabled/);
  console.log('PASS: 2001 fictional People; 48-only initial portrait lookup, instant offline search, stale query/nav and late initial-cover/write overlap suppression, focus recovery and empty-state safety');
})().catch(e=>{console.error(e);process.exitCode=1;});
