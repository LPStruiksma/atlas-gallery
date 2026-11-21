import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// FIND-2: screen sort interleaves SAME_PERSON and PROBABLE photos.
// A border alone is too subtle to keep those tiers visible to sighted users.
const face = readFileSync(
  'app/src/main/java/nl/atlas/gallery/featuresearch/face/FaceSearchResultsScreen.kt', 'utf8');
assert.match(face, /if \(screenSort\) orderedHits/);
assert.match(face, /contentDescription = \(if \(possible\) "Possible match: "/);
assert.match(face, /if \(possible\)\s*\{\s*Text\("Possible"/,
  'Probable hits need their own visible label in the mixed screen-sorted grid');
assert.match(face, /testTag\("atlas-face-result-possible-label"\)/);
console.log('FIND-2 screen-sort probable-tier labels passed');
