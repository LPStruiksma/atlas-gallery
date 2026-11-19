import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// DESIGN-6 / #136: no explanatory chrome in an empty or re-planning Stories view.
const screen = readFileSync(
  "app/src/main/java/nl/atlas/gallery/featurestories/AtlasStoriesScreen.kt",
  "utf8"
);
assert.match(screen, /testTag\("atlas-stories-empty"\)/);
assert.match(screen, /testTag\("atlas-stories-planning"\)/);
assert.match(screen, /Your stories will appear here/);
assert.match(screen, /Getting your stories ready…/);
// This route paints its own background; foreground must be themed as well.
assert.match(screen, /contentColor = MaterialTheme\.colorScheme\.onBackground/);
assert.doesNotMatch(screen, /When enough accessible photos share a date or a month/);
assert.doesNotMatch(screen, /ATLAS is looking through your library again/);
assert.doesNotMatch(screen, /Moments from your library/);
console.log("DESIGN-6 quiet Stories states: source contract OK");
