'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(a,b){
  const i=source.indexOf(a),j=source.indexOf(b,i+a.length);
  assert(i>=0&&j>i,'Missing real browser function '+a);
  return source.slice(i,j);
}
const code=actual('function reviewWriteContext(){','async function finishDirectReview(')+
 '\nthis.changeCover=setReviewedCover;this.searchPerson=searchCurrentPersonFace;';
assert(source.includes("await setReviewedCover(Number(cover.dataset.coverFace))"));
assert(source.includes("if(a==='searchperson'){await searchCurrentPersonFace();return}"));

const covers=[],lookups=[],toasts=[],navigation=[];
let paints=0,remembered=0,failNextPaint=false;
const ctx={
  tab:'person',person:7,personMode:'faces',page:2,group:null,renderEpoch:12,
  selected:new Set([100]),
  post:(url,body)=>new Promise((resolve,reject)=>{
    covers.push({url,body:JSON.parse(JSON.stringify(body)),resolve,reject});
  }),
  get:url=>new Promise((resolve,reject)=>lookups.push({url,resolve,reject})),
  toast:msg=>toasts.push(msg),
  render:async()=>{paints++;ctx.renderEpoch++;if(failNextPaint){failNextPaint=false;throw Error('Synthetic repaint failed AFTER acknowledged save')}},
  rememberSimilarityContext:()=>{remembered++},
  window:{currentFace:null,lastTab:null,profileSearch:false},
  go:tab=>{navigation.push(tab);ctx.tab=tab;ctx.renderEpoch++},
};
vm.createContext(ctx);vm.runInContext(code,ctx);
const tick=()=>new Promise(setImmediate);
(async()=>{
  // 50 repeated taps and another distinct face of the SAME Person while
  // in flight cannot enqueue conflicting aesthetic cover updates.
  const taps=Array.from({length:50},()=>ctx.changeCover(91));
  taps.push(ctx.changeCover(92));
  assert.equal(covers.length,1);
  assert.deepEqual([covers[0].url,covers[0].body],
    ['/api/person/cover',{person_id:7,face_id:91}]);
  covers[0].resolve({ok:true});await Promise.all(taps);
  assert.equal(paints,1);
  assert.equal(toasts.at(-1),'Profile portrait updated');

  // Different profiles are independent; their responses may complete in
  // reverse order, but an old profile cannot repaint a newer screen.
  let pending7=ctx.changeCover(93);
  ctx.person=8;ctx.page=0;ctx.renderEpoch++;ctx.selected=new Set([800]);
  let pending8=ctx.changeCover(801);
  assert.equal(covers.length,3);
  assert.deepEqual(covers[1].body,{person_id:7,face_id:93});
  assert.deepEqual(covers[2].body,{person_id:8,face_id:801});
  covers[1].resolve({ok:true});await pending7;
  assert.equal(paints,1);
  assert.deepEqual([...ctx.selected],[800]);
  assert.equal(toasts.at(-1),'Portrait updated in previous view');
  covers[2].resolve({ok:true});await pending8;
  assert.equal(paints,2);
  assert.deepEqual([...ctx.selected],[800]);

  // A server error must unlock the profile for a deliberate retry.
  let failed=ctx.changeCover(802);
  covers[3].reject(Error('Fictional server refusal'));await failed;
  assert.match(toasts.at(-1),/Could not verify portrait update.*Fictional server refusal.*check Person before retrying/);
  let retry=ctx.changeCover(803);
  assert.equal(covers.length,5);
  covers[4].resolve({ok:false});await retry;
  assert.match(toasts.at(-1),/Incomplete portrait acknowledgement/);
  // Invalid / non-confirmed face state rejects locally without a write.
  ctx.personMode='suggested';await ctx.changeCover(805);
  ctx.personMode='faces';await ctx.changeCover(NaN);
  assert.equal(covers.length,5);

  // The server may ACKNOWLEDGE a cover change before screen repaint fails.
  // In that case the DB change is KNOWN to be saved: no false rollback claim.
  failNextPaint=true;
  let savedButPaintFailed=ctx.changeCover(806);
  covers[5].resolve({ok:true});await savedButPaintFailed;
  assert.match(toasts.at(-1),
    /Portrait saved; could not refresh view: Synthetic repaint failed AFTER acknowledged save/);
  assert.equal(paints,3);

  // A rejected cover POST can still have committed, so the safe outcome is
  // unknown, not "Cover unchanged" or an invitation to blind repeat.
  let lostReply=ctx.changeCover(807);
  covers[6].reject(Error('Synthetic acknowledgement lost AFTER commit'));
  await lostReply;
  assert.match(toasts.at(-1),/Could not verify portrait update.*check Person before retrying/);
  assert(!toasts.some(x=>x.includes('Cover unchanged')));

  // Truthy HTTP-200 bodies are NOT evidence of a persisted manual portrait.
  // Real backend returns exactly {ok:true}. Test with a fictional Person 8.
  const incomplete=[undefined,null,{},[],false,true,{ok:null},{ok:0},
                    {ok:1},{ok:'true'},{ok:'false'},{ok:[]}];
  for(const reply of incomplete){
    const before=covers.length,previousPaints=paints;
    const operation=ctx.changeCover(1000+before);
    assert.equal(covers.length,before+1,'One explicit cover POST per deliberate tap');
    covers.at(-1).resolve(reply);
    await operation;
    assert.equal(paints,previousPaints,'Malformed response cannot optimistically repaint');
    assert.match(toasts.at(-1),
      /Could not verify portrait update: Incomplete portrait acknowledgement.*check Person before retrying/);
    assert.deepEqual([...ctx.selected],[800],
      'Malformed response must preserve existing face selection');
  }

  // Person search resolves after navigating from 8 to 9: never open
  // a similarity view for a face belonging to the original Person.
  let search=ctx.searchPerson();
  assert.equal(lookups.at(-1).url,'/api/person/faces?id=8');
  ctx.person=9;ctx.renderEpoch++;ctx.selected=new Set([900]);
  lookups.shift().resolve([{id:808}]);await search;
  assert.equal(remembered,0);assert.equal(navigation.length,0);
  assert.equal(ctx.window.currentFace,null);
  assert.deepEqual([...ctx.selected],[900]);

  // Current Person 9 can still initiate similarity from a confirmed face.
  search=ctx.searchPerson();
  assert.equal(lookups[0].url,'/api/person/faces?id=9');
  lookups.shift().resolve([{id:909}]);await search;
  assert.equal(remembered,1);assert.deepEqual(navigation,['similar']);
  assert.equal(ctx.window.currentFace,909);
  assert.equal(ctx.window.profileSearch,true);
  assert.equal(ctx.window.lastTab,'person');

  // An empty latest-person response never navigates to similarity.
  ctx.tab='person';ctx.person=10;ctx.renderEpoch++;
  search=ctx.searchPerson();lookups.shift().resolve([]);await search;
  assert.deepEqual(navigation,['similar']);
  assert.match(toasts.at(-1),/Assign at least one face/);
  assert.equal(paints,3);
  console.log('PASS: rapid portrait taps single-flight per Person; different profiles independent; late responses cannot redraw a newer Person; 12 malformed portrait acknowledgements remain unverified; stale lookalike search does not open earlier face');
})().catch(e=>{console.error(e);process.exitCode=1});
