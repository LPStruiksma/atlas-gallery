'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=source.indexOf('async function renameReviewedPerson(){');
const end=source.indexOf('async function mergePeople(){',start);
const normalizeStart=source.indexOf('function comparablePersonName(name){');
const normalizeEnd=source.indexOf('// Creating a profile with a name already in use',normalizeStart);
assert(start>0&&end>start&&normalizeStart>0);
const calls=[];
let name='Alice',approval=false;
const ctx={
  person:2,tab:'person',page:0,group:null,personMode:'faces',renderEpoch:1,
  get:async path=>{assert.equal(path,'/api/people?covers=0');return [{id:1,name:'alice',faces:19},{id:2,name:'Beth',faces:6}]},
  promptPersonName:()=>name,
  post:async(path,body)=>{calls.push([path,body]);return {ok:true}},
  toast:()=>{},render:async()=>{},
  confirm:message=>{assert(message.includes('#1'));return approval}
};
vm.createContext(ctx);
const guardStart=source.indexOf('function reviewWriteContext(){');
const guardEnd=source.indexOf('async function finishDirectReview(',guardStart);
assert(guardStart>0&&guardEnd>guardStart,'Execute real review context checks');
vm.runInContext(source.slice(guardStart,guardEnd)+source.slice(normalizeStart,normalizeEnd)+source.slice(start,end)+
 '\nthis.rename=renameReviewedPerson;',ctx);
(async()=>{
  await ctx.rename();
  assert.equal(calls.length,0,'Declined homonym must not POST');
  approval=true;await ctx.rename();
  assert.equal(calls.length,1);
  assert.equal(calls[0][0],'/api/person/rename');
  assert.equal(calls[0][1].person_id,2);
  assert.equal(calls[0][1].allow_duplicate_name,true);
  calls.length=0;name='Beth Revised';await ctx.rename();
  assert.equal(calls.length,1);
  assert.equal(calls[0][1].allow_duplicate_name,false);
  calls.length=0;name=null;await ctx.rename();
  assert.equal(calls.length,0);
  console.log('PASS: rename duplicate browser requires explicit homonym approval, safe cancel and unique-name path');
})().catch(e=>{console.error(e);process.exitCode=1});
