import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// #37: the full Android journey remains mandatory. A system launcher ANR must
// not mask MediaProvider consent or be accepted as a successful move.
const source = readFileSync(
  "app/src/androidTest/java/nl/atlas/gallery/SelectionMoreJourneyTest.kt",
  "utf8"
);
assert.match(source, /copyToAlbumMoveToNewAlbumThenHideAndUnhideTheAlbum/);
assert.match(source, /MediaStore\.MediaColumns\.RELATIVE_PATH/);
assert.match(source, /assertEquals\(NEW_PATH, fixtures\.relativePathOf\(ids\[2\]\)\)/);
assert.match(source, /Quickstep isn't responding/);
assert.match(source, /"Wait"/);
assert.match(source, /com\.android\.providers\.media/);
assert.match(source, /findAccessibilityNodeInfosByText\("Allow"\)/);
assert.match(source, /ACTION_CLICK/);
assert.match(source, /Move did not finish/);
assert.doesNotMatch(source, /Assume\.assume|assumeNoException|@Ignore/);
console.log("PASS: #37 move requires real MediaProvider consent, survives launcher ANR, and keeps row assertions");
