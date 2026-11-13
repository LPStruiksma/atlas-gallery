import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const read = (path) => readFileSync(resolve(root, path), 'utf8');
const host = read('app/src/main/java/nl/atlas/gallery/PeopleDiscoveryHost.kt');
const store = read('app/src/main/java/nl/atlas/gallery/featurepersistence/FaceIndexStore.kt');
const people = read('app/src/main/java/nl/atlas/gallery/featurepeopleui/PeopleHomeScreen.kt');

// #31: a reopened store keeps the last epoch dormant, never readable by itself.
assert.match(store, /dormant_epoch=COALESCE\(active_epoch,\s*dormant_epoch\), active_epoch=NULL/);
// Every permission invalidation drops the dormant epoch too.
const invalidate = store.split('fun invalidatePermissionScope()')[1].split('\n    }\n')[0];
assert.match(invalidate, /dormant_epoch=NULL/);
// Carry-over needs the stored publication AND an equal non-null grant token;
// selected-photos access has no token, so it never carries a catalogue.
assert.match(host, /if \(!g\.images \|\| g\.selectionScoped\) null/);
assert.match(host, /if \(saved\.grant != token \|\| stagingEpoch != null\) return Carry\.NONE/);
assert.match(host, /val carry = if \(token != null && digest != null\)/);
// A revoke forgets the stored publication.
const revoke = host.split('fun revoke() {')[1].split('\n    }\n')[0];
assert.match(revoke, /optInMemory\.savePublished\(null\)/);
// The scan worker holds while a catalogue stages behind the readable one.
assert.match(host, /scopeEpoch = \{ if \(stagingEpoch == null\) liveEpoch else null \}/);
// A restoring opt-in never shows the first-run empty page.
assert.match(people, /if \(restoring\) \{\s*Box\(Modifier\.fillMaxSize\(\)\.testTag\("atlas-people-restoring"\)\)/);

console.log('relaunch-first-frame-31: source contracts pass');
