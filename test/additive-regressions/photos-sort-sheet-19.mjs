import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const header = readFileSync(
  new URL("../../app/src/main/java/nl/atlas/gallery/PhotosHeader.kt", import.meta.url),
  "utf8",
);

// #19 regression: do not revert Photos Sort to the anchored overflow dropdown.
test("Photos sort opens a modal bottom choice sheet, not an anchored dropdown", () => {
  assert.match(header, /ModalBottomSheet\s*\(/);
  assert.doesNotMatch(header, /\bDropdownMenu\s*\(/);
  assert.match(header, /atlas-photos-sort-sheet/);
});

// The sort pipeline and all old choice/test tags are held by existing Android
// journeys; this narrowly protects the size/Random affordances in the UI.
test("size choices and deterministic Random Reshuffle remain reachable", () => {
  assert.match(header, /SortOrder\.entries\.forEach/);
  assert.match(header, /atlas-photos-sort-/);
  assert.match(header, /SortOrder\.RANDOM/);
  assert.match(header, /atlas-photos-reshuffle/);
  assert.match(header, /onShuffle\(\)/);
});
