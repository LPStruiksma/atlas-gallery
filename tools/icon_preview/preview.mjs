import { createRequire } from 'module'; const { chromium } = createRequire(import.meta.url)('playwright');
import fs from 'fs';
const names = process.argv.slice(2);
const read = f => fs.readFileSync(f,'utf8').replace(/^<svg[^>]*>|<\/svg>$/g,'');
function icon(n, size, shape, mode, wall){
  const L = size*108/72, off = -(L-size)/2;
  const r = shape==='circle'?'50%':shape==='squircle'?'32%':'22%';
  let bg = read(`${n}-bg.svg`), fg = read(`${n}-color-fg.svg`);
  if(mode==='themed'){ bg = `<rect width="108" height="108" fill="${wall==='dark'?'#2B2640':'#E9E3FF'}"/>`; fg = read(`${n}-mono-fg.svg`).replace(/#000000|#000/g, wall==='dark'?'#CFC2FF':'#3D3170'); }
  return `<div style="width:${size}px;height:${size}px;border-radius:${r};overflow:hidden;position:relative;display:inline-block;margin:10px;box-shadow:0 2px 6px rgba(0,0,0,.35)">
  <svg viewBox="0 0 108 108" width="${L}" height="${L}" style="position:absolute;left:${off}px;top:${off}px">${bg}${fg}</svg></div>`;
}
let html = '<body style="margin:0;font:14px sans-serif">';
for (const n of names) for (const wall of ['dark','light']) {
  html += `<div style="background:${wall==='dark'?'#1d2b3a':'#cfd8dc'};padding:10px;color:${wall==='dark'?'#fff':'#000'}">${n} / ${wall}<br>`;
  for (const shape of ['squircle','circle','rsquare']) html += icon(n,112,shape,'color',wall);
  html += icon(n,112,'squircle','themed',wall)+icon(n,56,'squircle','color',wall)+icon(n,40,'circle','color',wall)+icon(n,40,'squircle','themed',wall)+'</div>';
}
const b = await chromium.launch(); const p = await b.newPage({viewport:{width:900,height:300}, deviceScaleFactor:2});
await p.setContent(html); await p.screenshot({path:'preview.png', fullPage:true}); await b.close();
