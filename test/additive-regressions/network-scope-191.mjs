import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

// #191 (parent #183): INTERNET exists only for explicitly user-initiated AI Edit.
// Background intelligence (scanner, People, visual index, search) stays on-device,
// so no Kotlin source outside featureaiedit/ may reach the network.
const root = fileURLToPath(new URL('../../', import.meta.url));
const SRC = join(root, 'app/src/main/java');
const ALLOWED_DIR = 'nl/atlas/gallery/featureaiedit/';
const SAFE_JAVA_NET = new Set(['URI', 'URLEncoder', 'URLDecoder', 'URISyntaxException']);

// Any java.net use other than the pure string/URI helpers, including wildcard
// imports and fully qualified names; plus javax.net, HTTP client libraries,
// stream/connection openers, sockets, WebView and DownloadManager.
const BANNED = [
  /\bjavax\.net\b/,
  /\b(okhttp3|io\.ktor|retrofit2|com\.android\.volley|org\.apache\.http|android\.net\.http)\b/,
  /\.\s*(openStream|openConnection)\s*\(/,
  /\b(HttpURLConnection|HttpsURLConnection|URLConnection|Socket|ServerSocket|DatagramSocket|SocketFactory|WebView|DownloadManager|Cronet\w*)\b/,
];

export function networkViolations(text) {
  const found = [];
  for (const m of text.matchAll(/\bjava\.net\.(\*|\w+)/g)) {
    if (!SAFE_JAVA_NET.has(m[1])) found.push(m[0]);
  }
  for (const re of BANNED) {
    const m = text.match(re);
    if (m) found.push(m[0]);
  }
  return found;
}

function kotlinFiles(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...kotlinFiles(p));
    else if (name.endsWith('.kt')) out.push(p);
  }
  return out;
}

export function offenders(srcRoot) {
  const out = [];
  for (const file of kotlinFiles(srcRoot)) {
    const rel = relative(srcRoot, file).split('\\').join('/');
    if (rel.startsWith(ALLOWED_DIR)) continue;
    const hits = networkViolations(readFileSync(file, 'utf8'));
    if (hits.length) out.push(`${rel}: ${hits.join(', ')}`);
  }
  return out;
}

test('manifest declares INTERNET with the AI Edit scope comment and no cleartext', () => {
  const manifest = readFileSync(join(root, 'app/src/main/AndroidManifest.xml'), 'utf8');
  assert.match(manifest, /<!--[^>]*AI Edit[^>]*featureaiedit[\s\S]*?-->\s*<uses-permission android:name="android\.permission\.INTERNET"/);
  assert.match(manifest, /usesCleartextTraffic="false"/);
});

test('only featureaiedit/ sources may use the network', () => {
  assert.deepEqual(offenders(SRC), [], `network use outside ${ALLOWED_DIR}`);
});

test('the fence catches known bypasses outside featureaiedit/ and allows them inside', () => {
  const tmp = mkdtempSync(join(tmpdir(), 'net191-'));
  try {
    const bypasses = {
      wildcard: 'import java.net.*\nfun f() = URL("https://example.invalid").openStream()',
      qualified: 'fun f() = java.net.URL("https://example.invalid").readText()',
      openStream: 'import java.net.URL\nfun f() = URL("https://example.invalid").openStream()',
      connection: 'import java.net.HttpURLConnection\nfun f(c: HttpURLConnection) = c.connect()',
      socket: 'fun f() = java.net.Socket("example.invalid", 443)',
      javax: 'import javax.net.ssl.HttpsURLConnection',
      okhttp: 'import okhttp3.OkHttpClient',
      webview: 'import android.webkit.WebView',
    };
    for (const [name, body] of Object.entries(bypasses)) {
      const outside = join(tmp, 'nl/atlas/gallery', `${name}.kt`);
      const inside = join(tmp, ALLOWED_DIR, `${name}.kt`);
      for (const p of [outside, inside]) {
        mkdirSync(dirname(p), { recursive: true });
        writeFileSync(p, `package x\n${body}\n`);
      }
    }
    const found = offenders(tmp);
    assert.equal(found.length, Object.keys(bypasses).length, found.join('\n'));
    assert.ok(found.every((f) => !f.startsWith(ALLOWED_DIR)));
    // Pure URI helpers stay allowed everywhere.
    assert.deepEqual(networkViolations('import java.net.URI\nimport java.net.URLDecoder'), []);
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
});
