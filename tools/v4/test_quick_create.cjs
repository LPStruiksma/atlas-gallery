'use strict';
const fs = require('fs');
const vm = require('vm');
const assert = require('assert/strict');
const src = fs.readFileSync(__dirname + '/monochrome_studio_v4_people_ux_preview.py', 'utf8');
const from = src.indexOf('function comparablePersonName(name)');
const to = src.indexOf('async function mergePeople(){', from);
assert(from > 0 && to > from, 'Expected separate create/attach and duplicate-name functions');
const js = src.slice(from, to);
let calls = [];
let name = 'Alex';
let count = 7;
let roster = [];
let selection = null;
let approveHomonym = false;
let fixtureKeySeed=0;
const context = {
  lastUndoToken:null,
  // Deterministic fictional secure-random stub; production requires Web Crypto.
  crypto:{getRandomValues:bytes=>{const seed=++fixtureKeySeed;for(let i=0;i<bytes.length;i++)bytes[i]=(seed*29+i*17)&255;return bytes}},
  group: 43,tab:'group',page:0,person:null,personMode:'photos',renderEpoch:0,
  get: async path => {
    calls.push(['get', path]);
    if (path==='/api/people?covers=0') return roster;
    assert.notEqual(path,'/api/people','Homonym lookup may not rescore every Person portrait');
    return {count};
  },
  promptPersonName: prompt => {calls.push(['prompt', prompt]); return name;},
  post: async (endpoint, body) => {
    calls.push(['post', endpoint, body]);
    return endpoint==='/api/person/create'
      ?{person_id:777,undo_token:'e'.repeat(32)}
      :{count:7,undo_token:'d'.repeat(32)};
  },
  toast: msg => calls.push(['toast', msg]),
  returnToGroups: async () => calls.push(['return']),
  choosePerson: async options => {calls.push(['picker']); selection = options;},
  confirm: () => {calls.push(['homonym-confirm']); return approveHomonym;},
  keepReviewUndo: result => calls.push(['undo-recorded',result]),
};
vm.createContext(context);
const guardStart=src.indexOf('function reviewWriteContext(){');
const guardEnd=src.indexOf('async function finishDirectReview(',guardStart);
assert(guardStart>0&&guardEnd>guardStart);
vm.runInContext(src.slice(guardStart,guardEnd)+js + '\nthis.runNew=nameGroup;this.runAttach=attachWholeGroup;', context);
(async () => {
  await context.runNew();
  let writes = calls.filter(c => c[0]==='post');
  assert.equal(writes.length, 1, 'new person requires exactly one write');
  assert.equal(writes[0][1], '/api/person/create');
  assert.equal(writes[0][2].name, 'Alex');
  assert.equal(writes[0][2].cluster_id, 43);
  assert.equal(writes[0][2].expected_count, 7);
  assert.equal(writes[0][2].allow_duplicate_name, false);
  assert.equal(calls.filter(c=>c[0]==='picker').length, 0, 'new person must not open picker if name unique');
  assert.equal(calls.filter(c=>c[0]==='prompt').length, 1, 'one name entry only');
  calls = []; name = null;
  await context.runNew();
  assert.equal(calls.filter(c=>c[0]==='post').length, 0, 'Cancel must not write');
  calls=[]; name='Alex';
  roster=[{id:9,name:'alex',faces:10,images:6},{id:19,name:'Alex',faces:2,images:1}];
  await context.runNew();
  assert.equal(calls.filter(c=>c[0]==='post').length, 0, 'Same-name creation must pause for review');
  assert(selection && selection.allowNew, 'Deliberate different-person option must exist');
  assert.equal(selection.query,'Alex','Picker should show name matches first');
  assert.equal(selection.confirmLabel,'Use this existing person','Destination needs explicit confirmation');
  await selection.onChoose({id:19,name:'Alex'});
  writes = calls.filter(c=>c[0]==='post');
  assert.equal(writes.length,1);
  assert.equal(writes[0][1], '/api/group/attach');
  assert.equal(writes[0][2].person_id, 19);
  calls=[]; await context.runNew();
  await selection.onNew();
  assert.equal(calls.filter(c=>c[0]==='post').length,0,'Declined same-name override may not write');
  calls=[]; approveHomonym=true;
  await selection.onNew();
  writes=calls.filter(c=>c[0]==='post');
  assert.equal(writes.length,1);
  assert.equal(writes[0][1],'/api/person/create');
  assert.equal(writes[0][2].allow_duplicate_name,true,'Explicit homonym acceptance only');
  calls=[]; roster=[];
  await context.runAttach();
  assert.equal(calls.filter(c=>c[0]==='post').length, 0,'Existing-person picker does not write until confirmation');
  assert(selection.confirmText && selection.confirmLabel,'Existing-person selection requires review');
  assert.equal(selection.allowNew,undefined,'Attach-existing cannot create a person');
  await selection.onChoose({id:9,name:'Alex'});
  writes=calls.filter(c=>c[0]==='post');
  assert.equal(writes.length,1);
  assert.equal(writes[0][1],'/api/group/attach');
  assert.equal(writes[0][2].expected_count,7);
  calls=[]; count=0;
  await context.runNew();
  await context.runAttach();
  assert.equal(calls.filter(c=>c[0]==='post').length,0,'Empty group must not mutate identities');
  console.log('PASS: one-step unique create, exact-name picker, explicit homonym, confirm-gated attach, empty group');
})().catch(e => {console.error(e); process.exitCode=1});
