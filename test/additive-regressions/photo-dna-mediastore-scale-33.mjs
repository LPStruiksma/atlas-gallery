import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// #33: the existing planner-only 2,000-row test cannot reproduce a large
// MediaStore publication, the actual Photo DNA route, or the visual-index
// Settings switch in the running Android app. Require that device regression.
const source = readFileSync(
  "app/src/androidTest/java/nl/atlas/gallery/PhotoDnaMediaStoreCrashScaleTest.kt", "utf8");

assert.match(source, /2_000/);
assert.match(source, /MediaStore\.Images\.Media\.EXTERNAL_CONTENT_URI/);
assert.match(source, /createAndroidComposeRule<MainActivity>/);
assert.match(source, /atlas-photo-dna-back/);
assert.match(source, /1000 copies/);
assert.match(source, /atlas-visual-index-switch/);
assert.match(source, /assertIsOn\(/);
assert.match(source, /LastCrashRecorder\.read/);
assert.match(source, /inserted\.asReversed\(\)/);
assert.match(source, /ATLAS_SCALE/);

console.log("Photo DNA + visual-index 2,000-image MediaStore instrumentation is wired");
