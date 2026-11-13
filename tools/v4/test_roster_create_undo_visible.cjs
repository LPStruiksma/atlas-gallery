'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function piece(a,b){
  const start=source.indexOf(a),end=source.indexOf(b,start+a.length);
  assert(start>=0&&end>start,'Cannot find actual V4 browser function '+a);
  return source.slice(start,end);
}
const script=piece('const undoButton=()=>','\nfunction updateSelectionCount()')+
  piece('function comparablePersonName(','// Creating a profile with a name already in use')+
  piece('function peopleSearchMatches(','\nasync function returnToPeople()')+
  '\nthis.showPeople=people;';
let reads=0;
const ctx={
  tab:'people',peopleCache:[],peopleQuery:'',peopleListPage:0,
  PEOPLE_PAGE_SIZE:48,lastUndoToken:null,
  get:async()=>{reads++;throw Error('Empty synthetic roster cannot trigger network reads')},
  esc:s=>String(s??''),fmt:n=>String(n),
  btn:(label,action)=>'<button data-action="'+action+'">'+label+'</button>',
  header:(title)=>'<h1>'+title+'</h1>',
  portraitThumb:id=>'/thumb/portrait/'+id
};
vm.createContext(ctx);vm.runInContext(script,ctx);
(async()=>{
  const fresh=await ctx.showPeople();
  assert.match(fresh,/data-action="newperson"/);
  assert.doesNotMatch(fresh,/data-action="undomanual"/,
    'An Undo control with no valid one-use token is misleading');
  ctx.lastUndoToken='fictional-one-use-new-person-undo';
  const afterCreate=await ctx.showPeople();
  assert.match(afterCreate,/data-action="newperson"/);
  assert.match(afterCreate,/data-action="undomanual"/,
    'Person creation returns to People and must immediately expose its Undo');
  assert.match(afterCreate,/Undo last review action/);
  assert.equal(reads,0);
  // The existing global Undo handler owns the server transaction. This
  // change must expose it, not introduce an unaudited duplicate mutation.
  assert.match(source,/if\(a==='undomanual'\).*?post\('\/api\/review\/undo',\{undo_token:token\}\)/);
  console.log('PASS: People roster exposes one-use Undo after create, hides unusable Undo, reuses audited transaction; synthetic-only');
})().catch(e=>{console.error(e);process.exitCode=1});
