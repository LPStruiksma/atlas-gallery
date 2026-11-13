'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function part(a,z){const i=source.indexOf(a),j=source.indexOf(z,i);
  assert(i>=0&&j>i,'Expected real V4 function '+a);return source.slice(i,j);}
const fakeIds=Array.from({length:2001},(_,i)=>2001-i);
const calls=[];
const ctx={
  tab:'untagged',page:0,renderEpoch:10,groupResumePage:0,selected:new Set([2001]),
  groupReturn:null,peopleCache:null,
  reviewRequestCurrent:(epoch,screen,requestedPage)=>
    ctx.tab===screen&&ctx.page===requestedPage&&ctx.renderEpoch===epoch,
  get:async uri=>{
    calls.push(uri);
    const params=new URL('https://fictional.invalid'+uri).searchParams;
    assert.equal(params.get('cursor'),'1','New V4 Unsorted must opt into ID cursor');
    assert.equal(params.get('with_next'),'1');
    const page=Number(params.get('page'));
    const before=params.has('before_id')?Number(params.get('before_id')):null;
    const chosen=fakeIds.filter(id=>before==null||id<before);
    // Old API behavior only for a page missing its in-memory cursor.
    const start=before==null?page*60:0;
    const visible=chosen.slice(start,start+60);
    return {items:visible.map(id=>({id})),has_more:chosen.length>start+60,
      next_before:visible.length?visible.at(-1):null};
  },
  fmt:n=>String(n),esc:s=>String(s),faceTile:f=>'<i data-face="'+f.id+'"></i>',
  header:name=>'<h1>'+name+'</h1>',btn:(s)=>s,
  selectionToolbar:()=>'',undoButton:()=>'',pager:(n,more)=>'<p>'+n+' '+more+'</p>',
  window:{scrollTo:()=>{}},render:async()=>{},
};
vm.createContext(ctx);
vm.runInContext('let unsortedPageBounds=[null];\n'+
 part('async function untagged(epoch){','\nasync function settings(){')+
 '\n'+part('function go(t){','\n// A similarity detour')+
 '\nthis.loadInbox=untagged;this.bounds=()=>unsortedPageBounds;this.navigate=go;',ctx);
(async()=>{
  let html=await ctx.loadInbox(10);
  assert.match(html,/data-face="2001"/);
  assert.equal(ctx.bounds()[1],1942);
  assert.equal(calls[0],'/api/untagged?page=0&with_next=1&cursor=1');
  // External review removes 35 *earlier* IDs after page one.
  fakeIds.splice(0,35);
  ctx.page=1;ctx.selected.clear();html=await ctx.loadInbox(10);
  assert.match(html,/data-face="1941"/);
  assert.equal(ctx.bounds()[2],1882);
  assert.equal(calls[1],'/api/untagged?page=1&with_next=1&cursor=1&before_id=1942');
  assert.equal(Number(html.match(/data-face="([0-9]+)"/)[1]),1941,
    'A shifted OFFSET would begin at 1906 instead of the first unseen face');
  // A second page and a back turn retain original anchor, ignoring removals.
  ctx.page=2;html=await ctx.loadInbox(10);
  assert.match(html,/data-face="1881"/);
  ctx.page=1;html=await ctx.loadInbox(10);
  assert.match(html,/data-face="1941"/);
  assert.equal(ctx.bounds()[2],1882);
  // Navigate away and back: discard private in-memory IDs, fresh top begins.
  ctx.navigate('home');
  ctx.navigate('untagged');
  assert.deepEqual(Array.from(ctx.bounds()),[null]);
  assert.equal(ctx.page,0);
  html=await ctx.loadInbox(10);
  assert.match(html,/data-face="1966"/);
  assert.equal(ctx.bounds()[1],1907);
  // An old bookmarked deep page missing a cursor falls back to legacy offset,
  // instead of guessing an invalid bound or jumping to the wrong identity.
  ctx.page=3;await ctx.loadInbox(10);
  assert.equal(calls.at(-1),'/api/untagged?page=3&with_next=1&cursor=1');
  console.log('PASS: browser Unsorted Next uses last-visible-ID cursor; older-face assignment cannot skip unseen rows; Back anchors, re-entry reset and old-page fallback');
})().catch(e=>{console.error(e);process.exitCode=1});
