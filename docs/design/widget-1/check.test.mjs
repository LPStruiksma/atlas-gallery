// Geometry, contrast and render checks for the WIDGET-1 preflight (atlas-gallery #846).
// Run: node --test docs/design/widget-1/check.test.mjs
// The render checks need Playwright + Chromium (preinstalled on Claude cloud seats); they
// skip with a message elsewhere. Everything else runs on plain Node 22.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import {
  build, contrast, CAPTION, HERE, FRAME, INSETS, WIDGET, SOURCES, STATES, loadPlaywright,
} from './build.mjs';

const res = build();
const all = [...res.frames, ...res.panels];

test('committed SVGs are exactly what the generator produces', () => {
  assert.equal(readFileSync(join(HERE, 'preflight.svg'), 'utf8'), res.sheet.svg, 'run node docs/design/widget-1/build.mjs --png');
  for (const f of res.frames) assert.equal(readFileSync(join(HERE, 'frames', `${f.id}.svg`), 'utf8'), f.svg(), f.id);
});

test('every frame and panel carries the synthetic caption', () => {
  for (const f of res.frames) assert.ok(f.svg().includes(CAPTION), f.id);
  for (const p of res.panels) assert.ok(p.inner().includes(CAPTION), p.id);
  assert.ok(res.sheet.svg.includes(CAPTION));
});

test('no raster, external reference or script in any SVG', () => {
  for (const svg of [res.sheet.svg, ...res.frames.map((f) => f.svg())]) {
    assert.doesNotMatch(svg, /<image|<script|<foreignObject|href=|https?:\/\/(?!www\.w3\.org\/2000\/svg)/);
  }
});

test('phone frames are S24+ 1080 x 2340 at nominal 360 x 780 dp', () => {
  for (const f of res.frames) {
    assert.equal(f.w * FRAME.pxPerDp, 1080, f.id);
    assert.equal(f.h * FRAME.pxPerDp, 2340, f.id);
    assert.match(f.svg(), /width="1080" height="2340" viewBox="0 0 360 780"/);
  }
});

test('every interactive target is at least 48 x 48 dp', () => {
  let n = 0;
  for (const c of all) for (const h of c.hits) { n++; assert.ok(h.w >= 48 && h.h >= 48, `${c.id} ${h.label} ${h.w}x${h.h}`); }
  assert.ok(n > 100, `expected many targets, got ${n}`);
});

test('all product text meets 4.5:1 on its opaque background', () => {
  let n = 0;
  for (const c of all) for (const t of c.texts) { n++; assert.ok(contrast(t.fg, t.bg) >= 4.5, `${c.id} "${t.s}" ${t.fg}/${t.bg} ${contrast(t.fg, t.bg).toFixed(2)}`); }
  assert.ok(n > 200, `expected many text pairs, got ${n}`);
  assert.ok(res.contrastRows.every((r) => r.ratio >= 4.5));
});

test('Next chevron and radio indicators meet 3:1 non-text contrast', () => {
  for (const c of all) {
    for (const m of c.marks.nextNonText ?? []) assert.ok(contrast(m.fg, m.bg) >= 3, `${c.id} next`);
    for (const m of c.marks.radioNonText ?? []) assert.ok(contrast(m.fg, m.bg) >= 3, `${c.id} radio`);
  }
});

test('launcher widgets use the brief targets: 2x2 160x160, 4x2 328x160, radius 22, Next 48', () => {
  const launch = res.frames.filter((f) => f.kind === 'launcher');
  assert.equal(launch.length, 3);
  for (const f of launch) {
    const sizes = f.widgets.map((w) => `${w.w}x${w.h}`).sort();
    assert.deepEqual(sizes, ['160x160', '328x160'], f.id);
    for (const w of f.widgets) assert.equal(w.rx, WIDGET.radius);
    const next = f.hits.filter((h) => h.label === 'Next photo');
    assert.equal(next.length, 2);
    for (const w of f.widgets) {
      const n = next.find((h) => h.x >= w.x && h.x + h.w <= w.x + w.w && h.y + h.h <= w.y + w.h);
      assert.ok(n, `${f.id}: Next inside widget bounds`);
      assert.ok(w.x + w.w - (n.x + n.w) <= 8 && w.y + w.h - (n.y + n.h) <= 8, 'Next sits bottom-right');
    }
  }
  // label only on the wide widget at 100% type
  for (const c of all) for (const w of c.widgets) if (w.label) { assert.ok(w.w >= 300); assert.equal(w.font, 1); }
});

test('both themes and both type scales are drawn for every widget state', () => {
  const m = res.panels.find((p) => p.id === 'C1');
  for (const s of STATES) {
    for (const th of ['light', 'dark']) for (const f of [1, 2]) {
      assert.ok(m.widgets.some((w) => w.state === s.key && w.theme === th && w.font === f), `${s.key} ${th} ${f}`);
    }
  }
  const hidden = m.widgets.filter((w) => w.state === 'hidden');
  const empty = m.widgets.filter((w) => w.state === 'empty');
  assert.deepEqual(STATES.find((s) => s.key === 'hidden').copy, STATES.find((s) => s.key === 'empty').copy, 'hidden-only must not reveal hidden items');
  assert.equal(hidden.length, empty.length);
  for (const key of ['empty', 'hidden', 'revoked', 'invalid', 'loading']) assert.equal(STATES.find((s) => s.key === key).next, false, `${key} has no Next`);
});

