import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const source = (file) => readFileSync(
  new URL("../../app/src/main/java/nl/atlas/gallery/featuregrid/" + file, import.meta.url), "utf8"
);
const grid = source("GridDensityState.kt");
const ladder = source("GalleryZoomLadder.kt");
const index = source("TimelineIndex.kt");
const timelineUi = source("AtlasTimelineGallery.kt");
const plainUi = source("AtlasFeatureGrid.kt");
const evidence = readFileSync(new URL("../../scripts/ui-evidence.d/16-month-year-zoom.txt", import.meta.url), "utf8");

test("#16 six zoom stops bind to actual 3/4/7/12/28 Compose layouts", () => {
  assert.match(grid, /LARGE\(3,[^\n]*\),[\s\S]*MEDIUM\(4,[^\n]*\),[\s\S]*SMALL\(7,[^\n]*\),[\s\S]*MONTH\(12,[^\n]*\),[\s\S]*YEAR\(28,/);
  assert.match(ladder, /ORIGINAL, LARGE, MEDIUM, SMALL, MONTH, YEAR/);
  assert.match(ladder, /GridDensity\.MONTH\s*->\s*MONTH/);
  assert.match(ladder, /GridDensity\.YEAR\s*->\s*YEAR/);
  assert.match(timelineUi, /GridCells\.Fixed\(ready\.density\.columns\)/);
});

test("#16 group by local years and months only for genuine chronological sorts", () => {
  assert.match(index, /TimelineBucket \{ DAY, MONTH, YEAR \}/);
  assert.match(index, /GridDensity\.SMALL, GridDensity\.MONTH -> TimelineBucket\.MONTH/);
  assert.match(index, /GridDensity\.YEAR -> TimelineBucket\.YEAR/);
  assert.match(index, /TimelineBucket\.YEAR -> date\.year\.toLong\(\)/);
  assert.match(index, /val chronological = sort == TimelineSort\.NEWEST \|\| sort == TimelineSort\.OLDEST/);
  assert.match(index, /if \(!ordered\) \{/);
  assert.match(index, /return GridTimelineIndex\(slots, positions, headers, grouped = false\)/);
});

test("#16 leave ungrouped grids at classic levels and declare both-theme rendered evidence", () => {
  assert.match(plainUi, /allowOverview = false/);
  assert.match(plainUi, /takeWhile \{ it\.columns <= GridDensity\.SMALL\.columns \}/);
  for (const theme of ["light", "dark"]) {
    for (const stop of ["7", "12-month", "28-year", "original"]) {
      assert.match(evidence, new RegExp("zoom-16-" + theme + "-" + stop));
    }
  }
});
