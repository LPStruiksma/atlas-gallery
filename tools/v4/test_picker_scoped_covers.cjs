'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const slice=(a,b)=>{
  const start=source.indexOf(a),end=source.indexOf(b,start);
  assert(start>=0&&end>start,'Missing browser function '+a);
  return source.slice(start,end);
};
const code=slice('function closePicker(force=false){','// A row tap only PREVIEWS')+
  '\n'+slice('async function choosePerson(options){','\nasync function returnToGroups(){');
const people=Array.from({length:2001},(_,i)=>({
  id:i+1,name:'Fictional Person '+String(i+1).padStart(4,'0'),
  images:8,faces:11,cover:null
}));
people[1500].name='Alice Candidate';
people[1501].name='Bob Candidate';
let apiCalls=[],pending=[],timers=[],previews=[],focusRestored=0,optionFocus=0;
const elems={};
for(const sel of ['#picker-query','#picker-results','#picker-result-count','#picker-browse',
  '#picker-review','#picker-cancel','#picker-create','#picker-review-back','#picker-heading',
  '.picker','.overlay'])elems[sel]={innerHTML:'',textContent:'',value:'',hidden:false,
    scrollTop:47,addEventListener:()=>{},focus:()=>{optionFocus++},
    classList:{contains:()=>false},blur:()=>{}};
