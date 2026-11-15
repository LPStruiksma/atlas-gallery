// DESIGN-3: source contract for the Samsung-style person page.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const page = readFileSync('app/src/main/java/nl/atlas/gallery/PersonPageScreen.kt', 'utf8');
const timeline = readFileSync('app/src/main/java/nl/atlas/gallery/featuregrid/AtlasTimelineGallery.kt', 'utf8');
assert.match(page, /LocalAtlasHeaderCollapsed\.current/);
assert.match(page, /AnimatedVisibility\(/);
assert.match(page, /atlas-person-empty/);
assert.match(page, /No photos or videos/);
assert.match(page, /AtlasTimelineGallery\(/);
assert.match(timeline, /horizontalArrangement = Arrangement\.spacedBy\(3\.dp\)/);
for (const [label, tag] of [
  ['Edit name', 'atlas-person-edit-name'],
  ['Merge with…', 'atlas-person-merge'],
  ['Remove from this person', 'atlas-person-remove'],
  ['Hide this person', 'atlas-person-hide'],
]) {
  assert.ok(page.includes(label), label);
  assert.ok(page.includes(tag), tag);
}
console.log('DESIGN-3 person page polish contract OK');
