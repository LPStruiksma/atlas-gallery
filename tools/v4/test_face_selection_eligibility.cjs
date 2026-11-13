'use strict';
const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const begin=source.indexOf('function faceTile(f,selectable=true){');
const end=source.indexOf('\nfunction photoTile(p){',begin);
assert(begin>0&&end>begin,'Face tile generator changed unexpectedly');
const ctx={
  tab:'similar',personMode:'faces',person:11,
  selected:new Set(),faceThumb:id=>'/face/'+id
};
vm.createContext(ctx);
vm.runInContext(source.slice(begin,end)+'\nthis.makeFaceTile=faceTile;',ctx);
const face=(id,person_id=null,ignored=false)=>({id,person_id,ignored,score:0.78});
const checkbox=html=>html.includes('data-select=');
const cover=html=>html.includes('data-cover-face=');
const assigned=face(10,12);
const unknown=face(11,null);

assert(!checkbox(ctx.makeFaceTile(assigned)),'Already-tagged lookalike may not be selected for assignment');
assert(!cover(ctx.makeFaceTile(assigned)));
assert.match(ctx.makeFaceTile(assigned),/Already tagged to profile #12 · inspect only/);
assert.match(ctx.makeFaceTile(assigned),/data-face="10"/,'Assigned face stays inspectable');
assert(checkbox(ctx.makeFaceTile(unknown)),'Unassigned lookalike is selectable');
assert(!cover(ctx.makeFaceTile(unknown)),'Similar tab cannot change portraits');

ctx.tab='group';
assert(!checkbox(ctx.makeFaceTile(assigned)),'Historical group must not bulk-select someone already tagged');
assert(checkbox(ctx.makeFaceTile(unknown)),'Remaining unassigned group face stays selectable');
assert(!checkbox(ctx.makeFaceTile(face(15,null,true))),'Ignored group face is not assignable');
ctx.tab='untagged';
assert(checkbox(ctx.makeFaceTile(unknown)));
assert(!checkbox(ctx.makeFaceTile(assigned)));

ctx.tab='person';ctx.personMode='faces';ctx.person=11;
const owned=face(17,11);
assert(checkbox(ctx.makeFaceTile(owned)),'Current profile Review tags must allow selected face removal');
assert(cover(ctx.makeFaceTile(owned)),'Portrait selection remains deliberate');
assert(!checkbox(ctx.makeFaceTile(assigned)),'Another profile face must not become removable');
assert(!cover(ctx.makeFaceTile(assigned)));
assert(!checkbox(ctx.makeFaceTile(face(18,11,true))));
ctx.selected.add(17);
assert.match(ctx.makeFaceTile(owned),/facepick selected/);
assert.match(ctx.makeFaceTile(owned),/checked/);
assert(checkbox(ctx.makeFaceTile(owned,false))===false,'Photo-inspection-only mode should not select');
assert(!cover(ctx.makeFaceTile(owned,false)));
ctx.selected.clear();

ctx.personMode='suggested';
assert(checkbox(ctx.makeFaceTile(unknown)),'Review suggestions needs explicit selection');
assert(!checkbox(ctx.makeFaceTile(owned)),'Already confirmed face cannot be suggested again');
assert(!cover(ctx.makeFaceTile(unknown)));
assert(source.includes("ignored=bool(row['ignored'])"),
  'Live face APIs must expose ignored state for correct selection eligibility');
console.log('PASS: fictional tagged faces inspect-only outside owned Review tags; page-local assignment, hide and portrait choices remain separate');
