import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import { test } from 'node:test';

const source = readFileSync('app/src/main/java/nl/atlas/gallery/featurepersistence/FaceIndexStore.kt', 'utf8');

test('person media has an epoch-gated keyset page API', () => {
  assert.match(source, /fun personMediaPage\(/);
  assert.match(source, /afterMediaKey: String\? = null/);
  assert.match(source, /LIMIT \?4/);
  assert.match(source, /media_key>\?3/);
  assert.match(source, /authorizedEmbeddingRead\(epoch, emptyList\(\)\)/);
});

test('person media page uses the existing confirmed and matched indexes', () => {
  assert.match(source, /people_confirmed_person/);
  assert.match(source, /people_matched_person/);
  assert.match(source, /CREATE INDEX IF NOT EXISTS faces_by_uid_media/);
});

test('person media page returns only processed, source-matching media', () => {
  assert.match(source, /c\.state='processed'/);
  assert.match(source, /c\.revision=e\.source_revision/);
  assert.match(source, /e\.model_version=\?1/);
});
