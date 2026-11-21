#!/usr/bin/env node
// WIDGET-1 preflight generator (atlas-gallery #846, brief rev 1 on #590).
// Synthetic design evidence only: no app code, no real photos, no raster input.
// Every drawn element is generated from the geometry model below so that
// check.test.mjs can assert hit targets, contrast and layout from the same data.
//
//   node docs/design/widget-1/build.mjs          write SVG files
//   node docs/design/widget-1/build.mjs --png    also export PNGs (needs Playwright + Chromium)

import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

export const HERE = dirname(fileURLToPath(import.meta.url));
export const CAPTION = 'Synthetic design, not actual launcher/emulator capture';

// Quiet Frame v3 tokens (docs/design/QUIET_FRAME.md).
export const TOKENS = {
  light: {
    ground: '#FFFFFF', surface: '#F2F3F8', ink: '#0E1030', inkSecondary: '#5A5F7A',
    brandFill: '#2639C9', brandText: '#2639C9', onBrand: '#FFFFFF', onBrandSecondary: '#DCE1FF',
  },
  dark: {
    ground: '#000000', surface: '#14152A', ink: '#F2F3F8', inkSecondary: '#9AA0B8',
    brandFill: '#3448E0', brandText: '#8C9BFF', onBrand: '#FFFFFF', onBrandSecondary: '#DCE1FF',
  },
};
// Annotation layer only (never product UI): magenta callouts on a white label.
const ANN = '#B0126B';
const ANN_BG = '#FFFFFF';
const SANS = 'Funnel Sans, Inter, Arial, sans-serif';
const DISPLAY = 'Funnel Display, Inter Display, Inter, Arial, sans-serif';

// Frame geometry (dp). Nominal 360 dp wide canvas exported at 3 px/dp = 1080 x 2340.
export const FRAME = { w: 360, h: 780, pxPerDp: 3 };
// System insets are ASSUMPTIONS to verify on the device, never hardcoded in the app.
export const INSETS = { statusBar: 36, gestureNav: 20 };
export const GUTTER = 16;
export const WIDGET = { radius: 22, small: { w: 160, h: 160 }, wide: { w: 328, h: 160 }, nextHit: 48, nextDot: 18 };
export const SOURCES = ['Favourites', 'Person', 'Album', 'On this day'];

// Type roles (sp) at 100%. 200% doubles body/secondary (worst case, linear);
// titles use ~1.5x to model Android 14+ nonlinear font scaling (assumption).
const TYPE = {
  title: { size: 38, line: 44, weight: 700, family: DISPLAY, track: -0.03 },
  section: { size: 18, line: 24, weight: 600 },
  body: { size: 15, line: 22, weight: 400 },
  label: { size: 15, line: 22, weight: 600 },
  secondary: { size: 14, line: 20, weight: 400 },
};
function role(name, font) {
  const r = { family: SANS, track: 0, ...TYPE[name] };
  if (font === 1) return r;
  const k = name === 'title' ? 1.5 : 2;
  return { ...r, size: r.size * k, line: r.line * k };
}

