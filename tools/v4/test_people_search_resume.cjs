'use strict';
const fs=require('fs');
const vm=require('vm');
const assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const begin=source.indexOf('function peopleSearchMatches(people,query){');
const end=source.indexOf('\nasync function groups(epoch){',begin);
const normalizeStart=source.indexOf('function comparablePersonName(name){');
const normalizeEnd=source.indexOf('// Creating a profile with a name already in use',normalizeStart);
assert(begin>0&&end>begin&&normalizeStart>0&&normalizeEnd>normalizeStart);
const code='const PEOPLE_PAGE_SIZE=48;\n'+source.slice(begin,end)+'\n'+source.slice(normalizeStart,normalizeEnd);
const synthetic=Array.from({length:2_001},(_,i)=>({
  id:i+1,name:'Fictional Person '+String(i+1).padStart(4,'0'),
  images:i%100,faces:1+i%3,cover:i+10,cover_loaded:true}));
synthetic[1960].name='Alice';
synthetic[1961].name='Alice Example';
synthetic[1962].name='Other Alice';
synthetic[1963].name='<Unsafe & Name>';
const dom={};
for(const id of ['#people-grid','#people-search-count','[data-action="peopleprev"]',
  '[data-action="peoplenext"]'])dom[id]={innerHTML:'',textContent:'',disabled:false};
let requests=[];
let didRender=0;
let scrolled=[];
let queued=[];
const ctx={
  peopleCache:synthetic,peopleQuery:'',peopleListPage:0,peopleReturn:null,tab:'people',renderEpoch:1,
  selected:new Set(),$ : selector=>dom[selector],
  esc:input=>String(input).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])),
  fmt:v=>String(v),portraitThumb:id=>'/portrait/'+id,
  header:(h)=>h,btn:(title,action)=>'<button data-action="'+action+'">'+title+'</button>',
  // Single-use roster Undo is covered by its dedicated real-renderer fixture.
  undoButton:()=>'',
  // Real pending-key recovery toolbar is tested in test_pending_person_create_recovery.cjs.
  pendingPersonCreateToolbar:()=>'',
  get:async path=>{requests.push(path);return synthetic;},
  render:async()=>{didRender+=1;ctx.renderEpoch++},
  requestAnimationFrame:f=>queued.push(f),
  window:{scrollTo:(x,y)=>scrolled.push(y)}
};
vm.createContext(ctx);
vm.runInContext(code+'\nthis.runPage=peopleSearchPage;this.runSearch=people;this.runUpdate=updatePeopleResults;this.goBack=returnToPeople;',ctx);
(async()=>{
  let pages=[];
  for(let n=0;n<42;n++)pages.push(ctx.runPage(synthetic,'',n));
  assert.equal(pages[0].count,2001);
  assert.equal(pages.at(-1).matches.length,33);
  assert.equal(pages.flatMap(p=>p.matches).length,2001);
  assert.equal(new Set(pages.flatMap(p=>p.matches.map(item=>item.id))).size,2001);
  assert.ok(pages.every(p=>p.matches.length<=48),'No giant DOM-page permitted');
  const matches=ctx.runPage(synthetic,'alice',0);
  assert.deepEqual(Array.from(matches.matches.map(p=>p.name)),['Alice','Alice Example','Other Alice']);
  assert.equal(ctx.runPage(synthetic,'ＡＬＩＣＥ',0).count,3,'NFKC name query');
  assert.equal(ctx.runPage(synthetic,'not a person',0).count,0);
  assert.equal(ctx.runPage(synthetic,'',1000).page,41,'Out-of-range page clamped');
  let html=await ctx.runSearch();
  assert.equal(requests.length,0,'Cached people metadata must not re-fetch');
  assert.equal((html.match(/data-person="/g)||[]).length,48,'Only render visible page');
  assert.ok(html.includes('people-query'));
  assert.match(html,/data-action="peopleprev"[^>]*disabled/,'Initial page 1 Previous must be disabled before any search');
  assert.match(html,/data-action="peoplenext"[^>]*aria-controls="people-grid"/,'Initial large roster Next must be keyboard-operable');
  assert.doesNotMatch(html,/data-action="peoplenext"[^>]*disabled/,'Initial first page has more results');
  ctx.peopleListPage=41;
  html=await ctx.runSearch();
  assert.doesNotMatch(html,/data-action="peopleprev"[^>]*disabled/,'Last page still allows Previous');
  assert.match(html,/data-action="peoplenext"[^>]*disabled/,'Last page must disable Next immediately');
  ctx.peopleListPage=0;ctx.peopleCache=[];
  html=await ctx.runSearch();
  assert.match(html,/data-action="peopleprev"[^>]*disabled/,'Empty roster has no previous page');
  assert.match(html,/data-action="peoplenext"[^>]*disabled/,'Empty roster has no next page');
  ctx.peopleCache=synthetic;
  assert.ok(!html.includes('Unsafe & Name'),'Source names never inserted without escaping');
  ctx.peopleQuery='alice';ctx.peopleListPage=0;ctx.runUpdate();
  assert.equal((dom['#people-grid'].innerHTML.match(/data-person="/g)||[]).length,3);
  assert.equal(dom['#people-search-count'].textContent,'3 matching profiles · page 1/1 · 3 visible');
  assert.equal(dom['[data-action="peoplenext"]'].disabled,true);
  ctx.peopleQuery='<Unsafe';ctx.runUpdate();
  assert.ok(dom['#people-grid'].innerHTML.includes('&lt;Unsafe &amp; Name&gt;'),'XSS escaping');
  ctx.peopleReturn={query:'alice',page:0,scroll:277};
  await ctx.goBack();
  assert.equal(ctx.peopleReturn,null);
  assert.equal(ctx.peopleQuery,'alice');
  assert.equal(didRender,1);
  assert.equal(queued.length,1);
  queued.shift()();
  assert.deepEqual(scrolled,[277]);
  assert.equal(ctx.peopleCache,null,'Back must fetch fresh profile counts/portraits');
  console.log('PASS: 2001 synthetic People, 42 bounded pages, exact-first search, NFKC, escaping, cached typing, preserved return');
})().catch(e=>{console.error(e);process.exitCode=1});