test('configuration: four sources in brief order, Favourites default, CTA anchored and visible', () => {
  const configs = res.frames.filter((f) => f.kind === 'config');
  assert.ok(configs.some((f) => f.theme === 'light' && f.font === 2) && configs.some((f) => f.theme === 'dark' && f.font === 2));
  for (const f of configs) {
    assert.deepEqual(f.marks.sourceOrder, SOURCES, f.id);
    assert.equal(f.marks.rows.length, 4);
    for (const r of f.marks.rows) assert.ok(r.h >= 56, `${f.id} row ${r.label}`);
    if (!f.title.includes('On this day')) assert.equal(f.marks.defaultSource, 'Favourites', f.id);
    const cta = f.marks.cta;
    assert.equal(cta.w, 328); assert.ok(cta.h >= 48); assert.equal(cta.label, 'Add widget');
    assert.ok(cta.y + cta.h <= FRAME.h - INSETS.gestureNav, `${f.id} CTA above gesture inset`);
    assert.equal(cta.x, 16);
  }
  const otd = configs.find((f) => f.title.includes('On this day'));
  assert.ok(otd.texts.some((t) => /21 November in earlier years/.test(t.s)));
  assert.ok(!otd.texts.some((t) => /\b(\d+ years? old|age)\b/i.test(t.s)), 'no ages');
  assert.ok(configs.some((f) => f.marks.scrolls), '200% case scrolls under fixed CTA');
});

test('pickers: search field 48dp, keyboard case keeps preview >= 96dp and CTA above IME', () => {
  const pickers = res.frames.filter((f) => f.kind === 'picker');
  for (const f of pickers) {
    assert.ok(f.marks.search.h >= 48, f.id);
    assert.ok(f.marks.cta.y + f.marks.cta.h <= (f.marks.imeTop ?? FRAME.h - INSETS.gestureNav), `${f.id} CTA visible`);
  }
  const kb = pickers.filter((f) => f.marks.imeTop);
  assert.equal(kb.length, 2);
  for (const f of kb) {
    assert.ok(f.marks.preview.h >= 96, f.id);
    assert.ok(f.marks.preview.y + f.marks.preview.h < f.marks.imeTop);
    assert.equal(f.marks.cta.disabled, false);
  }
  assert.ok(pickers.some((f) => f.title.includes('no results') && f.marks.cta.disabled));
  assert.ok(pickers.some((f) => f.texts.some((t) => /214 people/.test(t.s))), '200+ people');
  assert.ok(pickers.some((f) => f.texts.some((t) => /236 albums/.test(t.s))), '200+ albums');
  assert.ok(pickers.some((f) => f.font === 2), '200% picker');
});

let pw = null;
try { pw = loadPlaywright(); } catch { /* skip render checks */ }

test('rendered text stays inside its layout box (Chromium getBBox)', { skip: pw ? false : 'Playwright not available' }, async () => {
  const browser = await pw.chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
    await page.setContent(`<!doctype html><body style="margin:0">${res.sheet.svg.replace(/^<\?xml[^>]*>/, '')}</body>`);
    await page.evaluate(() => document.fonts.ready);
    const bad = await page.evaluate(() => {
      const out = [];
      for (const el of document.querySelectorAll('text[data-fit]')) {
        const [x, y, w, h] = el.getAttribute('data-fit').split(' ').map(Number);
        const b = el.getBBox();
        const tol = 1.5;
        // vertical: glyph boxes include ascent/descent; allow line-gap slack
        if (b.x < x - tol || b.x + b.width > x + w + tol || b.y < y - 6 || b.y + b.height > y + h + 6) {
          out.push(`${el.textContent.slice(0, 50)} box=${[x, y, w, h].join(',')} bbox=${[b.x, b.y, b.width, b.height].map((v) => v.toFixed(1)).join(',')}`);
        }
      }
      return out;
    });
    assert.deepEqual(bad, []);
  } finally { await browser.close(); }
});

test('exported PNGs exist, are valid and have the expected size', () => {
  const png = (p) => {
    const b = readFileSync(p);
    assert.equal(b.subarray(1, 4).toString(), 'PNG', p);
    return [b.readUInt32BE(16), b.readUInt32BE(20)];
  };
  assert.ok(existsSync(join(HERE, 'preflight.png')));
  const [w] = png(join(HERE, 'preflight.png'));
  assert.equal(w, Math.ceil(res.sheet.w));
  for (const f of res.frames) assert.deepEqual(png(join(HERE, 'frames', `${f.id}.png`)), [1080, 2340], f.id);
});
