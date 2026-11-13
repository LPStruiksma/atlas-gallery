'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(from,to){
  const i=source.indexOf(from),j=source.indexOf(to,i+from.length);
  assert(i>=0&&j>i,'Production browser function not found: '+from);
  return source.slice(i,j);
}
const javascript=actual('function reviewWriteContext(){','async function finishDirectReview(')+
  actual('function comparablePersonName(name){','// Creating a SEPARATE person is cheap')+
  '\nthis.createFromRoster=createPersonFromRoster;'+
  'this.createReviewed=createReviewedPerson;'+
  'this.creationWarning=()=>unverifiedPersonCreate;';
const prompts=[],confirmations=[],writes=[],toasts=[],undo=[],navigation=[],pickers=[];
let roster=[],enteredName='Fictional June',response='drop-after-commit',explicitlyChecked=false,id=75;
let fixtureKeySeed=0;
const ctx={
  lastUndoToken:null,
  // Deterministic fictional secure-random stub; production requires Web Crypto.
  crypto:{getRandomValues:bytes=>{const seed=++fixtureKeySeed;for(let i=0;i<bytes.length;i++)bytes[i]=(seed*29+i*17)&255;return bytes}},
  tab:'people',page:0,person:null,group:null,personMode:'photos',
  renderEpoch:1,peopleQuery:'',peopleListPage:0,pickerState:null,peopleReturn:null,
  window:{scrollY:425},
  promptPersonName:label=>{prompts.push(label);return enteredName},
  get:async path=>{
    assert.equal(path,'/api/people?covers=0');
    return roster.map(p=>({...p}));
  },
  post:async(path,payload)=>{
    assert.equal(path,'/api/person/create');
    writes.push(JSON.parse(JSON.stringify(payload)));
    if(response==='drop-after-commit'){
      roster.push({id:id++,name:payload.name,faces:payload.cluster_id?4:0,images:0});
      throw Error('Synthetic reply lost AFTER SQLite commit');
    }
    if(response==='reject-before-commit')throw Error('Synthetic server rejection');
    const p={id:id++,name:payload.name,faces:payload.cluster_id?4:0,images:0};
    roster.push(p);
    return {person_id:p.id,undo_token:p.id.toString(16).padStart(32,'0')};
  },
  toast:s=>toasts.push(s),
  keepReviewUndo:r=>undo.push(r.undo_token),
  confirm:message=>{confirmations.push(message);return explicitlyChecked},
  choosePerson:async opts=>{pickers.push(opts)},
  go:dest=>{navigation.push(dest);ctx.tab=dest;ctx.renderEpoch++}
};
vm.createContext(ctx);vm.runInContext(javascript,ctx);
const reset=()=>{ctx.tab='people';ctx.group=null;ctx.person=null;ctx.renderEpoch++;};
(async()=>{
  // Simulate a true server COMMIT followed by a lost acknowledgement.
  await assert.rejects(ctx.createFromRoster(),/Could not verify person creation/);
  assert.deepEqual(roster.map(p=>p.name),['Fictional June']);
  assert.equal(writes.length,1);
  assert.equal(undo.length,0,'Lost reply must not invent an Undo token');
  assert.equal(ctx.creationWarning(),'Fictional June');
  assert(toasts.some(x=>/inspect People before retrying/.test(x)));
  const initialPrompts=prompts.length;
  await ctx.createFromRoster();
  assert.equal(writes.length,1,'A second tap must not create another identity');
  assert.equal(prompts.length,initialPrompts,'Recovery check precedes a new name prompt');
  assert.equal(ctx.creationWarning(),'Fictional June');
  assert(confirmations.at(-1).includes('may have SAVED'));
  assert(confirmations.at(-1).includes('Inspect the People roster'));

  // After the reviewer checks the roster and explicitly acknowledges that,
  // the fresh metadata check discovers the actual previously saved profile
  // and offers OPEN, never automatic second creation or reassignment.
  explicitlyChecked=true;
  await ctx.createFromRoster();
  assert.equal(writes.length,1);
  assert.equal(ctx.creationWarning(),null);
  assert.equal(pickers.length,1);
  assert.equal(pickers[0].confirmLabel,'Open this person');
  await pickers[0].onChoose(roster[0]);
  assert.deepEqual(navigation,['person']);
  assert.equal(ctx.person,75);
  assert.equal(writes.length,1);
  assert.equal(undo.length,0);

  // Group naming uses the same safeguard despite entering from a DIFFERENT
  // UI action. The created Person may also already have claimed group faces.
  reset();ctx.tab='group';ctx.group=92;ctx.renderEpoch++;
  roster=[];enteredName='Fictional Group June';response='drop-after-commit';
  await assert.rejects(ctx.createReviewed('Fictional Group June',{
    name:'Fictional Group June',cluster_id:92,expected_count:4
  },async()=>{},async()=>{}),/Could not verify person creation/);
  assert.equal(writes.length,2);
  assert.equal(undo.length,0);
  assert.equal(ctx.creationWarning(),'Fictional Group June');
  explicitlyChecked=false;
  // A different requested name may not evade the outstanding ambiguous
  // creation check across the Group/People/selected-face entrypoints.
  roster=[];
  await ctx.createReviewed('Another Person',{
    name:'Another Person',cluster_id:92,expected_count:4
  },async()=>{},async()=>{});
  assert.equal(writes.length,2);
  assert.equal(ctx.creationWarning(),'Fictional Group June');

  // After an explicit review, a future unique creation is allowed. A valid
  // acknowledgement (and ONLY that) generates a one-use Undo record.
  explicitlyChecked=true;response='acknowledge';
  await ctx.createReviewed('Another Person',{
    name:'Another Person',cluster_id:92,expected_count:4
  },async()=>{},async()=>{});
  assert.equal(writes.length,3);
  assert.equal(undo.length,1);
  assert.equal(ctx.creationWarning(),null);
  assert.equal(roster.at(-1).name,'Another Person');
  assert.equal(ctx.createFromRoster.launching,false);
  console.log('PASS: synthetic committed-but-lost People/group creates require explicit roster inspection before retry; discovery opens existing profile, no duplicate POST or invented Undo');
})().catch(e=>{console.error(e);process.exitCode=1});
