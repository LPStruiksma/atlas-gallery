'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
function part(start,end){const a=source.indexOf(start),b=source.indexOf(end,a+start.length);
 assert(a>=0&&b>a,'Expected real V4 browser function '+start);return source.slice(a,b);}
const script=part('function reviewWriteContext(){','async function finishDirectReview(')+
 part('async function renameReviewedPerson(){','async function moveReviewPage(')+
 '\nthis.actions={renameReviewedPerson,mergePeople,restoreMergedPerson};';
const events=[],pending=[],choosers=[];
let rendered=0,confirmations=0;
const ctx={
 tab:'person',page:0,person:7,group:null,personMode:'faces',renderEpoch:5,
 lastUndoToken:null,
 get:url=>new Promise((resolve,reject)=>{
   events.push(['get',url]);pending.push({url,resolve,reject});
 }),
 post:(url,data)=>new Promise((resolve,reject)=>{
   events.push(['post',url,JSON.parse(JSON.stringify(data))]);
   pending.push({url,resolve,reject});
 }),
 toast:message=>events.push(['toast',message]),
 render:async()=>{rendered++},
 promptPersonName:()=> 'Fictional renamed',
 comparablePersonName:name=>name.toLocaleLowerCase().trim(),
 confirm:()=>{confirmations++;return true},
 choosePerson:async options=>{choosers.push(options)},
 keepReviewUndo:result=>{if(result?.undo_token)ctx.lastUndoToken=result.undo_token},
};
vm.createContext(ctx);vm.runInContext(script,ctx);
const switchTo=id=>{ctx.person=id;ctx.renderEpoch++};
const backTo=id=>{ctx.person=id;ctx.renderEpoch++};
const p=()=>{assert(pending.length);return pending.shift()};
const countPosts=()=>events.filter(x=>x[0]==='post').length;
(async()=>{
 // A delayed metadata response after navigating 7->8 must not rename 8.
 let action=ctx.actions.renameReviewedPerson();
 assert.equal(pending.length,1);
 switchTo(8);p().resolve([{id:7,name:'Seven'},{id:8,name:'Eight'}]);
 await action;
 assert.equal(countPosts(),0);
 assert.equal(confirmations,0);
 assert.equal(rendered,0);
 // Same-screen rename writes captured 7. Late response cannot repaint 8.
 backTo(7);action=ctx.actions.renameReviewedPerson();
 p().resolve([{id:7,name:'Seven'}]);
 // Let the cross-VM async function resume after its resolved roster promise.
 await new Promise(setImmediate);
 assert.equal(pending.length,1);
 assert.equal(pending[0].url,'/api/person/rename');
 assert.equal(events.findLast(x=>x[0]==='post')[2].person_id,7);
 switchTo(8);p().resolve({ok:true});
 await action;assert.equal(rendered,0);
 // Pre-chooser merge read is stale: do not open potentially destructive UI.
 backTo(7);action=ctx.actions.mergePeople();
 switchTo(8);p().resolve([{id:7,name:'Seven',faces:3}]);await action;
 assert.equal(choosers.length,0);assert.equal(countPosts(),1);
 // Confirming a chooser after navigation is fail-closed; target freezes at 7.
 backTo(7);action=ctx.actions.mergePeople();
 p().resolve([{id:7,name:'Seven',faces:3},{id:2,name:'Two',faces:4}]);await action;
 assert.equal(choosers.length,1);
 switchTo(8);
 await assert.rejects(choosers[0].onChoose({id:2,name:'Two',faces:4}),/view changed/);
 assert.equal(countPosts(),1);
 // A deliberate confirmed merge uses original target even while a network
 // request is pending; its later response cannot steal new view focus.
 backTo(7);action=ctx.actions.mergePeople();
 p().resolve([{id:7,name:'Seven',faces:3}]);await action;
 let commit=choosers.at(-1).onChoose({id:2,name:'Two',faces:4});
 assert.equal(pending.length,1);
 assert.deepEqual(events.findLast(x=>x[0]==='post').slice(1),[
   '/api/person/merge',{target:7,source:2}]);
 switchTo(8);p().resolve({ok:true,count:4,undo_token:'a'.repeat(32)});await commit;
 assert.equal(rendered,0);assert.equal(ctx.lastUndoToken,'a'.repeat(32));
 // Archived-person restore must not open over a new Person after async GET.
 backTo(7);action=ctx.actions.restoreMergedPerson();
 assert.equal(pending[0].url,'/api/people/merged?target=7');
 switchTo(8);p().resolve([{id:2,name:'Two',faces:4}]);await action;
 assert.equal(choosers.length,2);
 // A displayed restore confirmation is also tied to original target 7.
 backTo(7);action=ctx.actions.restoreMergedPerson();
 p().resolve([{id:2,name:'Two',faces:4}]);await action;
 assert.equal(choosers.length,3);
 switchTo(8);
 await assert.rejects(choosers.at(-1).onChoose({id:2,name:'Two',faces:4}),
                      /view changed/);
 assert.equal(countPosts(),2);
 // Current restore targets exactly person 7; no background screen repaint.
 backTo(7);action=ctx.actions.restoreMergedPerson();
 p().resolve([{id:2,name:'Two',faces:4}]);await action;
 commit=choosers.at(-1).onChoose({id:2,name:'Two',faces:4});
 assert.deepEqual(events.findLast(x=>x[0]==='post').slice(1),[
    '/api/person/restore',{target:7,source:2}]);
 switchTo(8);p().resolve({count:4});await commit;
 assert.equal(rendered,0);
 assert.equal(countPosts(),3,'Stale requests must not create extra People writes');
 assert.equal(pending.length,0);
 console.log('PASS: delayed fictional Person Rename/Merge/Restore cannot target new profile, open stale chooser, repaint newer view or make extra writes');
})().catch(error=>{console.error(error);process.exitCode=1});
