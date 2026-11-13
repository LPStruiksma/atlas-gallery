'use strict';
const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=src.indexOf('function closePicker(force=false){');
const end=src.indexOf('\nasync function returnToGroups(){',start);
assert(start>0&&end>start,'Cannot isolate real V4 picker functions');
const events=[],opener={isConnected:true,focus:opts=>events.push(['opener focus',opts.preventScroll])};
const element=()=>({hidden:false,innerHTML:'',value:'',disabled:false,textContent:'',
  addEventListener:()=>{},blur:()=>{},focus:opts=>events.push(['picker focus',opts.preventScroll])});
const elements={
  '#person-picker':element(),
  '#picker-cancel':element(),
  '#picker-create':null,
  '.overlay':element(),
  '.picker':element(),
  '#picker-heading':element(),
  '#picker-browse':element(),
  '#picker-review':element(),
  '#picker-query':element(),
  '#picker-results':element(),
  '#picker-result-count':element(),
  '#picker-review-back':element(),
  '#picker-review-submit':element()
};
const root=elements['#person-picker'];
root.hidden=true;
root.querySelector=id=>elements[id]??null;
elements['#picker-review-back'].click=()=>elements['#picker-review-back'].onclick?.();
let lateRosterResolver=null;
const ctx={
  pickerState:null,person:9,group:null,tab:'person',renderEpoch:10,
  document:{activeElement:opener},
  $:selector=>elements[selector]??null,
  esc:s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])),
  fmt:v=>String(v),
  portraitThumb:id=>'/thumb/portrait/'+id,
  toast:s=>events.push(['toast',s]),
  // Real browser has a debounce timer; this DOM-only test intentionally
  // leaves optional portrait hydration pending rather than running it.
  setTimeout:()=>1,clearTimeout:()=>{},
  get:async url=>{
    assert.equal(url,'/api/people?covers=0');
    return await new Promise(resolve=>lateRosterResolver=resolve);
  }
};
vm.createContext(ctx);
vm.runInContext(src.slice(start,end)+
  '\nthis.openPicker=choosePerson;this.closeModal=closePicker;'+
  'this.previewProfile=showPickerConfirmation;this.state=()=>pickerState;this.rankPeople=rankPickerPeople;this.repaint=renderPickerResults;',ctx);
const fiction={id:1,name:'Fictional <&>',cover:null,faces:0,images:0};
const key=(name)=>({key:name,preventDefault:()=>events.push(['prevent',name]),
  stopPropagation:()=>events.push(['stop',name])});
