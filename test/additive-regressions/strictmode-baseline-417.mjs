// #417 PERF-7a contract: StrictMode is a debug-only, opt-in measurement. Release and
// main code carry no runtime policy; the debug provider is a no-op without its
// marker file, and only the emulator baseline step creates and then removes it.
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../', import.meta.url));
const read = (p) => readFileSync(join(root, p), 'utf8');

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}
for (const set of ['app/src/main', 'app/src/release']) {
  for (const file of walk(join(root, set)).filter((f) => /\.(kt|java|xml)$/.test(f))) {
    assert.ok(!readFileSync(file, 'utf8').includes('StrictMode'),
      `${file} must not install StrictMode (debug source set only)`);
  }
}

const manifest = read('app/src/debug/AndroidManifest.xml');
const provider = manifest.match(/<provider android:name="nl\.atlas\.gallery\.DebugStrictModeInstaller"[\s\S]*?\/>/);
assert.ok(provider, 'debug manifest declares the StrictMode provider');
assert.match(provider[0], /android:exported="false"/);

const baseline = read('app/src/debug/java/nl/atlas/gallery/StrictModeBaseline.kt');
assert.match(baseline, /if \(marker\?\.exists\(\) == true\) StrictModeBaseline\.install\(\)/);
assert.match(baseline, /const val MARKER = "atlas-strictmode-baseline"/);
for (const call of ['detectDiskReads()', 'detectDiskWrites()', 'penaltyLog()', 'penaltyListener(']) {
  assert.ok(baseline.includes(call), call);
}
assert.ok(!/penaltyDeath|penaltyDialog/.test(baseline), 'baseline only reports');

const test = read('app/src/androidTest/java/nl/atlas/gallery/StrictModeBaselineTest.kt');
assert.match(test, /atlas_strictmode_baseline/);
for (const phase of ['grid-first-open', 'viewer-first-open', 'people-first-open', 'search-first-open']) {
  assert.ok(test.includes(`"${phase}"`), phase);
}

const script = read('scripts/ci_android_upgrade_emulator.sh');
const step = script.match(/strictmode_baseline_417\(\) \{[\s\S]*?\n\}/)?.[0] ?? '';
assert.match(step, /local marker=files\/atlas-strictmode-baseline/);
assert.match(step, /run-as "\$pkg" touch "\$marker"/);
assert.match(step, /run-as "\$pkg" rm -f "\$marker"/);
assert.match(step, /-e atlas_strictmode_baseline true/);
assert.ok(step.indexOf('touch "$marker"') < step.indexOf('am start -W'), 'marker before the first launch');
console.log('strictmode-baseline-417 source contract OK');
