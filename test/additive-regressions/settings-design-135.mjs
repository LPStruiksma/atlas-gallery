import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = path => readFileSync(new URL("../../" + path, import.meta.url), "utf8");
const settings = read("app/src/main/java/nl/atlas/gallery/AtlasSettingsScreen.kt");
const journey = read("app/src/androidTest/java/nl/atlas/gallery/SettingsDesignJourneyTest.kt");
const evidence = read("scripts/ui-evidence.d/135-settings-design.txt");

test("#135 settings has One UI-like grouped hierarchy and theme-backed rows", () => {
  for (const token of ["SettingsGroup", "SettingsActionRow", "HorizontalDivider",
    "RoundedCornerShape(22.dp)", "heightIn(min = 64.dp)", "MaterialTheme.colorScheme.surfaceContainer",
    "MaterialTheme.colorScheme.onSurfaceVariant", "MaterialTheme.typography.titleSmall",
    "MaterialTheme.typography.bodyLarge", "atlas-settings-large-title",
    "atlas-settings-compact-title", "atlas-settings-group-card"]) {
    assert.ok(settings.includes(token), "missing Settings design contract: " + token);
  }
  assert.match(settings, /showLargeTitle\s*=\s*scrollState\.value\s*<\s*\d+/);
  assert.match(settings, /if \(onClick != null\) Modifier\.clickable/);
  assert.match(settings, /if \(advanced\)/);
  assert.match(settings, /if \(crashExpanded\)/);
});

test("#135 emulator verifies both themes, scroll collapse, advanced actions and captures", () => {
  for (const token of ["AtlasGalleryTheme(dark = dark)", "settings-135-main-light.png",
    "settings-135-main-dark.png", "settings-135-advanced-light.png",
    "settings-135-advanced-dark.png", "settings-135-scrolled-light.png",
    "settings-135-scrolled-dark.png", "atlas-settings-compact-title",
    "atlas-settings-advanced", "atlas-settings-diagnostics", "onNodeWithTag"]) {
    assert.ok(journey.includes(token), "missing Settings emulator assertion: " + token);
  }
  for (const item of ["settings-135-main-light", "settings-135-main-dark",
    "settings-135-advanced-light", "settings-135-advanced-dark",
    "settings-135-scrolled-light", "settings-135-scrolled-dark"]) {
    assert.ok(evidence.includes(item), "missing screenshot manifest entry: " + item);
  }
});
