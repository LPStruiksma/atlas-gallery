import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = (path) => readFileSync(new URL("../../" + path, import.meta.url), "utf8");
const main = read("app/src/main/java/nl/atlas/gallery/MainActivity.kt");
const journey = read("app/src/androidTest/java/nl/atlas/gallery/SettingsBackNavigationJourneyTest.kt");

test("#79 the last root BackHandler owns Settings before the origin tab", () => {
  // The earlier Settings handler loses priority to the later general handler.
  const root = main.match(/BackHandler\(enabled = settingsOpen \|\| coverSession[\s\S]*?\n    }/);
  assert.ok(root, "the final general BackHandler must enable itself for Settings");
  const handler = root[0];
  const cases = ["statusOpen -> statusOpen = false", "settingsOpen -> settingsOpen = false",
    "coverSession != null ->", "personFilter != null ->", "else -> tab = 0"];
  let last = -1;
  for (const statement of cases) {
    const next = handler.indexOf(statement);
    assert.ok(next > last, statement + " must precede any background navigation");
    last = next;
  }
  assert.match(main, /settingsOpen -> \(\{ settingsOpen = false \}\)/,
    "header Back must dismiss Settings");
});

test("#79 emulator journey verifies all origins, nested Diagnostics and restoration", () => {
  assert.match(journey, /listOf\("photos", "albums", "people", "search"\)/);
  assert.match(journey, /onBackPressedDispatcher\.onBackPressed\(\)/);
  assert.match(journey, /"atlas-settings-screen"\)\.assertDoesNotExist\(\)/);
  assert.match(journey, /"atlas-nav-\$tab"\)\.assertIsSelected\(\)/);
  assert.match(journey, /diagnosticsIsDismissedBeforeSettingsAndHeaderBackKeepsPeople/);
  assert.match(journey, /scenario\.recreate\(\)/);
  assert.match(journey, /saveUiEvidence\("settings-back-79-\$tab\.png"\)/);
});
