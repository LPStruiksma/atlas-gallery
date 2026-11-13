import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const read = (path) => readFileSync(resolve(root, path), 'utf8');
const host = read('app/src/main/java/nl/atlas/gallery/PeopleDiscoveryHost.kt');
const main = read('app/src/main/java/nl/atlas/gallery/MainActivity.kt');

// #75: served-but-pending people restore, never decide "No people".
assert.match(host, /access && optedIn && \(status == null \|\| status\.peoplePending\)/);
assert.match(host, /val pending = people == null && try \{ store\.hasPublishedMembership\(scoped\.epoch\) \}/);
// The UI keeps the people it showed for the same epoch while the next read is pending.
assert.match(host, /previous\?\.people\?\.takeIf \{ it\.permissionEpoch == next\.displayEpoch \}/);
assert.match(host, /value = holdPendingPeople\(value, published\)/);
assert.match(main, /restoring = peopleHomeRestoring\(access,/);

console.log('people-pending-75: source contracts pass');
