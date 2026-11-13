'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const begin=src.indexOf('async function groups(epoch){');
const end=src.indexOf('\nfunction pager(',begin);
assert(begin>0&&end>begin,'Must execute real Discover code');
const fictional=Array.from({length:2001},(_,i)=>({
  id:i+1,count:2001-i,remaining_count:1,representative:i+1,
  possible_existing_people:[]}));
const calls=[],saved=[];
const ctx={
  page:0,tab:'groups',renderEpoch:7,groupPendingOnly:true,groupOrder:'stable',
  groupShuffleSeed:123,groupResumePage:0,lastUndoToken:null,
  reviewRequestCurrent:(epoch,tab,page)=>epoch===ctx.renderEpoch&&
    tab===ctx.tab&&page===ctx.page,
  get:async route=>{
    calls.push(route);
    if(route.startsWith('/api/groups/total'))return {count:fictional.length};
    const query=new URL('http://fictional.invalid'+route).searchParams;
    let rows=fictional.filter(x=>query.get('pending_only')==='0'||x.remaining_count>0);
    if(query.get('order')==='stable'){
      assert.equal(query.get('cursor'),'1','Stable route requires opt-in cursor');
      if(query.has('after_count')){
        const size=Number(query.get('after_count')),id=Number(query.get('after_group'));
        rows=rows.filter(x=>x.count<size||(x.count===size&&x.id>id));
      } else {
        rows=rows.slice(Number(query.get('page'))*40);
      }
    }else rows=rows.slice(Number(query.get('page'))*40);
    const visible=rows.slice(0,40),last=visible.at(-1);
    return {items:visible,has_more:rows.length>40,
      next_count:last?.count??null,next_group:last?.id??null};
  },
  saveDiscoverCursor:()=>saved.push(ctx.groupResumePage),
  fmt:x=>String(x),esc:x=>String(x),header:x=>'<h1>'+x+'</h1>',
  faceThumb:x=>'/synthetic/'+x,
  btn:(label,action)=>'<button data-action="'+action+'">'+label+'</button>',
  undoButton:()=>'<i>Undo</i>',
  pager:(count,hasMore)=>'<p>'+count+' '+hasMore+'</p>'
};
vm.createContext(ctx);
vm.runInContext(src.slice(begin,end)+'\nthis.drawDiscover=groups;',ctx);
(async()=>{
  let page0=await ctx.drawDiscover(7);
  assert.match(page0,/Stable review pass/);
  assert.match(page0,/Stable pass by original group size/);
  assert.equal(ctx.drawDiscover.stableBounds[1].count,1962);
  assert.equal(ctx.drawDiscover.stableBounds[1].id,40);
  assert.equal(calls.at(-1),
    '/api/groups?page=0&order=stable&seed=123&pending_only=1&with_next=1&cursor=1');
  // Complete earlier groups AFTER they were rendered; do not skip first unseen.
  for(let i=0;i<27;i++)fictional[i].remaining_count=0;
  ctx.page=1;let second=await ctx.drawDiscover(7);
  assert.equal(calls.at(-1),
    '/api/groups?page=1&order=stable&seed=123&pending_only=1&with_next=1&cursor=1&after_count=1962&after_group=40');
  assert.match(second,/data-group="41"/);
  assert.equal(Number(second.match(/data-group="([0-9]+)"/)[1]),41);
  assert.equal(ctx.drawDiscover.stableBounds[2].id,80);
  assert.equal(calls.filter(x=>x.startsWith('/api/groups/total')).length,0,
    'Shrinking total must not clamp a valid keyset turn');

  // Fresh browser with only anonymous saved page cannot reconstruct an
  // exact group boundary. Must start a new safe pass at first page.
  ctx.drawDiscover.stableBounds=null;ctx.page=42;
  const restart=await ctx.drawDiscover(7);
  assert.equal(ctx.page,0);
  assert.match(restart,/data-group="28"/,'New pass starts from highest pending group');
  assert.equal(ctx.groupResumePage,0);
  assert(!calls.at(-1).includes('after_count='));

  // Historical dynamic sort remains untouched and still counts/clamps.
  ctx.groupOrder='largest';ctx.page=1;
  await ctx.drawDiscover(7);
  assert(calls.some(x=>x==='/api/groups/total?pending_only=1'),
    'Legacy dynamic sort must keep its prior count-clamp logic');
  assert.equal(saved.at(-1),1);
  console.log('PASS: real Discover renderer keysets 2001 fictional groups, 27 earlier complete, safe restart and legacy dynamic mode unchanged');
})().catch(e=>{console.error(e);process.exitCode=1});
