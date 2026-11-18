// DESIGN-12 (#358): the real MainActivity owns main-screen bar icon contrast.
// Keep viewer-specific overrides in their separate tests.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const activity = readFileSync(new URL('../../app/src/main/java/nl/atlas/gallery/MainActivity.kt', import.meta.url), 'utf8');
const design = readFileSync(new URL('../../app/src/main/java/nl/atlas/gallery/featuredesign/AtlasGalleryDesign.kt', import.meta.url), 'utf8');
const journey = readFileSync(new URL('../../app/src/androidTest/java/nl/atlas/gallery/SystemThemeJourneyTest.kt', import.meta.url), 'utf8');

test('main activity initializes readable status and navigation icon contrast', () => {
  assert.match(activity, /applyAtlasSystemBars\(window, resources\.configuration\.isNightMode\(\)\)/);
  assert.match(activity, /AtlasSystemBarIcons\(dark\)/);
  assert.match(activity, /isAppearanceLightStatusBars = !dark/);
  assert.match(activity, /isAppearanceLightNavigationBars = !dark/);
  assert.match(design, /LaunchedEffect\(view, dark\) \{ setSystemBarIcons\(view, dark\) \}/);
});

test('Photos and Search run on the real Activity under both system themes', () => {
  assert.match(journey, /class SystemLightThemeJourneyTest : SystemThemeJourney\(dark = false\)/);
  assert.match(journey, /class SystemDarkThemeJourneyTest : SystemThemeJourney\(dark = true\)/);
  assert.match(journey, /fun mainScreenSystemBarIconsMatchSystemTheme\(\)/);
  assert.match(journey, /listOf\("photos", "search"\)/);
  assert.match(journey, /controller\.isAppearanceLightStatusBars/);
  assert.match(journey, /controller\.isAppearanceLightNavigationBars/);
  assert.match(journey, /saveUiEvidence\("design-12-\$tab-\$mode\.png"\)/);
});
