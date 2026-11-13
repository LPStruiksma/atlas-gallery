import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = (path) => readFileSync(new URL("../../" + path, import.meta.url), "utf8");
const contract = read("app/src/main/java/nl/atlas/gallery/featureface/FaceDiscoveryContract.kt");
const presentation = read("app/src/main/java/nl/atlas/gallery/featurepeopleui/PeopleExperience.kt");
const home = read("app/src/main/java/nl/atlas/gallery/featurepeopleui/PeopleHomeScreen.kt");
const host = read("app/src/main/java/nl/atlas/gallery/PeopleDiscoveryHost.kt");
const settings = read("app/src/main/java/nl/atlas/gallery/AtlasSettingsScreen.kt");
const main = read("app/src/main/java/nl/atlas/gallery/MainActivity.kt");

test("#46 charger-required is not reported as low battery", () => {
  assert.match(contract, /CHARGER_REQUIRED/);
  assert.match(contract, /!batteryNotLow\s*->\s*FaceScanPhase\.BATTERY_LOW/);
  assert.match(contract, /backgroundMaintenance && !charging\)\s*->\s*FaceScanPhase\.CHARGER_REQUIRED/);
  assert.match(presentation, /WAITING_FOR_CHARGER/);
});

test("#46 People status is visible and queued does not claim an active scan", () => {
  assert.match(home, /Text\(line,/);
  assert.match(home, /peopleStatusLine\(state\)/);
  assert.match(host, /groupingEpoch/);
  assert.match(host, /scanBatchEpoch/);
  assert.match(host, /FaceScanPhase\\.QUEUED/);
  assert.match(presentation, /GROUPING/);
});

test("#46 Settings reports saved opt-in through restore and visual progress", () => {
  assert.match(settings, /scanHasSavedOptIn/);
  assert.match(settings, /faceScanSettingsValue\(/);
  assert.match(settings, /visualIndexStatusText\(/);
  assert.match(main, /scanHasSavedOptIn = discoveryHost\.hasSavedScanOptIn\(\)/);
  assert.match(main, /visualStatus = visualStatus/);
});
