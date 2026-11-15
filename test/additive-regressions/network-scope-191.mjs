import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

// #191 (parent #183): INTERNET exists only for explicitly user-initiated AI Edit.
// Background intelligence (scanner, People, visual index, search) stays on-device,
// so no Kotlin source outside featureaiedit/ may open network connections.
const root = fileURLToPath(new URL('../../', import.meta.url));
const src = join(root, 'app/src/main/java');
const ALLOWED_DIR = 'nl/atlas/gallery/featureaiedit/';
const NETWORK = [
  /^\s*import\s+java\.net\.(?!URI\b|URLEncoder\b|URLDecoder\b)\w+/m,
  /^\s*import\s+javax\.net\./m,
  /^\s*import\s+(okhttp3|io\.ktor|retrofit2|com\.android\.volley)\./m,
  /\bopenConnection\s*\(/,
  /\bjava\.net\.(Socket|URL|HttpURLConnection|DatagramSocket)\b/,
];

function kotlinFiles(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...kotlinFiles(p));
    else if (name.endsWith('.kt')) out.push(p);
  }
  return out;
}

test('manifest declares INTERNET with the AI Edit scope comment', () => {
  const manifest = readFileSync(join(root, 'app/src/main/AndroidManifest.xml'), 'utf8');
  assert.match(manifest, /android\.permission\.INTERNET/);
  assert.match(manifest, /usesCleartextTraffic="false"/);
});

test('only featureaiedit/ sources may use the network', () => {
  const offenders = [];
  for (const file of kotlinFiles(src)) {
    const rel = relative(src, file).split('\\').join('/');
    if (rel.startsWith(ALLOWED_DIR)) continue;
    const text = readFileSync(file, 'utf8');
    if (NETWORK.some((re) => re.test(text))) offenders.push(rel);
  }
  assert.deepEqual(offenders, [], `network use outside ${ALLOWED_DIR}`);
});
