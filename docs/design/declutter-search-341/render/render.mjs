// Renders the #341 design frames: node docs/design/declutter-search-341/render/render.mjs [outDir] [--clean]
// Needs Playwright with Chromium. Each frame is a 384 × 832 dp viewport at
// deviceScaleFactor 2.8125 = the S24+ 1080 × 2340 px default resolution.
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

const FRAMES = [
  'A-swipe-collapsed', 'A-review-collapsed', 'B-expanded', 'C-keyboard',
  'D-confirm-leave', 'D-search-tab', 'E-swipe-expanded',
  'S1-empty', 'S2-no-match', 'S3-verifying', 'S4-error', 'S5-long',
  'L-landscape-keyboard',
];
const page = pathToFileURL(join(here, 'frames.html')).href;
const browser = await chromium.launch();
for (const f of FRAMES) {
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
