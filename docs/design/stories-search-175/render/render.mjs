// Renders the Stories scoped-search design frames for atlas-gallery #797 (current main) and #872 (proposed target):
//   node docs/design/stories-search-175/render/render.mjs [outDir] [--clean] [--only=<frame>] [--legacy|--target]
//   node docs/design/stories-search-175/render/render.mjs --check      # re-measure the target frames, assert the rules
// Needs Playwright with Chromium. Each portrait frame is a 384 × 832 dp viewport at deviceScaleFactor 2.8125 =
// the S24+ 1080 × 2340 px default resolution (landscape 2340 × 1080).
//   frames/*.png         CURRENT MAIN: legacy four-tab pill and header-icon popup (frames.html, #797)
//   frames/target/*.png  PROPOSED TARGET: Quiet Frame v3 slab, L7 search bar above it (target.html, #872)
//   frames/target/metrics.json  every measured number the README quotes; --check fails when a rule breaks
import { createRequire } from 'node:module';
import { mkdirSync, writeFileSync, readFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const clean = args.includes('--clean');
const check = args.includes('--check');
const only = args.find((a) => a.startsWith('--only='))?.slice(7);
const doLegacy = !args.includes('--target') && !check;
const doTarget = !args.includes('--legacy');
const framesDir = resolve(args.find((a) => !a.startsWith('--')) ?? join(here, '..', 'frames'));

export const FRAMES = [
  'A-collapsed', 'B-expanded', 'C1-keyboard-typing', 'C2-no-match',
  'D1-whole-library', 'D2-search-tab', 'D3-back',
  'L-landscape-keyboard',
  'S1-no-stories', 'S2-planning', 'S3-unavailable-items', 'S4-large-text',
  'S5-one-handed', 'S6-playback',
];
export const TARGET_FRAMES = [
  'T1-collapsed', 'T2-typing-ime', 'T3-bottom-results', 'T3b-bottom-results-ime', 'T4-no-match-ime', 'T5-no-match-rest',
  'T6-handoff', 'T7-landscape-ime', 'T8-arrival', 'T9-back',
  'TS1-no-stories', 'TS2-planning', 'TS3-stale-permission', 'TS4-playback-exit',
];
export const SCALES = [1, 2]; // 100 % and 200 % font scale

const browser = await chromium.launch();
const metrics = [];
let failed = 0;

if (doLegacy) {
  mkdirSync(framesDir, { recursive: true });
  const page = pathToFileURL(join(here, 'frames.html')).href;
  for (const f of FRAMES.filter((x) => !only || x === only)) {
    const land = f.startsWith('L-');
    const ctx = await browser.newContext({
      viewport: land ? { width: 832, height: 384 } : { width: 384, height: 832 },
      deviceScaleFactor: 2.8125,
    });
    const p = await ctx.newPage();
    for (const theme of ['light', 'dark']) {
      await p.goto(`${page}?f=${f}&theme=${theme}&ann=${clean ? 0 : 1}${land ? '&land=1' : ''}`);
      await p.waitForFunction(() => document.title === 'ready');
      const file = join(framesDir, `${f}-${theme}.png`);
      await p.screenshot({ path: file });
      console.log(file);
    }
    await ctx.close();
  }
}

if (doTarget) {
  const outDir = check ? mkdtempSync(join(tmpdir(), 'stories-target-')) : join(framesDir, 'target');
  mkdirSync(outDir, { recursive: true });
  const page = pathToFileURL(join(here, 'target.html')).href;
  for (const f of TARGET_FRAMES.filter((x) => !only || x === only)) {
    const land = f === 'T7-landscape-ime';
    const ctx = await browser.newContext({
      viewport: land ? { width: 832, height: 384 } : { width: 384, height: 832 },
      deviceScaleFactor: 2.8125,
    });
    const p = await ctx.newPage();
    for (const fs of SCALES) {
      for (const theme of ['light', 'dark']) {
        await p.goto(`${page}?f=${f}&theme=${theme}&fs=${fs}&ann=${clean ? 0 : 1}${land ? '&land=1' : ''}`);
        await p.waitForFunction(() => document.title === 'ready');
        const m = await p.evaluate(() => window.__metrics);
        metrics.push(m);
        const file = join(outDir, `${f}-${fs * 100}-${theme}.png`);
        if (!check) await p.screenshot({ path: file });
        console.log(check ? `${f} ${fs * 100}% ${theme}` : file);
      }
    }
    await ctx.close();
  }
  // Rules every target frame must satisfy (measured in the DOM, in dp).
  const fail = (m, msg) => { failed++; console.error(`FAIL ${m.frame} ${m.scale * 100}% ${m.theme}: ${msg}`); };
  for (const m of metrics) {
    if (m.minTargetDp < 48 - 0.5) fail(m, `touch target ${m.minTargetDp} dp < 48`);
    if (m.chipLabelsClipped) fail(m, 'a scope chip label is clipped');
    if (m.chipsInsidePanel === false) fail(m, 'a scope chip leaves the panel');
    if (m.panelBelowHeader === false && m.frame !== 'T3b-bottom-results-ime') fail(m, 'search panel covers the header (Back unreachable)');
    if (m.emptyFits === false) fail(m, 'empty-state text does not fit the free region');
    if (m.slabInsideScreen === false) fail(m, 'slab leaves the screen');
    if (m.titleFits === false) fail(m, 'screen title does not fit on one line');
    if (m.slabBottomAboveInsetDp !== undefined && Math.abs(m.slabBottomAboveInsetDp - 12) > 0.5) fail(m, `slab is ${m.slabBottomAboveInsetDp} dp above the inset, expected 12`);
    // T3b: IME raised at the end of the same results. Viable at 100 % only with slab hidden and header scrolled away;
    // at 200 % it is measured infeasible, and the check pins that so the README cannot claim otherwise.
    if (m.frame === 'T3b-bottom-results-ime' && m.scale === 1 && !m.lastTileWhollyClear) fail(m, `100 % IME-raised end of results not clear (${m.lastTileClearAboveStackDp} dp)`);
    if (m.frame === 'T3b-bottom-results-ime' && m.scale === 2 && m.lastTileWhollyClear) fail(m, '200 % IME-raised layout unexpectedly fits: update the README claim');
    if (m.frame === 'T3-bottom-results' && !m.lastTileWhollyClear) fail(m, `last tile not wholly clear (${m.lastTileClearAboveStackDp} dp above the stack)`);
    if (m.frame === 'T1-collapsed' && m.scale === 2 && Math.abs(m.slabWidthDp - 359) > 1) fail(m, `slab ${m.slabWidthDp} dp wide, L4 audit says 359`);
    if (m.scale === 2 && m.panelHeightDp !== undefined && m.chipRows < 2 && !m.land) fail(m, 'chips did not wrap at 200 %');
  }
  const stable = JSON.stringify(metrics.map((m) => ({ ...m, targets: undefined })), null, 1);
  const committed = join(framesDir, 'target', 'metrics.json');
  if (check) {
    let want = '';
    try { want = readFileSync(committed, 'utf8'); } catch { /* missing */ }
    if (want.trim() !== stable.trim()) { failed++; console.error('FAIL metrics.json differs from a fresh measurement; re-run render.mjs and commit it'); }
  } else if (!only) {
    writeFileSync(committed, stable + '\n');
    console.log(committed);
  }
}
await browser.close();
if (failed) { console.error(`${failed} check(s) failed`); process.exit(1); }
if (check) console.log('all target-frame checks passed');
