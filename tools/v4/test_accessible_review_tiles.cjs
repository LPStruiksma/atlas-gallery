'use strict';
const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');

function segment(start,end) {
  const a=src.indexOf(start),b=src.indexOf(end,a);
  assert(a>=0&&b>a,'Missing actual browser function '+start);
  return src.slice(a,b);
}
const code=[
  segment('function personOptionMarkup(p){','\nfunction renderPickerResults(){'),
  segment('function faceTile(f,selectable=true){','\nasync function home(){'),
  segment('function peopleTile(p){','\nfunction peopleSearchPage('),
  segment('async function groups(epoch){','\nfunction pager('),
].join('\n');
const ctx={
  selected:new Set(),tab:'person',personMode:'faces',person:11,page:0,
  groupOrder:'largest',groupShuffleSeed:12345,groupPendingOnly:true,
  lastUndoToken:null,groupResumePage:0,
  reviewRequestCurrent:()=>true,
  esc:s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])),
  fmt:n=>String(n),faceThumb:id=>'/thumb/face/'+id,
  photoThumb:id=>'/thumb/photo/'+id,
  portraitThumb:id=>'/thumb/portrait/'+id,
  get:async url=> {
    assert(url.startsWith('/api/groups?'),'No private real data requests');
    return {items:[{id:54,representative:900,remaining_count:2,count:4,
             possible_existing_people:[{name:'Fictional match'}]}],has_more:false}
  },
  header:()=>'<h1>Discover</h1>',
  btn:(text,action)=>'<button data-action="'+action+'">'+text+'</button>',
  selectionToolbar:()=>'<span>Selection</span>',
  undoButton:()=>'',saveDiscoverCursor:()=>{},pager:()=>'<div>Pager</div>'
};
vm.createContext(ctx);
vm.runInContext(code+'\nthis.faceTile=faceTile;this.peopleTile=peopleTile;this.photoTile=photoTile;this.personOptionMarkup=personOptionMarkup;this.groupsPage=groups;',ctx);

function standaloneButtons(html){
  const tags=html.match(/<\/?button\b[^>]*>/g)||[];
  let depth=0;
  for(const tag of tags){
    depth+=tag.startsWith('</')?-1:1;
    assert(depth>=0,'Orphan button close');
    assert(depth<=1,'Nested interactive button');
  }
  assert.equal(depth,0,'Unclosed button element');
}
const face={id:810,person_id:11,ignored:false,score:0.67};
const faceMarkup=ctx.faceTile(face);
assert.match(faceMarkup,/<button type="button" class="face-open" data-face="810"/);
assert.match(faceMarkup,/<input class="select" type="checkbox" data-select="810" aria-label="Select face #810"/);
assert.match(faceMarkup,/<button class="coverpick" type="button" data-cover-face="810"/);
assert.match(faceMarkup,/loading="lazy" decoding="async" width="160" height="160"/);
assert.doesNotMatch(faceMarkup,/<div[^>]+data-face=/,'Only dedicated semantic button opens a face');
standaloneButtons(faceMarkup);
assert.equal((faceMarkup.match(/data-face="810"/g)||[]).length,1);
ctx.selected.add(810);
const checked=ctx.faceTile(face);
assert.match(checked,/facepick selected/);
assert.match(checked,/data-select="810"[^>]+checked/);
standaloneButtons(checked);
ctx.selected.clear();
ctx.tab='similar';
assert.doesNotMatch(ctx.faceTile(face),/data-cover-face=/,
  'Cover editing should only appear in named profile face-review mode');

const peopleWithoutCover=ctx.peopleTile({id:3,name:'Person <&>',images:0,faces:0,cover:null});
assert.match(peopleWithoutCover,/<button type="button" class="tile" data-person="3"/);
assert.match(peopleWithoutCover,/portrait-placeholder/);
assert.doesNotMatch(peopleWithoutCover,/<img/,'An empty person must not trigger an image request');
assert.match(peopleWithoutCover,/Person &lt;&amp;&gt;/,'Never inject private names as HTML');
standaloneButtons(peopleWithoutCover);
const peopleWithCover=ctx.peopleTile({id:4,name:'Known',images:10,faces:22,cover:141});
assert.match(peopleWithCover,/loading="lazy" decoding="async" width="160" height="160"/);
assert.match(peopleWithCover,/src="\/thumb\/portrait\/141"/);
standaloneButtons(peopleWithCover);
const pickerEmpty=ctx.personOptionMarkup({id:4,name:'No face',images:0,faces:0,cover:null});
assert.match(pickerEmpty,/portrait-placeholder/);
assert.doesNotMatch(pickerEmpty,/<img/,'Picker must not request src=""');
standaloneButtons(pickerEmpty);
const pickerWithCover=ctx.personOptionMarkup({id:8,name:'Face',images:1,faces:3,cover:5});
assert.match(pickerWithCover,/width="54" height="54"/);
standaloneButtons(pickerWithCover);

const photo=ctx.photoTile({id:9,path:'/imaginary/a<&>.jpg'});
assert.match(photo,/<button type="button" class="tile photo" data-photo="9"/);
assert.match(photo,/width="240" height="180"/);
assert.match(photo,/a&lt;&amp;&gt;.jpg/);
standaloneButtons(photo);
(async()=>{
  const groupHtml=await ctx.groupsPage();
  assert.match(groupHtml,/<button type="button" class="tile" data-group="54"/);
  assert.match(groupHtml,/loading="lazy" decoding="async" width="160" height="160"/);
  assert.match(groupHtml,/Fictional match/);
  assert.match(groupHtml,/data-group="54"[\s\S]*?<\/button>/);
  assert(src.includes('.tile:focus-visible')&&src.includes('.face-open:focus-visible'),
    'Keyboard focus must be visibly outlined');
  assert(src.includes('.picker-option:focus-visible'),
    'Picker list focus must remain visible');
  console.log('PASS: semantic keyboard-accessible People/Discover/photo/face tiles, independent face actions, focus, async lazy images, no empty image fetch');
})().catch(e=>{console.error(e);process.exitCode=1});
