// Renders the BRAND-2 (#638) target frames for #644:
//   node docs/design/brand-2-644/render/render.mjs [outDir] [--only=<frame>]
// Needs Playwright with Chromium and network access to Google Fonts (Instrument Sans,
// IBM Plex Mono, both OFL). Each frame is a 384 × 832 dp viewport at deviceScaleFactor
// 2.8125 = the S24+ default 1080 × 2340 px. The run fails if a font did not load.
import { createRequire } from 'node:module';
import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const root = resolve(args.find((a) => !a.startsWith('--')) ?? join(here, '..'));
const only = args.find((a) => a.startsWith('--only='))?.slice(7);
const THEMES = ['light', 'dark'];
const TARGETS = ['T1-photos', 'T2-viewer', 'T3-same-person'];
const STATES = ['S1-settings', 'S2-dialog', 'S3-empty-album', 'S4-loading', 'S5-large', 'S6-search-keyboard', 'S7-error'];

// [frame, fontScale, annotated, folder]
const jobs = [
  ...TARGETS.flatMap((f) => [[f, 1, true, 'targets'], [f, 2, true, 'targets'], [f, 1, false, 'targets/clean']]),
  ...STATES.map((f) => [f, 1, true, 'states']),
].filter(([f]) => !only || f === only);

const page = pathToFileURL(join(here, 'frames.html')).href;
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 384, height: 832 }, deviceScaleFactor: 2.8125 });
const p = await ctx.newPage();
for (const [f, fs, ann, folder] of jobs) {
  const dir = join(root, folder);
  mkdirSync(dir, { recursive: true });
  for (const theme of THEMES) {
    await p.goto(`${page}?f=${f}&theme=${theme}&fs=${fs}&ann=${ann ? 1 : 0}`);
    await p.waitForFunction(() => document.title !== 'loading', null, { timeout: 30000 });
    const title = await p.title();
    if (title !== 'ready') throw new Error(`${f} ${theme}: ${title}`);
    const file = join(dir, `${f}-${ann ? `fs${fs * 100}-` : ''}${theme}.png`);
    await p.screenshot({ path: file });
    console.log(file);
  }
}
await ctx.close();

// Contact sheets: the annotated frames side by side, one sheet per theme and group.
if (!only) {
  const sheet = await browser.newPage({ viewport: { width: 1800, height: 1000 }, deviceScaleFactor: 1 });
  const groups = {
    targets: { title: 'BRAND-2 targets · Meridian · 100% and 200% font', files: TARGETS.flatMap((f) => [`targets/${f}-fs100`, `targets/${f}-fs200`]) },
    states: { title: 'BRAND-2 state sheet · Meridian · 100% font', files: STATES.map((f) => `states/${f}-fs100`) },
  };
  for (const [name, g] of Object.entries(groups)) {
    for (const theme of THEMES) {
      const bg = theme === 'dark' ? '#0D1117' : '#F5F3EE', ink = theme === 'dark' ? '#EEF1F5' : '#12161C';
      const html = `<!doctype html><html><body style="margin:0;background:${bg};color:${ink};font:500 18px 'IBM Plex Mono',monospace">
        <div style="padding:24px 28px 8px">${g.title} · ${theme} · proposed pre-build targets, not device renders · synthetic media</div>
        <div style="display:flex;flex-wrap:wrap;gap:20px;padding:12px 28px 28px">${g.files.map((f) => `
          <figure style="margin:0;width:270px"><img src="${pathToFileURL(join(root, `${f}-${theme}.png`)).href}" style="width:270px;height:585px;display:block;border-radius:14px;outline:1px solid #6B748066">
          <figcaption style="font-size:12px;padding-top:6px">${f.split('/')[1]}</figcaption></figure>`).join('')}</div></body></html>`;
      const tmp = join(root, `.sheet-${name}-${theme}.html`);
      writeFileSync(tmp, html);
      await sheet.goto(pathToFileURL(tmp).href);
      await sheet.waitForLoadState('load');
      const file = join(root, `${name}-sheet-${theme}.png`);
      await sheet.screenshot({ path: file, fullPage: true });
      rmSync(tmp);
      console.log(file);
    }
  }
}
await browser.close();
