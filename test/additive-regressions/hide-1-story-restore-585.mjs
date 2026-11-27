import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const main = readFileSync(resolve(root, 'app/src/main/java/nl/atlas/gallery/MainActivity.kt'), 'utf8');

// HIDE-1 (#585): while the Hidden set is unread the filtered catalogue is empty.
// A story restored after an Activity recreate must wait for the real catalogue
// instead of settling against an empty live-token deck (StoriesJourneyTest).
assert.match(main, /val storyTokens = storyPlanTokensOrNull\(grants, publishedGallery\.epoch,\s+authorized = searchGrant != null && hiddenKeys != null,/);
assert.match(main, /val photoDnaTokens = photoDnaTokensOrNull\(grants, publishedGallery\.epoch,\s+authorized = searchGrant != null && hiddenKeys != null, source = media\)/);
console.log('hide-1-story-restore-585 source contract OK');
