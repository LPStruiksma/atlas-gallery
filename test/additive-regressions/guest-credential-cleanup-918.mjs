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
