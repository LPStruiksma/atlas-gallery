'use strict';
const fs=require('fs');
const vm=require('vm');
const assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const begin=source.indexOf('function rememberSimilarityContext(){');
const end=source.indexOf('function promptPersonName(',begin);
assert(begin>0&&end>begin,'Must expose bounded detour/return operations');
const code=source.slice(begin,end);
const visited=[];
const ctx={
  tab:'group',page:23,renderEpoch:1,
  selected:new Set([2,3]),
  similarityReturn:null,similarityTrail:[],
  window:{scrollY:620,profileSearch:false,currentFace:55,scrollTo:(x,y)=>visited.push(['scroll',y])},
  render:async()=>{ctx.renderEpoch++;visited.push(['render'])},
  requestAnimationFrame:callback=>callback(),
  go:t=>visited.push(['fallback',t])
};
vm.createContext(ctx);
vm.runInContext(code+'\nthis.remember=rememberSimilarityContext;this.goBack=returnFromSimilarity;',ctx);
(async()=>{
  ctx.remember();
  assert.equal(ctx.similarityReturn.tab,'group');
  assert.equal(ctx.similarityReturn.page,23);
  assert.equal(ctx.similarityReturn.scroll,620);
  assert.equal('group' in ctx.similarityReturn,false,'No group or face IDs retained in resume state');
  ctx.tab='similar';ctx.page=0;ctx.window.profileSearch=true;
  ctx.window.scrollY=190;ctx.remember();ctx.window.currentFace=88;
  await ctx.goBack();
  assert.equal(ctx.tab,'similar');
  assert.equal(ctx.window.currentFace,55);
  assert.equal(ctx.window.profileSearch,true);
  assert.equal(ctx.similarityTrail.length,0);
  assert.deepEqual(visited,[['render'],['scroll',190]]);
  visited.length=0;
  await ctx.goBack();
  assert.equal(ctx.tab,'group');
  assert.equal(ctx.page,23);
  assert.equal(ctx.window.profileSearch,false);
  assert.equal(ctx.selected.size,0);
  assert.equal(ctx.similarityReturn,null);
  assert.deepEqual(visited,[['render'],['scroll',620]]);
  visited.length=0;
  ctx.tab='person';ctx.page=11;ctx.window.scrollY=318;
  ctx.remember();
  ctx.tab='similar';ctx.page=0;
  await ctx.goBack();
  assert.equal(ctx.tab,'person');
  assert.equal(ctx.page,11);
  assert.deepEqual(visited,[['render'],['scroll',318]]);
  visited.length=0;
  await ctx.goBack();
  assert.deepEqual(visited,[['fallback','untagged']]);
  assert.ok(source.includes("if(a==='backsimilar'){await returnFromSimilarity();return}"));
  assert.ok(source.includes("if(f){rememberSimilarityContext();window.profileSearch=false;"));
  // Person search now validates its initiating review context before
  // entering similarity: a stale network response cannot hijack a new Person.
  assert.ok(source.includes("if(a==='searchperson'){await searchCurrentPersonFace();return}"));
  assert.ok(source.includes("if(!reviewWriteStillVisible(origin))return;"));
  assert.ok(source.includes("window.currentFace=faces[0].id;window.lastTab='person';window.profileSearch=true;"));
  console.log('PASS: deep Discover, person and profile-search detours preserve page/scroll; no persisted IDs; safe fallback');
})().catch(error=>{console.error(error);process.exitCode=1});
