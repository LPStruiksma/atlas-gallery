'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const first=src.indexOf("document.addEventListener('click',async e=>{");
const end=src.indexOf('\nasync function init(){',first);
assert(first>=0&&end>first,'Must execute the production delegated click handler');
const calls=[],messages=[];
let onAttach=async()=>{throw Error('Synthetic response lost AFTER SQLite commit')},handler=null;
const ctx={
  document:{addEventListener:(type,fn)=>{
    assert.equal(type,'click');assert.equal(handler,null);handler=fn;
  }},
  attachWholeGroup:async id=>{calls.push(id);await onAttach()},
  toast:s=>messages.push(s),
};
vm.createContext(ctx);
vm.runInContext(src.slice(first,end),ctx);
assert.equal(typeof handler,'function');
const candidateClick=id=>({target:{
  closest:selector=>selector==='[data-candidate-person]'?
    {dataset:{candidatePerson:String(id)}}:null
}});
(async()=>{
  // A suggested Person tap may already have attached the entire group
  // before its network response disappears; don't falsely report rollback.
  await handler(candidateClick(42));
  assert.deepEqual(calls,[42]);
  assert.match(messages.at(-1),
    /Could not verify group attachment: Synthetic response lost AFTER SQLite commit · inspect People before retrying/);
  assert(messages.every(msg=>!msg.includes('No changes')),
    'A lost acknowledgement must not imply no database changes');

  // A pre-write failure is also conservatively uncertain; there is no
  // auto-retry in the delegated handler or invented Undo token.
  onAttach=async()=>{throw Error('Synthetic group lookup refused')};
  await handler(candidateClick(43));
  assert.deepEqual(calls,[42,43]);
  assert.match(messages.at(-1),/inspect People before retrying/);

  // Successful explicit click should show no uncertain toast at all.
  const prior=messages.length;
  onAttach=async()=>{};
  await handler(candidateClick(44));
  assert.deepEqual(calls,[42,43,44]);
  assert.equal(messages.length,prior);
  console.log('PASS: production suggested-person group click reports uncertain post-commit failures safely without false rollback, automatic resubmission or misleading success');
})().catch(e=>{console.error(e);process.exitCode=1});
