import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// IDS-19 (#619): the model downloader. Guard the repository side of Strategy's choice:
// the HTTPS fetcher lives inside the #191 fence (featureaiedit/network/), redirects go only
// to an exact host list, the install work runs on any connection only after a tap, and the
// identity packages themselves stay free of network code.
const root = fileURLToPath(new URL('../../', import.meta.url));
const SRC = join(root, 'app/src/main/java/nl/atlas/gallery');
const FETCHER = join(SRC, 'featureaiedit/network/HttpsModelFetcher.kt');
const INSTALLER = join(SRC, 'featureidentity/install/IdentityModelInstaller.kt');
const WORKER = join(SRC, 'featureidentity/install/IdentityModelInstallWorker.kt');

test('fetcher sits inside the #191 network fence and follows redirects by exact host only', () => {
  assert.ok(existsSync(FETCHER));
  const src = readFileSync(FETCHER, 'utf8');
  assert.match(src, /instanceFollowRedirects = false/);
  assert.match(src, /"release-assets\.githubusercontent\.com",\s*"objects\.githubusercontent\.com"/);
  assert.match(src, /host in RELEASE_ASSET_HOSTS/);
  assert.doesNotMatch(src, /endsWith\(|Regex\("[^"]*githubusercontent/, 'no host pattern, only the exact list');
  assert.match(src, /ModelHostPolicy\.allowed\(url\)/);
});

test('install work: any connection, unique per model set, started only by start()', () => {
  const src = readFileSync(INSTALLER, 'utf8');
  assert.match(src, /NetworkType\.CONNECTED/);
  assert.doesNotMatch(src, /NetworkType\.UNMETERED/);
  assert.match(src, /enqueueUniqueWork\(workName, ExistingWorkPolicy\.KEEP/);
  assert.equal((src.match(/enqueueUniqueWork/g) || []).length, 1, 'one enqueue site');
  const worker = readFileSync(WORKER, 'utf8');
  assert.match(worker, /FOREGROUND_SERVICE_TYPE_DATA_SYNC/);
  assert.match(worker, /createCancelPendingIntent/);
});

test('the engine never downloads: load/generate code does not reach the installer', () => {
  const engine = readFileSync(join(SRC, 'featureidentity/local/LocalFaceSwapEngine.kt'), 'utf8');
  assert.doesNotMatch(engine, /IdentityModelInstaller|HttpsModelFetcher|\.install\(/);
});