// ---------- colour maths (WCAG 2.1) ----------
function chan(v) { v /= 255; return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }
export function luminance(hex) {
  const n = parseInt(hex.slice(1), 16);
  return 0.2126 * chan((n >> 16) & 255) + 0.7152 * chan((n >> 8) & 255) + 0.0722 * chan(n & 255);
}
export function contrast(a, b) {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

// ---------- deterministic pseudo-random ----------
function rng(seed) {
  let s = seed >>> 0;
  return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
}
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const r1 = (n) => Math.round(n * 100) / 100;

// Rough advance-width estimate used for wrapping; the rendered check measures real boxes.
function textWidth(s, size, weight = 400, track = 0) { return s.length * size * ((weight >= 600 ? 0.58 : 0.55) + track); }
function wrap(s, size, w, weight, track = 0) {
  const words = s.split(' ');
  const lines = [];
  let cur = '';
  for (const word of words) {
    const next = cur ? `${cur} ${word}` : word;
    if (cur && textWidth(next, size, weight, track) > w) { lines.push(cur); cur = word; } else cur = next;
  }
  if (cur) lines.push(cur);
  return lines;
}

// ---------- canvas: a drawing surface that also records the geometry model ----------
let uid = 0;
export class Canvas {
  constructor(id, title, { w = FRAME.w, h = FRAME.h, theme = 'light', font = 1, kind = 'panel', phone = false } = {}) {
    Object.assign(this, { id, title, w, h, theme, font, kind, phone });
    this.t = TOKENS[theme];
    this.defs = []; this.body = []; this.ann = [];
    this.hits = []; this.texts = []; this.fits = []; this.widgets = []; this.marks = {};
  }
  rect(x, y, w, h, o = {}) {
    const a = [`x="${r1(x)}" y="${r1(y)}" width="${r1(w)}" height="${r1(h)}"`];
    if (o.rx) a.push(`rx="${o.rx}"`);
    a.push(`fill="${o.fill ?? 'none'}"`);
    if (o.stroke) a.push(`stroke="${o.stroke}" stroke-width="${o.sw ?? 1}"`);
    if (o.dash) a.push(`stroke-dasharray="${o.dash}"`);
    if (o.opacity != null) a.push(`opacity="${o.opacity}"`);
    if (o.clip) a.push(`clip-path="url(#${o.clip})"`);
    if (o.id) a.push(`id="${o.id}"`);
    (o.layer ?? this.body).push(`<rect ${a.join(' ')}/>`);
  }
  circle(cx, cy, r, o = {}) {
    const a = [`cx="${r1(cx)}" cy="${r1(cy)}" r="${r1(r)}" fill="${o.fill ?? 'none'}"`];
    if (o.stroke) a.push(`stroke="${o.stroke}" stroke-width="${o.sw ?? 1}"`);
    if (o.dash) a.push(`stroke-dasharray="${o.dash}"`);
    if (o.opacity != null) a.push(`opacity="${o.opacity}"`);
    (o.layer ?? this.body).push(`<circle ${a.join(' ')}/>`);
  }
  path(d, o = {}) {
    const a = [`d="${d}" fill="${o.fill ?? 'none'}"`];
    if (o.stroke) a.push(`stroke="${o.stroke}" stroke-width="${o.sw ?? 1}" stroke-linecap="round" stroke-linejoin="round"`);
    if (o.dash) a.push(`stroke-dasharray="${o.dash}"`);
    if (o.opacity != null) a.push(`opacity="${o.opacity}"`);
    if (o.clip) a.push(`clip-path="url(#${o.clip})"`);
    (o.layer ?? this.body).push(`<path ${a.join(' ')}/>`);
  }
  raw(s, layer) { (layer ?? this.body).push(s); }
  // Single-line text. Product text passes `bg` so its contrast is recorded and asserted.
  text(x, y, s, o = {}) {
    const size = o.size ?? 15;
    const a = [`x="${r1(x)}" y="${r1(y)}" font-size="${r1(size)}" font-family="${o.family ?? SANS}" font-weight="${o.weight ?? 400}" fill="${o.fill}"`];
    if (o.anchor) a.push(`text-anchor="${o.anchor}"`);
    if (o.track) a.push(`letter-spacing="${r1(o.track * size)}"`);
    if (o.fit) a.push(`data-fit="${o.fit.map(r1).join(' ')}"`);
    (o.layer ?? this.body).push(`<text ${a.join(' ')}>${esc(s)}</text>`);
    if (o.bg) this.texts.push({ s, fg: o.fill, bg: o.bg, size, weight: o.weight ?? 400 });
    if (o.fit) this.fits.push({ s, box: o.fit });
  }
  // Wrapped paragraph with a fit box [x, y, w, h]; returns used height.
  para(x, y, w, s, r, o = {}) {
    const lines = wrap(s, r.size, w, r.weight, r.track ?? 0);
    const shown = o.maxLines ? lines.slice(0, o.maxLines) : lines;
    const h = shown.length * r.line;
    const base = y + r.line - (r.line - r.size) / 2 - r.size * 0.2;
    const tsp = shown.map((ln, i) => `<tspan x="${r1(x)}" y="${r1(base + i * r.line)}">${esc(ln)}</tspan>`).join('');
    const box = o.box ?? [x, y, w, h];
    const a = [`font-size="${r1(r.size)}" font-family="${r.family ?? SANS}" font-weight="${r.weight}" fill="${o.fill}" data-fit="${box.map(r1).join(' ')}"`];
    if (r.track) a.push(`letter-spacing="${r1(r.track * r.size)}"`);
    (o.layer ?? this.body).push(`<text ${a.join(' ')}>${tsp}</text>`);
    if (o.bg) this.texts.push({ s, fg: o.fill, bg: o.bg, size: r.size, weight: r.weight });
    this.fits.push({ s, box });
    return h;
  }
  // A touch target: recorded for the >=48 dp assertion and drawn on the annotation layer.
  hit(x, y, w, h, label, show = true) {
    this.hits.push({ x, y, w, h, label });
    if (!show) return;
    this.rect(x, y, w, h, { stroke: ANN, sw: 1, dash: '3 2', layer: this.ann });
  }
  note(x, y, s, o = {}) {
    const size = o.size ?? 7.5;
    const w = textWidth(s, size, 600) + 6;
    const bx = o.anchor === 'end' ? x - w : x;
    this.rect(bx, y - size - 2, w, size + 5, { fill: ANN_BG, opacity: 0.94, rx: 2, layer: this.ann });
    this.text(bx + 3, y, s, { size, weight: 600, fill: ANN, layer: this.ann });
  }
  dim(x1, y1, x2, y2, label, o = {}) {
    this.path(`M${r1(x1)} ${r1(y1)}L${r1(x2)} ${r1(y2)}`, { stroke: ANN, sw: 0.8, layer: this.ann });
    const tick = (x, y) => (y1 === y2 ? `M${r1(x)} ${r1(y - 3)}V${r1(y + 3)}` : `M${r1(x - 3)} ${r1(y)}H${r1(x + 3)}`);
    this.path(`${tick(x1, y1)}${tick(x2, y2)}`, { stroke: ANN, sw: 0.8, layer: this.ann });
    if (label) this.note(o.lx ?? (x1 + x2) / 2 + 3, o.ly ?? (y1 + y2) / 2 - 2, label, o);
  }
  badge(x, y, n) { // reading-order badge
    this.circle(x, y, 6, { fill: ANN, layer: this.ann });
    this.text(x, y + 2.6, String(n), { size: 7.5, weight: 700, fill: '#FFFFFF', anchor: 'middle', layer: this.ann });
  }
  clipRound(x, y, w, h, rx) {
    const id = `c${++uid}`;
    this.defs.push(`<clipPath id="${id}"><rect x="${r1(x)}" y="${r1(y)}" width="${r1(w)}" height="${r1(h)}" rx="${rx}"/></clipPath>`);
    return id;
  }
  gradient(stops, vertical = true) {
    const id = `g${++uid}`;
    const s = stops.map((c, i) => `<stop offset="${r1(i / (stops.length - 1))}" stop-color="${c}"/>`).join('');
    this.defs.push(`<linearGradient id="${id}" x1="0" y1="0" x2="${vertical ? 0 : 1}" y2="${vertical ? 1 : 0}">${s}</linearGradient>`);
    return id;
  }
  // Standalone SVG at 3 px/dp; or a group for the composite sheet.
  inner() {
    return `<defs>${this.defs.join('')}</defs><g id="${this.id}-design">${this.body.join('')}</g><g id="${this.id}-annotations">${this.ann.join('')}</g>`;
  }
  svg() {
    const label = `${this.id} ${this.title}. ${CAPTION}.`;
    return `<?xml version="1.0" encoding="UTF-8"?>\n<svg xmlns="http://www.w3.org/2000/svg" width="${this.w * FRAME.pxPerDp}" height="${this.h * FRAME.pxPerDp}" viewBox="0 0 ${this.w} ${this.h}" role="img" aria-label="${esc(label)}">\n<title>${esc(label)}</title>\n${this.inner()}\n</svg>\n`;
  }
}

// ---------- synthetic photo placeholders (vector landscapes, no people) ----------
const SCENES = {
  dunes: { sky: ['#F7C77E', '#F29A6B'], sun: '#FFF1C9', hills: ['#C9824F', '#9C5B3A'], water: null },
  lake: { sky: ['#9FD3F2', '#DCEFF8'], sun: '#FFFFFF', hills: ['#5F8F6E', '#2F5D4B'], water: '#4E86B5' },
  dusk: { sky: ['#2C2A6B', '#B05E8C'], sun: '#FFD6A0', hills: ['#3B2E5A', '#1D1838'], water: null },
  forest: { sky: ['#CFE6D8', '#F4F1E0'], sun: '#FFFDF2', hills: ['#3E7A4A', '#24513A'], water: null, trees: true },
  coast: { sky: ['#7FB7E0', '#E8F2F7'], sun: '#FFFFFF', hills: ['#C7B48A', '#8E7A55'], water: '#2F6E9E' },
  city: { sky: ['#F5D0B5', '#8FA5C9'], sun: '#FFE7CF', hills: ['#53607E', '#33405E'], water: null, city: true },
};
function photo(c, ox, oy, ow, oh, scene, clip) {
  const p = SCENES[scene];
  const g = c.gradient(p.sky);
  const cl = clip ? ` clip-path="url(#${clip})"` : '';
  const parts = [];
  parts.push(`<rect x="${r1(ox)}" y="${r1(oy)}" width="${r1(ow)}" height="${r1(oh)}" fill="url(#${g})"/>`);
  parts.push(`<circle cx="${r1(ox + ow * 0.72)}" cy="${r1(oy + oh * 0.3)}" r="${r1(Math.min(ow, oh) * 0.11)}" fill="${p.sun}"/>`);
  if (p.city) {
    const rand = rng(ow * 7 + oh);
    for (let i = 0; i < 9; i++) {
      const bw = ow / 9; const bh = oh * (0.25 + rand() * 0.35);
      parts.push(`<rect x="${r1(ox + i * bw)}" y="${r1(oy + oh - bh)}" width="${r1(bw - 1)}" height="${r1(bh)}" fill="${i % 2 ? p.hills[0] : p.hills[1]}"/>`);
    }
  } else {
    parts.push(`<path d="M${r1(ox)} ${r1(oy + oh * 0.62)}Q${r1(ox + ow * 0.3)} ${r1(oy + oh * 0.42)} ${r1(ox + ow * 0.6)} ${r1(oy + oh * 0.6)}T${r1(ox + ow)} ${r1(oy + oh * 0.52)}V${r1(oy + oh)}H${r1(ox)}Z" fill="${p.hills[0]}"/>`);
    parts.push(`<path d="M${r1(ox)} ${r1(oy + oh * 0.8)}Q${r1(ox + ow * 0.45)} ${r1(oy + oh * 0.6)} ${r1(ox + ow)} ${r1(oy + oh * 0.78)}V${r1(oy + oh)}H${r1(ox)}Z" fill="${p.hills[1]}"/>`);
  }
  if (p.water) parts.push(`<rect x="${r1(ox)}" y="${r1(oy + oh * 0.86)}" width="${r1(ow)}" height="${r1(oh * 0.14)}" fill="${p.water}"/>`);
  if (p.trees) {
    for (let i = 0; i < 7; i++) {
      const tx = ox + ow * (0.06 + i * 0.14); const ty = oy + oh * 0.82;
      parts.push(`<path d="M${r1(tx)} ${r1(ty - oh * 0.28)}L${r1(tx + ow * 0.05)} ${r1(ty)}H${r1(tx - ow * 0.05)}Z" fill="#1E3F2C"/>`);
    }
  }
  c.raw(`<g${cl}>${parts.join('')}</g>`);
}
// Abstract tile for People rows: geometric, never a face.
function personTile(c, x, y, s, seed) {
  const hues = ['#E9B98F', '#9CC3A7', '#A8B5E8', '#E6A6B6', '#D9CB8C', '#93C6D6'];
  const clip = c.clipRound(x, y, s, s, 8);
  const col = hues[seed % hues.length];
  c.raw(`<g clip-path="url(#${clip})"><rect x="${x}" y="${y}" width="${s}" height="${s}" fill="${col}"/><circle cx="${x + s / 2}" cy="${y + s * 0.42}" r="${s * 0.18}" fill="#FFFFFF" opacity="0.55"/><rect x="${x + s * 0.2}" y="${y + s * 0.66}" width="${s * 0.6}" height="${s * 0.5}" rx="${s * 0.25}" fill="#FFFFFF" opacity="0.55"/></g>`);
}

// ---------- reusable pieces ----------
function statusBar(c, onDark) {
  const col = onDark ? '#FFFFFF' : c.t.ink;
  c.text(16, 24, '9:41', { size: 13, weight: 600, fill: col });
  c.rect(318, 15, 22, 11, { rx: 3, stroke: col, sw: 1.2 });
  c.rect(320, 17, 15, 7, { rx: 1.5, fill: col });
  c.note(64, 13, `${CAPTION} · ${c.id}`, { size: 6.5 });
  c.rect(0, 0, c.w, INSETS.statusBar, { stroke: ANN, sw: 0.6, dash: '2 2', layer: c.ann });
  c.note(c.w - 4, 34, `status inset ~${INSETS.statusBar}dp (assumption)`, { size: 6, anchor: 'end' });
}
function navBar(c, onDark) {
  const y = c.h - INSETS.gestureNav;
  c.rect(130, c.h - 10, 100, 4, { rx: 2, fill: onDark ? '#FFFFFF' : c.t.ink, opacity: 0.8 });
  c.rect(0, y, c.w, INSETS.gestureNav, { stroke: ANN, sw: 0.6, dash: '2 2', layer: c.ann });
  c.note(4, y - 2, `gesture inset ~${INSETS.gestureNav}dp (assumption)`, { size: 6 });
}
function wallpaper(c, x, y, w, h, theme) {
  const dark = theme === 'dark';
  const g = c.gradient(dark ? ['#1B1F3A', '#3A2448', '#0F2A33'] : ['#F6B38E', '#B9A3E3', '#7FC4C9']);
  const clip = c.clipRound(x, y, w, h, 0);
  const rand = rng(4242);
  const blobs = [];
  const cols = dark ? ['#5B3B7A', '#1F5F6B', '#7A3B4F', '#2D3F7A', '#6B5A2A'] : ['#FFD9A8', '#F08FA6', '#8FD3C7', '#A7B8F5', '#F4E59A'];
  for (let i = 0; i < 26; i++) {
    blobs.push(`<circle cx="${r1(x + rand() * w)}" cy="${r1(y + rand() * h)}" r="${r1(18 + rand() * 70)}" fill="${cols[i % cols.length]}" opacity="${r1(0.35 + rand() * 0.4)}"/>`);
  }
  for (let i = 0; i < 14; i++) {
    const sx = x + rand() * w; const sy = y + rand() * h;
    blobs.push(`<path d="M${r1(sx)} ${r1(sy)}q${r1(30 + rand() * 40)} ${r1(-30 + rand() * 60)} ${r1(80 + rand() * 60)} 0" stroke="${dark ? '#FFFFFF' : '#2B2B44'}" stroke-width="${r1(1 + rand() * 3)}" fill="none" opacity="0.25"/>`);
  }
  c.raw(`<g clip-path="url(#${clip})"><rect x="${x}" y="${y}" width="${w}" height="${h}" fill="url(#${g})"/>${blobs.join('')}</g>`);
}
const APP_COLS = ['#E8554E', '#F2A541', '#3FA36B', '#2F7FD8', '#8E5BD6', '#E0609E', '#22A3A8', '#6B7280', '#D9822B', '#4C9A2A'];
const APP_NAMES = ['Phone', 'Messages', 'Camera', 'Maps', 'Clock', 'Music', 'Notes', 'Settings', 'Weather', 'Files', 'Calendar', 'Mail'];
function appIcon(c, x, y, i, label = true) {
  if (i === 'atlas') {
    c.rect(x, y, 52, 52, { rx: 16, fill: '#2639C9' });
    c.circle(x + 26, y + 19, 7, { fill: '#FFFFFF' });
    c.path(`M${x + 14} ${y + 40}L${x + 26} ${y + 28}L${x + 38} ${y + 40}`, { stroke: '#FFFFFF', sw: 4 });
  } else {
    c.rect(x, y, 52, 52, { rx: 16, fill: APP_COLS[i % APP_COLS.length] });
    c.circle(x + 26, y + 26, 11, { fill: '#FFFFFF', opacity: 0.85 });
  }
  if (label) {
    const name = i === 'atlas' ? 'Atlas' : APP_NAMES[i % APP_NAMES.length];
    c.text(x + 26, y + 67, name, { size: 11, weight: 500, fill: '#000000', anchor: 'middle', layer: undefined });
    c.text(x + 26, y + 66, name, { size: 11, weight: 500, fill: '#FFFFFF', anchor: 'middle' });
  }
}
// The Next photo control: lapis held sphere with 2 dp white halo, chevron, 48x48 hit.
function nextControl(c, x, y) {
  const cx = x + 24; const cy = y + 24;
  c.circle(cx, cy, WIDGET.nextDot, { fill: c.t.brandFill, stroke: '#FFFFFF', sw: 2 });
  c.path(`M${cx - 3} ${cy - 7}L${cx + 4} ${cy}L${cx - 3} ${cy + 7}`, { stroke: c.t.onBrand, sw: 2.6 });
  c.hit(x, y, 48, 48, 'Next photo', false);
  c.marks.nextNonText = (c.marks.nextNonText ?? []);
  c.marks.nextNonText.push({ fg: c.t.onBrand, bg: c.t.brandFill });
}
// Loading: held sphere climbing an arc (static frame of a real-load indicator).
function loadingSphere(c, x, y, s) {
  c.path(`M${x} ${y + s}A${s} ${s} 0 0 1 ${x + 2 * s} ${y + s}`, { stroke: c.t.inkSecondary, sw: 2, opacity: 0.6 });
  c.circle(x + s - s * Math.cos(Math.PI / 3), y + s - s * Math.sin(Math.PI / 3), 6, { fill: c.t.brandFill });
}

// Widget states and copy (copy is a proposal for review, from brief rev 1).
export const STATES = [
  { key: 'ready', name: 'Ready', copy: null, sub: null, next: true },
  { key: 'loading', name: 'New / first placement, loading', copy: 'Loading photos', sub: null, next: false, loading: true },
  { key: 'empty', name: 'Favourites empty / On this day no matches', copy: 'No photos to show.', sub: 'Tap to open Favourites.', next: false },
  { key: 'hidden', name: 'Hidden-only source (identical to empty on purpose)', copy: 'No photos to show.', sub: 'Tap to open Favourites.', next: false },
  { key: 'revoked', name: 'Permission revoked / media deleted', copy: 'Photos unavailable.', sub: 'Tap to open Atlas.', next: false },
  { key: 'invalid', name: 'Source deleted / invalid URI', copy: 'Choose another source.', sub: 'Tap to pick one.', next: false },
  { key: 'decode', name: 'Decode or refresh failure (no eligible cached photo)', copy: 'Could not show this photo.', sub: 'Tap Next to try another.', next: true },
];

// Draws one launcher widget and records it in the model.
function widget(c, x, y, w, h, o = {}) {
  const state = STATES.find((s) => s.key === (o.state ?? 'ready'));
  const t = c.t;
  const rx = Math.min(WIDGET.radius, h / 2);
  const clip = c.clipRound(x, y, w, h, rx);
  const wide = w >= 300;
  const showLabel = wide && c.font === 1 && o.label && state.key === 'ready';
  c.widgets.push({ x, y, w, h, rx, state: state.key, label: showLabel ? o.label : null, font: c.font, theme: c.theme, source: o.source ?? 'Favourites' });
  if (state.key === 'ready') {
    // centre crop: photo drawn at its original aspect, clipped by the frame
    const aspect = o.aspect ?? 1.5;
    let ow = w; let oh = w / aspect;
    if (oh < h) { oh = h; ow = h * aspect; }
    photo(c, x + (w - ow) / 2, y + (h - oh) / 2, ow, oh, o.scene ?? 'lake', clip);
    if (o.cropGuide) {
      c.rect(x + (w - ow) / 2, y + (h - oh) / 2, ow, oh, { stroke: ANN, sw: 0.8, dash: '5 3', layer: c.ann });
      c.note(Math.max(x + (w - ow) / 2 + 2, 60), y + (h - oh) / 2 - 3, `original ${aspect === 1.5 ? '3:2' : aspect === 0.75 ? '3:4' : aspect.toFixed(2)} photo, centre crop (no distortion)`, { size: 6.5 });
    }
  } else {
    c.rect(x, y, w, h, { rx, fill: t.surface });
    const pad = 16;
    const nextW = state.next ? 52 : 0;
    const tw = w - pad * 2 - (h < 200 && state.next ? nextW : 0);
    const main = role('secondary', c.font);
    const subR = role('secondary', c.font);
    let cy = y + pad;
    if (state.loading) { loadingSphere(c, x + pad, cy, 14); cy += 22; }
    const avail = y + h - pad - cy;
    const mainLines = wrap(state.copy, main.size, tw, 600).length;
    const subLines = state.sub ? wrap(state.sub, subR.size, tw, 400).length : 0;
    const fitsSub = state.sub && (mainLines * main.line + subLines * subR.line) <= avail;
    const usedMain = c.para(x + pad, cy, tw, state.copy, { ...main, weight: 600 }, { fill: t.ink, bg: t.surface, box: [x + pad, cy, tw, avail] });
    cy += usedMain;
    if (fitsSub) c.para(x + pad, cy, tw, state.sub, subR, { fill: t.inkSecondary, bg: t.surface, box: [x + pad, cy, tw, y + h - pad - cy] });
    c.widgets.at(-1).subDropped = Boolean(state.sub && !fitsSub);
  }
  if (showLabel) {
    const lr = role('secondary', 1);
    const lw = textWidth(o.label, lr.size, 600) + 24;
    c.rect(x + 12, y + h - 12 - 32, lw, 32, { rx: 16, fill: t.surface });
    c.text(x + 24, y + h - 12 - 11, o.label, { size: lr.size, weight: 600, fill: t.ink, bg: t.surface, fit: [x + 12, y + h - 44, lw, 32] });
  }
  if (state.next) nextControl(c, x + w - 52, y + h - 52);
  // the whole frame (minus Next) is the open-photo tap target
  c.hit(x, y, w, h, state.next ? 'Open photo (frame minus Next)' : 'Open source', false);
  return { clip };
}

// ---------- A: launcher contexts ----------
function launcher(id, theme, annotated) {
  const c = new Canvas(id, `Launcher home, ${theme}${annotated ? ', annotated' : ''}`, { theme, phone: true, kind: 'launcher' });
  wallpaper(c, 0, 0, 360, 780, theme);
  statusBar(c, true);
  const col = (i) => GUTTER + i * 82; // 4-column grid estimate
  // 4x2 host area
  const hostY = 52;
  if (annotated) {
    c.rect(GUTTER, hostY, 328, 176, { stroke: ANN, sw: 0.8, dash: '4 3', layer: c.ann });
    c.note(GUTTER + 2, hostY - 2, 'host 4×2 bounds estimate 328×176dp: ASSUMPTION, measure on S24+ One UI', { size: 6.2 });
  }
  widget(c, GUTTER, hostY + 8, WIDGET.wide.w, WIDGET.wide.h, { label: 'Favourites', scene: 'lake', cropGuide: annotated, aspect: 1.5 });
  let y = 236;
  for (let i = 0; i < 4; i++) appIcon(c, col(i) + 15, y, i);
  y = 324;
  if (annotated) {
    c.rect(col(0), 316, 164, 176, { stroke: ANN, sw: 0.8, dash: '4 3', layer: c.ann });
    c.note(col(0) + 2, 498, 'host 2×2 estimate 164×176dp', { size: 6.2 });
  }
  widget(c, col(0) + 2, y, WIDGET.small.w, WIDGET.small.h, { source: 'Person', scene: 'dunes', aspect: 0.75, cropGuide: annotated });
  appIcon(c, col(2) + 15, 324, 4); appIcon(c, col(3) + 15, 324, 5);
  appIcon(c, col(2) + 15, 412, 'atlas'); appIcon(c, col(3) + 15, 412, 6);
  for (let i = 0; i < 4; i++) appIcon(c, col(i) + 15, 508, 7 + i);
  for (let i = 0; i < 3; i++) c.circle(168 + i * 12, 604, 3, { fill: '#FFFFFF', opacity: i === 0 ? 1 : 0.5 });
  c.rect(12, 636, 336, 96, { rx: 28, fill: theme === 'dark' ? '#FFFFFF' : '#FFFFFF', opacity: 0.22 });
  for (let i = 0; i < 4; i++) appIcon(c, col(i) + 15, 658, i + 8, false);
  navBar(c, true);
  if (annotated) {
    const W = { x: GUTTER, y: hostY + 8, w: 328, h: 160 };
    c.dim(W.x, W.y + W.h + 6, W.x + W.w, W.y + W.h + 6, '328dp target', { ly: W.y + W.h + 15 });
    c.dim(W.x + W.w + 6, W.y, W.x + W.w + 6, W.y + W.h, '', {});
    c.note(W.x + W.w - 2, W.y + 80, '160dp', { anchor: 'end', size: 6.5 });
    c.path(`M${W.x} ${W.y + 30}A22 22 0 0 1 ${W.x + 22} ${W.y + 8}`, { stroke: ANN, sw: 1.4, layer: c.ann });
    c.note(W.x + 26, W.y + 14, '22dp nominal radius (clamp to host system radius)', { size: 6.5 });
    c.rect(W.x + W.w - 52, W.y + W.h - 52, 48, 48, { stroke: ANN, sw: 1, dash: '3 2', layer: c.ann });
    c.note(W.x + W.w - 54, W.y + W.h - 56, 'Next photo 48×48 hit', { anchor: 'end', size: 6.5 });
    // hatched photo-tap region
    const hid = `h${++uid}`;
    c.defs.push(`<pattern id="${hid}" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="1.2" height="6" fill="${ANN}" opacity="0.45"/></pattern>`);
    c.raw(`<path d="M${W.x} ${W.y}h${W.w}v${W.h - 52}h-52v52h-${W.w - 52}z" fill="url(#${hid})" opacity="0.5" clip-path="url(#${c.clipRound(W.x, W.y, W.w, W.h, 22)})"/>`, c.ann);
    c.note(W.x + 120, W.y + 96, 'photo tap region → opens this photo in Atlas', { size: 6.5 });
    c.note(W.x + 14, W.y + W.h - 48, 'source label: wide size + 100% type only', { size: 6.2 });
    c.rect(col(0) + 2 + 160 - 52, 324 + 160 - 52, 48, 48, { stroke: ANN, sw: 1, dash: '3 2', layer: c.ann });
    c.note(col(0) + 4, 324 + 12, '2×2: no label, photo + Next only', { size: 6.2 });
    c.note(20, 760 - 26, 'Cells 82×88dp are mockup targets. Android doc example (Pixel 4): (73n−16)×(118m−16)dp', { size: 6 });
  }
  return c;
}

// Widget anatomy at 2x for inspection, both themes (panel, not a phone frame).
function anatomy() {
  const c = new Canvas('A4', 'Widget anatomy at 2× (light top, dark bottom)', { w: 760, h: 800, kind: 'anatomy' });
  c.rect(0, 0, 760, 800, { fill: '#FFFFFF' });
  c.text(16, 24, `A4 · Widget anatomy, 2× scale · ${CAPTION}`, { size: 11, weight: 700, fill: '#0E1030', bg: '#FFFFFF' });
  for (const [i, theme] of ['light', 'dark'].entries()) {
    const sub = new Canvas(`A4${theme}`, '', { w: 360, h: 180, theme });
    wallpaper(sub, 0, 0, 360, 180, theme);
    widget(sub, 16, 10, 328, 160, { label: 'On this day', scene: 'coast', aspect: 1.5 });
    const oy = 44 + i * 372;
    c.defs.push(...sub.defs);
    c.raw(`<g transform="translate(16 ${oy}) scale(2)">${sub.body.join('')}</g>`);
    c.texts.push(...sub.texts); c.widgets.push(...sub.widgets); c.hits.push(...sub.hits);
    c.marks.nextNonText = [...(c.marks.nextNonText ?? []), ...(sub.marks.nextNonText ?? [])];
    // annotations in 2x coords
    const X = (v) => 16 + v * 2; const Y = (v) => oy + v * 2;
    c.rect(X(16 + 328 - 52), Y(10 + 160 - 52), 96, 96, { stroke: ANN, sw: 1.2, dash: '4 3', layer: c.ann });
    c.note(X(16 + 328 - 52) - 4, Y(10 + 160 - 52) - 4, '48×48dp hit · 36dp lapis sphere · 2dp white halo', { anchor: 'end', size: 9 });
    c.rect(X(16 + 12), Y(10 + 160 - 44), (textWidth('On this day', 14, 600) + 24) * 2, 64, { stroke: ANN, sw: 1.2, dash: '4 3', layer: c.ann });
    c.note(X(28), Y(10 + 160 - 44) - 4, 'label pill: surface token (opaque), 14/20 sp 600, 32dp high', { size: 9 });
    c.note(X(20), Y(18) + 14, `${theme}: frame radius 22dp nominal; photo fills, no bars`, { size: 9 });
  }
  return c;
}

// ---------- B: configuration ----------
function configHeader(c, title, o = {}) {
  const t = c.t;
  c.rect(0, 0, c.w, c.h, { fill: t.ground });
  statusBar(c, c.theme === 'dark');
  // back button 48x48
  c.path(`M${30} ${52}L${22} ${60}L${30} ${68}M${22} ${60}H${40}`, { stroke: t.ink, sw: 2.2 });
  c.hit(8, 36, 48, 48, 'Back');
  if (o.collapsed) {
    const r = role('section', c.font);
    c.text(64, 66, title, { size: r.size, weight: 600, fill: t.brandText, bg: t.ground, fit: [64, 44, 280, 32] });
    return 88;
  }
  const r = role('title', c.font);
  const h = c.para(GUTTER, 88, 328, title, r, { fill: t.brandText, bg: t.ground });
  return 88 + h + 12;
}
function preview(c, x, y, w, h, o = {}) {
  const t = c.t;
  const clip = c.clipRound(x, y, w, h, 22);
  if (o.empty) {
    c.rect(x, y, w, h, { rx: 22, fill: t.surface });
    c.para(x + 16, y + 16, w - 32, o.emptyText ?? 'No photos to show yet.', role('secondary', c.font), { fill: t.inkSecondary, bg: t.surface });
  } else {
    photo(c, x, y - (w / 1.5 - h) / 2, w, w / 1.5, o.scene ?? 'lake', clip);
    if (o.label && c.font === 1 && h >= 120) {
      const lw = textWidth(o.label, 14, 600) + 24;
      c.rect(x + 12, y + h - 44, lw, 32, { rx: 16, fill: t.surface });
      c.text(x + 24, y + h - 23, o.label, { size: 14, weight: 600, fill: t.ink, bg: t.surface, fit: [x + 12, y + h - 44, lw, 32] });
    }
    const cx = x + w - 28; const cy = y + h - 28;
    c.circle(cx, cy, WIDGET.nextDot, { fill: t.brandFill, stroke: '#FFFFFF', sw: 2 });
    c.path(`M${cx - 3} ${cy - 7}L${cx + 4} ${cy}L${cx - 3} ${cy + 7}`, { stroke: t.onBrand, sw: 2.6 });
  }
  c.marks.preview = { x, y, w, h };
}
function radioRow(c, y, label, sub, selected, chevron) {
  const t = c.t;
  const lr = role('body', c.font);
  const sr = role('secondary', c.font);
  const textX = 64;
  const tw = 360 - textX - GUTTER - (chevron ? 28 : 0);
  const lLines = wrap(label, lr.size, tw, 400).length;
  const sLines = sub ? wrap(sub, sr.size, tw, 400).length : 0;
  const h = Math.max(56, 16 + lLines * lr.line + sLines * sr.line);
  if (selected) {
    c.circle(36, y + h / 2, 10, { fill: t.brandFill, stroke: '#FFFFFF', sw: 2 });
    c.circle(36, y + h / 2, 13, { stroke: t.brandFill, sw: 1.5 });
  } else c.circle(36, y + h / 2, 10, { stroke: t.inkSecondary, sw: 2 });
  c.marks.radioNonText = c.marks.radioNonText ?? [];
  c.marks.radioNonText.push({ fg: selected ? t.brandFill : t.inkSecondary, bg: t.ground });
  let ty = y + (h - lLines * lr.line - sLines * sr.line) / 2;
  ty += c.para(textX, ty, tw, label, lr, { fill: t.ink, bg: t.ground });
  if (sub) c.para(textX, ty, tw, sub, sr, { fill: t.inkSecondary, bg: t.ground });
  if (chevron) c.path(`M${334} ${y + h / 2 - 6}L${340} ${y + h / 2}L${334} ${y + h / 2 + 6}`, { stroke: t.inkSecondary, sw: 2 });
  c.hit(GUTTER, y, 328, h, label);
  c.marks.rows = c.marks.rows ?? [];
  c.marks.rows.push({ label, selected, y, h });
  return h;
}
function cta(c, y, label, o = {}) {
  const t = c.t;
  const r = role('label', c.font);
  const h = Math.max(48, r.line + 20);
  const fill = o.disabled ? t.surface : t.brandFill;
  const fg = o.disabled ? t.inkSecondary : t.onBrand;
  c.rect(GUTTER, y - h, 328, h, { rx: 24, fill });
  c.text(180, y - h / 2 + r.size * 0.36, label, { size: r.size, weight: 600, fill: fg, bg: fill, anchor: 'middle', fit: [GUTTER, y - h, 328, h] });
  c.hit(GUTTER, y - h, 328, h, label);
  c.marks.cta = { x: GUTTER, y: y - h, w: 328, h, label, disabled: Boolean(o.disabled) };
  return h;
}
function scrollEdge(c, y) {
  const t = c.t;
  c.rect(0, y - 12, 360, 12, { fill: t.ground, opacity: 0.85 });
  c.path(`M0 ${y - 0.5}H360`, { stroke: t.inkSecondary, sw: 0.5, opacity: 0.4 });
}

function configMain(id, theme, font, o = {}) {
  const c = new Canvas(id, `Configuration, ${o.otd ? 'On this day selected' : 'Favourites default'}, ${theme}, ${font * 100}% type`, { theme, font, phone: true, kind: 'config' });
  let y = configHeader(c, 'Photo widget');
  const ctaBottom = c.h - INSETS.gestureNav - 16;
  const ph = font === 1 ? 176 : 140;
  preview(c, GUTTER, y, 328, ph, { label: o.otd ? 'On this day' : 'Favourites', scene: o.otd ? 'coast' : 'lake' });
  y += ph + 16;
  const order = [];
  const sel = o.otd ? 'On this day' : 'Favourites';
  const subs = {
    Favourites: null, Person: 'Choose a person', Album: 'Choose an album',
    'On this day': o.otd ? 'Photos from 21 November in earlier years. Uses your phone\'s date.' : null,
  };
  const rowsTop = y;
  for (const s of SOURCES) { order.push(s); y += radioRow(c, y, s, subs[s], s === sel, s === 'Person' || s === 'Album'); }
  c.marks.rowsTop = rowsTop;
  const h = Math.max(48, role('label', c.font).line + 20);
  if (y > ctaBottom - h - 8) {
    scrollEdge(c, ctaBottom - h - 8);
    c.rect(352, 200, 3, 120, { rx: 1.5, fill: c.t.inkSecondary, opacity: 0.6 });
    c.note(350, 196, 'content scrolls under fixed CTA', { anchor: 'end', size: 6.5 });
    c.marks.scrolls = true;
    // cover the overflow so the CTA stays legible
    c.rect(0, ctaBottom - h - 8, 360, c.h - (ctaBottom - h - 8), { fill: c.t.ground });
  }
  cta(c, ctaBottom, 'Add widget');
  c.marks.sourceOrder = order;
  c.marks.defaultSource = sel;
  navBar(c, theme === 'dark');
  if (o.annotate) {
    c.dim(GUTTER, rowsTop - 8, 344, rowsTop - 8, '', {});
    c.note(GUTTER, ctaBottom + 12, 'Add widget 328×48dp, anchored above gesture inset + 16dp', { size: 6.5 });
    c.note(GUTTER + 2, rowsTop + 10, 'rows ≥56dp, whole row is the target', { size: 6.2 });
    // one-handed reach: comfortable thumb arc from bottom-right
    c.path(`M360 ${c.h - 380}A380 380 0 0 0 0 ${c.h - 40}`, { stroke: ANN, sw: 1, dash: '6 4', layer: c.ann });
    c.note(356, c.h - 336, 'one-hand reach (right thumb, approx.): rows + CTA inside', { size: 6.2, anchor: 'end' });
    [[24, 110], [24, 160], [24, rowsTop + 28], [24, rowsTop + 84], [24, rowsTop + 140], [24, rowsTop + 196], [24, ctaBottom - 24]].forEach(([x, yy], i) => c.badge(x - 14, yy, i + 1));
    c.note(GUTTER, y + 16, 'TalkBack order: 1 title → 2 preview → 3–6 sources → 7 Add widget', { size: 6.2 });
  }
  return c;
}

const PEOPLE = [
  ['Ada Example', '1.204 photos'], ['Bram Voorbeeld', '87 photos'], ['Maximiliaan van den Berg-Oosterhuizen', '2.311 photos'],
  ['Mira Sample', '640 photos'], ['Noor Fictief', '45 photos'], ['Olav Demo', '19 photos'], ['Pim Placeholder', '302 photos'],
];
const ALBUMS = [
  ['Autumn walks', '312 photos'], ['Beach week', '98 photos'], ['Camping 2025 with the whole family and the dog', '1.076 photos'],
  ['City breaks', '540 photos'], ['Dunes', '61 photos'], ['Garden', '233 photos'], ['Harbour', '17 photos'],
];
function picker(id, theme, font, kind, o = {}) {
  const isPeople = kind === 'person';
  const title = isPeople ? 'Choose a person' : 'Choose an album';
  const c = new Canvas(id, `${isPeople ? 'Person' : 'Album'} picker${o.keyboard ? ', keyboard open' : ''}${o.zero ? ', no results' : ''}, ${theme}, ${font * 100}% type`, { theme, font, phone: true, kind: 'picker' });
  const t = c.t;
  let y = configHeader(c, title, { collapsed: o.keyboard });
  const ph = o.keyboard ? 96 : 120;
  preview(c, GUTTER, y, 328, ph, { scene: isPeople ? 'dunes' : 'forest', empty: o.zero, emptyText: `Choose ${isPeople ? 'a person' : 'an album'} to see a preview.` });
  y += ph + 12;
  // search field (always visible: 200+ results)
  const sr = role('body', c.font);
  const sh = Math.max(48, sr.line + 16);
  c.rect(GUTTER, y, 328, sh, { rx: 24, fill: t.surface, stroke: o.keyboard ? t.brandFill : null, sw: 2 });
  c.circle(GUTTER + 24, y + sh / 2 - 1, 6, { stroke: t.inkSecondary, sw: 2 });
  c.path(`M${GUTTER + 28} ${y + sh / 2 + 3}l5 5`, { stroke: t.inkSecondary, sw: 2 });
  const query = o.zero ? 'zzq' : o.keyboard ? 'ma' : '';
  c.text(GUTTER + 42, y + sh / 2 + sr.size * 0.36, query || (isPeople ? 'Search people' : 'Search albums'), { size: sr.size, weight: 400, fill: query ? t.ink : t.inkSecondary, bg: t.surface, fit: [GUTTER + 40, y, 280, sh] });
  if (o.keyboard) c.rect(GUTTER + 42 + textWidth(query, sr.size), y + 12, 1.5, sh - 24, { fill: t.brandFill });
  c.hit(GUTTER, y, 328, sh, 'Search');
  c.marks.search = { y, h: sh };
  y += sh + 8;
  const ctaBottom = o.keyboard ? 470 - 8 : c.h - INSETS.gestureNav - 16;
  const imeTop = o.keyboard ? 470 : null;
  const sec = role('secondary', c.font);
  if (o.zero) {
    y += 16;
    y += c.para(GUTTER, y, 328, `No ${isPeople ? 'people' : 'albums'} match “zzq”.`, role('body', c.font), { fill: t.ink, bg: t.ground });
    c.para(GUTTER, y + 4, 328, 'Check the spelling or clear the search.', sec, { fill: t.inkSecondary, bg: t.ground });
    cta(c, ctaBottom, 'Add widget', { disabled: true });
    c.note(GUTTER, ctaBottom - 56, 'Add widget disabled until a source is chosen; Back returns to sources', { size: 6.2 });
  } else {
    const total = isPeople ? '214 people, A to Z' : '236 albums, A to Z';
    const filtered = isPeople ? '12 people match “ma”' : '9 albums match “ma”';
    y += c.para(GUTTER, y, 328, o.keyboard ? filtered : total, sec, { fill: t.inkSecondary, bg: t.ground }) + 4;
    const list = (isPeople ? PEOPLE : ALBUMS).filter((r) => !o.keyboard || /ma/i.test(r[0]) || r === (isPeople ? PEOPLE[3] : ALBUMS[0]));
    const listTop = y;
    const scenes = ['lake', 'dunes', 'forest', 'coast', 'dusk', 'city', 'lake'];
    const limit = ctaBottom - (Math.max(48, role('label', c.font).line + 20)) - 8;
    const selName = isPeople ? 'Mira Sample' : 'Beach week';
    for (const [i, [name, count]] of list.entries()) {
      if (y >= limit) break;
      const nr = role('body', c.font);
      const tw = 328 - 64 - 36;
      const nl = wrap(name, nr.size, tw, 400).length;
      const sl = wrap(count, sec.size, tw, 400).length;
      const rh = Math.max(64, 16 + nl * nr.line + sl * sec.line);
      if (isPeople) personTile(c, GUTTER, y + (rh - 48) / 2, 48, i);
      else { const cl = c.clipRound(GUTTER, y + (rh - 48) / 2, 48, 48, 8); photo(c, GUTTER - 12, y + (rh - 48) / 2, 72, 48, scenes[i], cl); }
      let ty = y + (rh - nl * nr.line - sl * sec.line) / 2;
      ty += c.para(GUTTER + 64, ty, tw, name, nr, { fill: t.ink, bg: t.ground });
      c.para(GUTTER + 64, ty, tw, count, sec, { fill: t.inkSecondary, bg: t.ground });
      if (name === selName) {
        c.circle(330, y + rh / 2, 10, { fill: t.brandFill, stroke: '#FFFFFF', sw: 2 });
        c.circle(330, y + rh / 2, 13, { stroke: t.brandFill, sw: 1.5 });
      }
      c.hit(GUTTER, y, 328, rh, name, i < 2);
      y += rh;
    }
    c.marks.listTop = listTop;
    // fast-scroll held sphere (letter index) for 200+ rows
    if (!o.keyboard) {
      c.circle(334, listTop + 70, 23, { fill: t.brandFill });
      c.text(334, listTop + 70 + 5.5, isPeople ? 'M' : 'B', { size: 15, weight: 600, fill: t.onBrand, bg: t.brandFill, anchor: 'middle' });
      c.hit(310, listTop + 46, 48, 48, 'Fast scroll', true);
    }
    scrollEdge(c, limit);
    c.rect(0, limit, 360, (imeTop ?? c.h) - limit, { fill: t.ground });
    cta(c, ctaBottom, 'Add widget');
    if (o.annotate) {
      c.note(344, listTop + 10, 'rows ≥64dp, image-backed; name A–Z, then stable key', { size: 6.2, anchor: 'end' });
      c.note(344, listTop + 70, 'long name wraps; row grows', { size: 6.2, anchor: 'end' });
      c.note(GUTTER, (o.keyboard ? 92 : c.marks.preview.y) - 4, `preview ${ph}dp${o.keyboard ? ' (min 96dp with IME)' : ''}`, { size: 6.2 });
    }
  }
  if (o.keyboard) {
    // IME block (system-owned)
    c.rect(0, 470, 360, 310, { fill: theme === 'dark' ? '#202124' : '#E4E6EC' });
    const keys = 'qwertyuiop';
    for (let row = 0; row < 4; row++) {
      for (let k = 0; k < 10; k++) {
        c.rect(6 + k * 35, 486 + row * 58, 31, 46, { rx: 6, fill: theme === 'dark' ? '#3C4043' : '#FFFFFF' });
        if (row === 0) c.text(6 + k * 35 + 15.5, 486 + 29, keys[k], { size: 16, fill: theme === 'dark' ? '#FFFFFF' : '#202124', anchor: 'middle' });
      }
    }
    c.rect(0, 470, 360, 310, { stroke: ANN, sw: 0.8, dash: '4 3', layer: c.ann });
    c.note(4, 482, 'IME (system keyboard): height read from insets at runtime, ~310dp here is an assumption', { size: 6.2 });
    c.note(GUTTER, 470 - 6, 'Add widget stays above IME', { size: 6.2 });
    c.marks.imeTop = 470;
  } else navBar(c, theme === 'dark');
  return c;
}

// ---------- C: state matrix ----------
function stateMatrix() {
  const variants = [['light', 1], ['light', 2], ['dark', 1], ['dark', 2]];
  const cellW = 360; const cellH = 196; const left = 200; const top = 70;
  const c = new Canvas('C1', 'Widget state matrix 4×2: light/dark × 100%/200% type', { w: left + variants.length * cellW + 16, h: top + STATES.length * cellH + 20, kind: 'states' });
  c.rect(0, 0, c.w, c.h, { fill: '#FFFFFF' });
  c.text(16, 26, `C1 · Widget states, 4×2 (328×160dp target) · ${CAPTION}`, { size: 14, weight: 700, fill: '#0E1030', bg: '#FFFFFF' });
  variants.forEach(([th, f], i) => c.text(left + i * cellW + 16, 56, `${th === 'light' ? 'Light' : 'Dark'}, ${f * 100}% type`, { size: 12, weight: 600, fill: '#0E1030', bg: '#FFFFFF' }));
  const scenes = ['lake', 'dunes', 'dusk', 'forest', 'coast', 'city', 'lake'];
  STATES.forEach((s, r) => {
    const y = top + r * cellH;
    c.para(16, y + 8, 170, `${r + 1}. ${s.name}`, { size: 12, line: 16, weight: 600, family: SANS }, { fill: '#0E1030', bg: '#FFFFFF' });
    variants.forEach(([th, f], i) => {
      const sub = new Canvas(`C1-${s.key}-${th}-${f}`, '', { w: 360, h: 184, theme: th, font: f });
      wallpaper(sub, 0, 0, 360, 184, th);
      widget(sub, 16, 12, 328, 160, { state: s.key, label: 'Favourites', scene: scenes[r] });
      c.defs.push(...sub.defs);
      c.raw(`<g transform="translate(${left + i * cellW} ${y})"><g>${sub.body.join('')}</g><g>${sub.ann.join('')}</g></g>`);
      for (const tx of sub.texts) c.texts.push({ ...tx, panel: sub.id });
      for (const w of sub.widgets) c.widgets.push({ ...w, panel: sub.id });
      for (const h of sub.hits) c.hits.push({ ...h, panel: sub.id });
      for (const fit of sub.fits) c.fits.push({ ...fit, box: [fit.box[0] + left + i * cellW, fit.box[1] + y, fit.box[2], fit.box[3]], panel: sub.id, shift: [left + i * cellW, y] });
      c.marks.nextNonText = [...(c.marks.nextNonText ?? []), ...(sub.marks.nextNonText ?? [])];
    });
  });
  return c;
}

// Resize, two widgets, removal, accessibility (panel).
function lifecyclePanel() {
  const c = new Canvas('C2', 'Resize, two independent widgets, removal, accessibility', { w: 1640, h: 880, kind: 'lifecycle' });
  c.rect(0, 0, c.w, c.h, { fill: '#FFFFFF' });
  c.text(16, 26, `C2 · Resize, two widgets, removal, accessibility · ${CAPTION}`, { size: 14, weight: 700, fill: '#0E1030', bg: '#FFFFFF' });
  const embed = (sub, x, y) => {
    c.defs.push(...sub.defs);
    c.raw(`<g transform="translate(${x} ${y})"><g>${sub.body.join('')}</g><g>${sub.ann.join('')}</g></g>`);
    for (const tx of sub.texts) c.texts.push({ ...tx, panel: sub.id });
    for (const w of sub.widgets) c.widgets.push({ ...w, panel: sub.id });
    for (const h of sub.hits) c.hits.push({ ...h, panel: sub.id });
    for (const fit of sub.fits) c.fits.push({ ...fit, box: [fit.box[0] + x, fit.box[1] + y, fit.box[2], fit.box[3]], panel: sub.id });
    c.marks.nextNonText = [...(c.marks.nextNonText ?? []), ...(sub.marks.nextNonText ?? [])];
  };
  // Resize row: light 100% and dark 200%
  const sizes = [[160, 160, '2×2'], [246, 160, '3×2'], [328, 160, '4×2'], [328, 246, '4×3']];
  [['light', 1], ['dark', 2]].forEach(([th, f], row) => {
    const y0 = 50 + row * 300;
    c.text(16, y0 + 10, `Resize · ${th}, ${f * 100}% type`, { size: 12, weight: 600, fill: '#0E1030', bg: '#FFFFFF' });
    let x = 16;
    for (const [w, h, name] of sizes) {
      const sub = new Canvas(`C2-${th}-${name}`, '', { w: w + 24, h: 270, theme: th, font: f });
      wallpaper(sub, 0, 0, w + 24, 270, th);
      widget(sub, 12, 12, w, h, { label: 'Favourites', scene: 'coast', aspect: 1.5, cropGuide: row === 0 && name === '4×3' });
      embed(sub, x, y0 + 20);
      c.text(x + 12, y0 + 20 + 270 + 2 - 4, `${name} · ${w}×${h}dp target`, { size: 10, weight: 600, fill: ANN, layer: c.ann });
      x += w + 40;
    }
  });
  c.para(16, 660, 1180, 'Resize: the photo re-crops around the centre (or a valid stable focal point) without distortion. The source label appears only in wide sizes at 100% type; at 200% it is dropped so the photo stays primary. Next keeps a fixed 48×48dp target at every size. Sizes are mockup targets; the host reports real bounds.', { size: 12, line: 17, weight: 400, family: SANS }, { fill: '#0E1030', bg: '#FFFFFF' });
  // Two widgets
  const tx = 1240;
  c.text(tx, 60, 'Two widgets, chosen independently', { size: 12, weight: 600, fill: '#0E1030', bg: '#FFFFFF' });
  for (const [i, th] of ['light', 'dark'].entries()) {
    const sub = new Canvas(`C2-two-${th}`, '', { w: 376, h: 190, theme: th });
    wallpaper(sub, 0, 0, 376, 190, th);
    widget(sub, 12, 12, 160, 160, { source: 'Album', scene: 'forest' });
    widget(sub, 196, 12, 160, 160, { source: 'Person', scene: 'dusk', aspect: 0.75 });
    embed(sub, tx, 76 + i * 206);
    c.text(tx + 14, 76 + i * 206 + 186, 'id 41: Album “Beach week” · id 42: Person (stable key)', { size: 9, weight: 600, fill: ANN, layer: c.ann });
  }
  c.para(tx, 500, 380, 'Each placement keeps its own widget id, source and downscaled cache. Removing one widget deletes only its cache and cancels its pending work; the other keeps showing.', { size: 12, line: 17, weight: 400, family: SANS }, { fill: '#0E1030', bg: '#FFFFFF' });
  // Removal
  c.text(tx, 610, 'Widget removed', { size: 12, weight: 600, fill: '#0E1030', bg: '#FFFFFF' });
  const rm = new Canvas('C2-remove', '', { w: 376, h: 190, theme: 'light' });
  wallpaper(rm, 0, 0, 376, 190, 'light');
  rm.rect(12, 12, 160, 160, { rx: 22, stroke: '#FFFFFF', sw: 2, dash: '6 4' });
  widget(rm, 196, 12, 160, 160, { source: 'Person', scene: 'dusk', aspect: 0.75 });
  embed(rm, tx, 626);
  c.para(tx, 830, 380, 'After removal nothing of the old photo remains on the host or in the per-widget cache. No animation is added by Atlas.', { size: 12, line: 17, weight: 400, family: SANS }, { fill: '#0E1030', bg: '#FFFFFF' });
  return c;
}

// D: accessibility and labels; E: contrast table (both drawn into one panel).
export const TALKBACK = [
  ['Widget photo (ready)', 'Open photo in Atlas. Favourites.', 'button'],
  ['Widget Next control', 'Next photo', 'button'],
  ['Widget empty / hidden-only', 'No photos to show. Open Favourites in Atlas.', 'button'],
  ['Widget revoked', 'Photos unavailable. Open Atlas.', 'button'],
  ['Widget invalid source', 'Choose another source.', 'button'],
  ['Config source row', 'Favourites, radio button, checked, 1 of 4', 'radio'],
  ['Config Person row', 'Person, choose a person, radio button, not checked, 2 of 4', 'radio'],
  ['Picker row', 'Mira Sample, 640 photos, selected', 'radio'],
  ['Add widget', 'Add widget, button', 'button'],
];
function a11yPanel(contrastRows) {
  const c = new Canvas('D1', 'Accessibility labels, reading order, motion, contrast', { w: 1640, h: 540, kind: 'a11y' });
  c.rect(0, 0, c.w, c.h, { fill: '#FFFFFF' });
  const ink = '#0E1030'; const bg = '#FFFFFF';
  c.text(16, 26, `D1 · Accessibility and contrast · ${CAPTION}`, { size: 14, weight: 700, fill: ink, bg });
  c.text(16, 60, 'TalkBack labels (proposed copy)', { size: 12, weight: 700, fill: ink, bg });
  TALKBACK.forEach(([what, label, kind], i) => {
    const y = 84 + i * 22;
    c.text(16, y, what, { size: 11, weight: 600, fill: ink, bg });
    c.text(220, y, `“${label}”`, { size: 11, fill: ink, bg });
    c.text(600, y, kind, { size: 11, fill: '#5A5F7A', bg });
  });
  const notes = [
    'Reading order, widget: photo (open) → Next photo. Config: title → preview → 4 sources → Add widget (badges on B1-annotated).',
    'Photo and Next are separate focusable actions; there are no hidden swipe or tap zones.',
    'Motion: the widget has no ambient animation, so “remove animations” renders identically. Loading sphere shows only during a real load.',
    'Hidden-only and empty share one neutral state so the widget never reveals that hidden items exist.',
    'Every interactive target ≥48dp (asserted from the geometry model). Text contrast ≥4.5:1 on its actual opaque background (table right).',
  ];
  notes.forEach((n, i) => c.para(16, 300 + i * 40, 740, n, { size: 11, line: 15, weight: 400, family: SANS }, { fill: ink, bg }));
  c.text(820, 60, 'Measured contrast of every product text pair in this sheet', { size: 12, weight: 700, fill: ink, bg });
  c.text(820, 82, 'Foreground', { size: 10, weight: 700, fill: '#5A5F7A', bg });
  c.text(960, 82, 'Background', { size: 10, weight: 700, fill: '#5A5F7A', bg });
  c.text(1100, 82, 'Ratio', { size: 10, weight: 700, fill: '#5A5F7A', bg });
  c.text(1180, 82, 'Used for', { size: 10, weight: 700, fill: '#5A5F7A', bg });
  contrastRows.slice(0, 26).forEach((row, i) => {
    const y = 104 + i * 22;
    c.rect(820, y - 12, 14, 14, { fill: row.fg, stroke: '#5A5F7A', sw: 0.5 });
    c.text(840, y, row.fg, { size: 10, fill: ink, bg });
    c.rect(960, y - 12, 14, 14, { fill: row.bg, stroke: '#5A5F7A', sw: 0.5 });
    c.text(980, y, row.bg, { size: 10, fill: ink, bg });
    c.text(1100, y, `${row.ratio.toFixed(2)}:1`, { size: 10, weight: 600, fill: ink, bg });
    c.text(1180, y, row.use, { size: 10, fill: ink, bg });
  });
  return c;
}

// ---------- assemble ----------
export function build() {
  uid = 0;
  const frames = [
    launcher('A1', 'light', false),
    launcher('A2', 'dark', false),
    launcher('A3', 'light', true),
    configMain('B1', 'light', 1, { annotate: true }),
    configMain('B2', 'dark', 1),
    picker('B3', 'light', 1, 'person', { annotate: true }),
    picker('B4', 'dark', 1, 'album'),
    picker('B5', 'light', 1, 'person', { keyboard: true, annotate: true }),
    picker('B6', 'dark', 1, 'album', { keyboard: true }),
    configMain('B7', 'light', 2),
    configMain('B8', 'dark', 2),
    picker('B9', 'light', 2, 'person'),
    picker('B10', 'dark', 1, 'person', { zero: true }),
    configMain('B11', 'light', 1, { otd: true }),
  ];
  const panels = [anatomy(), stateMatrix(), lifecyclePanel()];
  // contrast summary across everything drawn so far
  const all = [...frames, ...panels];
  const seen = new Map();
  for (const c of all) {
    for (const tx of c.texts) {
      const k = `${tx.fg}|${tx.bg}`;
      if (!seen.has(k)) seen.set(k, { fg: tx.fg, bg: tx.bg, ratio: contrast(tx.fg, tx.bg), use: tx.s.slice(0, 40) });
    }
  }
  const contrastRows = [...seen.values()].sort((a, b) => a.ratio - b.ratio);
  const a11y = a11yPanel(contrastRows);
  panels.push(a11y);
  return { frames, panels, contrastRows, sheet: sheet(frames, panels) };
}

function sheet(frames, panels) {
  const gap = 40; const cap = 34; const cols = 7;
  const fw = FRAME.w; const fh = FRAME.h;
  const W = 32 + cols * (fw + gap);
  let y = 110;
  const groups = [];
  const header = [];
  header.push(`<rect x="0" y="0" width="${W}" height="100%" fill="#FFFFFF"/>`);
  header.push(`<text x="32" y="48" font-size="30" font-family="${DISPLAY}" font-weight="700" letter-spacing="-0.9" fill="#2639C9">WIDGET-1 preflight · photo widget</text>`);
  header.push(`<text x="32" y="80" font-size="15" font-family="${SANS}" font-weight="600" fill="#0E1030">${esc(CAPTION)}. Synthetic vector placeholders only. Brief rev 1 on atlas-gallery #590; unit #846. Magenta = annotation layer, not UI.</text>`);
  const rowsOfFrames = [frames.slice(0, 7), frames.slice(7)];
  for (const row of rowsOfFrames) {
    row.forEach((f, i) => {
      const x = 32 + i * (fw + gap);
      groups.push(`<g id="${f.id}" transform="translate(${x} ${y})"><rect x="-1" y="-1" width="${fw + 2}" height="${fh + 2}" fill="none" stroke="#C9CCD8"/><svg x="0" y="0" width="${fw}" height="${fh}" viewBox="0 0 ${fw} ${fh}" overflow="hidden">${f.inner()}</svg><text x="0" y="${fh + 18}" font-size="12" font-family="${SANS}" font-weight="700" fill="#0E1030">${esc(`${f.id} · ${f.title}`)}</text><text x="0" y="${fh + 32}" font-size="10" font-family="${SANS}" fill="#5A5F7A">${esc(CAPTION)}</text></g>`);
    });
    y += fh + cap + gap;
  }
  let px = 32; let rowH = 0;
  for (const p of panels) {
    if (px + p.w > W - 16) { px = 32; y += rowH + gap; rowH = 0; }
    groups.push(`<g id="${p.id}" transform="translate(${px} ${y})"><rect x="-1" y="-1" width="${p.w + 2}" height="${p.h + 2}" fill="none" stroke="#C9CCD8"/>${p.inner()}</g>`);
    px += p.w + gap; rowH = Math.max(rowH, p.h);
  }
  const H = y + rowH + 40;
  return { w: W, h: H, svg: `<?xml version="1.0" encoding="UTF-8"?>\n<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(`WIDGET-1 preflight sheet. ${CAPTION}.`)}">\n<title>${esc(`WIDGET-1 preflight sheet. ${CAPTION}.`)}</title>\n${header.join('\n')}\n${groups.join('\n')}\n</svg>\n` };
}

export function writeAll(out = HERE) {
  const res = build();
  mkdirSync(join(out, 'frames'), { recursive: true });
  for (const f of res.frames) writeFileSync(join(out, 'frames', `${f.id}.svg`), f.svg());
  writeFileSync(join(out, 'preflight.svg'), res.sheet.svg);
  return res;
}

// Playwright may be installed globally (cloud seat) rather than in the repo.
export function loadPlaywright() {
  const req = createRequire(import.meta.url);
  try { return req('playwright'); } catch { /* fall through */ }
  const { execSync } = req('node:child_process');
  const root = execSync('npm root -g').toString().trim();
  return req(join(root, 'playwright'));
}

export async function exportPng(res, out = HERE) {
  const { chromium } = loadPlaywright();
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: Math.ceil(res.sheet.w), height: 1000 }, deviceScaleFactor: 1 });
    await page.setContent(`<!doctype html><html><body style="margin:0;background:#fff">${res.sheet.svg.replace(/^<\?xml[^>]*>/, '')}</body></html>`);
    await page.screenshot({ path: join(out, 'preflight.png'), fullPage: true });
    for (const f of res.frames) {
      const p = await browser.newPage({ viewport: { width: 360, height: 780 }, deviceScaleFactor: 3 });
      await p.setContent(`<!doctype html><html><body style="margin:0">${f.svg().replace(/^<\?xml[^>]*>/, '').replace(/width="1080" height="2340"/, 'width="360" height="780"')}</body></html>`);
      await p.screenshot({ path: join(out, 'frames', `${f.id}.png`), clip: { x: 0, y: 0, width: 360, height: 780 } });
      await p.close();
    }
  } finally { await browser.close(); }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const res = writeAll();
  if (process.argv.includes('--png')) await exportPng(res);
  console.log(`wrote ${res.frames.length} frames, ${res.panels.length} panels, sheet ${res.sheet.w}×${res.sheet.h}`);
}