function cleanDOM(){
  elements['#picker-browse'].hidden=false;
  elements['#picker-review'].hidden=true;
  elements['#picker-review'].innerHTML='';
  opener.isConnected=true;
}
(async()=>{
  cleanDOM();
  await ctx.openPicker({people:[fiction],title:'Pick a person'});
  assert.equal(root.hidden,false);
  assert.equal(ctx.state().opener,opener);
  assert.match(elements['#picker-results'].innerHTML,/Fictional &lt;&amp;&gt;/,
    'Private names must remain safely escaped');
  assert.doesNotMatch(elements['#picker-results'].innerHTML,/<img[^>]*src=""/);
  let before=events.length;
  elements['.picker'].onkeydown(key('Enter'));
  assert.equal(events.length,before,'Unrelated key should not close picker');
  elements['.picker'].onkeydown(key('Escape'));
  assert.equal(root.hidden,true);
  assert.equal(ctx.state(),null);
  assert.deepEqual(events.at(-1),['opener focus',true]);

  // Escape in confirmation takes one step back without accidentally assigning
  // the candidate; a second Escape cancels the whole picker.
  cleanDOM();
  await ctx.openPicker({people:[fiction]});
  ctx.previewProfile(fiction);
  assert.equal(elements['#picker-review'].hidden,false);
  assert.match(elements['#picker-review'].innerHTML,/portrait-placeholder/);
  assert.doesNotMatch(elements['#picker-review'].innerHTML,/<img[^>]*src=""/);
  elements['.picker'].onkeydown(key('Escape'));
  assert.equal(root.hidden,false,'Escape in confirmation should return to list');
  assert.equal(elements['#picker-browse'].hidden,false);
  assert.equal(elements['#picker-review'].hidden,true);
  elements['.picker'].onkeydown(key('Escape'));
  assert.equal(root.hidden,true);
  assert.equal(ctx.state(),null);

  // A network-side assignment/merge may be in flight. Neither Escape nor the
  // programmatic cancel handler (receiving a truthy click event) may override
  // the submitting guard and mislead the user about whether it committed.
  cleanDOM();
  let finishChoose=null,commits=0;
  await ctx.openPicker({people:[fiction],onChoose:async()=>{
    await new Promise(resolve=>finishChoose=resolve);commits++;
  }});
  ctx.previewProfile(fiction);
  const submit=elements['#picker-review-submit'];
  const saving=submit.onclick();
  assert.equal(ctx.state().submitting,true);
  const stillOpen=root.hidden;
  elements['.picker'].onkeydown(key('Escape'));
  elements['#picker-cancel'].onclick({type:'click'});
  ctx.closeModal();
  assert.equal(root.hidden,stillOpen,'In-flight confirmation must remain open');
  assert.equal(ctx.state().submitting,true);
  finishChoose();await saving;
  assert.equal(commits,1);
  assert.equal(root.hidden,true);
  assert.equal(ctx.state(),null);

  // A slow People roster must never pop up over a different Person or tab.
  root.hidden=true;cleanDOM();
  const stale=ctx.openPicker({title:'Late fetch'});
  assert(lateRosterResolver,'Missing pending synthetic roster');
  ctx.person=20;ctx.renderEpoch++;
  lateRosterResolver([fiction]);await stale;
  assert.equal(root.hidden,true);
  assert.equal(ctx.state(),null,'Stale picker took over a new Person review');

  // Two overlapping roster requests on the SAME screen must not race.
  // A late old dialog may never overwrite the later intended action/label.
  ctx.person=9;ctx.renderEpoch=22;cleanDOM();
  const oldPicker=ctx.openPicker({title:'Earlier chooser'});
  const finishOld=lateRosterResolver;
  const freshPicker=ctx.openPicker({title:'Latest chooser'});
  const finishFresh=lateRosterResolver;
  assert.notEqual(finishOld,finishFresh);
  finishFresh([fiction]);await freshPicker;
  assert.match(root.innerHTML,/Latest chooser/);
  assert.equal(ctx.state().options.title,'Latest chooser');
  finishOld([fiction]);await oldPicker;
  assert.equal(ctx.state().options.title,'Latest chooser',
    'Late older chooser overwrote the intended confirmation');
  assert.match(root.innerHTML,/Latest chooser/);
  ctx.closeModal();

  // A pending roster request also must not reopen after user dismissed
  // another already-open picker on the same tab without rerendering.
  cleanDOM();await ctx.openPicker({people:[fiction],title:'Visible chooser'});
  const afterDismiss=ctx.openPicker({title:'Pending chooser'});
  const finishPending=lateRosterResolver;
  ctx.closeModal();
  finishPending([fiction]);await afterDismiss;
  assert.equal(root.hidden,true);
  assert.equal(ctx.state(),null,'Cancelled chooser unexpectedly reopened');

  // New Person / explicit homonym uses the separate-create action. A
  // network rejection after the picker closes must be handled and reported,
  // not become an unhandled Promise rejection with ambiguous outcome.
  const createButton=elements['#picker-create']=element();
  cleanDOM();
  await ctx.openPicker({people:[fiction],allowNew:true,onNew:async()=>{
    throw Error('Synthetic People write rejected');
  }});
  await createButton.onclick();
  assert.equal(root.hidden,true);
  assert.equal(ctx.state(),null);
  assert(events.some(e=>e[0]==='toast'&&
    /Synthetic People write rejected.*check People before retrying/.test(e[1])));

  // Successful create still invokes callback exactly once and dismisses.
  cleanDOM();let separateCalls=0;
  await ctx.openPicker({people:[fiction],allowNew:true,onNew:async()=>{
    separateCalls++;
  }});
  await createButton.onclick();
  assert.equal(separateCalls,1);
  assert.equal(root.hidden,true);
  elements['#picker-create']=null;

  // For a 2k+ People roster, exact/prefix matches must remain in the top 100,
  // independent of original record order; the duplicate guard's NFKC and
  // collapsed-whitespace semantics are also used by the picker.
  const largeRoster=Array.from({length:135},(_,i)=>({
    id:i+200,name:'Some Alex '+i,cover:null,images:0,faces:0
  })).concat([
    {id:13,name:'Alex Baker',cover:null,images:0,faces:0},
    {id:11,name:'Ａｌｅｘ',cover:null,images:0,faces:0},
    {id:12,name:'Alex',cover:null,images:0,faces:0},
    {id:14,name:'Alex  Beta',cover:null,images:0,faces:0}
  ]);
  assert.deepEqual(Array.from(ctx.rankPeople(largeRoster,'  ALEX  ').slice(0,4),p=>p.id),
    [11,12,13,14],'Exact same names then prefixes outrank substring matches');
  assert.deepEqual(Array.from(ctx.rankPeople(largeRoster,'alex beta'),p=>p.id),[14],
    'Picker must use the same collapsed-whitespace matching as duplicate guard');
  cleanDOM();
  await ctx.openPicker({people:largeRoster,title:'Large named roster'});
  elements['#picker-query'].value='  ＡＬＥＸ  ';
  ctx.repaint();
  const optionIds=Array.from(elements['#picker-results'].innerHTML.matchAll(
    /data-picker-person="(\d+)"/g),m=>Number(m[1]));
  assert.deepEqual(optionIds.slice(0,4),[11,12,13,14]);
  assert.equal(optionIds.length,100,'Picker must retain bounded first-page DOM');
  assert.match(elements['#picker-result-count'].textContent,/139 matches · first 100 shown/);
  ctx.closeModal();
  elements['#picker-query'].value='';

  // The transport response can fail AFTER the server accepted an assignment.
  // Never claim no writes, or let rapid retry repeat the ambiguous request.
  cleanDOM();
  let ambiguousWrites=0;
  await ctx.openPicker({people:[fiction],onChoose:async()=>{
    ambiguousWrites++;throw Error('Synthetic acknowledgement lost after commit');
  }});
  ctx.previewProfile(fiction);
  const uncertainSubmit=elements['#picker-review-submit'];
  await uncertainSubmit.onclick();
  assert.equal(ambiguousWrites,1);
  assert.equal(root.hidden,false,'Recovery choices must remain visible');
  assert.equal(ctx.state().uncertain,true);
  assert.equal(uncertainSubmit.disabled,true,'An unknown outcome cannot be auto-retried');
  assert.equal(elements['#picker-review-back'].disabled,false);
  assert.match(uncertainSubmit.textContent,/Check People before retrying/);
  assert(events.some(e=>e[0]==='toast'&&
    /Could not verify completion.*check People before retrying/.test(e[1])));
  await uncertainSubmit.onclick();
  assert.equal(ambiguousWrites,1,'Even a stale programmatic click must not resubmit');
  elements['#picker-review-back'].onclick();
  ctx.previewProfile(fiction);
  assert.equal(elements['#picker-review'].hidden,true,
    'A new confirmation cannot open until the uncertain picker is dismissed');
  ctx.closeModal();

  // A removed opener must not receive focus after newer UI reconstruction.
  ctx.person=9;ctx.renderEpoch=22;cleanDOM();
  await ctx.openPicker({people:[fiction]});
  opener.isConnected=false;
  before=events.filter(x=>x[0]==='opener focus').length;
  ctx.closeModal();
  assert.equal(events.filter(x=>x[0]==='opener focus').length,before);
  console.log('PASS: picker Escape, focus, in-flight write guard, stale Person navigation, latest same-screen chooser wins and dismissal invalidates pending requests');
})().catch(e=>{console.error(e);process.exitCode=1});
