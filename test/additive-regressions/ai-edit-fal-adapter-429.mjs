import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// #429 (parent #183): fal.ai queue adapter. Source guarantees beside the JVM fake-server tests.
const root = fileURLToPath(new URL('../../', import.meta.url));
const dir = join(root, 'app/src/main/java/nl/atlas/gallery/featureaiedit/provider/hosted/fal');
const provider = readFileSync(join(dir, 'FalImageEditProvider.kt'), 'utf8');
const exchange = readFileSync(join(dir, 'FalExchange.kt'), 'utf8');

test('fal adapter talks only to queue.fal.run with the Key scheme and never replays the submit', () => {
  assert.match(provider, /const val QUEUE_HOST = "queue\.fal\.run"/);
  assert.match(exchange, /const val AUTH_SCHEME = "Key"/);
  assert.match(exchange, /EditHttpAuth\.Scheme\(AUTH_SCHEME, key\)/);
  assert.match(provider, /FalCall\(FalMethod\.POST, queueConfig\(\)\.copy\(maxAttempts = 1\)/);
  assert.match(provider, /FalMethod\.PUT, .*"\/cancel"/);
});

test('fal results come only from fal.media over HTTPS without the key, verified by bytes', () => {
  assert.match(provider, /host == "fal\.media" \|\| \(host\.endsWith\("\.fal\.media"\)/);
  assert.match(provider, /uri\.scheme != "https"/);
  assert.match(provider, /withKey = false, downloadTo = file/);
  assert.match(provider, /ImageProbe\.probe\(it\)/);
});

test('fal safety flags are refusals and no model is called uncensored', () => {
  assert.match(provider, /EditOutcome\.Refused\("fal:has_nsfw_concepts"/);
  assert.doesNotMatch(provider + exchange, /uncensored/i);
});

test('PROVIDERS.md records the fal adapter with dated sources', () => {
  const doc = readFileSync(join(root, 'docs/ai-edit/PROVIDERS.md'), 'utf8');
  assert.match(doc, /### fal\.ai queue \(#429\), 2026-11-18/);
  assert.match(doc, /@fal-ai\/client` 1\.11\.0/);
});
