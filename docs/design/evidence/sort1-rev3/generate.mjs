// Deterministic, dependency-free SORT-1D synthetic SVG producer.
// Run node docs/design/evidence/sort1-rev3/generate.mjs [--check]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const modes=[["album-date","Album","Family album · 164 photos","date","Date created","Descending",""],["folder-name","Folder","Camera · 2,413 photos","date","File name","Ascending",""],["person-size","Person","People and pets · 301 photos","date","Size","Descending",""],["search-relevance","Search","Results for canal · 234 media","results","Relevance","","canal"],["similar-relevance","Find similar","Similar photos · 18 matches","results","Relevance","","source photo"],["stories-random","Stories","Stories overview · 17 items","random","Random","",""],["trash-size","Trash","Declutter · 84 items","date","Size","Ascending",""],["search-empty","Search","No matching media","results","Relevance","","canal"],["state-matrix","Collections","Fallback states","matrix","","",""],["ime-transition","Search","Query retained","ime","","","canal"],["font200-direction","Album","200% text · scrolled to direction","large-dir","Size","Descending",""],["font200-shuffle","Stories","200% text · scrolled to shuffle","large-random","Random","",""]];
const colors={"light":{"bg":"#FFFFFF","sheet":"#FFFFFF","mist":"#F2F3F8","ink":"#0E1030","sub":"#5A5F7A","fill":"#2639C9","title":"#2639C9","line":"#D8DAE6","tile":"#C5CAE1"},"dark":{"bg":"#000000","sheet":"#000000","mist":"#14152A","ink":"#F2F3F8","sub":"#9AA0B8","fill":"#3448E0","title":"#8C9BFF","line":"#343955","tile":"#333C6C"}};
const standard=["Date created","Date modified","File name","Size","Random"];
function xml(s){return String(s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}
function attrs(m){return Object.entries(m).map(([k,v])=>` ${k}="${xml(v)}"`).join("")}
function R(x,y,w,h,fill,m={}){return `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${m.rx||0}" fill="${fill}"${attrs(Object.fromEntries(Object.entries(m).filter(([k])=>k!=="rx")))}/>`}
function T(x,y,text,size,fill,m={}){return `<text x="${x}" y="${y}" fill="${fill}" font-size="${size}" font-family="${m.family||"Funnel Sans, sans-serif"}" font-weight="${m.weight||400}"${attrs(Object.fromEntries(Object.entries(m).filter(([k])=>k!=="family"&&k!=="weight")))}>${xml(text)}</text>`}
function dot(p,y,active){return `<circle cx="319" cy="${y}" r="9" stroke="${active?p.fill:p.sub}" stroke-width="2" fill="none"/>`+(active?`<circle cx="319" cy="${y}" r="5" fill="${p.fill}"/>`:"")}
function opt(p,y,h,label,kind,active=false,size=15,m={}){return R(15,y,330,h,active?p.mist:p.sheet,{rx:5,"data-target":kind,"data-selected":String(active),...m})+T(32,y+(h+size)/2-3,label,size,p.ink)+(kind==="shuffle"?"":dot(p,y+h/2,active))}
function tiles(p,offset=0){return [[16,148,104,72],[123,148,81,72],[207,148,137,72],[16,223,140,59],[159,223,92,59],[254,223,90,59]].map(([x,y,w,h],i)=>R(x,y+offset,w,h,p.tile,{rx:6,opacity:i%2?".56":".84"})+`<path d="M${x+7} ${y+offset+h-9} l${Math.round(w*.22)} -${Math.round(h*.23)} l${Math.round(w*.19)} ${Math.round(h*.13)} l${Math.round(w*.24)} -${Math.round(h*.29)}" stroke="${p.mist}" stroke-width="3" fill="none"/>`).join("\n")}
function draw(a,theme){const [id,title,sub,kind,selected,direction,query]=a,p=colors[theme],s=[],large=kind.startsWith("large");s.push(`<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="2340" viewBox="0 0 360 780" role="img" aria-labelledby="title desc" data-scene="${id}" data-theme="${theme}" data-synthetic="true" data-density-illustrative="3" data-font-scale="${large?2:1}"${large?' data-scroll-offset-dp="240"':""}${query?` data-query="${xml(query)}"`:""}>`,`<title id="title">${xml(title)} — synthetic ${theme} design proposal</title>`,`<desc id="desc">Coordinate-audited illustrative dp layout. Not a Samsung, Android, emulator or device screenshot. Fallback typography may differ from Funnel font metrics.</desc>`,R(0,0,360,780,p.bg),T(16,large?112:84,title,large?76:38,p.title,{"family":"Funnel Display, sans-serif",weight:700,"letter-spacing":"-0.03em","data-role":"screen-title","data-spec":"38/44sp; 76/88sp at 200%"}),T(16,large?151:118,sub,13,p.sub),R(295,35,52,52,p.mist,{"data-target":"sort",rx:15}),T(303,67,"Sort",14,p.title,{weight:600}));
if(["date","results","random"].includes(kind)){
 if(query)s.push(R(16,132,328,52,p.mist,{rx:12,"data-target":"query"}),T(31,164,query,16,p.ink));
 if(id==="search-empty")s.push(T(18,223,"No matching media",18,p.ink),T(18,252,"Try another search",15,p.sub));else s.push(tiles(p,query?48:0));
 let top=kind==="date"?255:kind==="random"?305:302,start=kind==="date"?310:kind==="random"?374:364;
 s.push(R(0,top,360,757-top,p.bg,{opacity:".72"}),R(0,top,360,757-top,p.sheet,{rx:24,"data-role":"sort-sheet"}),R(157,top+10,46,4,p.line,{rx:2}),T(24,top+43,"Sort by",20,p.ink,{weight:600}));
 (kind==="results"?["Relevance",...standard]:standard).forEach((k,i)=>s.push(opt(p,start+i*52,52,k,"key",selected===k,15,{"data-key":k})));
 if(kind==="date"){s.push(R(24,583,312,1,p.line),T(24,609,"Direction",17,p.ink,{weight:600}));["Ascending","Descending"].forEach((d,i)=>s.push(opt(p,616+i*52,52,d,"direction",direction===d,15,{"data-direction":d})))}
 if(kind==="random")s.push(R(15,655,330,52,p.mist,{rx:7,"data-target":"shuffle"}),T(32,688,"Shuffle again",15,p.ink,{weight:600}));
 if(kind==="results")s.push(T(24,708,"Relevance has no direction",13,p.sub));
}else if(kind==="matrix"){
 s.push(T(16,142,"Pictured loading, retry, grants and huge library",12,p.sub));
 for(const [state,x,y,label] of [["loading",15,159,"Loading"],["error",185,159,"Error and retry"],["revoked",15,446,"Access revoked"],["progressive",185,446,"250k media"]]){
  s.push(`<g data-state="${state}">`,R(x,y,160,274,p.mist,{rx:15}),T(x+12,y+28,label,16,p.ink,{weight:600}));
  if(state==="loading")s.push(`<path d="M${x+27} ${y+137} A57 57 0 0 1 ${x+136} ${y+137}" stroke="${p.line}" stroke-width="5" fill="none"/>`,`<circle cx="${x+106}" cy="${y+94}" r="17" stroke="white" stroke-width="2" fill="${p.fill}"/>`,T(x+13,y+182,"7,140 / 12,305",13,p.ink),R(x+12,y+202,136,12,p.line,{rx:5}),R(x+12,y+202,83,12,p.fill,{rx:5}));
  if(state==="error")s.push(R(x+15,y+76,130,76,p.bg,{rx:11}),T(x+24,y+109,"Couldn't load",14,p.ink),T(x+24,y+130,"No changes made",11,p.sub),R(x+15,y+174,130,52,p.fill,{rx:10,"data-target":"retry"}),T(x+57,y+208,"Retry",15,"#FFFFFF",{weight:600}));
  if(state==="revoked")s.push(R(x+15,y+76,130,76,p.bg,{rx:11}),T(x+22,y+108,"Media hidden",14,p.ink),T(x+22,y+130,"Scope protected",11,p.sub),R(x+15,y+174,130,52,p.fill,{rx:10,"data-target":"grant"}),T(x+39,y+208,"Allow access",14,"#FFFFFF",{weight:600}));
  if(state==="progressive"){s.push(T(x+13,y+66,"18,400 of 250,000",11,p.sub));for(let i=0;i<6;i++)s.push(R(x+12+(i%3)*46,y+78+Math.floor(i/3)*54,43,50,p.tile,{rx:4}));s.push(R(x+12,y+208,136,10,p.line,{rx:5}),R(x+12,y+208,55,10,p.fill,{rx:5}),T(x+12,y+243,"Load as you scroll",11,p.sub))}
  s.push("</g>");
 }
}else if(kind==="ime"){
 [["keyboard","Keyboard visible"],["dismissed","IME dismissed"],["sheet","Sort opened"]].forEach(([state,label],i)=>{let y=142+205*i;s.push(`<g data-transition="${state}">`,R(16,y,328,187,p.mist,{rx:15}),T(28,y+23,(i+1)+" · "+label,15,p.ink,{weight:600}),R(28,y+35,304,43,p.bg,{rx:9}),T(43,y+63,"canal",16,p.ink));
 if(state==="keyboard"){s.push(R(28,y+87,304,90,p.line,{rx:8}));for(let k=0;k<21;k++)s.push(R(35+(k%7)*42,y+96+Math.floor(k/7)*27,36,20,p.bg,{rx:4}))}
 if(state==="dismissed"){s.push(T(30,y+113,"234 matching media · query retained",13,p.ink));for(let k=0;k<3;k++)s.push(R(30+k*101,y+126,98,43,p.tile,{rx:5}))}
 if(state==="sheet")s.push(R(28,y+87,304,87,p.bg,{rx:12}),T(40,y+108,"Sort by",14,p.ink,{weight:600}),R(39,y+116,282,48,p.mist,{rx:6}),T(51,y+145,"Relevance selected",14,p.ink));
 s.push("</g>")});s.push(T(16,767,"Full six-key list shown in search-relevance",11,p.sub));
}else if(large){
 s.push(tiles(p),R(0,287,360,470,p.bg,{opacity:".77"}),R(0,287,360,470,p.sheet,{rx:24}),R(157,297,46,4,p.line,{rx:2}),T(24,330,"Sort by",25,p.ink,{weight:600}),T(24,348,"Scrolled to terminal controls",12,p.sub));
 if(kind==="large-dir")s.push(opt(p,356,72,"Size","key",true,30,{"data-key":"Size"}),opt(p,428,72,"Random","key",false,30,{"data-key":"Random"}),T(24,531,"Direction",23,p.ink,{weight:600}),opt(p,544,72,"Ascending","direction",false,30,{"data-direction":"Ascending"}),opt(p,616,72,"Descending","direction",true,30,{"data-direction":"Descending"}));
 else s.push(opt(p,438,72,"Random","key",true,30,{"data-key":"Random"}),R(15,590,330,72,p.mist,{rx:9,"data-target":"shuffle"}),T(28,635,"Shuffle again",30,p.ink));
 s.push(R(349,353,4,338,p.line,{rx:2}),R(349,566,4,92,p.fill,{rx:2}),T(24,738,"Focus and swipe reach end",12,p.sub));
}
s.push(R(0,757,360,23,p.bg),R(126,768,108,4,p.ink,{rx:2}),"</svg>");
return s.join("\n")+"\n"}
const root=path.dirname(fileURLToPath(import.meta.url));
let stale=0;
for(const scene of modes)for(const theme of ['light','dark']){const file=path.join(root,scene[0]+'-'+theme+'.svg'),expected=draw(scene,theme);if(process.argv.includes('--check')){if(!fs.existsSync(file)||fs.readFileSync(file,'utf8')!==expected){stale++;console.error('STALE '+file)}}else fs.writeFileSync(file,expected)}
if(stale)process.exitCode=1;
else console.log((process.argv.includes('--check')?'Checked':'Wrote')+' '+(modes.length*2)+' synthetic SVGs');
