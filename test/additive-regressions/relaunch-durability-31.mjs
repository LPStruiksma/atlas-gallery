import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const read = (path) => readFileSync(resolve(root, path), 'utf8');
const host = read('app/src/main/java/nl/atlas/gallery/PeopleDiscoveryHost.kt');
const micro = read('app/src/main/java/nl/atlas/gallery/featuremedia/MicroThumbnails.kt');

// A cold-start permission observation must not erase previously chosen
// face/visual discovery, and genuinely revoked scopes must stay hidden.
assert.match(host, /if \(!photoGrantCurrent\(\)\) \{\s*if \(synchronized\(lock\) \{ scope == null \}\)/);
assert.match(host, /visualOptedIn = optedIn && optInMemory\.loadVisual\(\)/);
assert.match(host, /optInMemory\.saveVisual\(true\)/);
assert.match(host, /optInMemory\.saveVisual\(false\)/);
assert.match(host, /fun scopeCurrent\(epoch: String\): Boolean =\s*liveEpoch == epoch && synchronized\(lock\) \{ optedIn \} && photoGrantCurrent\(\)/);

// Not cacheDir: Android is allowed to purge cacheDir under storage pressure,
// which makes every gallery relaunch regenerate all micro thumbnails.
assert.match(micro, /MicroThumbnailStore\(File\(context\.noBackupFilesDir, "atlas-micro-v1"\)/);
assert.match(micro, /if \(!now && \(wasAllowed \|\| first\)\)/);
console.log('relaunch-durability-31: source contracts pass');
