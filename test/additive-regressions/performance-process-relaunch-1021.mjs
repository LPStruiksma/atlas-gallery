// #1021: a process-death proof needs two instrumentation invocations and the real pause hook.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const read = path => readFileSync(new URL('../../' + path, import.meta.url), 'utf8');
const test = read('app/src/androidTest/java/nl/atlas/gallery/PerformanceProcessRelaunchTest.kt');
const script = read('scripts/ci_android_upgrade_emulator.sh');
const app = read('app/src/main/java/nl/atlas/gallery/AtlasApplication.kt');
assert.match(test, /PerformanceSession\.recordGridFrame/);
assert.match(test, /moveToState\(Lifecycle\.State\.STARTED\)/);
assert.doesNotMatch(test, /PerformanceSession\.flush\(/);
assert.match(test, /Process\.getStartElapsedRealtime\(\) > previousStart/);
assert.match(test, /PerformanceSession\.aggregate\.snapshot\(metric\)/);
assert.match(test, /PerformancePrefsStore\(context, version \+ 1\)/);
assert.match(app, /onActivityPaused[^\n]*PerformanceSession\.flush\(\)/);
assert.match(script, /run_perf_persistence_phase seed seedBeforeForceStop\s+adb shell am force-stop nl\.atlas\.gallery\s+run_perf_persistence_phase verify verifyAfterForceStop/);
console.log('PASS #1021 real pause and separate-process persistence contract');
