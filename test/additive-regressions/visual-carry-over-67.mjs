// #67 acceptance contract: newly launched visual scopes remain hidden until the
// same full grant has revived the face catalogue, then restore without restage.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const root = new URL('../../', import.meta.url);
const store = readFileSync(new URL('app/src/main/java/nl/atlas/gallery/featurevisual/store/VisualIndexStore.kt', root), 'utf8');
const host = readFileSync(new URL('app/src/main/java/nl/atlas/gallery/PeopleDiscoveryHost.kt', root), 'utf8');
const test = readFileSync(new URL('app/src/androidTest/java/nl/atlas/gallery/PeopleCatalogueCarryOverTest.kt', root), 'utf8');
assert.match(store, /fun reviveDormantEpoch\(modelVersion: String, dimension: Int\)/);
assert.match(store, /dormant_epoch=COALESCE\(active_epoch, dormant_epoch\)/);
assert.match(host, /private fun reviveVisual\(faceEpoch: String, token: String/);
assert.match(host, /loadPublishedVisual\(\)/);
assert.match(host, /visualScope\?\.staging == true/);
for (const name of ['unchangedRelaunchRevivesVisualOnceWithoutRestaging',
  'differentGrantRejectsDormantVisual', 'selectedPhotosDoesNotCarryVisual',
  'revokedGrantDropsVisualAcrossRelaunch']) assert.ok(test.includes(name), name);
console.log('visual-carry-over-67 source contract OK');
