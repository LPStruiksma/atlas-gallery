'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(a,b){const i=src.indexOf(a),j=src.indexOf(b,i+a.length);
  assert(i>=0&&j>i,'missing production handler '+a);return src.slice(i,j)}
const code=actual('function reviewWriteContext(){','async function finishDirectReview(')+
 actual('function comparablePersonName(name){','// Creating a SEPARATE person is cheap')+
 '\nthis.newRosterPerson=createPersonFromRoster;this.createReviewed=createReviewedPerson;';
assert(src.includes("if(a==='newperson'){await createPersonFromRoster();return}"));
const pending=[],pickers=[],writes=[],feedback=[],navigations=[],undo=[];
let roster=[],name='Alex',prompts=0,delayedRoster=false,delayedPost=false;
let fixtureKeySeed=0;
const ctx={
  lastUndoToken:null,
  // Deterministic fictional secure-random stub; production requires Web Crypto.
  crypto:{getRandomValues:bytes=>{const seed=++fixtureKeySeed;for(let i=0;i<bytes.length;i++)bytes[i]=(seed*29+i*17)&255;return bytes}},
 tab:'people',page:0,person:null,group:null,personMode:'photos',renderEpoch:2,
 peopleQuery:'alex',peopleListPage:4,peopleReturn:null,
 window:{scrollY:603},
 promptPersonName:()=>{prompts++;return name;},
 get:uri=>{
   assert.equal(uri,'/api/people?covers=0');
   if(delayedRoster)return new Promise(resolve=>pending.push({kind:'get',resolve}));
   return Promise.resolve(roster);
 },
 post:(uri,payload)=>{
   writes.push([uri,JSON.parse(JSON.stringify(payload))]);
   if(delayedPost)return new Promise(resolve=>pending.push({kind:'post',resolve}));
   return Promise.resolve({person_id:101,undo_token:'f'.repeat(32)});
 },
 choosePerson:async options=>{pickers.push(options)},
 keepReviewUndo:r=>undo.push(r.undo_token),
 toast:msg=>feedback.push(msg),
 confirm:()=>true,
 go:destination=>{navigations.push(destination);ctx.tab=destination;ctx.page=0;ctx.renderEpoch++},
};
vm.createContext(ctx);vm.runInContext(code,ctx);
const reset=()=>{ctx.tab='people';ctx.page=0;ctx.person=null;ctx.renderEpoch++;ctx.peopleReturn=null};
const drain=()=>new Promise(setImmediate);
(async()=>{
  // Clicking existing homonym must OPEN its profile, not change global
  // person before the originating roster-view guard can approve navigation.
  roster=[{id:10,name:'Alex',faces:12,images:7},{id:11,name:'Other',faces:3,images:3}];
  await ctx.newRosterPerson();
  assert.equal(pickers.length,1);assert.equal(writes.length,0);
  assert.equal(pickers[0].confirmLabel,'Open this person');
  assert.match(pickers[0].confirmText({id:10,name:'Alex'}),/Open the existing profile/);
  await pickers[0].onChoose({id:10,name:'Alex',faces:12});
  assert.deepEqual(navigations,['person']);
  assert.equal(ctx.person,10);
  assert.deepEqual(JSON.parse(JSON.stringify(ctx.peopleReturn)),
    {query:'alex',page:4,scroll:603});
  assert.equal(writes.length,0,'Opening an existing Person must not assign faces');
  assert.match(feedback.at(-1),/Opening existing Alex/);
  assert(!feedback.some(s=>/Previous-view attachment/.test(s)));

  // A unique NEW person creates a real profile, keeps its Undo token, and
  // returns to the People roster without creating any phantom assignment.
  reset();roster=[];name='Fictional New';
  await ctx.newRosterPerson();
  assert.equal(writes.at(-1)[0],'/api/person/create');
  assert.equal(writes.at(-1)[1].name,'Fictional New');
  assert.equal(writes.at(-1)[1].allow_duplicate_name,false);
  assert.match(writes.at(-1)[1].request_id,/^[0-9a-f]{32}$/);
  assert.equal(navigations.at(-1),'people');
  assert.equal(ctx.person,null);assert.equal(undo.at(-1),'f'.repeat(32));
  assert.equal(pickers.length,1);

  // The duplicate name explicitly offers a SEPARATE Person; that flow
  // creates an identity only after its explicit confirmation.
  reset();roster=[{id:10,name:'Alex'}];name='Alex';
  await ctx.newRosterPerson();
  const chooser=pickers.at(-1);
  assert.equal(chooser.allowNew,true);assert.equal(chooser.confirmTitle,'Open existing profile');
  await chooser.onNew();
  assert.equal(writes.at(-1)[0],'/api/person/create');
  assert.equal(writes.at(-1)[1].name,'Alex');
  assert.equal(writes.at(-1)[1].allow_duplicate_name,true);
  assert.match(writes.at(-1)[1].request_id,/^[0-9a-f]{32}$/);
  assert.equal(ctx.tab,'people');

  // Previous-group callers still use explicit Attach semantics, not the
  // People-roster "Open" path. No server assignment occurs until callback.
  reset();ctx.tab='group';ctx.group=55;ctx.renderEpoch++;
  let attached=null,finished=0;
  await ctx.createReviewed('Alex',{name:'Alex',cluster_id:55,expected_count:7},
    async p=>{attached=p.id},async()=>{finished++});
  assert.equal(pickers.at(-1).confirmLabel,'Use this existing person');
  assert.match(pickers.at(-1).confirmText({id:10,name:'Alex'}),/Attach reviewed faces/);
  await pickers.at(-1).onChoose({id:10,name:'Alex'});
  assert.equal(attached,10);assert.equal(finished,1);

  // Delayed roster response following navigation is a cancelled choice.
  reset();name='Alex';delayedRoster=true;
  const before=pickers.length,countBefore=writes.length;
  let late=ctx.newRosterPerson();
  assert.equal(pending[0]?.kind,'get');
  ctx.tab='settings';ctx.renderEpoch++;
  pending.shift().resolve([{id:10,name:'Alex'}]);await late;
  assert.equal(pickers.length,before);assert.equal(writes.length,countBefore);

  // A confirmed NEW Person POST finishing on another tab preserves Undo
  // but does not navigate away from the newer screen.
  reset();delayedRoster=false;roster=[];name='Late New';delayedPost=true;
  late=ctx.newRosterPerson();await drain();
  assert.equal(pending[0]?.kind,'post');
  const navBefore=navigations.length;
  ctx.tab='settings';ctx.renderEpoch++;
  pending.shift().resolve({person_id:104,undo_token:'e'.repeat(32)});await late;
  assert.equal(navigations.length,navBefore);
  assert.equal(undo.at(-1),'e'.repeat(32));
  name=null;reset();
  const promptCount=prompts,writeCount=writes.length;
  await ctx.newRosterPerson();assert.equal(prompts,promptCount+1);
  assert.equal(writes.length,writeCount);

  console.log('PASS: People roster homonym opens existing profile with preserved Back position and zero writes; unique/homonym creation explicit, group Attach unchanged, stale lookup/post cannot navigate newer tab');
})().catch(e=>{console.error(e);process.exitCode=1});
