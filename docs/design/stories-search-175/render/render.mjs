// Renders the FIND-6 (#797) Stories scoped-search design frames:
//   node docs/design/stories-search-175/render/render.mjs [outDir] [--clean] [--only=<frame>]
// Needs Playwright with Chromium. Each portrait frame is a 384 × 832 dp viewport at
// deviceScaleFactor 2.8125 = the S24+ 1080 × 2340 px default resolution (landscape 2340 × 1080).
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const clean = args.includes('--clean');
const out = resolve(args.find((a) => !a.startsWith('--')) ?? join(here, '..', 'frames'));
mkdirSync(out, { recursive: true });

export const FRAMES = [
  'A-collapsed', 'B-expanded', 'C1-keyboard-typing', 'C2-no-match',
  'D1-whole-library', 'D2-search-tab', 'D3-back',
  'L-landscape-keyboard',
  'S1-no-stories', 'S2-planning', 'S3-unavailable-items', 'S4-large-text',
  'S5-one-handed', 'S6-playback',
];
const only = args.find((a) => a.startsWith('--only='))?.slice(7);
const page = pathToFileURL(join(here, 'frames.html')).href;
const browser = await chromium.launch();
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
    const file = join(out, `${f}-${theme}.png`);
    await p.screenshot({ path: file });
    console.log(file);
  }
  await ctx.close();
}
await browser.close();
