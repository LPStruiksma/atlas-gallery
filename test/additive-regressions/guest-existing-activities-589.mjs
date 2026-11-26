import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL('../../' + path, import.meta.url), 'utf8');
const main = read('app/src/main/java/nl/atlas/gallery/MainActivity.kt');
const guest = read('app/src/main/java/nl/atlas/gallery/featureguest/GuestSession.kt');

test('existing activity reconciles durable guest state before foreground and intent dispatch', () => {
  for (const method of ['onStart', 'onResume', 'onNewIntent']) {
    const body = main.slice(main.indexOf('override fun ' + method + '(', main.indexOf('class MainActivity :')), main.indexOf('override fun ' + method + '(', main.indexOf('class MainActivity :')) + 350);
    assert.match(body, /reconcileGuestGuard\(\)/);
    assert.ok(body.indexOf('reconcileGuestGuard()') < body.indexOf('super.' + method));
  }
  assert.match(main, /broadcastGuestBoot\(next\)/);
  assert.match(main, /private fun answerPick\([^\n]*\n\s*if \(reconcileGuestGuard\(\)/);
  assert.match(main, /private fun startGuest\([^\n]*\n\s*if \(reconcileGuestGuard\(\)/);
  assert.match(main, /externalEntry is ExternalEntry\.Pick/);
});

test('selection menu hides video-only, offers mixed for whole-request refusal', () => {
  assert.match(guest, /fun offeredInSelection\([^\n]*\n\s*[^\n]*any\(::offeredInViewer\)/);
  assert.match(main, /guestPort != null && live && GuestEntry\.offeredInSelection\(guestCandidates\(photosSelection\)\)/);
  assert.match(guest, /selectedInTapOrder\.any \{ it\.isVideo \|\| !isImageUri\(it\.uri\) \}/);
});
