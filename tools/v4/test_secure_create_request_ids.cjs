'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function actual(from,to){
  const i=src.indexOf(from),j=src.indexOf(to,i+from.length);
  assert(i>=0&&j>i,'Must execute production browser function '+from);
  return src.slice(i,j);
}
const code=actual('function reviewWriteContext(){','async function finishDirectReview(')+
 actual('function comparablePersonName(name){','// Creating a SEPARATE person is cheap')+
 '\nthis.create=createReviewedPerson;this.newRequestId=newSecurePersonCreateRequestId;';
const writes=[],toasts=[],undo=[],seeds=[];
let counter=0,serverMode='normal';
const ctx={
  lastUndoToken:null,
  tab:'people',page:0,person:null,group:null,personMode:'photos',
  renderEpoch:0,peopleQuery:'',peopleListPage:0,unverifiedPersonCreate:null,
  crypto:{getRandomValues:buf=>{
    assert(ArrayBuffer.isView(buf)&&buf.BYTES_PER_ELEMENT===1,'Web Crypto must receive 16 random bytes');
    assert.equal(buf.length,16);
    let seed=++counter;seeds.push(seed);
    for(let i=0;i<buf.length;i++)buf[i]=(seed*31+i*11)&255;
    return buf;
  }},
  get:async path=>{assert.equal(path,'/api/people?covers=0');return []},
  post:async(path,body)=>{
    assert.equal(path,'/api/person/create');
    writes.push(JSON.parse(JSON.stringify(body)));
    if(serverMode==='lost-reply')throw Error('Synthetic HTTP response lost after commit');
    return {person_id:100+writes.length,undo_token:'f'.repeat(32)};
  },
  keepReviewUndo:result=>undo.push(result.undo_token),
  toast:message=>toasts.push(message),
  confirm:()=>false,
  choosePerson:async()=>{throw Error('Unexpected homonym chooser')},
};
vm.createContext(ctx);vm.runInContext(code,ctx);
(async()=>{
  const one=ctx.newRequestId(),two=ctx.newRequestId();
  assert.match(one,/^[0-9a-f]{32}$/);assert.match(two,/^[0-9a-f]{32}$/);
  assert.notEqual(one,two);
  await ctx.create('Fictional Name A',{name:'Fictional Name A'},
    async()=>{},async()=>{});
  await ctx.create('Fictional Name B',{name:'Fictional Name B'},
    async()=>{},async()=>{});
  assert.equal(writes.length,2);
  assert(writes.every(body=>/^[0-9a-f]{32}$/.test(body.request_id)));
  assert.notEqual(writes[0].request_id,writes[1].request_id,
    'Distinct deliberate Person creations require distinct opaque keys');
  assert.equal(undo.length,2);
  assert.equal(writes[0].allow_duplicate_name,false);

  // If Web Crypto is missing, no weak Math.random/timestamp fallback may
  // silently generate collision-prone IDs and issue a person-create POST.
  const count=writes.length;
  delete ctx.crypto;
  await assert.rejects(ctx.create('Unkeyed',{name:'Unkeyed'},
    async()=>{},async()=>{}),/Secure browser randomness unavailable/);
  assert.equal(writes.length,count);
  ctx.crypto={getRandomValues:()=>{throw Error('Synthetic randomness unavailable')}};
  await assert.rejects(ctx.create('Random Error',{name:'Random Error'},
    async()=>{},async()=>{}),/Synthetic randomness unavailable/);
  assert.equal(writes.length,count);

  // An ambiguous response retains the UI's inspection-before-retry safety.
  ctx.crypto={getRandomValues:buffer=>{buffer.fill(0xc3);return buffer}};
  serverMode='lost-reply';
  await assert.rejects(ctx.create('Ambiguous Fictional',{name:'Ambiguous Fictional'},
    async()=>{},async()=>{}),/Could not verify person creation/);
  assert.equal(writes.length,count+1);
  assert.match(writes.at(-1).request_id,/^(?:c3){16}$/);
  assert.equal(undo.length,2,'No ack means no manufactured Undo token');
  assert(toasts.some(msg=>msg.includes('inspect People before retrying')));
  await ctx.create('Another Fictional',{name:'Another Fictional'},
    async()=>{},async()=>{});
  assert.equal(writes.length,count+1,'Unreviewed retry should not send POST');
  console.log('PASS: real browser create helper uses 128-bit Web Crypto IDs per intent, fails closed without secure randomness, no weak fallback or blind ambiguous retry');
})().catch(error=>{console.error(error);process.exitCode=1});
