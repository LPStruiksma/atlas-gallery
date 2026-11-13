'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=source.indexOf('async function createReviewedPerson(');
const end=source.indexOf('// Creating a SEPARATE person is cheap',start);
const normalStart=source.indexOf('function comparablePersonName(');
const normalEnd=source.indexOf('// Creating a profile with a name already in use',normalStart);
assert(start>0&&end>start&&normalStart>0&&normalEnd>normalStart);
const requests=[],remembered=[];
let existing=[];
let fixtureKeySeed=0;
const context={
  lastUndoToken:null,
  // Deterministic fictional secure-random stub; production requires Web Crypto.
  crypto:{getRandomValues:bytes=>{const seed=++fixtureKeySeed;for(let i=0;i<bytes.length;i++)bytes[i]=(seed*29+i*17)&255;return bytes}},
  tab:'people',page:0,person:null,group:null,personMode:'photos',renderEpoch:1,
  get:async()=>existing,
  post:async(url,body)=>{
    requests.push([url,body]);
    return {person_id:17,undo_token:'a'.repeat(32)};
  },
  keepReviewUndo:response=>remembered.push(response?.undo_token),
  toast:()=>{},
  choosePerson:async()=>{throw Error('No existing matches, no picker')},
  comparablePersonName:undefined
};
vm.createContext(context);
const guardStart=source.indexOf('function reviewWriteContext(){');
const guardEnd=source.indexOf('async function finishDirectReview(',guardStart);
assert(guardStart>0&&guardEnd>guardStart);
vm.runInContext(source.slice(guardStart,guardEnd)+source.slice(normalStart,normalEnd)+source.slice(start,end)+
    '\nthis.make=createReviewedPerson;',context);
(async()=>{
  let completed=0;
  await context.make('Invented Person',{name:'Invented Person',face_ids:[1,2]},()=>{throw Error('Wrong existing target')},async()=>completed++);
  assert.equal(completed,1);
  assert.equal(requests.length,1);
  assert.equal(requests[0][0],'/api/person/create');
  assert.equal(requests[0][1].allow_duplicate_name,false);
  assert.deepEqual(remembered,['a'.repeat(32)]);
  assert.equal(source.includes("if path=='/api/person/create':v=create_person("),true);
  const createRoute=source.split('\\n').find(line=>
    line.includes("if path=='/api/person/create':v=create_person("));
  assert(createRoute&&createRoute.includes("undo=True,request_id=body.get('request_id'))"),
    'Person creation must still request Undo and use its durable request ID');
  assert.equal(source.includes('Undo last review action'),true);
  console.log('PASS: new People creation retains one-use server undo token and renders review-action undo');
})().catch(e=>{console.error(e);process.exitCode=1});
