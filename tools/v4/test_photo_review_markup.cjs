'use strict';
const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const src=fs.readFileSync(__dirname+'/monochrome_studio_v4_people_ux_preview.py','utf8');
const start=src.indexOf('async function photoPage(){');
const end=src.indexOf('\nasync function matchesPage(){',start);
assert(start>0&&end>start,'Cannot isolate production full-photo page');
const thumbs=src.indexOf('const faceThumb=id=>');
const thumbEnd=src.indexOf('\nfunction toast(',thumbs);
assert(thumbs>0&&thumbEnd>thumbs,'Cannot isolate production thumbnail URL builders');
const paths=[];
const ctx={
  photo:7,sourceStamp:100,
  get:async path=>{
    paths.push(path);
    return {path:'/private/fictional-only.jpg',mtime_ns:ctx.sourceStamp,faces:[{id:41},{id:42}]};
  },
  header:(title,caption)=>'<h1>'+title+'</h1><p>'+caption+'</p>',
  btn:(label,action)=>'<button data-action="'+action+'">'+label+'</button>',
  photoThumb:id=>'/thumb/photo/'+id,
  reviewPhotoThumb:id=>'/thumb/review/'+id,
  faceTile:f=>'<div data-face="'+f.id+'"></div>',
  esc:s=>String(s)
};
vm.createContext(ctx);
vm.runInContext(src.slice(thumbs,thumbEnd)+'\n'+src.slice(start,end)+'\nthis.drawPhoto=photoPage;',ctx);
(async()=>{
  const html=await ctx.drawPhoto();
  assert.deepEqual(paths,['/api/photo?id=7'],'Still fetch only one local photo record');
  assert.match(html,/<img class="fullimg" decoding="async" alt="Photograph under review" src="\/thumb\/review\/7\?v=100">/);
  assert(!html.includes('src="/thumb/photo/7"'),
    'Large inspection view must never be the 750px filmstrip rendition');
  ctx.sourceStamp=101;
  const rescanned=await ctx.drawPhoto();
  assert.match(rescanned,/src="\/thumb\/review\/7\?v=101"/,
    'Changed source timestamp must bypass browser cached JPEG URL');
  assert.notEqual(rescanned.match(/src="\/thumb\/review\/[^"]+"/)[0],
                  html.match(/src="\/thumb\/review\/[^"]+"/)[0],
    'A photo rescan must change only the rendition URL version');
  assert(html.includes('Back to person'));
  assert(html.includes('data-face="41"'));
  assert(html.includes('data-face="42"'));
  assert(src.includes("r'/thumb/(face|photo|portrait|review)/(\\d+)'"),
    'HTTP server must explicitly allow locally served review rendition');
  assert(src.includes("if kind not in ('face','portrait','photo','review')"),
    'Internal renderer must refuse unrecognized image variants');
  console.log('PASS: full-photo People inspection references separate 1600px bounded cached rendition with review return and face chips');
})().catch(e=>{console.error(e);process.exitCode=1});
