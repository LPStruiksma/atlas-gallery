import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// TIDY-1A source contract: only positive Keeps persist; staging, skip and the
// Trash path are untouched. Emulator journeys cover the rendered behaviour.
const root = 'app/src/main/java/nl/atlas/gallery/';
const screen = readFileSync(root + 'DeclutterScreen.kt', 'utf8');
const progress = readFileSync(root + 'DeclutterProgress.kt', 'utf8');
const host = readFileSync(root + 'MainActivity.kt', 'utf8');

assert.match(screen, /fun decide\(discard: Boolean\)[\s\S]*?if \(!discard\) onKeep\?\.invoke\(media\)/,
  'only a Keep may be persisted, never a staged Trash choice');
assert.match(screen, /fun undoLast\(\)[\s\S]*?onUnkeep\?\.invoke\(last\.media\)/,
  'undo removes the saved Keep because the item returns to review');
assert.match(screen, /"Resume review"[\s\S]*?"Start again"[\s\S]*?"Not now"/, 'resume prompt choices');
assert.match(screen, /Start this review again\?[\s\S]*?Nothing is deleted\./, 'reset needs a confirmation that says nothing is deleted');
assert.match(screen, /Reviewed \$\{reviewedBefore \+ session\.position\} of \$sourceTotal/, 'source-local progress line');
assert.doesNotMatch(progress, /ContentResolver|createTrashRequest|\.delete\(/, 'the store never touches MediaStore');
assert.match(progress, /"keeps\.v1\."/, 'versioned storage key');
assert.match(host, /scopeKey = declutterScopeKey\("album", folderForReview, sort = "size"\)/, 'album source key');
assert.match(host, /atlas_declutter_keeps[\s\S]*?MODE_PRIVATE/, 'private on-device preferences');
assert.match(host, /DeclutterTrashPreflight\.unchanged\(\s*context\.contentResolver, selected\)/,
  'pre-Trash re-query guard stays in the host');
console.log('declutter resume contract ok');
assert.match(screen, /All \$total items reviewed\./, 'completed source shows All items reviewed');
assert.match(screen, /atlas-declutter-complete-back/, 'completed state has a safe exit');
assert.match(screen, /onRestart = \{ confirmingReset = true \}/, 'completed restart goes through the confirmation');
assert.match(screen, /current == null && pendingCount == 0 && onKeep != null[\s\S]*?DeclutterCompletedBody/, 'last Keep in an active session ends on the completed state');
assert.match(screen, /private fun DeclutterResetDialog/, 'one shared reset confirmation');
