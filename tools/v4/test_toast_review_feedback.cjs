'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=src.indexOf('let toastTimer=null;');
const end=src.indexOf('\nasync function get(u)',start);
assert(start>0&&end>start,'Exercise real review toast implementation, not a duplicate');

// Accessibility and stacking are part of the actual bundled HTML. The picker
// must not paint over crucial errors or the Undo result on small screens.
assert.match(src,/<div id="toast" role="status" aria-live="polite" aria-atomic="true"><\/div>/);
const toastZ=Number(src.match(/#toast\{[^}]*z-index:(\d+)/)?.[1]);
const pickerZ=Number(src.match(/\.overlay\{[^}]*z-index:(\d+)/)?.[1]);
assert(Number.isFinite(toastZ)&&Number.isFinite(pickerZ)&&toastZ>pickerZ,
  'Review feedback must remain visible above an open Person picker');

const element={textContent:'',style:{display:'none'}};
const pending=new Map(),cleared=[];
let nextId=1;
const ctx={
  $:sel=>{assert.equal(sel,'#toast');return element},
  setTimeout:(callback,ms)=>{assert.equal(ms,3500);const id=nextId++;pending.set(id,callback);return id},
  clearTimeout:id=>{cleared.push(id);pending.delete(id)}
};
vm.createContext(ctx);
vm.runInContext(src.slice(start,end)+'\nthis.notify=toast;',ctx);
const runTimer=id=>{const callback=pending.get(id);assert(callback,'Expected live timer');pending.delete(id);callback()};
ctx.notify('2 faces assigned · Undo available');
assert.equal(element.style.display,'block');
assert.equal(pending.size,1);
assert.equal(pending.has(1),true);
ctx.notify('Review action undone · 2 faces restored');
assert.deepEqual(cleared,[1],'New Undo message cancels earlier assignment hide');
assert.equal(pending.has(1),false);
assert.equal(pending.has(2),true);
assert.equal(element.style.display,'block');
assert.match(element.textContent,/Review action undone/);
runTimer(2);
assert.equal(element.style.display,'none');
assert.equal(pending.size,0);
ctx.notify('<synthetic face data must not become HTML>');
assert.equal(element.textContent,'<synthetic face data must not become HTML>');
assert.equal(element.style.display,'block');
runTimer(3);
assert.equal(element.style.display,'none');
console.log('PASS: review feedback stays above picker, announces with stable polite live region, preserves Undo duration across consecutive messages, treats text safely');
