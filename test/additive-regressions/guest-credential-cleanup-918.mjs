import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL('../../' + path, import.meta.url), 'utf8');
const kit = 'app/src/androidTest/java/nl/atlas/gallery/testkit/';
const fixture = read(kit + 'DeviceCredential.kt');

test('synthetic guest credential fixture checks command status and device postconditions', () => {
  assert.match(fixture, /ATLAS_CREDENTIAL_EXIT=/);
  assert.match(fixture, /isDeviceSecure/);
  assert.match(fixture, /isKeyguardLocked/);
  assert.match(fixture, /checkedShell\("set synthetic PIN"/);
  assert.match(fixture, /checkedShell\("clear synthetic PIN"/);
  assert.match(fixture, /checkedShell\("verify synthetic PIN"/);
  assert.match(fixture, /addSuppressed/);
  assert.match(fixture, /restore\("lock_to_app_enabled", pinning\)/);
  assert.match(fixture, /restore\("lock_to_app_exit_locked", exitLocked\)/);
});

test('fixture has success and exceptional-body cleanup probes', () => {
  const proof = read(kit + 'DeviceCredentialRuleTest.kt');
  assert.match(proof, /successRestoresDevice/);
  assert.match(proof, /failurePreservesPrimaryAndRestoresDevice/);
  assert.match(proof, /assertSame/);
  assert.match(proof, /isDeviceSecure/);
  assert.match(proof, /isKeyguardLocked/);
});

test('selection credential submission waits for focus and IME before typing and never retries the PIN', () => {
  const journey = read('app/src/androidTest/java/nl/atlas/gallery/GuestViewJourneyTest.kt');
  const selection = journey.slice(journey.indexOf('// Exit again'), journey.indexOf('// Ending screen pinning'));
  assert.match(selection, /compose\.submitSelectionCredential\(\)/);
  assert.match(selection, /store\(\)\.boot\(\) == GuestBoot\.Owner/);
  const helper = journey.slice(journey.indexOf('private fun ComposeTestRule.submitSelectionCredential()'), journey.indexOf('private fun lockState()'));
  assert.match(helper, /credentialOnTop\(\) && credentialFocused\(\) && credentialImeShown\(\)/);
  assert.ok(helper.indexOf('credentialImeShown()') < helper.indexOf('shell("input text'));
  assert.equal((helper.match(/shell\("input text/g) || []).length, 1);
  assert.match(helper, /else !credentialOnTop\(\)/);
  assert.match(journey, /Application Not Responding: com\.android\.launcher3/);
  assert.match(journey, /findAccessibilityNodeInfosByText\("Wait"\)/);
});


test('guest frame C evidence includes the 200 percent viewer and both landscape themes', () => {
  const journey = read('app/src/androidTest/java/nl/atlas/gallery/GuestViewJourneyTest.kt');
  const manifest = read('scripts/ui-evidence.d/589-guest-view.txt').split(/\r?\n/);
  const viewer = journey.slice(journey.indexOf('class GuestViewViewerEntryTest'), journey.indexOf('private object GuestGuardCheck'));
  const capture = 'captureGuest(compose.activityRule.scenario, "guest-589-light-c-viewer-font200.png")';
  assert.match(viewer, /fontScale = 2f/);
  assert.ok(viewer.includes(capture));
  assert.ok(viewer.indexOf('Exit inside the screen at 200%') < viewer.indexOf(capture));
  assert.ok(viewer.indexOf(capture) < viewer.indexOf('compose.tap(hasTestTag("atlas-guest-exit"))'));
  for (const name of ['guest-589-light-c-viewer-font200', 'guest-589-light-c-landscape', 'guest-589-dark-c-landscape']) assert.ok(manifest.includes(name));
  const rotation = journey.slice(journey.indexOf('// Recreation (as on rotation)'), journey.indexOf('// Exit → system confirmation'));
  assert.doesNotMatch(rotation, /if \(!dark\)/);
  assert.ok(rotation.includes('captureGuest("guest-589-$theme-c-landscape.png")'));
  const helper = journey.slice(journey.indexOf('private fun captureGuest(scenario:'), journey.indexOf('private fun writeGuestPng'));
  assert.match(helper, /FLAG_SECURE/);
  assert.match(helper, /PixelCopy\.request\(activity\.window/);
  assert.match(helper, /PixelCopy\.SUCCESS/);
});
