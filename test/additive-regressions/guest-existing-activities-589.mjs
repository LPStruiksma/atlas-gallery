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


test('guest journey observes the second activity resume rather than assuming idle means intent delivered', () => {
  const journey = read('app/src/androidTest/java/nl/atlas/gallery/GuestViewJourneyTest.kt');
  const helper = journey.slice(journey.indexOf('private fun awaitSecondResume('), journey.indexOf('private fun activateGuestInSecondActivity('));
  assert.match(helper, /seen === activity && event == stage/);
  assert.match(helper, /Stage\.RESUMED/);
  assert.ok(helper.indexOf('monitor.addLifecycleCallback(callback)') < helper.indexOf('trigger()'));
  assert.match(helper, /assertTrue\(description, resumed\.await/);
  assert.match(helper, /finally[\s\S]*removeLifecycleCallback/);
  const probe = journey.slice(journey.indexOf('private fun activateGuestInSecondActivity('));
  assert.ok(probe.indexOf('awaitSecondResume(other, "initial second activity resume")') < probe.indexOf('awaitSecondResume(other, "second activity resume after new intent")'));
  assert.ok(probe.indexOf('FLAG_ACTIVITY_SINGLE_TOP') < probe.indexOf('assertTrue("second activity reconciles on new intent"'));
  assert.match(probe, /second activity window secure after new intent/);
  assert.match(journey, /assertEquals\(android\.app\.Activity\.RESULT_CANCELED, scenario\.result\.resultCode\)/);
  assert.match(journey, /assertEquals\(null, scenario\.result\.resultData\)/);
});

test('viewer dismissal rejects a live pin surface and requires app focus and unpinned state', () => {
  const journey = read('app/src/androidTest/java/nl/atlas/gallery/GuestViewJourneyTest.kt');
  const viewer = journey.slice(journey.indexOf('class GuestViewViewerEntryTest'), journey.indexOf('private object GuestGuardCheck'));
  const check = journey.slice(journey.indexOf('private fun viewerPinPromptDismissed()'), journey.indexOf('The OS pinning prompt\'s accept or decline button'));
  assert.match(viewer, /if \(pinPromptFocused\(\)\) shell\("input keyevent 4"\)/);
  assert.match(viewer, /awaitThat\("viewer pinning prompt dismissed"\) \{ viewerPinPromptDismissed\(\) \}/);
  assert.match(viewer, /awaitThat\("viewer pinning prompt still dismissed"\)/);
  assert.match(check, /ScreenPinningConfirmation/);
  assert.match(check, /mHasSurface=false/);
  assert.match(check, /mCurrentFocus=/);
  assert.match(check, /mFocusedApp=/);
  assert.match(check, /LOCK_TASK_MODE_NONE/);
});
