import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const read = (path) => readFileSync(resolve(root, path), 'utf8');
const host = read('app/src/main/java/nl/atlas/gallery/PeopleDiscoveryHost.kt');
const visual = read('app/src/main/java/nl/atlas/gallery/featurevisual/store/VisualIndexStore.kt');

// #684: the store commits a staged-behind epoch before the host swaps to it.
// A People read in that window (or one that took its scope just before the
// swap) is a read gap: the screen keeps its people instead of "No people".
assert.match(host, /restagedFrom = if \(keepReadable\) liveEpoch else null/);
assert.match(host, /\(stagingEpoch != null && displayEpoch == live\) \|\| displayEpoch == restagedFrom/);
assert.match(host, /holdPendingPeople\(previous, next, next != null && host\.restageReadGap\(next\.displayEpoch\)\)/);
// Never held across a lost grant or a failure.
assert.match(host, /next\.ui\.phase != FaceDiscoveryPhase\.PERMISSION_REQUIRED &&\s*next\.ui\.phase != FaceDiscoveryPhase\.FAILED/);
assert.match(host, /gap && synchronized\(lock\) \{ optedIn && failure == null \} && photoGrantCurrent\(\)/);
// Visual counts stay readable: the swap keeps a staging visual scope and the
// next visual catalogue stages behind the active one.
assert.match(host, /VisualScope\(epoch, it\.epoch, it\.leftOutPhotos, staging = true\)/);
assert.match(host, /store\.beginSnapshot\(epoch, keepActiveReadable = keepReadable\)/);
assert.match(visual, /fun beginSnapshot\(epoch: String, keepActiveReadable: Boolean = false\)/);
// The deterministic emulator proof exists.
assert.ok(existsSync(resolve(root,
  'app/src/androidTest/java/nl/atlas/gallery/PeopleRestageReadableTest.kt')));

console.log('people-restage-684: source contracts pass');
