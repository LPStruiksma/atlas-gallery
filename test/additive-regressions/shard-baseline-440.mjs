// Shard baseline, keyboard health check and failure report (#440, goal #437 U2).
//
// Every emulator test must start from the run's baseline device state, a
// leaked state must name the test that leaked it, a dead emulator keyboard
// must be one named failure before the suite, and every failing test must
// print the device state. These checks pin the wiring; the proof that the
// listener works on a device is AtlasRunListenerSelfTest in an opt-in stress run.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const read = (...parts) => readFileSync(join(root, ...parts), 'utf8');
const kit = ['app', 'src', 'androidTest', 'java', 'nl', 'atlas', 'gallery', 'testkit'];
const script = read('scripts', 'ci_android_upgrade_emulator.sh');
const listener = read(...kit, 'AtlasRunListener.kt');
const health = read(...kit, 'KeyboardHealthTest.kt');
const selfTest = read(...kit, 'AtlasRunListenerSelfTest.kt');
const rules = read('docs', 'testing', 'EMULATOR_TESTS.md');

test('the listener checks before each test, reports failures and sums drift per class', () => {
  assert.match(listener, /class AtlasRunListener : RunListener\(\)/);
  for (const callback of ['testRunStarted', 'testStarted', 'testFinished', 'testFailure', 'testRunFinished']) {
    assert.match(listener, new RegExp(`override fun ${callback}\\(`), `${callback} is handled`);
  }
  assert.match(listener, /"drift \$\{.*\} " \+\s*"left by \$previousTest"/s, 'a drift line names the previous test');
  assert.match(listener, /val device = withInteractiveWindows \{ describeDevice\(\) \}/);
  assert.match(listener, /Log\.e\(DIAG_LOG_TAG, "\$\{failure\.description\.name\(\)\} \$device"\)/);
  assert.match(listener, /const val BASELINE_LOG_TAG = "ATLAS_BASELINE"/);
  assert.match(listener, /const val DIAG_LOG_TAG = "ATLAS_DIAG"/);
  for (const kind of ['Keyboard', 'Display', 'Animator', 'Night', 'TopApp']) {
    assert.match(listener, new RegExp(`DriftKind\\.${kind}\\b`), `${kind} drift is compared`);
  }
});

test('a test that leaves nothing behind costs no accessibility window reads (#870)', () => {
  assert.doesNotMatch(listener, /interactiveUiAutomation/, 'the listener never turns interactive windows on for good');
  assert.doesNotMatch(listener, /\.windows\b/, 'no accessibility window list on the check path');
  const anr = listener.slice(listener.indexOf('if (first.anrDialog)'));
  assert.match(anr.slice(0, 300), /withInteractiveWindows \{ clearSystemAnrDialogs\(/,
    'interactive windows only to answer a dialog that was found');
  assert.match(listener, /startsWith\("mInputShown=true"\)/, 'the keyboard flag that caught the window-list miss');
  assert.match(listener, /"dumpsys window displays"/);
  const focus = listener.slice(listener.indexOf('internal data class Focus('));
  assert.doesNotMatch(focus, /Regex\(/, 'no Regex in Focus: an unescaped } failed to load on Android (run 44897203737)');
});

test('the keyboard is hidden before a display reset, and a listener never fails a test', () => {
  const restore = listener.slice(listener.indexOf('private fun restore('));
  assert.ok(restore.indexOf('DriftKind.Keyboard in kinds') < restore.indexOf('wm size reset'),
    'the keyboard goes before the display reset (#437: reset with the keyboard up can kill LatinIME)');
  assert.match(listener, /catch \(t: Throwable\)/, 'every callback is guarded');
});

test('the suite runs with the listener and prints its lines next to ATLAS_PERF10', () => {
  assert.match(script, /^LISTENER=nl\.atlas\.gallery\.testkit\.AtlasRunListener$/m);
  assert.match(script,
    /gradle :app:connectedDebugAndroidTest "\$\{shard_args\[@\]\}" \\\n\s+-Pandroid\.testInstrumentationRunnerArguments\.listener="\$LISTENER"/);
  const suite = script.indexOf('gradle :app:connectedDebugAndroidTest');
  const dump = script.indexOf('adb logcat -d -v brief -s ATLAS_BASELINE:V ATLAS_DIAG:V');
  assert.ok(dump > suite && dump < script.indexOf('::group::ATLAS_PERF10'), 'printed after the suite');
});

test('a keyboard health check runs once per shard before the stress run and the suite', () => {
  const check = script.indexOf('-e atlas_keyboard_health true');
  assert.notEqual(check, -1);
  assert.ok(check < script.indexOf('if [[ -f scripts/ci-opt-in/stress-classes'), 'before the stress run');
  assert.ok(check < script.indexOf('gradle :app:connectedDebugAndroidTest'), 'before the suite');
  assert.match(script, /echo "::error::IME unavailable on this emulator: \$\{device_line:-/);
  const failBranch = script.slice(check, script.indexOf('# Opt-in stress run'));
  assert.match(failBranch, /exit 1\nfi/, 'a failed check stops the shard');
  assert.match(health, /InstrumentationRegistry\.getArguments\(\)\.getString\(HEALTH_ARGUMENT\) == "true"/);
  assert.match(health, /const val HEALTH_ARGUMENT = "atlas_keyboard_health"/);
  assert.doesNotMatch(health, /KeyboardRule\(|withTestKeyboard\(/, 'checks the emulator keyboard, not a test keyboard');
});

test('the listener self-tests run only in an opt-in proof run', () => {
  assert.match(selfTest, /getString\(SELF_TEST_ARGUMENT\) == "true"/);
  assert.match(selfTest, /const val SELF_TEST_ARGUMENT = "atlas_listener_selftest"/);
  assert.doesNotMatch(selfTest, /@Ignore/);
  const suiteCall = script.slice(script.indexOf('gradle :app:connectedDebugAndroidTest'));
  assert.doesNotMatch(suiteCall.split('\n').slice(0, 4).join('\n'), /atlas_listener_selftest/,
    'the full suite never turns the self-test on');
});

test('the rules page says how to read a red shard', () => {
  assert.match(rules, /^## Reading a red shard$/m);
  for (const word of ['ATLAS_BASELINE', 'ATLAS_DIAG', 'IME unavailable on this emulator']) {
    assert.ok(rules.includes(word), `the rules page explains ${word}`);
  }
});
