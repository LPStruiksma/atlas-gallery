'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=source.indexOf('async function nameGroup(){'),
      end=source.indexOf('async function renameReviewedPerson(){',start);
const guard=source.indexOf('function reviewWriteContext(){'),
      guardEnd=source.indexOf('async function finishDirectReview(',guard);
assert(start>0&&end>start&&guard>0&&guardEnd>guard);
const pending=[],requests=[],pickers=[],toasts=[];
let prompts=0,created=0,returns=0,failCreate=false;
const ctx={
  tab:'group',group:43,page:0,person:null,personMode:'photos',
  renderEpoch:1,pickerState:null,
  get:url=>new Promise((resolve,reject)=>{requests.push(url);pending.push({resolve,reject})}),
  promptPersonName:()=>{prompts++;return 'Fictional Name'},
  createReviewedPerson:async()=>{created++;if(failCreate)throw Error('Synthetic creation error')},
  choosePerson:async opts=>{pickers.push(opts);ctx.pickerState={options:opts}},
  toast:msg=>toasts.push(msg),
  post:async()=>({undo_token:'fixture'}),
  keepReviewUndo:()=>{},
  returnToGroups:async()=>{returns++},
};
vm.createContext(ctx);
vm.runInContext(source.slice(guard,guardEnd)+source.slice(start,end)+
  '\nthis.nameIt=nameGroup;this.attachIt=attachWholeGroup;',ctx);
const countQueries=()=>requests.length;
const burst=(fn,n)=>Array.from({length:n},()=>fn());
(async()=>{
  // Before a first group-count GET resolves, 80 conflicting attempts
  // must not make another network request or open a second prompt.
  const batch=[...burst(ctx.nameIt,40),...burst(ctx.attachIt,40)];
  assert.equal(countQueries(),1);
  assert.equal(pending.length,1);
  pending.shift().resolve({count:5});await Promise.all(batch);
  assert.equal(prompts,1);assert.equal(created,1);assert.equal(pickers.length,0);
  assert.equal(ctx.nameIt.launching,false);

  // Repeated Attach attempts also coalesce until its chooser opens.
  const attach=burst(ctx.attachIt,75);
  assert.equal(countQueries(),2);assert.equal(pending.length,1);
  pending.shift().resolve({count:5});await Promise.all(attach);
  assert.equal(pickers.length,1);
  assert.equal(ctx.attachIt.launching,false);
  await Promise.all([...burst(ctx.nameIt,9),...burst(ctx.attachIt,9)]);
  assert.equal(countQueries(),2,'A visible confirmation chooser was replaced');

  // Dismissal legitimately unlocks the next deliberate group action.
  ctx.pickerState=null;
  let retry=ctx.nameIt();assert.equal(countQueries(),3);
  pending.shift().resolve({count:5});await retry;
  assert.equal(prompts,2);assert.equal(created,2);

  // An injected network failure must release both action guards.
  retry=ctx.attachIt();assert.equal(countQueries(),4);
  pending.shift().reject(Error('Synthetic count failure'));
  await assert.rejects(retry,/Synthetic count failure/);
  assert.equal(ctx.attachIt.launching,false);
  retry=ctx.attachIt();assert.equal(countQueries(),5);
  pending.shift().resolve({count:0});await retry;
  assert.equal(pickers.length,1); // no chooser for an already completed group
  assert.match(toasts.at(-1),/already been assigned/);

  // After a new group opens, an old count result cannot prompt for it.
  retry=ctx.nameIt();assert.equal(countQueries(),6);
  ctx.group=44;ctx.renderEpoch++;
  pending.shift().resolve({count:7});await retry;
  assert.equal(prompts,2);
  assert.equal(ctx.nameIt.launching,false);
  assert.equal(returns,0);
  assert.equal(pending.length,0);
  console.log('PASS: 80 rapid Group Name/Attach taps coalesce, chooser blocks replacements, close/retry works, rejected count releases lock, stale previous-group count cannot prompt');
})().catch(e=>{console.error(e);process.exitCode=1});
