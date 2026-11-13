'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(begin,end){
  const a=src.indexOf(begin),b=src.indexOf(end,a+begin.length);
  assert(a>=0&&b>a,'Missing production Discover logic '+begin);
  return src.slice(a,b);
}
const bootstrap=actual("const DISCOVER_CURSOR_KEY='atlas-v4-discover-cursor-v1';",
  'const esc=s=>');
const render=actual('async function groups(epoch){','\nfunction pager(');
const code=bootstrap+'\n'+render+'\nthis.draw=groups;'+
  '\nthis.navigation=()=>({order:groupOrder,pending:groupPendingOnly,page:groupResumePage});';
function fixture(raw=null,storageFails=false){
  let saved=raw,updates=[],calls=[];
  const cards=Array.from({length:101},(_,i)=>({
    id:i+1,count:101-i,remaining_count:1,representative:i+1,
    possible_existing_people:[]
  }));
  const ctx={
    tab:'groups',page:0,renderEpoch:7,lastUndoToken:null,
    localStorage:{
      getItem(key){assert.equal(key,'atlas-v4-discover-cursor-v1');
        if(storageFails)throw Error('Synthetic denied local storage');return saved},
      setItem(key,value){assert.equal(key,'atlas-v4-discover-cursor-v1');
        if(storageFails)throw Error('Synthetic denied local storage');
        saved=value;updates.push(JSON.parse(value))}
    },
    get:async url=>{
      calls.push(url);
      if(url.startsWith('/api/groups/total'))return {
        count:cards.filter(x=>x.remaining_count>0).length
      };
      const q=new URL(url,'http://fictional.invalid').searchParams;
      let rows=cards.filter(x=>q.get('pending_only')==='0'||x.remaining_count>0);
      if(q.get('order')==='stable'){
        assert.equal(q.get('cursor'),'1','Default must request gap-free keyset');
        if(q.has('after_count')){
          const size=Number(q.get('after_count')),id=Number(q.get('after_group'));
          rows=rows.filter(x=>x.count<size||(x.count===size&&x.id>id));
        }else rows=rows.slice(Number(q.get('page'))*40);
      }else rows=rows.slice(Number(q.get('page'))*40);
      const visible=rows.slice(0,40),last=visible.at(-1);
      return {items:visible,has_more:rows.length>40,
        next_count:last?.count??null,next_group:last?.id??null};
    },
    reviewRequestCurrent:(epoch,screen,requested)=>epoch===ctx.renderEpoch&&
      screen===ctx.tab&&requested===ctx.page,
    fmt:n=>String(n),esc:s=>String(s),
    header:title=>'<h1>'+title+'</h1>',
    faceThumb:id=>'/fixture/'+id,
    btn:(label,action)=>'<button data-action="'+action+'">'+label+'</button>',
    undoButton:()=>'<button>Undo</button>',
    pager:(n,more)=>'<p>Page '+n+' '+more+'</p>'
  };
  vm.createContext(ctx);vm.runInContext(code,ctx);
  return {ctx,cards,calls,updates,saved:()=>saved};
}
(async()=>{
  const brandNew=fixture();
  assert.equal(brandNew.ctx.navigation().order,'stable',
    'A new browser must start gap-free, not with shrinking OFFSET pagination');
  let first=await brandNew.ctx.draw(7);
  assert.match(first,/Stable review pass/);
  assert.equal(brandNew.ctx.draw.stableBounds[1].id,40);
  for(let i=0;i<26;i++)brandNew.cards[i].remaining_count=0;
  brandNew.ctx.page=1;
  const second=await brandNew.ctx.draw(7);
  assert.match(second,/data-group="41"/);
  assert.equal(Number(second.match(/data-group="(\d+)"/)[1]),41,
    'Completing previous-page groups must not skip the first unseen group');
  assert.equal(brandNew.calls.filter(c=>c.includes('/api/groups/total')).length,0);
  assert.deepEqual(brandNew.updates.map(x=>x.order),['stable','stable']);
  for(const entry of brandNew.updates){
    assert.deepEqual(Object.keys(entry).sort(),['order','page','pending','seed'],
      'Storage may contain only anonymous navigation fields');
  }

  // Previously stored user choice is authoritative. Do not silently switch
  // their preferred largest/smallest/shuffle order to stable.
  for(const mode of ['largest','smallest','shuffle','stable']){
    const old={order:mode,pending:false,page:2,seed:12345};
    const reopened=fixture(JSON.stringify(old));
    assert.deepEqual(JSON.parse(JSON.stringify(reopened.ctx.navigation())),
      {order:mode,pending:false,page:2});
  }
  const legacy=fixture(JSON.stringify({
    order:'largest',pending:true,page:0,seed:20261014
  }));
  await legacy.ctx.draw(7);
  for(let i=0;i<26;i++)legacy.cards[i].remaining_count=0;
  legacy.ctx.page=1;
  let oldPage=await legacy.ctx.draw(7);
  assert.equal(Number(oldPage.match(/data-group="(\d+)"/)[1]),67,
    'Historical explicit dynamic Largest behavior stays unchanged');
  assert(legacy.calls.some(c=>c.includes('/api/groups/total')));

  assert.equal(fixture('{invalid json').ctx.navigation().order,'stable');
  assert.equal(fixture(JSON.stringify({order:'unknown',pending:true,
    page:0,seed:1})).ctx.navigation().order,'stable');
  const denied=fixture(null,true);
  assert.equal(denied.ctx.navigation().order,'stable');
  await denied.ctx.draw(7);
  assert.equal(denied.updates.length,0,'Denied storage may not break Discover');
  console.log('PASS: new Discover defaults to lossless historical-size keyset, 26 earlier completions do not skip unseen #41, all saved sort preferences preserved, malformed/denied browser storage safe');
})().catch(error=>{console.error(error);process.exitCode=1});
