'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function fragment(first,last){
  const a=source.indexOf(first),b=source.indexOf(last,a);
  assert(a>=0&&b>a,'Missing actual V4 browser code '+first);
  return source.slice(a,b);
}
let pending=[],drawn=[];
const fakeView={set innerHTML(x){drawn.push(x);},get innerHTML(){return drawn.at(-1)||'';}};
const ctx={
  tab:'groups',page:7,person:11,group:44,personMode:'photos',
  groupOrder:'largest',groupShuffleSeed:9,groupPendingOnly:true,groupResumePage:6,
  lastUndoToken:null,peopleReturn:null,selected:new Set(),
  saveDiscoverCursor:()=>{ctx.saved=(ctx.saved||0)+1},
  document:{querySelectorAll:()=>[]},$:()=>fakeView,
  get:url=>new Promise(resolve=>pending.push({url,resolve})),
  home:async()=>'<div>Home</div>',
  settings:async()=>'<div>Settings</div>',
  photoPage:async()=>'<div>Photo</div>',
  matchesPage:async()=>'<div>Lookalikes</div>',
  fmt:n=>String(n),esc:s=>String(s),photoThumb:id=>'/photo/'+id,
  faceThumb:id=>'/face/'+id,portraitThumb:id=>'/portrait/'+id,
  header:name=>'<h1>'+name+'</h1>',
  btn:(label,action)=>'<button data-action="'+action+'">'+label+'</button>',
  faceTile:f=>'<span data-face="'+f.id+'"></span>',
  photoTile:p=>'<span data-photo="'+p.id+'"></span>',
  peopleTile:p=>'<span data-person="'+p.id+'"></span>',
  peopleSearchPage:(list,query,page)=>({matches:list.slice(0,48),count:list.length,page,pages:1}),
  selectionToolbar:()=>'',undoButton:()=>'',
  // Pending-key recovery has independent real-browser tests.
  pendingPersonCreateToolbar:()=>''
};
vm.createContext(ctx);
vm.runInContext(
  "let peopleCache=null,peopleCacheRevision=0,peopleQuery='',peopleListPage=0,unsortedPageBounds=[null];\n"+
  fragment('async function fillVisiblePeopleCovers(profiles,epoch){','\nasync function returnToPeople(){')+
  '\n'+fragment('function reviewRequestCurrent(', '\nasync function settings(){')+
  '\n'+fragment('let renderEpoch=0;', '\nfunction go(t){')+
  '\nthis.draw=render;this.readPeopleCache=()=>peopleCache;',
ctx);
function one(part){
  const choices=pending.filter(p=>p.url.includes(part));
  assert.equal(choices.length,1,'Expected one outstanding request '+part+': '+pending.map(p=>p.url));
  const value=choices[0];pending.splice(pending.indexOf(value),1);return value;
}
function first(part){
  const match=pending.find(item=>item.url.includes(part));
  assert(match,'No queued request: '+part);
  pending.splice(pending.indexOf(match),1);
  return match;
}
function resolve(part,data){one(part).resolve(data)}
function hasNoMutation(page,groupCursor,saved){
  assert.equal(ctx.page,page,'Stale HTTP response changed active page');
  assert.equal(ctx.groupResumePage,groupCursor,'Stale response changed saved Discover page');
  assert.equal(ctx.saved||0,saved,'Stale response persisted Discover cursor');
}
const batch=(items,has_more=false)=>({items,has_more});
const tick=()=>new Promise(resolve=>setImmediate(resolve));
(async()=>{
  // Groups count was started on page 7; user jumps to a named Person at page 4.
  // The late zero-group count must not force the named Person onto page zero.
  const oldGroups=ctx.draw();
  assert(pending.some(x=>x.url==='/api/groups/total?pending_only=1'));
  ctx.tab='person';ctx.page=4;
  const freshPerson=ctx.draw();
  assert(pending.some(x=>x.url==='/api/person/summary?id=11'));
  resolve('/api/groups/total',{count:0});
  await oldGroups;
  hasNoMutation(4,6,0);
  resolve('/api/person/summary?id=11',{id:11,name:'Fiction',faces:20,images:20});
  resolve('/api/person?id=11&page=4',batch([{id:401}]));
  await freshPerson;
  assert.match(fakeView.innerHTML,/Fiction/);
  assert.match(fakeView.innerHTML,/data-photo="401"/);

  // A late EMPTY named-person page cannot rewind the new Unsorted inbox page.
  ctx.tab='person';ctx.page=9;ctx.personMode='faces';
  const oldPerson=ctx.draw();
  ctx.tab='untagged';ctx.page=3;
  const freshUnsorted=ctx.draw();
  resolve('/api/person/summary?id=11',{id:11,name:'Fiction',faces:0,images:0});
  resolve('/api/person/faces?id=11&page=9',batch([]));
  await oldPerson;
  hasNoMutation(3,6,0);
  resolve('/api/untagged?page=3',batch([{id:302}]));
  await freshUnsorted;
  assert.match(fakeView.innerHTML,/data-face="302"/);

  // Historical group details finish after navigating to the Discover queue.
  ctx.tab='group';ctx.group=44;ctx.page=3;
  const staleGroupDetails=ctx.draw();
  ctx.tab='groups';ctx.page=5;
  const liveGroupList=ctx.draw();
  resolve('/api/group?id=44&page=3',batch([]));
  resolve('/api/group/candidates?id=44',[]);
  await staleGroupDetails;
  hasNoMutation(5,6,0);
  resolve('/api/groups/total',{count:400});
  await tick(); // newly started page 5 request after guarded count.
  resolve('/api/groups?page=5',batch([{id:600,representative:600,count:1,remaining_count:1}]));
  await liveGroupList;
  assert.equal(ctx.page,5);
  assert.equal(ctx.groupResumePage,5);
  assert.equal(ctx.saved,1);

  // Two requests to SAME Person tab but different pages: stale page must
  // neither reset navigation nor overwrite newer tiles.
  ctx.tab='person';ctx.page=6;ctx.personMode='suggested';
  const oldMode=ctx.draw();
  ctx.page=7;
  const currentMode=ctx.draw();
  assert.equal(pending.filter(x=>x.url.includes('/api/person/summary')).length,2);
  const summary={id:11,name:'Fiction',faces:40,images:40};
  resolve('/api/person/suggested?id=11&page=6',batch([]));
  first('/api/person/summary').resolve(summary);
  await oldMode;
  hasNoMutation(7,5,1);
  resolve('/api/person/suggested?id=11&page=7',batch([{id:707,person_id:null}]));
  first('/api/person/summary').resolve(summary);
  await currentMode;
  assert.match(fakeView.innerHTML,/data-face="707"/);
  assert.equal(ctx.page,7);

  // A no-longer-active People roster fetch must not enter the metadata cache.
  ctx.tab='people';ctx.page=0;
  const staleRoster=ctx.draw();
  assert(pending.some(x=>x.url==='/api/people?covers=0'));
  ctx.tab='home';
  const currentHome=ctx.draw();
  resolve('/api/people?covers=0',[{id:999,name:'Stale'}]);
  await Promise.all([staleRoster,currentHome]);
  assert.equal(ctx.readPeopleCache(),null,'Old roster polluted later People list');
  ctx.tab='people';
  const freshRoster=ctx.draw();
  assert(pending.some(x=>x.url==='/api/people?covers=0'),'Re-entry must refetch roster');
  resolve('/api/people?covers=0',[{id:12,name:'Fresh'}]);
  await tick();
  assert(pending.some(x=>x.url==='/api/people/covers?ids=12'),
    'Fresh People roster should resolve only the visible portrait');
  resolve('/api/people/covers?ids=12',{'12':{cover:112,cover_manual:false}});
  await freshRoster;
  assert.equal(ctx.readPeopleCache()[0].id,12);
  assert.match(fakeView.innerHTML,/data-person="12"/);
  assert.equal(pending.length,0);

  console.log('PASS: stale count, group detail, Person mode/page, and roster responses cannot rewind browser state or persist cursors; fresh renders remain intact');
})().catch(e=>{console.error(e);process.exitCode=1});
