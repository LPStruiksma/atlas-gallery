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
