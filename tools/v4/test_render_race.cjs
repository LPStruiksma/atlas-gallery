'use strict';
const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const begin=source.indexOf('let renderEpoch=0;');
const end=source.indexOf('\nfunction go(t){',begin);
assert(begin>0&&end>begin,'Expected guarded production render definition');

let domUpdates=[];
const view={set innerHTML(html){domUpdates.push(String(html));},get innerHTML(){return domUpdates.at(-1)||'';}};
const navs=['home','people','groups','person'].map(t=>({
    dataset:{tab:t},
    active:false,
    classList:{toggle:(_class,enabled)=>{if(_class!=='active')throw Error('Unexpected navigation class');}}
}));
const pending=[];
const request=(label)=>new Promise((resolve,reject)=>pending.push({label,resolve,reject}));
const ctx={
  tab:'home',
  $:selector=>{assert.equal(selector,'#view');return view},
  document:{querySelectorAll:selector=>{assert.equal(selector,'nav button');return navs;}},
  home:()=>request('home'),people:()=>request('people'),groups:()=>request('groups'),
  groupPage:()=>request('group'),personPage:()=>request('person'),
  untagged:()=>request('untagged'),settings:()=>request('settings'),
  photoPage:()=>request('photo'),matchesPage:()=>request('similar'),
  esc:v=>String(v).replace(/</g,'&lt;'),
};
vm.createContext(ctx);
vm.runInContext(source.slice(begin,end)+'\nthis.draw=render;',ctx);

(async()=>{
  // Older request finishes AFTER newer navigation. Old HTML cannot flash.
  const older=ctx.draw();assert.equal(pending.at(-1).label,'home');
  ctx.tab='groups';
  const newer=ctx.draw();assert.equal(pending.at(-1).label,'groups');
  pending.at(-1).resolve('<h1>Latest groups</h1>');
  await newer;
  assert.equal(view.innerHTML,'<h1>Latest groups</h1>');
  pending[0].resolve('<h1>Stale home</h1>');
  await older;
  assert.deepEqual(domUpdates,['<h1>Latest groups</h1>']);

  // Same-tab page-to-page races must also honor the newest invocation.
  ctx.tab='person';
  const previousPage=ctx.draw(),currentPage=ctx.draw();
  pending.at(-1).resolve('<h1>Page 19</h1>');
  await currentPage;
  pending.at(-2).resolve('<h1>Page 18</h1>');
  await previousPage;
  assert.equal(view.innerHTML,'<h1>Page 19</h1>');

  // An old rejected network request must not replace a valid later page
  // with a scary error, even if the old tab became active again.
  ctx.tab='home';
  const failedStale=ctx.draw();
  ctx.tab='people';
  const latest=ctx.draw();
  pending.at(-1).resolve('<h1>People</h1>');
  await latest;
  pending.at(-2).reject(new Error('<stale network failure>'));
  await failedStale;
  assert.equal(view.innerHTML,'<h1>People</h1>');

  // A failure on the *current* render must still be disclosed and escaped.
  ctx.tab='groups';
  const realFailure=ctx.draw();
  pending.at(-1).reject(new Error('<actual failure>'));
  await realFailure;
  assert.ok(view.innerHTML.includes('&lt;actual failure>'));
  assert.ok(!view.innerHTML.includes('<actual failure>'));

  // Returning to a tab after a detour: use epoch, not just tab equality.
  ctx.tab='home';const first=ctx.draw();
  ctx.tab='groups';const middle=ctx.draw();
  ctx.tab='home';const last=ctx.draw();
  pending.at(-1).resolve('<h1>Fresh home</h1>');
  await last;
  pending.at(-2).resolve('<h1>Old groups</h1>');
  pending.at(-3).resolve('<h1>Very old home</h1>');
  await Promise.all([first,middle]);
  assert.equal(view.innerHTML,'<h1>Fresh home</h1>');

  console.log('PASS: synthetic rapid tab/page switches, out-of-order responses, stale errors and latest error visibility');
})().catch(e=>{console.error(e);process.exitCode=1});
