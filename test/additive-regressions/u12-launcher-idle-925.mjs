// U12 / #925 + U2 / #986: cold-start splash proof belongs outside instrumentation.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const kotlin = readFileSync(join(root,
  'app/src/androidTest/java/nl/atlas/gallery/LapisLauncherSplashEvidenceTest.kt'), 'utf8');
const shell = readFileSync(join(root, 'scripts/ci_android_upgrade_emulator.sh'), 'utf8');
const start = shell.indexOf('lapis_cold_start_splash_986()');
const end = shell.indexOf('\n}\n\nif [[ "${ATLAS_SYNTHETIC_MISMATCH', start) + 2;
assert.ok(start >= 0 && end > start, 'standalone post-suite splash phase remains present');
const splash = shell.slice(start, end);

test('instrumentation no longer force-stops its own target process', () => {
  assert.doesNotMatch(kotlin, /LapisColdStartSplash|coldStartShowsStaticLapisSplash/);
});

test('external shell phase performs three independent launches for both themes', () => {
  assert.match(splash, /for theme in light dark/);
  assert.match(splash, /for launch in 1 2 3/);
  assert.match(splash, /adb shell am force-stop "\$pkg"[\s\S]*?adb shell am start -W -n "\$pkg\/\.MainActivity"/);
  assert.match(splash, /adb exec-out screencap -p/);
  assert.match(splash, /PASS: U2 splash \$theme launch \$launch\/3/);
  assert.match(splash, /FAIL: U2 splash \$theme launch \$launch\/3/);
  assert.doesNotMatch(splash, /retry|attempt/,
    'each of the three required launches is asserted once, never retried until green');
});

test('phase retains splash pixels, system bars, evidence, and Quickstep idle boundaries', () => {
  // Blocking proof: splash present (Lapis field + white mark). Nav-bar glyph contrast is logged
  // non-blocking until #997 is fixed (decision on #995, 2026-11-22); keep both visible.
  assert.match(splash, /lapis \* 10 >= samples \* 7 and white > 20 else 1/);
  assert.match(splash, /U2 NAV-CONTRAST \$theme launch \$launch\/3: PASS/);
  assert.match(splash, /U2 NAV-CONTRAST \$theme launch \$launch\/3: FAIL .*#997/);
  assert.match(splash, /IMG_20010101_000000_lapis656-splash-\$theme\.png/);
  assert.match(splash, /await_launcher_idle_925/);
  assert.match(splash, /isn't responding\|Application Not Responding\|Application Error/);
  assert.match(shell, /strictmode_baseline_417\n  lapis_cold_start_splash_986/,
    'splash runs after the connected suite and existing StrictMode post-suite phase');
});
