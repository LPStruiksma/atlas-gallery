'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=src.indexOf('function cyclePickerTab(e,root){');
const end=src.indexOf('\nasync function returnToGroups(){',start);
assert(start>0&&end>start,'Test must execute the real picker keyboard handler');
const events=[],document={activeElement:null};
function node(name,owner=null,tabindex=null){
  return {name,disabled:false,hidden:false,innerHTML:'',textContent:'',value:'',
    dataset:{},scrollTop:0,isConnected:true,
    getAttribute:k=>k==='tabindex'?tabindex:null,
    closest:selector=>selector.includes('[hidden]')&&owner?.hidden?owner:null,
    focus:opts=>{assert.equal(opts.preventScroll,true);document.activeElement=nodes[name];events.push(['focus',name]);},
    addEventListener:()=>{},blur:()=>{},click(){this.onclick?.();},classList:{contains:()=>false}};
}
const nodes={};
const browse=nodes.browse=node('browse');
const review=nodes.review=node('review');review.hidden=true;
for(const [name,owner,tabindex] of [
  ['heading',browse,'-1'],['query',browse,null],['option',browse,null],
  ['cancel',browse,null],['create',browse,null],['reviewHeading',review,'-1'],
  ['back',review,null],['submit',review,null],['picker',null,null],
  ['overlay',null,null],['results',browse,null],['count',browse,null]
])nodes[name]=node(name,owner,tabindex);
const order=['heading','query','option','cancel','create','reviewHeading','back','submit'];
nodes.picker.querySelectorAll=()=>order.map(name=>nodes[name]);
const selectors={
  '#picker-heading':'heading','#picker-query':'query','#picker-results':'results',
  '#picker-result-count':'count','#picker-cancel':'cancel','#picker-create':'create',
  '#picker-review-heading':'reviewHeading','#picker-review-back':'back',
  '#picker-review-submit':'submit','#picker-browse':'browse','#picker-review':'review',
  '.picker':'picker','.overlay':'overlay'
};
const root={hidden:true,innerHTML:'',querySelector:sel=>{
  if(sel.startsWith('#picker-results [data-picker-person='))return nodes.option;
  return nodes[selectors[sel]]||null;
}};
const opener={isConnected:true,focus:()=>{document.activeElement=opener;events.push(['opener'])}};
document.activeElement=opener;
let writes=0;
const ctx={
  document,pickerState:null,tab:'group',person:null,group:2,renderEpoch:3,
  $:sel=>sel==='#person-picker'?root:root.querySelector(sel),
  esc:s=>String(s??''),fmt:n=>String(n),portraitThumb:id=>'/portrait/'+id,
  toast:msg=>{throw Error(msg)},get:()=>{throw Error('Unexpected network request')}
};
vm.createContext(ctx);
vm.runInContext(src.slice(start,end)+'\nthis.pick=choosePerson;this.preview=showPickerConfirmation;this.dismiss=closePicker;',ctx);
const fictional={id:9,name:'Fixture Person',cover:null,images:2,faces:3};
function key(key,shiftKey=false){
  const event={key,shiftKey,prevented:0,preventDefault(){this.prevented++;},
    stopPropagation(){}};
  nodes.picker.onkeydown(event);
  return event;
}
(async()=>{
  await ctx.pick({people:[fictional],allowNew:true,onChoose:async()=>{writes++}});
  assert.match(root.innerHTML,/id="picker-heading" tabindex="-1"/);
  assert.equal(document.activeElement,nodes.heading,'Opening must not summon Android keyboard');
  assert.equal(key('Tab').prevented,1);
  assert.equal(document.activeElement,nodes.query,'First Tab enters visible controls');
  assert.equal(key('Tab',true).prevented,1);
  assert.equal(document.activeElement,nodes.create,'Shift+Tab from first wraps to last');
  assert.equal(key('Tab').prevented,1);
  assert.equal(document.activeElement,nodes.query,'Last Tab wraps to first');
  nodes.option.focus({preventScroll:true});
  assert.equal(key('Tab').prevented,0,'Normal forward Tab is left to the browser');
  ctx.preview(fictional);
  assert.equal(document.activeElement,nodes.back,'Safe Back action focused instead of Confirm');
  assert.equal(key('Tab',true).prevented,1);
  assert.equal(document.activeElement,nodes.submit,'Confirmation skips hidden browse controls');
  assert.equal(key('Tab').prevented,1);
  assert.equal(document.activeElement,nodes.back,'Confirmation never tabs behind dialog');
  nodes.back.onclick();
  assert.equal(document.activeElement,nodes.option,'Back returns to chosen person on same list');
  assert.equal(writes,0);
  ctx.preview(fictional);
  nodes.back.disabled=true;nodes.submit.disabled=true;document.activeElement=nodes.submit;
  assert.equal(key('Tab').prevented,1,'Saving without tabbable buttons cannot leak focus');
  assert.equal(document.activeElement,nodes.reviewHeading);
  assert.equal(root.hidden,false);
  nodes.back.disabled=false;nodes.submit.disabled=false;
  assert.equal(key('Escape').prevented,1);
  assert.equal(review.hidden,true,'Escape returns to choices, no identity mutation');
  assert.equal(key('Escape').prevented,1);
  assert.equal(root.hidden,true);
  assert.equal(document.activeElement,opener,'Close returns to the original review tile');
  assert.equal(writes,0);

  // Direct-to-confirmation still starts on the reversible action, not Search.
  await ctx.pick({people:[fictional],initialPersonId:9,onChoose:async()=>{writes++}});
  assert.equal(document.activeElement,nodes.back);
  assert.equal(key('Tab',true).prevented,1);
  assert.equal(document.activeElement,nodes.submit);
  ctx.dismiss();
  assert.equal(writes,0);
  console.log('PASS: modal focus stays inside visible browser/confirm controls, no Android keyboard popup, safe Back/Escape and unchanged identity');
})().catch(ex=>{console.error(ex);process.exitCode=1});
