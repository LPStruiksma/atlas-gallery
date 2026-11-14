import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const read = (path) => readFileSync(resolve(root, path), 'utf8');
const store = read('app/src/main/java/nl/atlas/gallery/featurepersistence/FaceIndexStore.kt');
const body = (name) => {
  const start = store.indexOf(`fun ${name}(`);
  assert.ok(start >= 0, `${name} exists`);
  return store.slice(start, store.indexOf('\n    }\n', start) + 6);
};

// #39: People reads, the generation read and review commits are gated on
// "servable" (clean, or matched rows current and no split publication in
// between parts), not on a clean generation, so a scan never empties People.
for (const name of ['peopleHome', 'peopleReviewGeneration', 'personMatchInputs', 'commitPeopleReview']) {
  assert.match(body(name), /servable\(db\)/, `${name} uses servable`);
  assert.doesNotMatch(body(name), /clusterGenerationClean\(db\)/, `${name} is not gated on clean`);
}
assert.match(body('peopleReview'), /if \(!servable\(db\)\) null/);
// A dirty snapshot leaves pending faces out, as a publication seeds from it.
assert.match(body('peopleReview'), /buildClusterState\(db, version, onlyMaterialized = !clean\)/);
assert.match(store, /return !gen\.dirty \|\| \(gen\.pending < PUBLICATION_CONTINUES && matchedCurrent\(db\)\)/);

// Every face removal moves the review revision on, so a snapshot that still
// holds a removed face is refused at commit.
assert.match(store, /if \(pruned\) \{\s*db\.execSQL\("UPDATE cluster_generation SET dirty=1 WHERE singleton=1"\)\s*retirePublishedRevision\(db\)/);
assert.equal((store.match(/delete\("faces", ?"media_key=\?", ?arrayOf\(lease\.media\.key\)\) > 0\)\s*retirePublishedRevision\(db\)/g) ?? []).length, 2);

// Search and photo details still hide anchors while dirty (unchanged here).
assert.match(body('faceSearchCorpus'), /val published = clusterGenerationClean\(db\)/);

console.log('people-during-scan-39: source contracts pass');