elems['#picker-review'].hidden=true;
const root={hidden:true,innerHTML:'',querySelector:s=>elems[s]||null};
const opener={isConnected:true,focus:()=>{focusRestored++}};
let focusedPerson=1502,tab='group',group=7,person=null,renderEpoch=8;
const ctx={
  pickerState:null,tab,group,person,renderEpoch,
  $:sel=>sel==='#person-picker'?root:elems[sel],
  document:{activeElement:opener},
  fmt:n=>String(n),esc:s=>String(s??'').replace(/[&<>"']/g,k=>({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[k])),
  portraitThumb:id=>'/thumb/portrait/'+id,
  toast:msg=>{throw Error('Unexpected portrait lookup failure: '+msg)},
  showPickerConfirmation:p=>previews.push({id:p.id,cover:p.cover}),
  setTimeout:(fn,ms)=>{assert.equal(ms,140);const token={fn,active:true};timers.push(token);return token},
  clearTimeout:token=>{token.active=false},
  get:async uri=>{
    apiCalls.push(uri);
    if(uri==='/api/people?covers=0')return people.map(p=>({...p}));
    if(uri.startsWith('/api/people/covers?ids=')){
      const ids=uri.split('ids=')[1].split(',').map(Number);
      assert(ids.length>=1&&ids.length<=48);
      return new Promise(resolve=>pending.push({ids,resolve}));
    }
    throw Error('Unexpected API request '+uri);
  },
  window:{},console
};
ctx.document.activeElement={
  ...opener,closest:selector=>selector==='[data-picker-person]'?
    {dataset:{pickerPerson:String(focusedPerson)}}:null
};
elems['#picker-results'].querySelector=selector=>{
  assert(selector.includes(String(focusedPerson)));
  return {focus:()=>{optionFocus++}};
};
vm.createContext(ctx);
vm.runInContext(code+'\nthis.pick=choosePerson;this.show=renderPickerResults;this.close=closePicker;',ctx);
const tick=()=>new Promise(resolve=>setImmediate(resolve));
function fireNewestTimer(){
  const timer=timers.at(-1);
  assert(timer.active,'Expected active debounce timer');
  timer.active=false;timer.fn();
}
function resolvePending(idsPredicate,score=70000){
  const ix=pending.findIndex(x=>idsPredicate(x.ids));
  assert(ix>=0,'Missing expected batch '+pending.map(x=>x.ids.slice(0,4)));
  const req=pending.splice(ix,1)[0];
  req.resolve(Object.fromEntries(req.ids.map(id=>[id,{cover:id+score,cover_manual:false}])));
}
(async()=>{
  await ctx.pick({title:'Choose one existing person'});
  assert.equal(apiCalls[0],'/api/people?covers=0');
  assert.equal(apiCalls.length,1,'Opening picker must not rescore every face');
  assert.equal(root.hidden,false);
  assert.equal(ctx.pickerState.people.length,2001);
  assert.match(elems['#picker-results'].innerHTML,/Fictional Person 0001/);
  assert.equal(timers.length,1,'One debounced round after opening picker');

  fireNewestTimer();
  assert.equal(pending.length,3,'100 visible rows require just three bounded batches');
  assert.deepEqual(Array.from(pending.map(x=>x.ids.length)),[48,48,4]);
  assert.equal(new Set(pending.flatMap(x=>x.ids)).size,100);
  assert(!pending.flatMap(x=>x.ids).includes(1502),'No offscreen candidate portrait rescore');

  elems['#picker-query'].value='alice candidate';
  ctx.show();
  assert.equal(timers.at(-2).active,false,'Old keyboard timer cancelled');
  assert.match(elems['#picker-results'].innerHTML,/Alice Candidate/);
  fireNewestTimer();
  assert.equal(pending.length,4);
  assert.deepEqual(pending.at(-1).ids,[1501]);

  // Old zero-query portraits may populate data but must not redraw newer
  // Alice search results or steal focus.
  while(pending.some(x=>x.ids[0]<=100))resolvePending(x=>x[0]<=100);
  await tick();
  assert.match(elems['#picker-results'].innerHTML,/Alice Candidate/);
  assert.doesNotMatch(elems['#picker-results'].innerHTML,/Fictional Person 0001/);

  focusedPerson=1501;
  resolvePending(x=>x[0]===1501);
  await tick();
  assert.match(elems['#picker-results'].innerHTML,/\/thumb\/portrait\/71501/);
  assert(optionFocus>=1,'Keyboard-selected option focus restored after portrait paint');

  // A fast tap on a row whose portrait is still outstanding requests that
  // ONE face's portrait and only then opens the deliberate confirm step.
  elems['#picker-query'].value='bob candidate';
  ctx.show();
  const target={dataset:{pickerPerson:'1502'}};
  const open=elems['#picker-results'].onclick({target:{closest:()=>target}});
  await tick();
  assert.equal(previews.length,0,'No premature unreviewed person confirmation');
  assert(pending.some(x=>x.ids.length===1&&x.ids[0]===1502));
  resolvePending(x=>x.length===1&&x[0]===1502);
  await open;
  assert.deepEqual(previews,[{id:1502,cover:71502}]);

  // When an older tapped portrait is slow, typing another name cancels that
  // pending destination. No stale confirmation may appear over newer results.
  elems['#picker-query'].value='Fictional Person 1503';
  ctx.show();
  const staleTap=elems['#picker-results'].onclick({target:{
    closest:()=>({dataset:{pickerPerson:'1503'}})}});
  await tick();
  assert(pending.some(x=>x.ids.length===1&&x.ids[0]===1503));
  elems['#picker-query'].value='alice candidate';
  ctx.show();
  resolvePending(x=>x.length===1&&x[0]===1503);
  await staleTap;
  assert.deepEqual(previews,[{id:1502,cover:71502}],
    'A changed query must cancel a pending choice, not open stale confirmation');

  // The next genuine tap still resolves its own one-Person portrait normally.
  elems['#picker-query'].value='Fictional Person 1504';
  ctx.show();
  const freshTap=elems['#picker-results'].onclick({target:{
    closest:()=>({dataset:{pickerPerson:'1504'}})}});
  await tick();
  resolvePending(x=>x.length===1&&x[0]===1504);
  await freshTap;
  assert.deepEqual(previews,[{id:1502,cover:71502},{id:1504,cover:71504}]);
  ctx.close();
  assert.equal(root.hidden,true);
  assert.equal(ctx.pickerState,null);
  assert.equal(focusRestored,1);

  // A merge-restore caller can pass its own archive rows and must not
  // accidentally fetch or filter active People from the main roster.
  apiCalls=[];timers=[];
  elems['#picker-query'].value=''; // New dialog reparses input from HTML; mock must reset it.
  await ctx.pick({title:'Restore archived profile',
    people:[{id:7777,name:'Archived Profile',cover:222,images:1,faces:1}]});
  assert.equal(apiCalls.length,0);
  assert.equal(timers.length,0);
  assert.match(elems['#picker-results'].innerHTML,/Archived Profile/);
  ctx.close();
  assert.equal(focusRestored,2);
  console.log('PASS: 2001 fictional People; picker metadata-only, 3 capped portrait batches, input debounce, stale search suppression, focus retention, single-profile confirmation and archive-source isolation');
})().catch(e=>{console.error(e);process.exitCode=1});
