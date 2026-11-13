'use strict';
const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function part(a,b){const i=src.indexOf(a),j=src.indexOf(b,i);assert(i>=0&&j>i,a);return src.slice(i,j);}
const script=part('async function groups(epoch){','\nasync function settings(){');
const ctx={
  page:0,person:11,group:1,personMode:'photos',
  groupOrder:'largest',groupShuffleSeed:3,groupPendingOnly:true,
  groupResumePage:0,lastUndoToken:null,selected:new Set(),
  reviewRequestCurrent:()=>true,
  saveDiscoverCursor:()=>{},
  fmt:n=>String(n),esc:s=>String(s),
  faceThumb:n=>'/fictional/face/'+n,portraitThumb:n=>'/fictional/portrait/'+n,
  header:t=>'<h1>'+t+'</h1>',
  btn:(t,a)=>'<button data-action="'+a+'">'+t+'</button>',
  selectionToolbar:()=>'<b>selection</b>',
  undoButton:()=>'',photoTile:p=>'<i data-photo="'+p.id+'"></i>',
  faceTile:f=>'<i data-face="'+f.id+'"></i>',
};
const calls=[];
ctx.get=async path=>{
  calls.push(path);
  if(path.startsWith('/api/groups/total'))return {count:81};
  if(path.startsWith('/api/groups?')){
    const n=Number(path.match(/page=(\d+)/)[1]);
    return {items:n===0?[{id:1,representative:1,count:1,remaining_count:1}]:
      n===1?Array.from({length:40},(_,i)=>({id:i+41,representative:i+41,count:1,remaining_count:1})):
      n===2?[{id:81,representative:81,count:1,remaining_count:1}]:[],has_more:n<2};
  }
  if(path.startsWith('/api/group/candidates'))return [];
  if(path.startsWith('/api/group?')){
    const n=Number(path.match(/page=(\d+)/)[1]);
    return {items:n===0?[{id:1,person_id:null}]:n===1?[{id:61,person_id:null}]:[],has_more:n===0};
  }
  if(path.startsWith('/api/person/summary'))return {id:11,name:'Test person',faces:61,images:61};
  if(path.startsWith('/api/person?')||path.startsWith('/api/person/faces?')||
      path.startsWith('/api/person/suggested?')){
    const n=Number(path.match(/page=(\d+)/)[1]);
    return {items:n===0?[{id:1}]:n===1?[{id:61}]:[],has_more:n===0};
  }
  if(path.startsWith('/api/untagged?')){
    const n=Number(path.match(/page=(\d+)/)[1]);
    return {items:n===0?[{id:2,person_id:null}]:[],has_more:false};
  }
  throw Error('Unexpected synthetic API route: '+path);
};
vm.createContext(ctx);
vm.runInContext('let unsortedPageBounds=[null];\n'+script+[
  'this.groupList=groups;',
  'this.groupDetail=groupPage;',
  'this.personDetail=personPage;',
  'this.unsorted=untagged;',
  'this.pagerView=pager;'
].join('\n'),ctx);

function controls(html,back,next){
  assert.match(html,back?/data-action="prev"(?![^>]*disabled)/:
                         /data-action="prev" disabled/,
               'Incorrect previous enabled state');
  assert.match(html,next?/data-action="next"(?![^>]*disabled)/:
                         /data-action="next" disabled/,
               'Incorrect next enabled state');
}
(async()=>{
  ctx.page=0;controls(ctx.pagerView(60,false),false,false);
  ctx.page=1;controls(ctx.pagerView(60,true),true,true);
  ctx.page=2;controls(ctx.pagerView(1,false),true,false);
  ctx.page=0;let html=await ctx.groupList();
  assert.match(calls.at(-1),/with_next=1/);
  controls(html,false,true);
  ctx.page=2;html=await ctx.groupList();
  controls(html,true,false);
  assert.match(html,/Candidate group #81/);
  ctx.page=800;html=await ctx.groupList();
  assert.equal(ctx.page,2,'Outdated group bookmark must be clamped');
  controls(html,true,false);

  ctx.page=1;html=await ctx.groupDetail();
  assert.match(html,/data-face="61"/);
  controls(html,true,false);
  ctx.page=9;html=await ctx.groupDetail();
  assert.equal(ctx.page,0,'Empty group page returns directly to first valid page');
  controls(html,false,true);

  for(const mode of ['photos','faces','suggested']){
    ctx.personMode=mode;ctx.page=1;html=await ctx.personDetail();
    assert.match(html,/Page 2/);
    controls(html,true,false);
    assert.match(calls.at(-1),/with_next=1/);
    ctx.page=44;html=await ctx.personDetail();
    assert.equal(ctx.page,0,'Stale Person mode must return to safe first page');
    controls(html,false,true);
  }
  ctx.page=33;html=await ctx.unsorted();
  assert.equal(ctx.page,0);
  controls(html,false,false);
  assert.match(calls.at(-1),/with_next=1/);
  assert(src.includes('const next=document.querySelector'), 'Next click must guard disabled action');
  assert(src.includes("if(page<=0)return;page--"), 'Previous must not underflow');
  console.log('PASS: exact Next/Previous disablement; stale Discover/Group/People/Unsorted pages recover safely; opt-in metadata across fictional modes');
})().catch(e=>{console.error(e);process.exitCode=1;});
