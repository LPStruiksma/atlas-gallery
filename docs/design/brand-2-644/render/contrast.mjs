// Contrast of every text colour against the background it sits on in the BRAND-2 frames.
//   node docs/design/brand-2-644/render/contrast.mjs   (exits 1 if any text pair is below 4.5:1)
const hex = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const over = (fg, a, bg) => '#' + hex(fg).map((c, i) => Math.round(c * a + hex(bg)[i] * (1 - a)).toString(16).padStart(2, '0')).join('');
const lum = (h) => hex(h).map((c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; })
  .reduce((s, c, i) => s + c * [0.2126, 0.7152, 0.0722][i], 0);
const ratio = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((m, n) => n - m); return (x + 0.05) / (y + 0.05); };
const D = { ground: '#0D1117', surface: '#161B23', raised: '#212833', ink: '#EEF1F5', muted: '#A9B1BD', faint: '#8B94A1', accent: '#FFB547' };
const L = { ground: '#F5F3EE', surface: '#FFFFFF', raised: '#ECE8DF', ink: '#12161C', muted: '#4A515C', faint: '#5E6672', accent: '#9A5B00' };
const rows = [];
for (const [theme, t, pill] of [['dark', D, over('#212833', 0.8, D.ground)], ['light', L, over('#FFFFFF', 0.9, L.ground)]]) {
  for (const bg of ['ground', 'surface']) for (const fg of ['ink', 'muted', 'faint', 'accent']) rows.push([theme, fg, bg, t[fg], t[bg]]);
  rows.push([theme, 'ink', 'raised (search field text)', t.ink, t.raised]);
  rows.push([theme, 'ink', 'nav pill (selected tab)', t.ink, pill]);
  rows.push([theme, 'muted', 'nav pill (other tabs)', t.muted, pill]);
}
rows.push(['both', 'on-accent', 'accent-fill', '#1A1206', '#FFB547']);
rows.push(['viewer', 'ink #EEF1F5 icons', 'viewer pill over black', '#EEF1F5', over('#161B23', 0.85, '#000000')]);
rows.push(['viewer', 'favourite #FFB547', 'viewer pill over black', '#FFB547', over('#161B23', 0.85, '#000000')]);
rows.push(['light', 'accent', 'raised (NOT USED: review flag)', L.accent, L.raised]);
let fail = false;
console.log('| Theme | Text | On | Ratio |\n|---|---|---|---|');
for (const [theme, fg, bg, a, b] of rows) {
  const r = ratio(a, b); const unused = bg.includes('NOT USED');
  if (r < 4.5 && !unused) fail = true;
  console.log(`| ${theme} | ${fg} \`${a}\` | ${bg} \`${b}\` | ${r.toFixed(2)}:1${r < 4.5 ? (unused ? ' (below 4.5, so never used)' : ' **FAIL**') : ''} |`);
}
process.exit(fail ? 1 : 0);
