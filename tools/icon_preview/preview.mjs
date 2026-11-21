import { createRequire } from 'module'; const { chromium } = createRequire(import.meta.url)('playwright');
import fs from 'fs';
// Renders the committed adaptive-icon vectors under launcher masks, plus (#656) an annotated
// geometry panel: the r 33 dp safe circle, the 0.77-scaled stroked outer bound and a 48 dp row.
const names = process.argv.slice(2);
const read = f => fs.readFileSync(f,'utf8').replace(/^<svg[^>]*>|<\/svg>$/g,'');
// Themed-icon tints are illustrative stand-ins for a launcher's Material You palette.
const tint = { dark: { bg: '#2E3142', fg: '#C9CEF5' }, light: { bg: '#E3E6FA', fg: '#2A3270' } };
function icon(n, size, shape, mode, wall){
  const L = size*108/72, off = -(L-size)/2;
  const r = shape==='circle'?'50%':shape==='squircle'?'32%':'22%';
  let bg = read(`${n}-bg.svg`), fg = read(`${n}-color-fg.svg`);
  if(mode==='themed'){ bg = `<rect width="108" height="108" fill="${tint[wall].bg}"/>`; fg = read(`${n}-mono-fg.svg`).replace(/#000000|#000/g, tint[wall].fg); }
  return `<div style="width:${size}px;height:${size}px;border-radius:${r};overflow:hidden;position:relative;display:inline-block;margin:10px;vertical-align:middle;box-shadow:0 2px 6px rgba(0,0,0,.35)">
  <svg viewBox="0 0 108 108" width="${L}" height="${L}" style="position:absolute;left:${off}px;top:${off}px">${bg}${fg}</svg></div>`;
}
function label(t){ return `<span style="display:inline-block;vertical-align:middle;margin:0 6px;opacity:.8">${t}</span>`; }
function geometry(n){
  const outer = (Math.hypot(24,30)+4)*0.77; // farthest stroked chevron point from (54,54)
  const at48 = v => (v*48/108).toFixed(2);
  return `<div style="background:#fff;color:#111;padding:10px">
  <b>${n} / geometry</b> · 108 dp canvas, 72 dp visible mask (dashed square), safe circle r 33 dp (green), stroked mark outer bound ${outer.toFixed(2)} dp (red) after the whole-mark 0.77 scale about (54,54)<br>
  <svg viewBox="-4 -4 116 116" width="360" height="360" style="margin:8px;vertical-align:middle;background:#eee">
    ${read(`${n}-bg.svg`)}${read(`${n}-color-fg.svg`)}
    <rect x="18" y="18" width="72" height="72" fill="none" stroke="#000" stroke-width=".4" stroke-dasharray="2 1.5"/>
    <circle cx="54" cy="54" r="33" fill="none" stroke="#19c37d" stroke-width=".6"/>
    <circle cx="54" cy="54" r="${outer}" fill="none" stroke="#ff3b30" stroke-width=".4" stroke-dasharray="1 1"/>
    <line x1="54" y1="54" x2="${54+24*0.77+4*0.77*24/Math.hypot(24,30)}" y2="${54+30*0.77+4*0.77*30/Math.hypot(24,30)}" stroke="#ff3b30" stroke-width=".4"/>
    <text x="56" y="20" font-size="3.6" fill="#0a7">r 33 safe</text>
    <text x="57" y="96" font-size="3.6" fill="#c00">${outer.toFixed(2)} dp</text>
  </svg>
  <span style="display:inline-block;vertical-align:middle;max-width:470px;line-height:1.5">
  Sphere: centre (54,43.22) r 11.55 after scale. Chevron stroke 8 → 6.16 dp.<br>
  At 48 dp: safe radius ${at48(33)} dp, farthest stroked point ${at48(outer)} dp, stroke ${at48(6.16)} dp, sphere r ${at48(11.55)} dp.<br>
  Raster check at 48 dp (1×, 2×, 3× density):<br>
  ${[48,96,144].map(s=>icon(n,s,'circle','color','light')).join('')}<br>
  ${[48,96,144].map(s=>icon(n,s,'squircle','color','light')).join('')}
  </span></div>`;
}
let html = '<body style="margin:0;font:14px sans-serif">';
for (const n of names) {
  for (const wall of ['dark','light']) {
    html += `<div style="background:${wall==='dark'?'#1d2b3a':'#cfd8dc'};padding:10px;color:${wall==='dark'?'#fff':'#000'}">${n} / ${wall} wallpaper<br>`;
    for (const shape of ['squircle','circle','rsquare']) html += icon(n,112,shape,'color',wall);
    html += icon(n,112,'squircle','themed',wall)+icon(n,112,'circle','themed',wall)+label('themed')
      +icon(n,48,'circle','color',wall)+icon(n,48,'squircle','color',wall)+icon(n,48,'circle','themed',wall)+icon(n,48,'squircle','themed',wall)+label('48 dp')+'</div>';
  }
  html += geometry(n);
}
const b = await chromium.launch(); const p = await b.newPage({viewport:{width:900,height:300}, deviceScaleFactor:2});
await p.setContent(html); await p.screenshot({path:'preview.png', fullPage:true}); await b.close();
